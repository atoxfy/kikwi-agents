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
package io.kikwiflow.agents.platform.conversation;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.kikwiflow.agents.conversation.ConversationStore;
import io.kikwiflow.agents.conversation.PendingChannelSwitch;
import io.kikwiflow.agents.model.channel.ChannelContext;
import io.kikwiflow.agents.model.channel.ChannelEntry;
import io.kikwiflow.agents.model.channel.ChannelRef;
import io.kikwiflow.agents.model.channel.ChannelType;
import io.kikwiflow.agents.model.channel.ConsentPolicy;
import io.kikwiflow.agents.model.conversation.Attachment;
import io.kikwiflow.agents.model.conversation.Contact;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.ConversationStatus;
import io.kikwiflow.agents.model.conversation.Message;
import io.kikwiflow.agents.model.conversation.MessageAuthor;
import io.kikwiflow.agents.model.conversation.MessagePart;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Conversas no Mongo — contatos, conversas, transcrição e o lock por conversa sobrevivem a reinícios e valem entre
 * nós. Coleções:
 * <ul>
 *   <li>{@code agents_contacts}: o lead (o id vem do roteador de canais, que guarda as identidades por canal);</li>
 *   <li>{@code agents_conversations}: conversas do lead, com o canal por onde ele fala agora;</li>
 *   <li>{@code agents_channel_switches}: perguntas de troca de canal em aberto, com a mensagem que as motivou;</li>
 *   <li>{@code agents_messages}: a transcrição (o "claim" do claim check — a auditoria do motor só guarda a
 *   referência). Anexos ficam como referência; os bytes estão no {@code AttachmentStore}. {@code seq} (ObjectId)
 *   desempata mensagens do mesmo instante;</li>
 *   <li>{@code agents_locks}: lock com dono e expiração, reentrante na mesma thread. A expiração protege contra
 *   um nó que caiu segurando o lock.</li>
 * </ul>
 * Mapeamento explícito (não Jackson): {@link MessagePart} é uma interface selada e o formato no banco precisa ficar
 * estável entre versões.
 */
public class MongoConversationStore implements ConversationStore {

    static final String CONTACTS = "agents_contacts";
    static final String CONVERSATIONS = "agents_conversations";
    static final String MESSAGES = "agents_messages";
    static final String LOCKS = "agents_locks";
    static final String SWITCHES = "agents_channel_switches";

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(60);

    private final MongoCollection<Document> contacts;
    private final MongoCollection<Document> conversations;
    private final MongoCollection<Document> messages;
    private final MongoCollection<Document> locks;
    private final MongoCollection<Document> switches;
    private final Clock clock;
    private final String owner = UUID.randomUUID().toString();
    private final ThreadLocal<Map<String, Integer>> held = ThreadLocal.withInitial(HashMap::new);

    public MongoConversationStore(MongoDatabase database, Clock clock) {
        this.contacts = database.getCollection(CONTACTS);
        this.conversations = database.getCollection(CONVERSATIONS);
        this.messages = database.getCollection(MESSAGES);
        this.locks = database.getCollection(LOCKS);
        this.switches = database.getCollection(SWITCHES);
        this.clock = clock;
        conversations.createIndex(Indexes.ascending("tenantId", "contactId", "status"));
        messages.createIndex(Indexes.ascending("conversationId", "seq"));
        messages.createIndex(Indexes.ascending("conversationId", "channelMessageId"), new IndexOptions().sparse(true));
        locks.createIndex(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    // ── contatos ───────────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<Contact> findContact(String id) {
        return Optional.ofNullable(contacts.find(Filters.eq("_id", id)).first()).map(MongoConversationStore::contact);
    }

    @Override
    public Contact save(Contact contact) {
        Document identities = new Document();
        if (contact.identities() != null) {
            contact.identities().forEach((type, value) -> identities.append(type.name(), value));
        }
        Document document = new Document("_id", contact.id())
                .append("tenantId", contact.tenantId())
                .append("displayName", contact.displayName())
                .append("identities", identities)
                .append("consentGivenAt", date(contact.consentGivenAt()))
                .append("optedOutAt", date(contact.optedOutAt()));
        contacts.replaceOne(Filters.eq("_id", contact.id()), document, new ReplaceOptions().upsert(true));
        return contact;
    }

    private static Contact contact(Document d) {
        Map<ChannelType, String> identities = new EnumMap<>(ChannelType.class);
        Document ids = d.get("identities", Document.class);
        if (ids != null) {
            ids.forEach((type, value) -> identities.put(ChannelType.valueOf(type), (String) value));
        }
        return new Contact(d.getString("_id"), d.getString("tenantId"), d.getString("displayName"), identities,
                instant(d.getDate("consentGivenAt")), instant(d.getDate("optedOutAt")));
    }

    // ── conversas ──────────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<Conversation> findById(String id) {
        return Optional.ofNullable(conversations.find(Filters.eq("_id", id)).first()).map(MongoConversationStore::conversation);
    }

    @Override
    public List<Conversation> findOpenByLead(String tenantId, String contactId) {
        List<Conversation> open = new ArrayList<>();
        conversations.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("contactId", contactId),
                        Filters.ne("status", ConversationStatus.CLOSED.name())))
                .sort(Sorts.descending("startedAt")).forEach(d -> open.add(conversation(d)));
        return open;
    }

    @Override
    public Conversation save(Conversation c) {
        Document document = new Document("_id", c.id())
                .append("tenantId", c.tenantId())
                .append("channel", channelRef(c.channel()))
                .append("contactId", c.contactId())
                .append("status", c.status() == null ? null : c.status().name())
                .append("processInstanceId", c.processInstanceId())
                .append("activeMissionTaskId", c.activeMissionTaskId())
                .append("startedAt", date(c.startedAt()))
                .append("lastMessageAt", date(c.lastMessageAt()));
        conversations.replaceOne(Filters.eq("_id", c.id()), document, new ReplaceOptions().upsert(true));
        return c;
    }

    private static Conversation conversation(Document d) {
        return new Conversation(d.getString("_id"), d.getString("tenantId"), channelRef(d.get("channel", Document.class)),
                d.getString("contactId"), d.getString("status") == null ? null : ConversationStatus.valueOf(d.getString("status")),
                d.getString("processInstanceId"), d.getString("activeMissionTaskId"), instant(d.getDate("startedAt")),
                instant(d.getDate("lastMessageAt")));
    }

    private static Document channelRef(ChannelRef ref) {
        return ref == null ? null : new Document("type", ref.type().name()).append("channelId", ref.channelId());
    }

    private static ChannelRef channelRef(Document d) {
        return d == null ? null : new ChannelRef(ChannelType.valueOf(d.getString("type")), d.getString("channelId"));
    }

    // ── trocas de canal pendentes ──────────────────────────────────────────────────────────────

    private static String switchId(String tenantId, String leadId, String channelId) {
        return tenantId + "|" + leadId + "|" + channelId;
    }

    @Override
    public Optional<PendingChannelSwitch> findPendingSwitch(String tenantId, String leadId, String channelId) {
        return Optional.ofNullable(switches.find(Filters.eq("_id", switchId(tenantId, leadId, channelId))).first())
                .map(MongoConversationStore::pendingSwitch);
    }

    @Override
    public void savePendingSwitch(PendingChannelSwitch p) {
        ChannelContext channel = p.channel();
        Document entry = channel.entry() == null ? null : new Document("processKey", channel.entry().processKey())
                .append("startVariables", channel.entry().startVariables() == null ? null
                        : new Document(channel.entry().startVariables()));
        Document consent = channel.consent() == null ? null
                : new Document("requireOptIn", channel.consent().requireOptIn())
                        .append("optOutKeywords", channel.consent().optOutKeywords())
                        .append("privacyNoticeUrl", channel.consent().privacyNoticeUrl());
        Document document = new Document("_id", switchId(p.tenantId(), p.leadId(), channel.ref().channelId()))
                .append("tenantId", p.tenantId())
                .append("leadId", p.leadId())
                .append("channel", new Document("ref", channelRef(channel.ref())).append("label", channel.label())
                        .append("entry", entry).append("consent", consent))
                .append("openConversationId", p.openConversationId())
                .append("displayName", p.displayName())
                .append("parts", parts(p.parts()))
                .append("receivedAt", date(p.receivedAt()))
                .append("channelMessageId", p.channelMessageId())
                .append("asks", p.asks());
        switches.replaceOne(Filters.eq("_id", document.get("_id")), document, new ReplaceOptions().upsert(true));
    }

    @Override
    public void deletePendingSwitch(String tenantId, String leadId, String channelId) {
        switches.deleteOne(Filters.eq("_id", switchId(tenantId, leadId, channelId)));
    }

    @SuppressWarnings("unchecked")
    private static PendingChannelSwitch pendingSwitch(Document d) {
        Document c = d.get("channel", Document.class);
        Document e = c.get("entry", Document.class);
        Document k = c.get("consent", Document.class);
        ChannelContext channel = new ChannelContext(channelRef(c.get("ref", Document.class)), c.getString("label"),
                e == null ? null : new ChannelEntry(e.getString("processKey"),
                        e.get("startVariables") == null ? null : new HashMap<>(e.get("startVariables", Document.class))),
                k == null ? null : new ConsentPolicy(Boolean.TRUE.equals(k.getBoolean("requireOptIn")),
                        (List<String>) k.get("optOutKeywords"), k.getString("privacyNoticeUrl")));
        return new PendingChannelSwitch(d.getString("tenantId"), d.getString("leadId"), channel,
                d.getString("openConversationId"), d.getString("displayName"), parts(d.getList("parts", Document.class, List.of())),
                instant(d.getDate("receivedAt")), d.getString("channelMessageId"), d.getInteger("asks", 1));
    }

    // ── transcrição ────────────────────────────────────────────────────────────────────────────

    @Override
    public Message append(Message m) {
        List<Document> parts = parts(m.parts());
        Document document = new Document("_id", m.id())
                .append("seq", new ObjectId())
                .append("conversationId", m.conversationId())
                .append("direction", m.direction().name())
                .append("author", new Document("type", m.author().type().name()).append("ref", m.author().ref()))
                .append("parts", parts)
                .append("at", date(m.at()))
                .append("channelMessageId", m.channelMessageId());
        messages.insertOne(document);
        return m;
    }

    @Override
    public List<Message> findLast(String conversationId, int limit) {
        List<Message> last = new ArrayList<>();
        messages.find(Filters.eq("conversationId", conversationId)).sort(Sorts.descending("seq")).limit(limit)
                .forEach(d -> last.add(message(d)));
        Collections.reverse(last);
        return last;
    }

    @Override
    public boolean existsByChannelMessageId(String conversationId, String channelMessageId) {
        if (channelMessageId == null) {
            return false;
        }
        return messages.find(Filters.and(Filters.eq("conversationId", conversationId),
                Filters.eq("channelMessageId", channelMessageId))).first() != null;
    }

    private static List<Document> parts(List<MessagePart> parts) {
        List<Document> documents = new ArrayList<>();
        for (MessagePart part : parts) {
            switch (part) {
                case MessagePart.Text t -> documents.add(new Document("type", "TEXT").append("text", t.text()));
                case MessagePart.File f -> documents.add(new Document("type", "FILE")
                        .append("uri", f.attachment().uri())
                        .append("name", f.attachment().name())
                        .append("mimeType", f.attachment().mimeType())
                        .append("size", f.attachment().size()));
            }
        }
        return documents;
    }

    private static List<MessagePart> parts(Collection<Document> documents) {
        List<MessagePart> parts = new ArrayList<>();
        for (Document p : documents) {
            if ("FILE".equals(p.getString("type"))) {
                Number size = p.get("size", Number.class);
                parts.add(new MessagePart.File(new Attachment(p.getString("uri"), p.getString("name"),
                        p.getString("mimeType"), size == null ? 0 : size.longValue())));
            } else {
                parts.add(new MessagePart.Text(p.getString("text")));
            }
        }
        return parts;
    }

    private static Message message(Document d) {
        List<MessagePart> parts = parts(d.getList("parts", Document.class, List.of()));
        Document author = d.get("author", Document.class);
        return new Message(d.getString("_id"), d.getString("conversationId"),
                Message.Direction.valueOf(d.getString("direction")),
                new MessageAuthor(MessageAuthor.Type.valueOf(author.getString("type")), author.getString("ref")),
                parts, instant(d.getDate("at")), d.getString("channelMessageId"));
    }

    // ── lock ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public <T> T withLock(String conversationKey, Supplier<T> work) {
        Map<String, Integer> mine = held.get();
        if (mine.containsKey(conversationKey)) {
            mine.merge(conversationKey, 1, Integer::sum);
            try {
                return work.get();
            } finally {
                mine.merge(conversationKey, -1, Integer::sum);
            }
        }
        acquire(conversationKey);
        mine.put(conversationKey, 1);
        try {
            return work.get();
        } finally {
            mine.remove(conversationKey);
            locks.deleteOne(Filters.and(Filters.eq("_id", conversationKey), Filters.eq("owner", owner)));
        }
    }

    private void acquire(String key) {
        Instant deadline = clock.instant().plus(LOCK_WAIT);
        long backoff = 20;
        while (true) {
            Instant now = clock.instant();
            try {
                // Pega o lock livre ou vencido; se outro dono o tem válido, o upsert colide no _id e tentamos de novo.
                locks.updateOne(Filters.and(Filters.eq("_id", key), Filters.lt("expiresAt", Date.from(now))),
                        Updates.combine(Updates.set("owner", owner), Updates.set("expiresAt", Date.from(now.plus(LOCK_TTL)))),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
                Document current = locks.find(Filters.eq("_id", key)).first();
                if (current != null && owner.equals(current.getString("owner"))) {
                    return;
                }
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    throw e;
                }
            }
            if (now.isAfter(deadline)) {
                throw new IllegalStateException("Conversa ocupada há mais de " + LOCK_WAIT.toSeconds() + "s: " + key);
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrompido esperando o lock da conversa " + key, e);
            }
            backoff = Math.min(backoff * 2, 500);
        }
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
