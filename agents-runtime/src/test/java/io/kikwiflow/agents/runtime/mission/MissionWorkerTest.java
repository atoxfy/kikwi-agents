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
package io.kikwiflow.agents.runtime.mission;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kikwiflow.agents.channel.OutboundGateway;
import io.kikwiflow.agents.channel.DeliveryResult;
import io.kikwiflow.agents.channel.OutboundMessage;
import io.kikwiflow.agents.conversation.memory.InMemoryConversationStore;
import io.kikwiflow.agents.llm.LlmRouter;
import io.kikwiflow.agents.llm.fake.ScriptedLlmClient;
import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.model.agent.AgentLimits;
import io.kikwiflow.agents.model.agent.AgentModelSettings;
import io.kikwiflow.agents.model.agent.AgentParameter;
import io.kikwiflow.agents.model.agent.AgentStatus;
import io.kikwiflow.agents.model.agent.AgentToolRef;
import io.kikwiflow.agents.model.agent.AgentToolType;
import io.kikwiflow.agents.model.channel.ChannelRef;
import io.kikwiflow.agents.model.channel.ChannelType;
import io.kikwiflow.agents.model.conversation.Contact;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.ConversationStatus;
import io.kikwiflow.agents.model.conversation.Message;
import io.kikwiflow.agents.model.conversation.MessageAuthor;
import io.kikwiflow.agents.model.conversation.MessagePart;
import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.model.mission.MissionLimits;
import io.kikwiflow.agents.model.mission.OutputField;
import io.kikwiflow.agents.runtime.audit.AgentActivity;
import io.kikwiflow.agents.runtime.audit.AgentActivityType;
import io.kikwiflow.agents.runtime.loop.AgentTurnProcessor;
import io.kikwiflow.agents.runtime.tool.BuiltinTools;
import io.kikwiflow.agents.runtime.tool.AgentToolResolver;
import io.kikwiflow.agents.runtime.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MissionWorker")
class MissionWorkerTest {

    private static final ChannelRef SITE = new ChannelRef(ChannelType.WEBCHAT, "site");

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final String WORKER = "worker-1";

    private final List<OutputField> contract = List.of(
            new OutputField("resultado", "Resultado", null, "SINGLE_SELECT", true,
                    List.of("AGENDADO", "PERDIDO", "HUMANO"), Map.of()),
            new OutputField("dataAgendamento", "Data da visita", null, "DATETIME", false, List.of(), Map.of()));

    private final AgentDefinition profile = new AgentDefinition("pre-vendas", 2, AgentStatus.PUBLISHED,
            "loja-exemplo", "Pré-vendas", null, "Você é a Ana. Seja breve.", List.of(), Map.of(),
            new AgentModelSettings("fake:roteiro", null, null), null, List.of(), List.of(), new AgentLimits(40));

    private ScriptedLlmClient llm;
    private FakeMissionSource source;
    private InMemoryConversationStore store;
    private RecordingChannel channel;
    private MissionWorker worker;

    @BeforeEach
    void setUp() {
        llm = new ScriptedLlmClient();
        source = new FakeMissionSource();
        store = new InMemoryConversationStore();
        channel = new RecordingChannel();

        store.save(new Contact("contact-1", "loja-exemplo", "Maria", Map.of(ChannelType.WEBCHAT, "sess-1"), null, null));
        store.save(new Conversation("conv-1", "loja-exemplo", SITE, "contact-1", ConversationStatus.OPEN, "pi-1", null,
                NOW, NOW));
        store.append(fromLead("Oi, quero vender meu carro"));


        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ToolRegistry registry = new ToolRegistry();
        AgentTurnProcessor processor = new AgentTurnProcessor(new LlmRouter(List.of(llm)),
                new AgentToolResolver(registry, null), registry, new MissionSourceActivityRecorder(source, WORKER),
                new ObjectMapper(), clock, 4);
        MissionWorkerSettings settings = new MissionWorkerSettings(WORKER, List.of("kikwi.agents"), 5,
                Duration.ofMinutes(10), 40, "resultado", "HUMANO", "Vou chamar uma pessoa do time.", 2,
                Duration.ofMinutes(1));
        worker = new MissionWorker(source,
                (tenantId, key, version) -> tenantId.equals(profile.tenantId()) && key.equals(profile.key())
                        ? Optional.of(profile) : Optional.empty(),
                processor, store, store, store, channel, clock, settings);
    }

    private Mission mission(String taskId, String agentKey, MissionLimits limits, Integer retries) {
        return new Mission(taskId, "pi-1", "loja-exemplo", "conv-1", agentKey, null, List.of(),
                "Agende uma visita para {{leadNome}}.", contract, limits, Map.of("leadNome", "Maria"),
                NOW.plusSeconds(600), NOW.minusSeconds(60), retries);
    }

    private static Message fromLead(String text) {
        return new Message("m-" + text.hashCode(), "conv-1", Message.Direction.INBOUND,
                new MessageAuthor(MessageAuthor.Type.CONTACT, "contact-1"), List.of(new MessagePart.Text(text)), NOW,
                null);
    }

    private Conversation conversation() {
        return store.findById("conv-1").orElseThrow();
    }

    private List<Message> transcript() {
        return store.findLast("conv-1", 100);
    }

    @Nested
    @DisplayName("Dado uma missão disponível no motor")
    class Acquisition {

        @Test
        @DisplayName("Quando o worker adquire, então liga a conversa, reporta o início e responde o lead")
        void attachesAndReplies() {
            source.available.add(mission("task-1", "pre-vendas", MissionLimits.none(), null));
            llm.thenReply("Oi Maria! Qual é o carro?");

            assertThat(worker.poll()).isEqualTo(1);

            assertThat(conversation().activeMissionTaskId()).isEqualTo("task-1");
            assertThat(source.activities).extracting(AgentActivity::type)
                    .containsExactly(AgentActivityType.AGENT_MISSION_STARTED);
            assertThat(source.activities.get(0).data()).containsEntry("agentVersion", 2)
                    .containsEntry("conversationRef", "conv-1");
            assertThat(channel.sent).containsExactly("Oi Maria! Qual é o carro?");
            assertThat(transcript().getLast().author()).isEqualTo(
                    new MessageAuthor(MessageAuthor.Type.AGENT, "pre-vendas@2"));
            assertThat(source.extended).contains("task-1");
            assertThat(worker.activeMissionIds()).containsExactly("task-1");
        }

        @Test
        @DisplayName("Quando o perfil do agente não existe, então abre incidente (falha sem retry) e não fala com o lead")
        void unknownProfileOpensIncident() {
            source.available.add(mission("task-1", "agente.inexistente", MissionLimits.none(), null));

            worker.poll();

            assertThat(source.failures).singleElement().satisfies(f -> {
                assertThat(f.retries()).isZero();
                assertThat(f.message()).contains("agente.inexistente");
            });
            assertThat(channel.sent).isEmpty();
            assertThat(conversation().activeMissionTaskId()).isNull();
        }

        @Test
        @DisplayName("Quando o lead não falou desde a última mensagem do agente, então o agente puxa a conversa")
        void recoveryMissionSpeaksFirst() {
            store.append(new Message("m-agent", "conv-1", Message.Direction.OUTBOUND,
                    new MessageAuthor(MessageAuthor.Type.AGENT, "pre-vendas@1"),
                    List.of(new MessagePart.Text("Qual o ano?")), NOW, null));
            source.available.add(mission("task-2", "pre-vendas", MissionLimits.none(), null));
            llm.thenReply("Oi Maria, ainda pensando em vender?");

            worker.poll();

            assertThat(llm.received().get(0).messages().getLast().content().toString())
                    .contains("[Sistema] Retome a conversa");
            assertThat(channel.sent).containsExactly("Oi Maria, ainda pensando em vender?");
        }
    }

    @Nested
    @DisplayName("Dado uma missão em andamento")
    class InProgress {

        @BeforeEach
        void acquire() {
            source.available.add(mission("task-1", "pre-vendas", MissionLimits.none(), null));
            llm.thenReply("Qual é o carro?");
            worker.poll();
            channel.sent.clear();
        }

        @Test
        @DisplayName("Quando o agente conclui, então chama complete com o relatório, solta a conversa e se despede")
        void completes() {
            store.append(fromLead("Gol 2018, pode ser sábado às 10h"));
            llm.thenCallTool(BuiltinTools.CONCLUIR_MISSAO,
                    Map.of("resultado", "AGENDADO", "dataAgendamento", "2026-10-31T10:00:00-03:00"));

            assertThat(worker.onMessage("conv-1", "task-1")).isTrue();

            assertThat(source.completions).singleElement().satisfies(c -> {
                assertThat(c.outputs()).containsEntry("resultado", "AGENDADO");
                assertThat(c.report()).containsEntry("turns", 2).containsEntry("agentVersion", 2)
                        .containsKeys("inputTokens", "outputTokens");
            });
            assertThat(conversation().activeMissionTaskId()).isNull();
            assertThat(worker.activeMissionIds()).isEmpty();
        }

        @Test
        @DisplayName("Quando o motor recusa o contrato, então o agente recebe a nota do sistema e pergunta ao lead")
        void rejectedContractGoesBackToAgent() {
            source.rejectNextComplete = List.of("dataAgendamento: INVALID_FORMAT (data inválida)");
            store.append(fromLead("Pode ser sábado"));
            llm.thenCallTool(BuiltinTools.CONCLUIR_MISSAO, Map.of("resultado", "AGENDADO", "dataAgendamento", "sábado"))
                    .thenReply("Sábado que dia do mês e que horas?");

            worker.onMessage("conv-1", "task-1");

            assertThat(source.completions).isEmpty();
            assertThat(channel.sent).containsExactly("Sábado que dia do mês e que horas?");
            assertThat(transcript()).anySatisfy(m -> {
                assertThat(m.author().type()).isEqualTo(MessageAuthor.Type.SYSTEM);
                assertThat(m.text()).contains("dataAgendamento: INVALID_FORMAT");
            });
            assertThat(conversation().activeMissionTaskId()).isEqualTo("task-1");
        }

        @Test
        @DisplayName("Quando o agente escala, então a conversa vai para humano e a missão conclui com resultado HUMANO")
        void handOff() {
            store.append(fromLead("Quero falar com uma pessoa"));
            llm.thenCallTool(BuiltinTools.ESCALAR_HUMANO, Map.of("motivo", "pedido de humano"));

            worker.onMessage("conv-1", "task-1");

            assertThat(conversation().status()).isEqualTo(ConversationStatus.HANDED_OFF);
            assertThat(source.completions).singleElement()
                    .satisfies(c -> assertThat(c.outputs()).isEqualTo(Map.of("resultado", "HUMANO")));
            assertThat(source.activities).extracting(AgentActivity::type).contains(AgentActivityType.AGENT_HANDOFF);
            assertThat(channel.sent).containsExactly("Vou chamar uma pessoa do time.");
        }

        @Test
        @DisplayName("Quando o LLM falha, então reporta falha técnica com as tentativas configuradas e solta a conversa")
        void llmFailureReportsFailure() {
            store.append(fromLead("Oi?"));
            // Roteiro vazio: o ScriptedLlmClient lança LlmException.

            worker.onMessage("conv-1", "task-1");

            assertThat(source.failures).singleElement().satisfies(f -> assertThat(f.retries()).isEqualTo(2));
            assertThat(conversation().activeMissionTaskId()).isNull();
        }

        @Test
        @DisplayName("Quando um boundary interrompe a missão, então a renovação perde o lease e solta a conversa")
        void boundaryInterruptsMission() {
            source.lost.add("task-1");

            worker.renewLeases();

            assertThat(worker.activeMissionIds()).isEmpty();
            assertThat(conversation().activeMissionTaskId()).isNull();
            assertThat(worker.onMessage("conv-1", "task-1")).isFalse();
        }

        @Test
        @DisplayName("Quando o lead pede opt-out, então a missão sai pelo erro de negócio LEAD_OPT_OUT")
        void optOut() {
            worker.onOptOut(conversation().withStatus(ConversationStatus.CLOSED));

            assertThat(source.businessErrors).containsExactly(MissionWorker.OPT_OUT_ERROR_CODE);
            assertThat(worker.activeMissionIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Dado os limites da missão")
    class Limits {

        @Test
        @DisplayName("Quando o teto de turnos é ultrapassado, então escala para humano sem chamar o LLM")
        void maxTurnsHandsOff() {
            source.available.add(mission("task-1", "pre-vendas", new MissionLimits(1, null), null));
            llm.thenReply("Qual é o carro?");
            worker.poll();
            store.append(fromLead("Gol"));

            worker.onMessage("conv-1", "task-1");

            assertThat(llm.received()).hasSize(1);
            assertThat(source.completions).singleElement()
                    .satisfies(c -> assertThat(c.outputs()).containsEntry("resultado", "HUMANO"));
            assertThat(source.activities).filteredOn(a -> a.type() == AgentActivityType.AGENT_HANDOFF)
                    .singleElement().satisfies(a -> assertThat(a.data().get("reason").toString()).contains("1 turnos"));
        }

        @Test
        @DisplayName("Quando a missão já falhou antes, então a nova falha decrementa as tentativas restantes")
        void retriesDecrement() {
            source.available.add(mission("task-1", "pre-vendas", MissionLimits.none(), 1));
            // Roteiro vazio: o primeiro turno falha.

            worker.poll();

            assertThat(source.failures).singleElement().satisfies(f -> assertThat(f.retries()).isZero());
        }
    }

    record Completion(String taskId, Map<String, Object> outputs, Map<String, Object> report) {
    }

    record Failure(String taskId, String message, int retries) {
    }

    /** Dublê do motor: guarda as chamadas e simula lease perdido e contrato recusado. */
    static class FakeMissionSource implements MissionSource {
        final Deque<Mission> available = new ArrayDeque<>();
        final Set<String> lost = new HashSet<>();
        final List<String> extended = new ArrayList<>();
        final List<Completion> completions = new ArrayList<>();
        final List<Failure> failures = new ArrayList<>();
        final List<String> businessErrors = new ArrayList<>();
        final List<AgentActivity> activities = new ArrayList<>();
        List<String> rejectNextComplete;

        @Override
        public List<Mission> fetchAndLock(String workerId, List<String> topics, int maxTasks, Duration lockDuration) {
            List<Mission> fetched = new ArrayList<>(available);
            available.clear();
            return fetched;
        }

        @Override
        public void extendLock(String taskId, String workerId, Duration lockDuration) {
            check(taskId);
            extended.add(taskId);
        }

        @Override
        public void complete(String taskId, String workerId, Map<String, Object> outputs, Map<String, Object> report) {
            check(taskId);
            if (rejectNextComplete != null) {
                List<String> problems = rejectNextComplete;
                rejectNextComplete = null;
                throw new MissionOutputRejectedException(problems, null);
            }
            completions.add(new Completion(taskId, outputs, report));
            lost.add(taskId);
        }

        @Override
        public void fail(String taskId, String workerId, String message, int retries, Duration retryTimeout) {
            check(taskId);
            failures.add(new Failure(taskId, message, retries));
            lost.add(taskId);
        }

        @Override
        public void throwBusinessError(String taskId, String workerId, String errorCode, String message,
                                       Map<String, Object> variables) {
            check(taskId);
            businessErrors.add(errorCode);
            lost.add(taskId);
        }

        @Override
        public void reportActivities(String taskId, String workerId, List<AgentActivity> reported) {
            check(taskId);
            activities.addAll(reported);
        }

        private void check(String taskId) {
            if (lost.contains(taskId)) {
                throw new MissionLeaseLostException(taskId, "lease perdido", null);
            }
        }
    }

    /** O roteador de canais do host, de mentira: guarda o que foi entregue e por qual canal. */
    static class RecordingChannel implements OutboundGateway {
        final List<String> sent = new ArrayList<>();
        final List<ChannelRef> via = new ArrayList<>();

        @Override
        public DeliveryResult deliver(String tenantId, String leadId, ChannelRef channel, String conversationId,
                                      OutboundMessage message) {
            sent.add(message.parts().stream().map(p -> ((MessagePart.Text) p).text()).reduce("", String::concat));
            via.add(channel);
            return DeliveryResult.ok("out-" + sent.size());
        }
    }
}
