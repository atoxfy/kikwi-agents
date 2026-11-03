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

import io.kikwiflow.agents.channel.DeliveryResult;
import io.kikwiflow.agents.channel.OutboundGateway;
import io.kikwiflow.agents.channel.OutboundMessage;
import io.kikwiflow.agents.conversation.ConversationLock;
import io.kikwiflow.agents.conversation.ConversationRepository;
import io.kikwiflow.agents.conversation.MessageRepository;
import io.kikwiflow.agents.llm.LlmUsage;
import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.ConversationStatus;
import io.kikwiflow.agents.model.conversation.Message;
import io.kikwiflow.agents.model.conversation.MessageAuthor;
import io.kikwiflow.agents.model.conversation.MessagePart;
import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.runtime.agent.AgentDefinitionRepository;
import io.kikwiflow.agents.runtime.audit.AgentActivity;
import io.kikwiflow.agents.runtime.audit.AgentActivityType;
import io.kikwiflow.agents.runtime.loop.AgentTurnProcessor;
import io.kikwiflow.agents.runtime.loop.TurnOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Liga as missões do motor às conversas (doc 01, "Ciclo de vida de uma conversa"):
 * <ol>
 *   <li>{@link #poll()} adquire missões, liga cada uma à conversa do seu {@code businessKey}, reporta
 *       {@code AGENT_MISSION_STARTED} e roda o primeiro turno (o lead já falou, ou é uma missão de recuperação e o
 *       agente puxa o assunto);</li>
 *   <li>{@link #onMessage} roda um turno a cada mensagem do lead;</li>
 *   <li>{@link #renewLeases()} renova o lease das missões em andamento enquanto o lead não responde;</li>
 *   <li>o resultado do turno vira chamada ao motor: {@code complete}, handoff ({@code complete} com
 *       {@code resultado = HUMANO}), falha técnica ou erro de negócio (opt-out).</li>
 * </ol>
 *
 * <p>As missões em andamento ficam só em memória. Se o app cair, nada se perde: o lease vence, a missão volta à
 * fila e a próxima aquisição (aqui ou em outro nó) relê a transcrição. Turnos da mesma conversa rodam um por vez,
 * sob o {@link ConversationLock}.
 *
 * <p>Nenhuma regra de negócio mora aqui: o que acontece depois de cada saída é rota do processo.
 */
public class MissionWorker {

    /** Erro de negócio lançado quando o lead pede opt-out com uma missão em andamento. */
    public static final String OPT_OUT_ERROR_CODE = "LEAD_OPT_OUT";

    /** Quantas vezes o agente pode tentar de novo depois de o motor recusar o contrato, antes da falha técnica. */
    private static final int MAX_REJECTED_COMPLETIONS = 2;

    private static final Logger log = LoggerFactory.getLogger(MissionWorker.class);

    private final MissionSource source;
    private final AgentDefinitionRepository agents;
    private final AgentTurnProcessor processor;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationLock lock;
    private final OutboundGateway outbound;
    private final Clock clock;
    private final MissionWorkerSettings settings;

    private final Map<String, ActiveMission> active = new ConcurrentHashMap<>();

    public MissionWorker(MissionSource source,
                         AgentDefinitionRepository agents,
                         AgentTurnProcessor processor,
                         ConversationRepository conversations,
                         MessageRepository messages,
                         ConversationLock lock,
                         OutboundGateway outbound,
                         Clock clock,
                         MissionWorkerSettings settings) {
        this.source = source;
        this.agents = agents;
        this.processor = processor;
        this.conversations = conversations;
        this.messages = messages;
        this.lock = lock;
        this.outbound = outbound;
        this.clock = clock;
        this.settings = settings;
    }

    /** Adquire e inicia as missões disponíveis. Devolve quantas foram adquiridas. */
    public int poll() {
        List<Mission> missions = source.fetchAndLock(settings.workerId(), settings.topics(), settings.maxTasks(),
                settings.leaseDuration());
        for (Mission mission : missions) {
            try {
                attach(mission);
            } catch (MissionLeaseLostException e) {
                drop(mission.taskId());
            } catch (RuntimeException e) {
                log.error("Falha ao iniciar a missão {}", mission.taskId(), e);
                ActiveMission orphan = active.get(mission.taskId());
                fail(orphan != null ? orphan : new ActiveMission(mission, null), "Falha ao iniciar: " + e.getMessage());
            }
        }
        return missions.size();
    }

    /**
     * Uma mensagem do lead chegou para a missão {@code taskId}.
     *
     * @return {@code false} se a missão não está em andamento neste nó (ex.: o app reiniciou). A mensagem já está
     *         na transcrição e será lida quando a missão for readquirida.
     */
    public boolean onMessage(String conversationId, String taskId) {
        ActiveMission mission = active.get(taskId);
        if (mission == null || !mission.conversationId().equals(conversationId)) {
            return false;
        }
        lock.withLock(lockKey(conversationId), () -> {
            runTurn(mission);
            return null;
        });
        return true;
    }

    /** O lead pediu para parar: a missão em andamento sai pelo erro de negócio {@value #OPT_OUT_ERROR_CODE}. */
    public void onOptOut(Conversation conversation) {
        ActiveMission mission = conversation.activeMissionTaskId() == null ? null
                : active.get(conversation.activeMissionTaskId());
        if (mission == null) {
            return;
        }
        lock.withLock(lockKey(conversation.id()), () -> {
            optOut(mission);
            return null;
        });
    }

    /** Renova o lease de todas as missões em andamento; solta as que o processo interrompeu. */
    public void renewLeases() {
        for (ActiveMission mission : List.copyOf(active.values())) {
            try {
                source.extendLock(mission.taskId(), settings.workerId(), settings.leaseDuration());
            } catch (MissionLeaseLostException e) {
                log.info("Missão {} não é mais deste worker (boundary do processo ou lease vencido)", mission.taskId());
                lock.withLock(lockKey(mission.conversationId()), () -> {
                    release(mission);
                    return null;
                });
            }
        }
    }

    /** Ids das missões em andamento neste nó. */
    public List<String> activeMissionIds() {
        return List.copyOf(active.keySet());
    }

    private void attach(Mission mission) {
        Optional<AgentDefinition> profile = agents.find(mission.tenantId(), mission.agentKey(), mission.agentVersion());
        if (profile.isEmpty()) {
            // Erro de configuração: tentar de novo não resolve. Incidente direto, visível no Monitor.
            source.fail(mission.taskId(), settings.workerId(), "Agente publicado não encontrado no catálogo: " + mission.agentKey()
                    + (mission.agentVersion() != null ? "@" + mission.agentVersion() : "") + " (tenant "
                    + mission.tenantId() + ")", 0, null);
            return;
        }
        Conversation conversation = conversations.findById(mission.businessKey())
                .filter(c -> c.tenantId().equals(mission.tenantId()))
                .orElse(null);
        if (conversation == null) {
            source.fail(mission.taskId(), settings.workerId(), "Conversa " + mission.businessKey()
                    + " não encontrada para o tenant " + mission.tenantId(), 0, null);
            return;
        }

        ActiveMission started = new ActiveMission(mission, profile.get());
        active.put(mission.taskId(), started);
        lock.withLock(lockKey(conversation.id()), () -> {
            Conversation current = conversations.findById(conversation.id()).orElse(conversation);
            if (current.status() == ConversationStatus.CLOSED) {
                optOut(started);
                return null;
            }
            // Uma missão nova reabre a conversa que estava com humano: o processo decidiu devolvê-la ao agente.
            conversations.save(current.withStatus(ConversationStatus.OPEN)
                    .withMission(mission.processInstanceId(), mission.taskId()));
            source.reportActivities(mission.taskId(), settings.workerId(),
                    List.of(processor.missionStarted(mission, started.profile())));
            runTurn(started);
            return null;
        });
    }

    private void runTurn(ActiveMission mission) {
        Conversation conversation = conversations.findById(mission.conversationId()).orElse(null);
        if (conversation == null || !mission.taskId().equals(conversation.activeMissionTaskId())) {
            drop(mission.taskId());
            return;
        }
        try {
            mission.turns++;
            String limit = limitReached(mission);
            if (limit != null) {
                source.reportActivities(mission.taskId(), settings.workerId(), List.of(new AgentActivity(
                        AgentActivityType.AGENT_HANDOFF, clock.instant(), Map.of("reason", limit, "toHuman", true))));
                handOff(mission, conversation);
                return;
            }

            List<Message> transcript = new ArrayList<>(messages.findLast(conversation.id(), settings.transcriptWindow()));
            if (transcript.isEmpty() || transcript.getLast().author().type() == MessageAuthor.Type.AGENT) {
                // Sem fala nova do lead (missão de recuperação, ou retomada): o agente puxa a conversa. A nota não vai
                // para a transcrição, só para este pedido ao LLM.
                transcript.add(systemNote(conversation, "Retome a conversa com o contato conforme o objetivo da missão."));
            }
            handle(mission, conversation, transcript, 0);
        } catch (MissionLeaseLostException e) {
            log.info("Missão {} interrompida durante o turno", mission.taskId());
            release(mission);
        }
    }

    private void handle(ActiveMission mission, Conversation conversation, List<Message> transcript, int rejections) {
        TurnOutcome outcome;
        try {
            outcome = processor.processTurn(mission.mission(), mission.profile(), conversation, transcript);
        } catch (MissionLeaseLostException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Turno da missão {} falhou", mission.taskId(), e);
            fail(mission, "Falha no turno do agente: " + e.getMessage());
            return;
        }
        mission.usage = mission.usage.plus(outcome.usage());

        switch (outcome) {
            case TurnOutcome.Reply reply -> {
                send(mission, conversation, reply.text());
                source.extendLock(mission.taskId(), settings.workerId(), settings.leaseDuration());
            }
            case TurnOutcome.Completed completed -> {
                try {
                    source.complete(mission.taskId(), settings.workerId(), completed.outputs(), report(mission));
                } catch (MissionOutputRejectedException e) {
                    if (rejections + 1 >= MAX_REJECTED_COMPLETIONS) {
                        fail(mission, "Contrato de saída recusado " + (rejections + 1) + " vezes: " + e.problems());
                        return;
                    }
                    // A nota fica na transcrição: numa retomada o agente ainda sabe por que não concluiu.
                    Message note = messages.append(systemNote(conversation, "O sistema recusou a conclusão: "
                            + String.join("; ", e.problems()) + ". Resolva com o contato e só então conclua de novo."));
                    transcript.add(note);
                    handle(mission, conversation, transcript, rejections + 1);
                    return;
                }
                release(mission);
                send(mission, conversation, completed.resolveFarewell());
            }
            case TurnOutcome.HandedOff handedOff -> handOff(mission, conversation);
            case TurnOutcome.Exhausted exhausted -> fail(mission, exhausted.reason());
        }
    }

    /**
     * A conversa vai para o humano e a missão conclui com {@code resultado = HUMANO}: quem decide para onde o lead
     * vai é o gateway do processo.
     */
    private void handOff(ActiveMission mission, Conversation conversation) {
        conversations.save(conversation.withStatus(ConversationStatus.HANDED_OFF));
        Map<String, Object> outputs = Map.of(settings.handoffOutputKey(), settings.handoffOutputValue());
        try {
            source.complete(mission.taskId(), settings.workerId(), outputs, report(mission));
        } catch (MissionOutputRejectedException e) {
            fail(mission, "O contrato da missão não aceita o handoff (" + settings.handoffOutputKey() + "="
                    + settings.handoffOutputValue() + "): " + e.problems());
            return;
        }
        release(mission);
        send(mission, conversation, settings.handoffMessage());
    }

    private void optOut(ActiveMission mission) {
        try {
            source.throwBusinessError(mission.taskId(), settings.workerId(), OPT_OUT_ERROR_CODE,
                    "O contato pediu para não receber mais mensagens", Map.of());
        } catch (MissionLeaseLostException e) {
            log.info("Missão {} já tinha sido interrompida no opt-out", mission.taskId());
        }
        release(mission);
    }

    private void fail(ActiveMission mission, String message) {
        Integer retries = mission.mission().retries();
        int remaining = Math.max(0, retries == null ? settings.failureRetries() : retries - 1);
        try {
            source.fail(mission.taskId(), settings.workerId(), message, remaining, settings.failureRetryDelay());
        } catch (MissionLeaseLostException e) {
            log.info("Missão {} já tinha sido interrompida ao reportar a falha", mission.taskId());
        }
        release(mission);
    }

    /** Solta a missão e a desliga da conversa (se ainda for a missão ativa dela). */
    private void release(ActiveMission mission) {
        drop(mission.taskId());
        conversations.findById(mission.conversationId())
                .filter(c -> mission.taskId().equals(c.activeMissionTaskId()))
                .ifPresent(c -> conversations.save(c.withMission(c.processInstanceId(), null)));
    }

    private void drop(String taskId) {
        active.remove(taskId);
    }

    private String limitReached(ActiveMission mission) {
        Integer missionMax = mission.mission().limits() == null ? null : mission.mission().limits().maxTurns();
        Integer agentMax = mission.profile().limits().maxTurns();
        int profileMax = agentMax == null ? 0 : agentMax;
        int max = missionMax == null ? profileMax : profileMax <= 0 ? missionMax : Math.min(missionMax, profileMax);
        if (max > 0 && mission.turns > max) {
            return "Limite de " + max + " turnos da missão";
        }
        if (mission.mission().limits() != null && mission.mission().limits().ttl() != null
                && mission.mission().createdAt() != null
                && clock.instant().isAfter(mission.mission().createdAt().plus(mission.mission().limits().ttl()))) {
            return "Prazo da missão (" + mission.mission().limits().ttl() + ") esgotado";
        }
        return null;
    }

    private Map<String, Object> report(ActiveMission mission) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("agentVersion", mission.profile().version());
        report.put("turns", mission.turns);
        report.put("inputTokens", mission.usage.inputTokens());
        report.put("outputTokens", mission.usage.outputTokens());
        return report;
    }

    /**
     * Grava na transcrição e entrega pelo canal. Se a entrega falhar (visitante offline), a mensagem continua na
     * transcrição e o canal a reapresenta quando o lead voltar.
     */
    private void send(ActiveMission mission, Conversation conversation, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        messages.append(new Message(UUID.randomUUID().toString(), conversation.id(), Message.Direction.OUTBOUND,
                new MessageAuthor(MessageAuthor.Type.AGENT, mission.profile().key() + "@" + mission.profile().version()),
                List.of(new MessagePart.Text(text)), clock.instant(), null));

        // Responde pelo canal por onde o lead está falando agora (muda quando ele troca de canal e continua).
        if (conversation.channel() == null) {
            log.warn("Conversa {} sem canal; a resposta ficou só na transcrição", conversation.id());
            return;
        }
        DeliveryResult result = outbound.deliver(conversation.tenantId(), conversation.contactId(),
                conversation.channel(), conversation.id(), OutboundMessage.text(text));
        if (!result.delivered()) {
            log.info("Resposta da conversa {} não entregue agora ({}); ficou na transcrição", conversation.id(),
                    result.error());
        }
    }

    private Message systemNote(Conversation conversation, String text) {
        Instant now = clock.instant();
        return new Message(UUID.randomUUID().toString(), conversation.id(), Message.Direction.INBOUND,
                new MessageAuthor(MessageAuthor.Type.SYSTEM, settings.workerId()), List.of(new MessagePart.Text(text)),
                now, null);
    }

    /** Chave própria do runtime: não disputa com o lock de entrada do {@code ConversationService}. */
    private static String lockKey(String conversationId) {
        return "mission|" + conversationId;
    }

    /** Estado de uma missão em andamento; só alterado sob o lock da conversa. */
    private static final class ActiveMission {
        private final Mission mission;
        private final AgentDefinition profile;
        private int turns;
        private LlmUsage usage = LlmUsage.zero();

        private ActiveMission(Mission mission, AgentDefinition profile) {
            this.mission = mission;
            this.profile = profile;
        }

        Mission mission() {
            return mission;
        }

        AgentDefinition profile() {
            return profile;
        }

        String taskId() {
            return mission.taskId();
        }

        String conversationId() {
            return mission.businessKey();
        }
    }
}
