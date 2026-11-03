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
package io.kikwiflow.agents.app.kikwi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kikwiflow.agents.platform.agent.AgentCatalogSeeder;
import io.kikwiflow.agents.platform.kikwi.EngineMissionSource;
import io.kikwiflow.agents.platform.kikwi.EngineProcessTools;
import io.kikwiflow.agents.platform.kikwi.KikwiflowRouteListener;
import io.kikwiflow.agents.channel.DeliveryResult;
import io.kikwiflow.agents.channel.InboundMessage;
import io.kikwiflow.agents.channel.OutboundGateway;
import io.kikwiflow.agents.model.channel.ChannelContext;
import io.kikwiflow.agents.model.channel.ChannelRef;
import io.kikwiflow.agents.conversation.ConversationService;
import io.kikwiflow.agents.conversation.memory.InMemoryConversationStore;
import io.kikwiflow.agents.llm.LlmRouter;
import io.kikwiflow.agents.llm.fake.ScriptedLlmClient;
import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.model.agent.AgentModelSettings;
import io.kikwiflow.agents.model.agent.AgentToolRef;
import io.kikwiflow.agents.model.agent.AgentToolType;
import io.kikwiflow.agents.llm.LlmContent;
import io.kikwiflow.agents.runtime.agent.AgentCatalog;
import io.kikwiflow.agents.runtime.agent.InMemoryAgentDefinitionStore;
import io.kikwiflow.agents.runtime.tool.AgentToolResolver;
import io.kikwiflow.agents.runtime.tool.InlineBindingExecutor;
import io.kikwiflow.model.event.ProcessInstanceStarted;
import io.kikwiflow.model.execution.ProcessVariable;
import io.kikwiflow.agents.model.channel.ChannelEntry;
import io.kikwiflow.agents.model.channel.ChannelType;
import io.kikwiflow.agents.model.channel.ConsentPolicy;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.ConversationStatus;
import io.kikwiflow.agents.model.conversation.MessagePart;
import io.kikwiflow.agents.runtime.loop.AgentTurnProcessor;
import io.kikwiflow.agents.runtime.mission.MissionSourceActivityRecorder;
import io.kikwiflow.agents.runtime.mission.MissionWorker;
import io.kikwiflow.agents.runtime.mission.MissionWorkerSettings;
import io.kikwiflow.agents.runtime.tool.BuiltinTools;
import io.kikwiflow.agents.runtime.tool.ToolRegistry;
import io.kikwiflow.model.event.AgentActivityRecorded;
import io.kikwiflow.model.execution.node.ExternalTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ponta a ponta sem rede: widget (mensagem normalizada) → {@code ConversationService} → motor real com o processo
 * {@code processes/pre-vendas.kikwi} → {@code EngineMissionSource} → {@code MissionWorker} → LLM
 * roteirizado → canal.
 */
@DisplayName("Pré-vendas de ponta a ponta (motor em memória)")
class PreVendasMissionFlowTest {

    private static final String TENANT = "loja-exemplo";
    private static final String LEAD = "lead-1";
    private static final ChannelContext SITE = new ChannelContext(new ChannelRef(ChannelType.WEBCHAT, "site-loja"),
            "site", new ChannelEntry("pre-vendas", Map.of("origem", "site")),
            new ConsentPolicy(true, List.of("PARAR"), null));

    private EmbeddedEngine kikwi;
    private ScriptedLlmClient llm;
    private InMemoryConversationStore store;
    private List<String> sent;
    private ConversationService conversations;
    private AgentCatalog catalog;

    @BeforeEach
    void setUp() {
        kikwi = EmbeddedEngine.create(Map.of(
                "criarNegocioCrm", SampleStubHandlers.criarNegocioCrm(),
                "registrarPerda", SampleStubHandlers.registrarPerda(),
                "agendaStub", execution -> execution.setVariable(new ProcessVariable("agendamentoId", "AG-1"))));
        kikwi.deploy("/processes/pre-vendas.kikwi");
        kikwi.deploy("/processes-test/agendar-avaliacao.kikwi");

        // Agentes de exemplo do app (classpath:agents), publicados como no start; o modelo vira o roteirizado.
        catalog = new AgentCatalog(new InMemoryAgentDefinitionStore());
        AgentCatalogSeeder seeder = new AgentCatalogSeeder(catalog, new ObjectMapper());
        for (String key : List.of("pre-vendas", "recuperacao")) {
            try (var in = getClass().getResourceAsStream("/agents/" + key + ".agent.json")) {
                AgentDefinition seeded = seeder.seed(TENANT, in);
                catalog.saveDraft(TENANT, withModel(seeded, List.of()));
                catalog.publish(TENANT, key);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        llm = new ScriptedLlmClient();
        store = new InMemoryConversationStore();
        sent = new ArrayList<>();
        // O roteador de canais do host, de mentira: entrega ao lead e guarda o que foi enviado.
        OutboundGateway channel = (tenantId, leadId, ref, conversationId, message) -> {
            sent.add(((MessagePart.Text) message.parts().get(0)).text());
            return DeliveryResult.ok("out");
        };

        Clock clock = Clock.systemUTC();
        EngineMissionSource source = new EngineMissionSource(kikwi.engine());
        MissionWorkerSettings settings = new MissionWorkerSettings("worker-e2e", List.of("kikwi.agents"), 5,
                Duration.ofMinutes(10), 40, "resultado", "HUMANO", "Vou chamar uma pessoa do time.", 2,
                Duration.ofMinutes(1));
        EngineProcessTools processTools = new EngineProcessTools(kikwi.engine(), kikwi.definitions(), new ObjectMapper());
        ToolRegistry registry = new ToolRegistry().registerExecutor(new InlineBindingExecutor())
                .registerExecutor(processTools);
        AgentTurnProcessor processor = new AgentTurnProcessor(new LlmRouter(List.of(llm)),
                new AgentToolResolver(registry, processTools), registry,
                new MissionSourceActivityRecorder(source, settings.workerId()), new ObjectMapper(), clock, 4);
        MissionWorker worker = new MissionWorker(source, catalog, processor, store, store, store, channel, clock,
                settings);
        conversations = new ConversationService(store, new KikwiflowRouteListener(kikwi.engine(), store, worker, channel),
                clock);
    }

    /** O agente de exemplo com o modelo roteirizado e as ferramentas dadas. */
    private static AgentDefinition withModel(AgentDefinition d, List<AgentToolRef> tools) {
        return new AgentDefinition(d.key(), 0, d.status(), d.tenantId(), d.name(), d.description(), d.instructions(),
                d.parameters(), d.parameterValues(), new AgentModelSettings("fake:roteiro", null, null), d.perception(),
                tools, d.subagents(), d.limits());
    }

    /** Publica uma versão do pré-vendas cuja ferramenta {@code agendar_visita} é o processo agendar-avaliacao. */
    private void publishAgentWithSchedulingProcess() {
        AgentDefinition current = catalog.find(TENANT, "pre-vendas", null).orElseThrow();
        catalog.saveDraft(TENANT, withModel(current,
                List.of(new AgentToolRef("agendar_visita", AgentToolType.PROCESS, "agendar-avaliacao", null))));
        catalog.publish(TENANT, "pre-vendas");
    }

    private void leadSays(String text) {
        conversations.onInbound(new InboundMessage(TENANT, LEAD, "Maria", SITE, List.of(new MessagePart.Text(text)),
                Instant.now(), null));
    }

    private Conversation conversation() {
        return store.findOpenByLead(TENANT, LEAD).getFirst();
    }

    private ExternalTask mission() {
        return kikwi.repository().findExternalTasksByProcessInstanceId(conversation().processInstanceId()).stream()
                .filter(t -> "QUALIFICAR".equals(t.taskDefinitionId()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Dado um lead novo no site, quando conversa até agendar, então o processo cria o negócio e termina")
    void scheduledVisitFinishesProcess() {
        llm.thenReply("Oi Maria! Qual é o carro, ano e km?");

        leadSays("Oi, quero vender meu carro");

        assertThat(sent).containsExactly("Oi Maria! Qual é o carro, ano e km?");
        ExternalTask mission = mission();
        assertThat(mission.lockOwner()).isEqualTo("worker-e2e");
        assertThat(conversation().activeMissionTaskId()).isEqualTo(mission.id());
        AgentActivityRecorded started = (AgentActivityRecorded) kikwi.events("AGENT_MISSION_STARTED").get(0)
                .getPayload();
        assertThat(started.data()).containsEntry("agentKey", "pre-vendas").containsEntry("model", "fake:roteiro");
        // Instruções do agente com os parâmetros da versão; o nome do lead vem como dado, fora da instrução.
        assertThat(llm.received().get(0).system())
                .contains("Você é Ana, consultora da Loja Exemplo")
                .contains("Nome informado: «contatoNome»")
                .contains("- contatoNome: Maria");

        llm.thenCallTool(BuiltinTools.CONCLUIR_MISSAO, Map.of(
                        "resultado", "AGENDADO",
                        "dataAgendamento", "2026-10-31T10:00:00-03:00",
                        "agenciaId", "AG-POA-01",
                        "veiculoModelo", "Gol",
                        "fotosVeiculo", List.of(Map.of("uri", "dms://doc-1/1.0", "name", "frente.jpg",
                                "mimeType", "image/jpeg", "size", 1234))));

        leadSays("Gol 2018, 80 mil km. Sábado às 10h na agência do centro, pode ser");
        // CRIAR_NEGOCIO_CRM tem commitBefore: em produção quem o executa é o TaskAcquirer.
        kikwi.drainExecutableTasks(conversation().processInstanceId());

        assertThat(kikwi.eventNames()).contains("EXTERNAL_TASK_LOCKED", "AGENT_MISSION_COMPLETED",
                "PROCESS_INSTANCE_FINISHED");
        AgentActivityRecorded completed = (AgentActivityRecorded) kikwi.events("AGENT_MISSION_COMPLETED").get(0)
                .getPayload();
        assertThat(completed.data()).containsKeys("outputs", "outputsDigest");
        assertThat(completed.data().get("report")).isInstanceOfSatisfying(Map.class,
                report -> assertThat(report.get("turns")).isEqualTo(2));
        assertThat(conversation().activeMissionTaskId()).isNull();
    }

    @Test
    @DisplayName("Dado o agente conclui com data inválida, quando o motor recusa, então o agente pergunta de novo ao lead")
    void rejectedOutputsGoBackToTheLead() {
        llm.thenReply("Oi! Qual é o carro?");
        leadSays("Oi");
        llm.thenCallTool(BuiltinTools.CONCLUIR_MISSAO, Map.of("resultado", "AGENDADO", "dataAgendamento", "sábado"))
                .thenReply("Combinado! Qual a data do sábado e o horário?");

        leadSays("Pode ser sábado");

        assertThat(sent).last().isEqualTo("Combinado! Qual a data do sábado e o horário?");
        assertThat(llm.received().getLast().messages().getLast().content().toString())
                .contains("[Sistema] O sistema recusou a conclusão").contains("dataAgendamento");
        assertThat(mission().lockOwner()).isEqualTo("worker-e2e");
        assertThat(kikwi.eventNames()).doesNotContain("AGENT_MISSION_COMPLETED");
    }

    @Test
    @DisplayName("Dado o lead pede uma pessoa, quando o agente escala, então o processo abre o atendimento humano")
    void handOffRoutesToHumanTask() {
        llm.thenReply("Oi! Qual é o carro?");
        leadSays("Oi");
        llm.thenCallTool(BuiltinTools.ESCALAR_HUMANO, Map.of("motivo", "pedido de humano"));

        leadSays("Quero falar com uma pessoa");

        assertThat(sent).last().isEqualTo("Vou chamar uma pessoa do time.");
        assertThat(kikwi.repository().findExternalTasksByProcessInstanceId(conversation().processInstanceId()))
                .extracting(ExternalTask::taskDefinitionId).containsExactly("ATENDIMENTO_HUMANO");
        assertThat(conversation().status()).isEqualTo(ConversationStatus.HANDED_OFF);
        assertThat(kikwi.eventNames()).contains("AGENT_HANDOFF", "AGENT_MISSION_COMPLETED");
    }

    @Test
    @DisplayName("Dado o agente tem um processo como ferramenta, quando agenda, então o processo roda e o agente recebe só o retorno")
    void processToolRunsAndReturnsOnlyItsReturn() {
        publishAgentWithSchedulingProcess();
        llm.thenReply("Oi Maria! Qual é o carro, ano e km?");
        leadSays("Oi, quero vender meu carro");

        llm.thenCallTool("agendar_visita", Map.of("dataHora", "2026-11-07T10:00:00-03:00", "agenciaId", "AG-POA-01"))
                .thenCallTool(BuiltinTools.CONCLUIR_MISSAO, Map.of(
                        "resultado", "AGENDADO",
                        "dataAgendamento", "2026-11-07T10:00:00-03:00",
                        "agenciaId", "AG-POA-01"));
        leadSays("Sábado às 10h na loja do centro");
        kikwi.drainExecutableTasks(conversation().processInstanceId());

        // A ferramenta chegou ao modelo descrita pelo processo, com o schema das entradas do start.
        var toolSeen = llm.received().getLast().tools().stream().filter(t -> t.name().equals("agendar_visita"))
                .findFirst().orElseThrow();
        assertThat(toolSeen.description()).contains("Agenda a avaliação presencial").contains("Retorna: agendamentoId, dataHora");
        assertThat(toolSeen.inputSchema().get("required")).isEqualTo(List.of("dataHora"));
        // conversationId é obrigatório no processo, mas vem do contexto da missão: o modelo nem vê.
        assertThat(((Map<?, ?>) toolSeen.inputSchema().get("properties")).containsKey("conversationId")).isFalse();

        // O resultado devolvido ao modelo é só o retorno do processo.
        // (a última chamada pode ser o pedido da despedida, quando o modelo conclui sem escrever)
        String toolResult = llm.received().stream().flatMap(r -> r.messages().getLast().content().stream())
                .filter(LlmContent.ToolResult.class::isInstance).map(c -> ((LlmContent.ToolResult) c).content())
                .filter(c -> c.contains("CONCLUIDO"))
                .findFirst().orElseThrow();
        assertThat(toolResult).contains("CONCLUIDO").contains("AG-1").doesNotContain("agenciaId");

        // O processo-ferramenta rodou de verdade no motor, com o rastro da missão.
        assertThat(kikwi.events("PROCESS_INSTANCE_STARTED")).extracting(e -> (ProcessInstanceStarted) e.getPayload())
                .anySatisfy(started -> assertThat(started.origin()).startsWith("agent-task:"));
        assertThat(kikwi.eventNames()).contains("AGENT_TOOL_INVOKED", "AGENT_MISSION_COMPLETED");
    }

    @Test
    @DisplayName("Dado o agente chama o processo sem uma entrada obrigatória, então recebe o erro para corrigir com o lead")
    void processToolRejectsMissingInput() {
        publishAgentWithSchedulingProcess();
        llm.thenReply("Oi! Qual é o carro?");
        leadSays("Oi");

        llm.thenCallTool("agendar_visita", Map.of("agenciaId", "AG-POA-01"))
                .thenReply("Qual dia e horário ficam bons para você?");
        leadSays("Pode agendar");

        String toolResult = llm.received().getLast().messages().getLast().content().stream()
                .filter(LlmContent.ToolResult.class::isInstance).map(c -> ((LlmContent.ToolResult) c).content())
                .findFirst().orElseThrow();
        assertThat(toolResult).contains("Entradas recusadas").contains("dataHora");
        assertThat(sent).last().isEqualTo("Qual dia e horário ficam bons para você?");
    }
}
