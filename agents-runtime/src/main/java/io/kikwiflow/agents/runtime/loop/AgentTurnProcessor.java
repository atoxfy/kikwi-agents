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
package io.kikwiflow.agents.runtime.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kikwiflow.agents.llm.LlmClient;
import io.kikwiflow.agents.llm.LlmContent;
import io.kikwiflow.agents.llm.LlmMessage;
import io.kikwiflow.agents.llm.LlmRequest;
import io.kikwiflow.agents.llm.LlmResponse;
import io.kikwiflow.agents.llm.LlmRouter;
import io.kikwiflow.agents.llm.LlmTool;
import io.kikwiflow.agents.llm.LlmUsage;
import io.kikwiflow.agents.llm.ToolChoice;
import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.model.agent.ModelRef;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.Message;
import io.kikwiflow.agents.model.conversation.MessageAuthor;
import io.kikwiflow.agents.model.conversation.MessagePart;
import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.model.mission.OutputField;
import io.kikwiflow.agents.model.tool.ToolBinding;
import io.kikwiflow.agents.model.tool.ToolDefinition;
import io.kikwiflow.agents.runtime.audit.ActivityRecorder;
import io.kikwiflow.agents.runtime.audit.AgentActivity;
import io.kikwiflow.agents.runtime.audit.AgentActivityType;
import io.kikwiflow.agents.runtime.audit.Hashing;
import io.kikwiflow.agents.runtime.prompt.OutputSchemaBuilder;
import io.kikwiflow.agents.runtime.prompt.SystemPromptAssembler;
import io.kikwiflow.agents.runtime.tool.BuiltinTools;
import io.kikwiflow.agents.runtime.tool.ToolContext;
import io.kikwiflow.agents.runtime.tool.AgentToolResolver;
import io.kikwiflow.agents.runtime.tool.ToolRegistry;
import io.kikwiflow.agents.runtime.tool.ToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Processa um turno de uma missão: monta o pedido ao LLM, executa as ferramentas que ele pedir (com auditoria) e
 * devolve um {@link TurnOutcome}. Dois modos ({@code AgentDefinition.mode}): {@link #processTurn} para o agente
 * conversacional (uma mensagem do contato) e {@link #processTask} para o agente de tarefa técnica (sem interlocutor:
 * trabalha com os dados da missão e as ferramentas até concluir).
 *
 * <p>É sem estado: a transcrição vem do repositório de mensagens e a missão vem do motor. Dá para reprocessar o
 * mesmo turno depois de um crash, com uma ressalva: ferramentas com {@code sideEffect} já executadas aparecem na
 * auditoria e precisam ser idempotentes do lado de quem as implementa.
 *
 * <p>Na conversa, o texto que o modelo escreve no turno (inclusive junto de chamadas de ferramenta) vai para o contato.
 */
public class AgentTurnProcessor {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnProcessor.class);
    private static final int MAX_TOKENS = 1024;
    /** Resultado do concluir_missao quando o modelo concluiu sem escrever nada ao lead. */
    static final String CLOSING_REQUEST = "Missão concluída e registrada. Escreva agora a sua última mensagem ao contato, "
            + "em texto: confirme o que ficou combinado (datas, horários, locais, próximos passos) ou despeça-se com cordialidade. "
            + "Não chame ferramentas.";
    /** A ordem que abre uma missão de tarefa técnica (não há mensagem de ninguém para responder). */
    static final String TASK_KICKOFF = "Execute a missão agora com os dados do atendimento e as ferramentas disponíveis. "
            + "Ao terminar, chame " + BuiltinTools.CONCLUIR_MISSAO + " com o resultado.";
    /** Quando o modelo de uma tarefa escreve em vez de concluir. */
    public static final String TASK_NUDGE = "Ninguém vai ler esta resposta: esta missão é uma tarefa sem interlocutor. "
            + "Continue com as ferramentas e conclua com " + BuiltinTools.CONCLUIR_MISSAO + ", ou chame "
            + BuiltinTools.ESCALAR_HUMANO + " se precisar de uma pessoa.";
    /** Imagens/PDFs entregues ao modelo por turno (os mais recentes); os demais ficam só como referência. */
    static final int MAX_MEDIA = 8;
    /** Limite por arquivo dos provedores para imagem inline. */
    static final long MAX_MEDIA_BYTES = 5L * 1024 * 1024;

    private final LlmClientResolver llm;
    private final AgentToolResolver toolResolver;
    private final ToolRegistry registry;
    private final ActivityRecorder recorder;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxToolIterations;
    private final AttachmentReader attachments;

    /**
     * @param toolResolver monta as ferramentas do agente para a missão (processos lidos do motor, código do runtime).
     * @param registry     executa as ferramentas, pelo binding.
     */
    public AgentTurnProcessor(LlmClientResolver llm, AgentToolResolver toolResolver, ToolRegistry registry,
                              ActivityRecorder recorder, ObjectMapper mapper, Clock clock, int maxToolIterations) {
        this(llm, toolResolver, registry, recorder, mapper, clock, maxToolIterations, AttachmentReader.NONE);
    }

    /**
     * @param attachments lê fotos e PDFs da conversa para o modelo ver, conforme a {@code perception} do agente.
     */
    public AgentTurnProcessor(LlmClientResolver llm, AgentToolResolver toolResolver, ToolRegistry registry,
                              ActivityRecorder recorder, ObjectMapper mapper, Clock clock, int maxToolIterations,
                              AttachmentReader attachments) {
        this.attachments = attachments == null ? AttachmentReader.NONE : attachments;
        this.llm = llm;
        this.toolResolver = toolResolver;
        this.registry = registry;
        this.recorder = recorder;
        this.mapper = mapper;
        this.clock = clock;
        this.maxToolIterations = maxToolIterations;
    }

    /** Clientes globais por provedor (sem credencial por tenant). */
    public AgentTurnProcessor(LlmRouter router, AgentToolResolver toolResolver, ToolRegistry registry,
                              ActivityRecorder recorder, ObjectMapper mapper, Clock clock, int maxToolIterations) {
        this(LlmClientResolver.of(router), toolResolver, registry, recorder, mapper, clock, maxToolIterations);
    }

    /** A atividade que complementa o {@code AGENT_MISSION_STARTED} do motor com o que só o runtime sabe. */
    public AgentActivity missionStarted(Mission mission, AgentDefinition agent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentKey", agent.key());
        data.put("agentVersion", agent.version());
        data.put("model", agent.modelRef().toString());
        data.put("tools", List.copyOf(allowedTools(mission, agent)));
        data.put("promptHash", Hashing.shortSha256(SystemPromptAssembler.assemble(agent, mission)));
        data.put("mode", agent.mode().json());
        if (!agent.isTask()) {
            data.put("conversationRef", mission.businessKey());
        }
        return new AgentActivity(AgentActivityType.AGENT_MISSION_STARTED, clock.instant(), data);
    }

    public TurnOutcome processTurn(Mission mission, AgentDefinition agent, Conversation conversation,
                                   List<Message> transcript) {
        List<LlmMessage> messages = toLlmMessages(transcript, mediaFor(mission.tenantId(), agent, transcript));
        return run(mission, agent, conversation, messages, false);
    }

    /**
     * Um passo de uma missão de tarefa técnica: sem conversa, o pedido começa pela ordem de executar a missão e
     * {@code history} guarda o que o modelo já fez (o worker reaproveita entre passos, ex.: depois de o motor recusar o
     * contrato). Um {@link TurnOutcome.Reply} aqui é o modelo escrevendo em vez de concluir: o worker insiste ou falha.
     *
     * @param history mutável; vazio no primeiro passo.
     */
    public TurnOutcome processTask(Mission mission, AgentDefinition agent, List<LlmMessage> history) {
        if (history.isEmpty()) {
            history.add(new LlmMessage(LlmMessage.Role.USER, List.of(new LlmContent.Text(TASK_KICKOFF))));
        }
        return run(mission, agent, null, history, true);
    }

    private TurnOutcome run(Mission mission, AgentDefinition agent, Conversation conversation, List<LlmMessage> messages,
                            boolean task) {
        Set<String> allowed = allowedTools(mission, agent);
        Map<String, ToolDefinition> businessTools = new LinkedHashMap<>();
        toolResolver.resolve(mission, agent, allowed).forEach(tool -> businessTools.put(tool.id(), tool));
        ModelRef model = agent.modelRef();
        LlmClient client = llm.clientFor(mission.tenantId(), model);

        List<LlmTool> tools = new ArrayList<>();
        for (ToolDefinition tool : businessTools.values()) {
            tools.add(new LlmTool(tool.id(), tool.description(), tool.inputSchema()));
        }
        tools.add(new LlmTool(BuiltinTools.CONCLUIR_MISSAO, task
                ? "Entrega o resultado da tarefa. Use quando tiver todos os campos obrigatórios, sem inventar valores."
                : "Encerra a missão entregando os dados coletados. Use só quando tiver os campos obrigatórios.",
                OutputSchemaBuilder.build(mission.outputContract())));
        tools.add(new LlmTool(BuiltinTools.ESCALAR_HUMANO, task
                ? "Pede a revisão de uma pessoa: quando faltam dados que nenhuma ferramenta dá ou a decisão exige alguém."
                : "Transfere a conversa para um atendente humano.",
                Map.of("type", "object",
                        "properties", Map.of("motivo", Map.of("type", "string")),
                        "required", List.of("motivo"))));

        String system = SystemPromptAssembler.assemble(agent, mission);
        LlmUsage usage = LlmUsage.zero();
        ToolContext context = new ToolContext(mission, conversation);
        // O que o modelo escreve junto de uma chamada de ferramenta também é para o lead (ex.: "Combinado!" ao
        // agendar): acumula no turno e sai junto da resposta final ou da despedida.
        List<String> said = new ArrayList<>();

        for (int iteration = 0; iteration < maxToolIterations; iteration++) {
            Integer maxTokens = agent.model().maxOutputTokens() == null ? MAX_TOKENS : agent.model().maxOutputTokens();
            LlmResponse response = client.generate(new LlmRequest(model.model(), system, messages, tools,
                    null, maxTokens, agent.model().temperature(), null));
            usage = usage.plus(response.usage());
            String text = response.text();
            if (text != null && !text.isBlank()) {
                said.add(text.strip());
            }
            List<LlmContent.ToolCall> calls = response.toolCalls();
            if (calls.isEmpty()) {
                return new TurnOutcome.Reply(String.join("\n\n", said), usage);
            }

            messages.add(new LlmMessage(LlmMessage.Role.ASSISTANT, response.content()));
            List<LlmContent> results = new ArrayList<>();
            for (LlmContent.ToolCall call : calls) {
                switch (call.name()) {
                    case BuiltinTools.CONCLUIR_MISSAO -> {
                        List<String> missing = missingRequired(mission.outputContract(), call.input());
                        if (missing.isEmpty()) {
                            String farewell = String.join("\n\n", said);
                            if (task) {
                                // Ninguém lê: sem despedida.
                                return new TurnOutcome.Completed(call.input(), "", usage);
                            }
                            if (!farewell.isBlank()) {
                                return new TurnOutcome.Completed(call.input(), farewell, usage);
                            }
                            // Há modelos (ex.: Gemini) que concluem só com chamadas de ferramenta, sem uma palavra ao
                            // lead: a última mensagem é pedida numa chamada sem ferramentas, depois do aceite do motor.
                            List<LlmContent> closing = new ArrayList<>(results);
                            closing.add(new LlmContent.ToolResult(call.id(), CLOSING_REQUEST, false));
                            List<LlmMessage> closingMessages = new ArrayList<>(messages);
                            closingMessages.add(new LlmMessage(LlmMessage.Role.USER, closing));
                            LlmRequest closingRequest = new LlmRequest(model.model(), system, closingMessages, tools,
                                    ToolChoice.none(), maxTokens, agent.model().temperature(), null);
                            return new TurnOutcome.Completed(call.input(), "", usage,
                                    () -> lateFarewell(client, closingRequest, mission));
                        }
                        results.add(new LlmContent.ToolResult(call.id(),
                                "Faltam campos obrigatórios: " + String.join(", ", missing) + (task
                                        ? ". Obtenha pelos dados ou pelas ferramentas; se não for possível, chame "
                                                + BuiltinTools.ESCALAR_HUMANO + "."
                                        : ". Pergunte ao contato antes de concluir."), true));
                    }
                    case BuiltinTools.ESCALAR_HUMANO -> {
                        Object motivo = call.input().get("motivo");
                        String reason = motivo == null ? "não informado" : motivo.toString();
                        recorder.record(mission, new AgentActivity(AgentActivityType.AGENT_HANDOFF, clock.instant(),
                                Map.of("reason", reason, "toHuman", true)));
                        return new TurnOutcome.HandedOff(reason, usage);
                    }
                    default -> results.add(invokeBusinessTool(mission, context, businessTools, call));
                }
            }
            messages.add(new LlmMessage(LlmMessage.Role.USER, results));
        }
        return new TurnOutcome.Exhausted("Limite de " + maxToolIterations + " iterações de ferramenta no turno", usage);
    }

    private static String lateFarewell(LlmClient client, LlmRequest request, Mission mission) {
        try {
            String text = client.generate(request).text();
            return text == null ? "" : text.strip();
        } catch (RuntimeException e) {
            // A conclusão já valeu: sem despedida, o lead só não recebe a última mensagem.
            log.warn("Missão {} concluída sem mensagem final ao contato: {}", mission.taskId(), e.getMessage());
            return "";
        }
    }

    private LlmContent.ToolResult invokeBusinessTool(Mission mission, ToolContext context,
                                                     Map<String, ToolDefinition> available, LlmContent.ToolCall call) {
        Instant start = clock.instant();
        ToolDefinition tool = available.get(call.name());
        if (tool == null) {
            auditTool(mission, call, null, "DENIED", start);
            return new LlmContent.ToolResult(call.id(), "Ferramenta '" + call.name() + "' não disponível nesta missão.",
                    true);
        }
        try {
            ToolResult result = registry.execute(tool, context, call.input());
            auditTool(mission, call, tool.binding(), result.ok() ? "OK" : "ERROR", start);
            return new LlmContent.ToolResult(call.id(), result.content(), !result.ok());
        } catch (RuntimeException e) {
            auditTool(mission, call, tool.binding(), "ERROR", start);
            return new LlmContent.ToolResult(call.id(), "Falha ao executar a ferramenta. Não tente de novo agora.",
                    true);
        }
    }

    private void auditTool(Mission mission, LlmContent.ToolCall call, ToolBinding binding, String outcome,
                           Instant start) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolId", call.name());
        data.put("binding", binding == null ? null : binding.getClass().getSimpleName().toUpperCase());
        data.put("inputHash", Hashing.sha256(toJson(call.input())));
        data.put("outcome", outcome);
        data.put("durationMs", Duration.between(start, clock.instant()).toMillis());
        recorder.record(mission, new AgentActivity(AgentActivityType.AGENT_TOOL_INVOKED, clock.instant(), data));
    }

    /** As do agente; o {@code tools} do nó, quando preenchido, só restringe (vazio = todas). */
    static Set<String> allowedTools(Mission mission, AgentDefinition agent) {
        Set<String> allowed = new LinkedHashSet<>(agent.toolNames());
        if (mission.toolIds() != null && !mission.toolIds().isEmpty()) {
            allowed.retainAll(mission.toolIds());
        }
        return allowed;
    }

    static List<String> missingRequired(List<OutputField> contract, Map<String, Object> outputs) {
        List<String> missing = new ArrayList<>();
        for (OutputField field : contract) {
            if (!field.required()) {
                continue;
            }
            Object value = outputs.get(field.key());
            boolean empty = value == null
                    || (value instanceof String s && s.isBlank())
                    || (value instanceof List<?> l && l.isEmpty());
            if (empty) {
                missing.add(field.key());
            }
        }
        return missing;
    }

    /**
     * Transcrição → mensagens do LLM. Mensagens consecutivas do mesmo papel são unidas (alguns provedores exigem
     * alternância) e anexos viram uma linha de texto com a referência do anexo — seguida da própria imagem/PDF quando
     * ele está em {@code media}. Notas {@code SYSTEM} (ex.: a
     * rejeição do contrato pelo motor) entram como fala do usuário marcada, porque nunca chegam ao lead.
     */
    static List<LlmMessage> toLlmMessages(List<Message> transcript) {
        return toLlmMessages(transcript, Map.of());
    }

    /**
     * Os anexos que o modelo vai <i>ver</i> neste turno: imagens se o agente entende imagens, PDFs se entende
     * documentos; só os {@link #MAX_MEDIA} mais recentes, até {@link #MAX_MEDIA_BYTES} cada. A chave é a referência
     * do anexo. Os demais seguem só como referência em texto (o modelo ainda pode repassá-los a ferramentas).
     */
    private Map<String, LlmContent.Media> mediaFor(String tenantId, AgentDefinition agent, List<Message> transcript) {
        Map<String, LlmContent.Media> media = new LinkedHashMap<>();
        for (int i = transcript.size() - 1; i >= 0 && media.size() < MAX_MEDIA; i--) {
            for (MessagePart part : transcript.get(i).parts()) {
                if (!(part instanceof MessagePart.File file) || media.size() >= MAX_MEDIA) {
                    continue;
                }
                String mime = file.attachment().mimeType() == null ? "" : file.attachment().mimeType();
                boolean wanted = (agent.perception().images() && mime.startsWith("image/"))
                        || (agent.perception().documents() && mime.equals("application/pdf"));
                if (!wanted || file.attachment().size() > MAX_MEDIA_BYTES) {
                    continue;
                }
                attachments.read(tenantId, file.attachment())
                        .filter(bytes -> bytes.length <= MAX_MEDIA_BYTES)
                        .ifPresent(bytes -> media.put(file.attachment().uri(),
                                new LlmContent.Media(mime, Base64.getEncoder().encodeToString(bytes), null)));
            }
        }
        return media;
    }

    static List<LlmMessage> toLlmMessages(List<Message> transcript, Map<String, LlmContent.Media> media) {
        List<LlmMessage> result = new ArrayList<>();
        LlmMessage.Role currentRole = null;
        List<LlmContent> buffer = new ArrayList<>();
        for (Message message : transcript) {
            LlmMessage.Role role = message.author().type() == MessageAuthor.Type.AGENT
                    ? LlmMessage.Role.ASSISTANT
                    : LlmMessage.Role.USER;
            if (currentRole != null && role != currentRole) {
                result.add(new LlmMessage(currentRole, buffer));
                buffer = new ArrayList<>();
            }
            currentRole = role;
            String prefix = switch (message.author().type()) {
                case HUMAN -> "[Atendente humano] ";
                case SYSTEM -> "[Sistema] ";
                default -> "";
            };
            for (MessagePart part : message.parts()) {
                switch (part) {
                    case MessagePart.Text t -> buffer.add(new LlmContent.Text(prefix + t.text()));
                    case MessagePart.File f -> {
                        buffer.add(new LlmContent.Text("[anexo recebido: " + f.attachment().name() + " ("
                                + f.attachment().mimeType() + ") " + f.attachment().uri() + "]"));
                        LlmContent.Media seen = media.get(f.attachment().uri());
                        if (seen != null) {
                            buffer.add(seen);
                        }
                    }
                }
            }
        }
        if (currentRole != null) {
            result.add(new LlmMessage(currentRole, buffer));
        }
        return result;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
