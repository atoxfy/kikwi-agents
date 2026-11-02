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
package io.kikwiflow.agents.conversation;

import io.kikwiflow.agents.channel.InboundMessage;
import io.kikwiflow.agents.channel.InboundMessageHandler;
import io.kikwiflow.agents.channel.InboundPreprocessor;
import io.kikwiflow.agents.channel.OutboundMessage;
import io.kikwiflow.agents.model.channel.ChannelContext;
import io.kikwiflow.agents.model.channel.ConsentPolicy;
import io.kikwiflow.agents.model.conversation.Contact;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.ConversationStatus;
import io.kikwiflow.agents.model.conversation.Message;
import io.kikwiflow.agents.model.conversation.MessageAuthor;
import io.kikwiflow.agents.model.conversation.MessagePart;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Porta de entrada de toda mensagem recebida, de qualquer canal, com o lead já identificado pelo roteador de canais.
 * A conversa é do <b>lead</b>, não do canal:
 * <ol>
 *   <li>mesmo canal da conversa aberta → segue;</li>
 *   <li>canal novo e conversa aberta em outro (recente) → a plataforma pergunta, pelo canal novo, se o lead quer
 *   continuar de onde parou ou tratar de outro assunto ({@link RouteDecision.AskChannelSwitch}). Continuar troca o
 *   canal da conversa e registra a troca na transcrição; outro assunto abre conversa nova. A mensagem que motivou a
 *   pergunta é entregue depois da escolha;</li>
 *   <li>nenhuma conversa aberta → conversa nova, que inicia o processo de entrada do canal.</li>
 * </ol>
 * Descarta reentregas e trata opt-out antes de qualquer agente. Tudo sob o {@link ConversationLock} do lead.
 */
public class ConversationService implements InboundMessageHandler {

    /** Conversa parada há mais tempo que isso não é oferecida para continuar: abre-se uma nova direto. */
    static final Duration SWITCH_WINDOW = Duration.ofDays(7);
    /** Depois de perguntar duas vezes sem entender a resposta, segue como outro assunto. */
    static final int MAX_SWITCH_ASKS = 2;
    static final String CONTINUE = "Continuar";
    static final String NEW_SUBJECT = "Outro assunto";

    private static final Set<String> CONTINUE_WORDS = Set.of("1", "continuar", "continua", "continue", "sim", "s",
            "mesmo assunto", "isso", "pode continuar", "quero continuar", "seguir");
    private static final Set<String> NEW_SUBJECT_WORDS = Set.of("2", "outro assunto", "outro", "nao", "n", "novo",
            "nova", "outra coisa", "novo assunto");

    private final ContactRepository contacts;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationLock lock;
    private final PendingChannelSwitchRepository switches;
    private final RouteListener listener;
    private final Clock clock;
    private final InboundPreprocessor preprocessor;

    public ConversationService(ConversationStore store, RouteListener listener, Clock clock) {
        this(store, listener, clock, InboundPreprocessor.NONE);
    }

    /** @param preprocessor ex.: transcrever áudio antes de a mensagem entrar na conversa. */
    public ConversationService(ConversationStore store, RouteListener listener, Clock clock,
                               InboundPreprocessor preprocessor) {
        this(store, store, store, store, store, listener, clock, preprocessor);
    }

    public ConversationService(ContactRepository contacts,
                               ConversationRepository conversations,
                               MessageRepository messages,
                               ConversationLock lock,
                               PendingChannelSwitchRepository switches,
                               RouteListener listener,
                               Clock clock,
                               InboundPreprocessor preprocessor) {
        this.contacts = contacts;
        this.conversations = conversations;
        this.messages = messages;
        this.lock = lock;
        this.switches = switches;
        this.listener = listener;
        this.clock = clock;
        this.preprocessor = preprocessor == null ? InboundPreprocessor.NONE : preprocessor;
    }

    @Override
    public void onInbound(InboundMessage received) {
        // Fora do lock: transcrever um áudio pode levar segundos e não deve segurar a conversa.
        InboundMessage inbound = new InboundMessage(received.tenantId(), received.leadId(), received.displayName(),
                received.channel(), preprocessor.process(received.tenantId(), received.parts()), received.receivedAt(),
                received.channelMessageId());
        RouteDecision decision = lock.withLock(lockKey(inbound.tenantId(), inbound.leadId()), () -> route(inbound));
        listener.onRoute(decision);
    }

    /**
     * O roteador descobriu que dois leads são a mesma pessoa (ex.: o visitante do site informou um telefone que já
     * era de um lead do WhatsApp): as conversas abertas de {@code fromLeadId} passam para {@code toLeadId}. A próxima
     * mensagem segue as regras de sempre (inclusive a pergunta de troca de canal).
     */
    public void linkLeads(String tenantId, String fromLeadId, String toLeadId) {
        if (fromLeadId.equals(toLeadId)) {
            return;
        }
        lock.withLock(lockKey(tenantId, toLeadId), () -> lock.withLock(lockKey(tenantId, fromLeadId), () -> {
            for (Conversation conversation : conversations.findOpenByLead(tenantId, fromLeadId)) {
                conversations.save(conversation.withContact(toLeadId));
            }
            return null;
        }));
    }

    private RouteDecision route(InboundMessage inbound) {
        Contact contact = contacts.findContact(inbound.leadId())
                .orElseGet(() -> contacts.save(new Contact(inbound.leadId(), inbound.tenantId(), inbound.displayName(),
                        Map.of(), null, null)));
        if (!contact.tenantId().equals(inbound.tenantId())) {
            throw new IllegalArgumentException("Lead " + contact.id() + " não pertence ao tenant " + inbound.tenantId());
        }
        ChannelContext channel = inbound.channel();
        List<Conversation> open = conversations.findOpenByLead(inbound.tenantId(), contact.id());

        Optional<PendingChannelSwitch> pending = switches.findPendingSwitch(inbound.tenantId(), contact.id(),
                channel.ref().channelId());
        if (pending.isPresent()) {
            return answerSwitch(pending.get(), inbound, contact, open);
        }

        Optional<Conversation> sameChannel = open.stream()
                .filter(c -> channel.ref().equals(c.channel())).findFirst();
        if (sameChannel.isPresent()) {
            return deliver(sameChannel.get(), contact, channel, inbound.parts(), inbound.receivedAt(),
                    inbound.channelMessageId(), false);
        }

        Optional<Conversation> elsewhere = open.stream()
                .filter(c -> c.status() != ConversationStatus.CLOSED)
                .filter(c -> c.lastMessageAt() != null
                        && Duration.between(c.lastMessageAt(), inbound.receivedAt()).compareTo(SWITCH_WINDOW) < 0)
                .findFirst();
        if (elsewhere.isPresent()) {
            switches.savePendingSwitch(new PendingChannelSwitch(inbound.tenantId(), contact.id(), channel,
                    elsewhere.get().id(), inbound.displayName(), inbound.parts(), inbound.receivedAt(),
                    inbound.channelMessageId(), 1));
            return ask(elsewhere.get(), contact, channel);
        }

        return deliver(newConversation(inbound.tenantId(), contact, channel, inbound.receivedAt()), contact, channel,
                inbound.parts(), inbound.receivedAt(), inbound.channelMessageId(), true);
    }

    private RouteDecision answerSwitch(PendingChannelSwitch pending, InboundMessage answer, Contact contact,
                                       List<Conversation> open) {
        String text = normalize(textOf(answer.parts()));
        Optional<Conversation> previous = open.stream().filter(c -> c.id().equals(pending.openConversationId()))
                .findFirst();
        Boolean continues = CONTINUE_WORDS.contains(text) ? Boolean.TRUE
                : NEW_SUBJECT_WORDS.contains(text) ? Boolean.FALSE : null;
        if (continues == null && previous.isPresent() && pending.asks() < MAX_SWITCH_ASKS) {
            switches.savePendingSwitch(pending.askedAgain());
            return ask(previous.get(), contact, pending.channel());
        }
        switches.deletePendingSwitch(pending.tenantId(), pending.leadId(), pending.channel().ref().channelId());

        ChannelContext channel = pending.channel();
        if (Boolean.TRUE.equals(continues) && previous.isPresent()) {
            Conversation conversation = previous.get();
            String from = conversation.channel() == null ? "outro canal" : labelOf(conversation.channel().type().name());
            conversation = conversations.save(conversation.withChannel(channel.ref()));
            messages.append(note(conversation, "O lead passou a falar pelo " + channel.label() + " (antes: " + from
                    + ") e quis continuar esta conversa. As respostas agora vão pelo " + channel.label() + ".",
                    answer.receivedAt()));
            return deliver(conversation, contact, channel, pending.parts(), pending.receivedAt(),
                    pending.channelMessageId(), false);
        }
        previous.ifPresent(p -> messages.append(note(p, "O lead abriu outro assunto pelo " + channel.label()
                + "; esta conversa continua aberta.", answer.receivedAt())));
        return deliver(newConversation(pending.tenantId(), contact, channel, pending.receivedAt()), contact, channel,
                pending.parts(), pending.receivedAt(), pending.channelMessageId(), true);
    }

    private RouteDecision ask(Conversation previous, Contact contact, ChannelContext channel) {
        String where = previous.channel() == null ? "outro canal" : labelOf(previous.channel().type().name());
        OutboundMessage question = OutboundMessage.choice("Oi! Vi que você já estava conversando com a gente pelo "
                + where + ". Quer continuar de onde parou ou é outro assunto?", List.of(CONTINUE, NEW_SUBJECT));
        return new RouteDecision.AskChannelSwitch(previous, contact.id(), channel.ref(), question);
    }

    private RouteDecision deliver(Conversation conversation, Contact contact, ChannelContext channel,
                                  List<MessagePart> parts, Instant receivedAt, String channelMessageId, boolean isNew) {
        if (channelMessageId != null && messages.existsByChannelMessageId(conversation.id(), channelMessageId)) {
            return new RouteDecision.Ignored(conversation, "reentrega duplicada " + channelMessageId);
        }
        Message message = messages.append(new Message(UUID.randomUUID().toString(), conversation.id(),
                Message.Direction.INBOUND, new MessageAuthor(MessageAuthor.Type.CONTACT, contact.id()), parts,
                receivedAt, channelMessageId));
        conversation = conversations.save(conversation.touchedAt(receivedAt));

        if (isOptOut(channel.consent(), message.text())) {
            contacts.save(new Contact(contact.id(), contact.tenantId(), contact.displayName(), contact.identities(),
                    contact.consentGivenAt(), clock.instant()));
            Conversation closed = conversations.save(conversation.withStatus(ConversationStatus.CLOSED));
            return new RouteDecision.Ignored(closed, "opt-out");
        }
        if (conversation.status() == ConversationStatus.HANDED_OFF) {
            return new RouteDecision.HumanInbox(conversation, message);
        }
        if (conversation.activeMissionTaskId() != null) {
            return new RouteDecision.DeliverToMission(conversation, conversation.activeMissionTaskId(), message);
        }
        if (isNew || conversation.processInstanceId() == null) {
            if (channel.entry() == null || channel.entry().processKey() == null) {
                return new RouteDecision.Buffered(conversation, message);
            }
            Map<String, Object> variables = new HashMap<>();
            if (channel.entry().startVariables() != null) {
                variables.putAll(channel.entry().startVariables());
            }
            variables.put("conversationId", conversation.id());
            variables.put("canal", channel.ref().type().name());
            if (contact.displayName() != null) {
                variables.put("leadNome", contact.displayName());
            }
            return new RouteDecision.StartProcess(conversation, channel.entry().processKey(), variables, message);
        }
        return new RouteDecision.Buffered(conversation, message);
    }

    private Conversation newConversation(String tenantId, Contact contact, ChannelContext channel, Instant at) {
        return conversations.save(new Conversation(UUID.randomUUID().toString(), tenantId, channel.ref(), contact.id(),
                ConversationStatus.OPEN, null, null, at, at));
    }

    private static Message note(Conversation conversation, String text, Instant at) {
        return new Message(UUID.randomUUID().toString(), conversation.id(), Message.Direction.INBOUND,
                new MessageAuthor(MessageAuthor.Type.SYSTEM, "conversas"), List.of(new MessagePart.Text(text)), at,
                null);
    }

    private static String lockKey(String tenantId, String leadId) {
        return tenantId + "|lead|" + leadId;
    }

    private static String labelOf(String channelType) {
        return switch (channelType) {
            case "WEBCHAT" -> "site";
            case "WHATSAPP" -> "WhatsApp";
            case "EMAIL" -> "e-mail";
            default -> channelType.toLowerCase(Locale.ROOT);
        };
    }

    private static String textOf(List<MessagePart> parts) {
        StringBuilder sb = new StringBuilder();
        for (MessagePart part : parts) {
            if (part instanceof MessagePart.Text t) {
                sb.append(t.text()).append(' ');
            }
        }
        return sb.toString();
    }

    /** Minúsculas, sem acento e sem pontuação: "Continuar!" e "continuar" são a mesma resposta. */
    static String normalize(String text) {
        String noAccents = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean isOptOut(ConsentPolicy consent, String text) {
        if (consent == null || consent.optOutKeywords() == null || text == null) {
            return false;
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        return consent.optOutKeywords().stream().anyMatch(k -> normalized.equals(k.trim().toUpperCase(Locale.ROOT)));
    }
}
