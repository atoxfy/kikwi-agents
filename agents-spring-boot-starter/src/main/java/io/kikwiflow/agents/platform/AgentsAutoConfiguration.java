/*
 * Copyright 2026 Atoxfy and/or licensed to Atoxfy
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Atoxfy licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kikwiflow.agents.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import io.kikwiflow.KikwiflowEngine;
import io.kikwiflow.agents.channel.AttachmentStore;
import io.kikwiflow.agents.channel.DeliveryResult;
import io.kikwiflow.agents.channel.InboundPreprocessor;
import io.kikwiflow.agents.channel.OutboundGateway;
import io.kikwiflow.agents.conversation.ConversationService;
import io.kikwiflow.agents.conversation.memory.InMemoryConversationStore;
import io.kikwiflow.agents.dms.DmsAttachmentStore;
import io.kikwiflow.agents.dms.DmsSettings;
import io.kikwiflow.agents.llm.LlmClient;
import io.kikwiflow.agents.llm.LlmRouter;
import io.kikwiflow.agents.llm.anthropic.AnthropicLlmClient;
import io.kikwiflow.agents.llm.gemini.GeminiLlmClient;
import io.kikwiflow.agents.llm.gemini.GeminiSettings;
import io.kikwiflow.agents.llm.anthropic.AnthropicSettings;
import io.kikwiflow.agents.llm.fake.ScriptedLlmClient;
import io.kikwiflow.agents.platform.agent.AgentCatalogController;
import io.kikwiflow.agents.platform.agent.AgentCatalogSeeder;
import io.kikwiflow.agents.platform.agent.MongoAgentDefinitionStore;
import io.kikwiflow.agents.platform.channel.GridFsAttachmentStore;
import io.kikwiflow.agents.platform.channel.InMemoryAttachmentStore;
import io.kikwiflow.agents.platform.conversation.MongoConversationStore;
import io.kikwiflow.agents.conversation.ConversationStore;
import io.kikwiflow.agents.platform.kikwi.EngineMissionSource;
import io.kikwiflow.agents.platform.kikwi.EngineProcessTools;
import io.kikwiflow.agents.platform.kikwi.KikwiflowRouteListener;
import io.kikwiflow.agents.platform.kikwi.MissionScheduler;
import io.kikwiflow.agents.runtime.agent.AgentCatalog;
import io.kikwiflow.agents.runtime.agent.AgentDefinitionStore;
import io.kikwiflow.agents.runtime.agent.InMemoryAgentDefinitionStore;
import io.kikwiflow.agents.runtime.loop.AgentTurnProcessor;
import io.kikwiflow.agents.runtime.loop.AttachmentReader;
import io.kikwiflow.agents.runtime.loop.LlmClientResolver;
import io.kikwiflow.agents.runtime.mission.MissionSource;
import io.kikwiflow.agents.runtime.mission.MissionSourceActivityRecorder;
import io.kikwiflow.agents.runtime.mission.MissionWorker;
import io.kikwiflow.agents.runtime.mission.MissionWorkerSettings;
import io.kikwiflow.agents.runtime.tool.AgentToolResolver;
import io.kikwiflow.agents.runtime.tool.InlineBindingExecutor;
import io.kikwiflow.agents.runtime.tool.ToolRegistry;
import io.kikwiflow.navigation.ProcessDefinitionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Montagem da plataforma de agentes sobre o motor Kikwiflow embutido: catálogo de agentes, worker de missões,
 * processos como ferramenta e conversas omnicanal (a conversa é do lead; o canal só diz por onde ele fala agora).
 *
 * <p>Canais ficam fora: o roteador de canais do host identifica o lead, entrega as mensagens ao
 * {@link ConversationService} e implementa o {@link OutboundGateway} para as respostas. O host também troca, por
 * bean, a credencial de LLM por tenant ({@link LlmClientResolver}), os anexos e o armazenamento. Conversas,
 * transcrição e anexos no Mongo ({@code kikwi-agents.storage}).
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentsProperties.class)
@Import(AgentCatalogController.class)
public class AgentsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Clientes globais por provedor: o roteirizado ({@code fake}) e, com chave configurada, Anthropic e Gemini. */
    @Bean
    @ConditionalOnMissingBean
    LlmRouter llmRouter(AgentsProperties props, ObjectMapper mapper) {
        List<LlmClient> clients = new ArrayList<>();
        clients.add(new ScriptedLlmClient());
        AgentsProperties.Anthropic anthropic = props.anthropic();
        if (anthropic != null && anthropic.apiKey() != null && !anthropic.apiKey().isBlank()) {
            AnthropicSettings defaults = AnthropicSettings.of(anthropic.apiKey());
            String baseUrl = anthropic.baseUrl() == null || anthropic.baseUrl().isBlank()
                    ? defaults.baseUrl() : anthropic.baseUrl();
            clients.add(new AnthropicLlmClient(new AnthropicSettings(anthropic.apiKey(), baseUrl,
                    defaults.apiVersion(), Duration.ofSeconds(120)), mapper));
        }
        AgentsProperties.Gemini gemini = props.gemini();
        if (gemini != null && gemini.apiKey() != null && !gemini.apiKey().isBlank()) {
            GeminiSettings defaults = GeminiSettings.of(gemini.apiKey());
            String baseUrl = gemini.baseUrl() == null || gemini.baseUrl().isBlank() ? defaults.baseUrl() : gemini.baseUrl();
            clients.add(new GeminiLlmClient(new GeminiSettings(gemini.apiKey(), baseUrl, defaults.timeout()), mapper));
        }
        return new LlmRouter(clients);
    }

    /** Sem credencial por tenant: todos usam os clientes globais. Um produto multi-tenant substitui. */
    @Bean
    @ConditionalOnMissingBean
    LlmClientResolver llmClientResolver(LlmRouter router) {
        return LlmClientResolver.of(router);
    }

    /**
     * Sem roteador de canais no host, as respostas ficam só na transcrição (com aviso no log). O produto registra o
     * seu {@link OutboundGateway}.
     */
    @Bean
    @ConditionalOnMissingBean
    OutboundGateway outboundGateway() {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OutboundGateway.class);
        return (tenantId, leadId, channel, conversationId, message) -> {
            log.warn("Sem roteador de canais: resposta da conversa {} ({}) ficou só na transcrição", conversationId,
                    channel);
            return DeliveryResult.failed("sem roteador de canais");
        };
    }

    /** Anexos: DMS quando ligado; senão GridFS ({@code agents_attachments}) ou memória. Nunca disco local. */
    @Bean
    @ConditionalOnMissingBean
    AttachmentStore attachmentStore(AgentsProperties props, RestClient.Builder restClientBuilder,
                                    ObjectProvider<MongoClient> mongoClient,
                                    @Value("${spring.data.mongodb.database:kikwi_agents_db}") String database) {
        AgentsProperties.Dms dms = props.dms();
        if (dms != null && dms.enabled()) {
            return new DmsAttachmentStore(restClientBuilder,
                    new DmsSettings(dms.baseUrl(), dms.category(), "CONVERSATION", "kikwi-agents"), dms::token);
        }
        if (props.storage() != null && props.storage().memory()) {
            return new InMemoryAttachmentStore();
        }
        return new GridFsAttachmentStore(mongoClient.getObject().getDatabase(database));
    }

    /**
     * Contatos, conversas, transcrição e lock por conversa: no Mongo por padrão (sobrevivem a reinícios e valem
     * entre nós); {@code kikwi-agents.storage.store=memory} para testes.
     */
    @Bean
    @ConditionalOnMissingBean
    ConversationStore conversationStore(AgentsProperties props, ObjectProvider<MongoClient> mongoClient,
                                        @Value("${spring.data.mongodb.database:kikwi_agents_db}") String database,
                                        Clock clock) {
        if (props.storage() != null && props.storage().memory()) {
            return new InMemoryConversationStore();
        }
        return new MongoConversationStore(mongoClient.getObject().getDatabase(database), clock);
    }

    /**
     * Catálogo de agentes no Mongo ({@code agent_definitions}); {@code kikwi-agents.catalog.store=memory} usa memória
     * (some ao reiniciar).
     */
    @Bean
    @ConditionalOnMissingBean
    AgentDefinitionStore agentDefinitionStore(AgentsProperties props, ObjectProvider<MongoClient> mongoClient,
                                              @Value("${spring.data.mongodb.database:kikwi_agents_db}") String database,
                                              ObjectMapper mapper) {
        AgentsProperties.Catalog catalog = props.catalog();
        if (catalog != null && "memory".equalsIgnoreCase(catalog.store())) {
            return new InMemoryAgentDefinitionStore();
        }
        return new MongoAgentDefinitionStore(mongoClient.getObject().getDatabase(database), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentCatalog agentCatalog(AgentDefinitionStore store) {
        return new AgentCatalog(store);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentCatalogSeeder agentCatalogSeeder(AgentCatalog catalog, ObjectMapper mapper) {
        return new AgentCatalogSeeder(catalog, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    EngineProcessTools engineProcessTools(KikwiflowEngine engine, ProcessDefinitionService definitions,
                                          ObjectMapper mapper) {
        return new EngineProcessTools(engine, definitions, mapper);
    }

    /** Executores por binding: código do runtime ({@code Inline}) e processos do motor ({@code Process}). */
    @Bean
    @ConditionalOnMissingBean
    ToolRegistry toolRegistry(EngineProcessTools processTools) {
        return new ToolRegistry().registerExecutor(new InlineBindingExecutor()).registerExecutor(processTools);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentToolResolver agentToolResolver(ToolRegistry registry, EngineProcessTools processTools) {
        return new AgentToolResolver(registry, processTools);
    }

    @Bean
    @ConditionalOnMissingBean
    MissionSource missionSource(KikwiflowEngine engine) {
        return new EngineMissionSource(engine);
    }

    @Bean
    @ConditionalOnMissingBean
    MissionWorkerSettings missionWorkerSettings(AgentsProperties props) {
        AgentsProperties.Worker worker = props.worker();
        String workerId = worker.workerId() == null || worker.workerId().isBlank()
                ? "kikwi-agents-" + UUID.randomUUID() : worker.workerId();
        return new MissionWorkerSettings(workerId, worker.topics(), worker.maxTasks(), worker.leaseDuration(),
                worker.transcriptWindow(), "resultado", "HUMANO", worker.handoffMessage(), worker.failureRetries(),
                worker.failureRetryDelay());
    }

    @Bean
    @ConditionalOnMissingBean
    AgentTurnProcessor agentTurnProcessor(LlmClientResolver llm, AgentToolResolver resolver, ToolRegistry tools,
                                          MissionSource source, MissionWorkerSettings settings, ObjectMapper mapper,
                                          Clock clock, AttachmentStore attachments) {
        // Fotos e PDFs chegam ao modelo lidos do mesmo armazenamento onde o canal os guardou (tenant conferido lá).
        AttachmentReader reader = (tenantId, attachment) -> attachments.open(tenantId, attachment.uri()).map(in -> {
            try (in) {
                return in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return new AgentTurnProcessor(llm, resolver, tools,
                new MissionSourceActivityRecorder(source, settings.workerId()), mapper, clock, 6, reader);
    }

    @Bean
    @ConditionalOnMissingBean
    MissionWorker missionWorker(MissionSource source, AgentCatalog agents, AgentTurnProcessor processor,
                                ConversationStore store, OutboundGateway outbound, Clock clock,
                                MissionWorkerSettings settings) {
        return new MissionWorker(source, agents, processor, store, store, store, outbound, clock, settings);
    }

    @Bean
    ThreadPoolTaskScheduler missionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("missions-");
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    MissionScheduler missionScheduler(MissionWorker worker, ThreadPoolTaskScheduler missionTaskScheduler,
                                      AgentsProperties props) {
        return new MissionScheduler(worker, missionTaskScheduler, props.worker());
    }

    @Bean
    @ConditionalOnMissingBean
    KikwiflowRouteListener routeListener(KikwiflowEngine engine, ConversationStore store, MissionWorker worker,
                                         OutboundGateway outbound) {
        return new KikwiflowRouteListener(engine, store, worker, outbound);
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationService conversationService(ConversationStore store, KikwiflowRouteListener listener, Clock clock,
                                            ObjectProvider<InboundPreprocessor> preprocessor) {
        return new ConversationService(store, listener, clock, preprocessor.getIfAvailable(() -> InboundPreprocessor.NONE));
    }



}
