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

import com.mongodb.MongoClientSettings;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
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
import io.kikwiflow.agents.platform.channel.GridFsAttachmentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contra um Mongo de verdade ({@code MONGODB_URI}, padrão localhost), num banco descartável. Sem Mongo acessível o
 * teste é pulado: {@code mvn test} continua rodando sem rede.
 */
@DisplayName("Dado conversas guardadas no Mongo")
class MongoConversationStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final ChannelRef SITE = new ChannelRef(ChannelType.WEBCHAT, "t1-site");

    private MongoClient client;
    private MongoDatabase database;

    @BeforeEach
    void connect() {
        String uri = System.getenv().getOrDefault("MONGODB_URI", "mongodb://localhost:27017/?directConnection=true");
        client = MongoClients.create(MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(1500, TimeUnit.MILLISECONDS)).build());
        boolean up;
        try {
            client.getDatabase("admin").runCommand(new org.bson.Document("ping", 1));
            up = true;
        } catch (RuntimeException e) {
            up = false;
        }
        assumeTrue(up, "Mongo indisponível: teste de integração pulado");
        database = client.getDatabase("kikwi_agents_it_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterEach
    void drop() {
        if (database != null) {
            database.drop();
        }
        if (client != null) {
            client.close();
        }
    }

    private static Message message(String id, String conversationId, Instant at, MessagePart... parts) {
        return new Message(id, conversationId, Message.Direction.INBOUND,
                new MessageAuthor(MessageAuthor.Type.CONTACT, "c-1"), List.of(parts), at, "wamid-" + id);
    }

    @Test
    @DisplayName("Quando o app reinicia, então contato, conversa e transcrição (com anexo) continuam lá, em ordem")
    void survivesRestart() {
        MongoConversationStore store = new MongoConversationStore(database, Clock.systemUTC());
        store.save(new Contact("c-1", "t1", "Maria", Map.of(), T0, null));
        store.save(new Conversation("conv-1", "t1", SITE, "c-1", ConversationStatus.OPEN, "pi-1", "task-1", T0, T0));
        Attachment foto = new Attachment("gridfs://abc", "frente.jpg", "image/jpeg", 1234);
        // Mesmo instante de propósito: a ordem vem da inserção, não do relógio.
        store.append(message("m1", "conv-1", T0, new MessagePart.Text("Oi")));
        store.append(message("m2", "conv-1", T0, new MessagePart.Text("Segue a foto"), new MessagePart.File(foto)));
        store.append(message("m3", "conv-1", T0.plusSeconds(5), new MessagePart.Text("Gol 2018")));

        MongoConversationStore restarted = new MongoConversationStore(database, Clock.systemUTC());

        assertThat(restarted.findContact("c-1")).get().extracting(Contact::displayName).isEqualTo("Maria");
        assertThat(restarted.findOpenByLead("t1", "c-1")).singleElement().satisfies(c -> {
            assertThat(c.activeMissionTaskId()).isEqualTo("task-1");
            assertThat(c.channel()).isEqualTo(SITE);
        });
        assertThat(restarted.findOpenByLead("t2", "c-1")).isEmpty();
        List<Message> last = restarted.findLast("conv-1", 2);
        assertThat(last).extracting(Message::id).containsExactly("m2", "m3");
        assertThat(last.get(0).parts()).containsExactly(new MessagePart.Text("Segue a foto"), new MessagePart.File(foto));
        assertThat(restarted.existsByChannelMessageId("conv-1", "wamid-m1")).isTrue();
        assertThat(restarted.existsByChannelMessageId("conv-1", "wamid-x")).isFalse();

        restarted.save(restarted.findById("conv-1").orElseThrow().withStatus(ConversationStatus.CLOSED));
        assertThat(restarted.findOpenByLead("t1", "c-1")).isEmpty();
    }

    @Test
    @DisplayName("Quando há pergunta de troca de canal pendente, então ela e a mensagem guardada sobrevivem a reinício")
    void pendingSwitchSurvivesRestart() {
        MongoConversationStore store = new MongoConversationStore(database, Clock.systemUTC());
        ChannelContext whatsapp = new ChannelContext(new ChannelRef(ChannelType.WHATSAPP, "t1-whatsapp"), "WhatsApp",
                new ChannelEntry("pre-vendas", Map.of("origem", "whatsapp")),
                new ConsentPolicy(false, List.of("PARAR"), null));
        Attachment foto = new Attachment("gridfs://abc", "frente.jpg", "image/jpeg", 1234);
        store.savePendingSwitch(new PendingChannelSwitch("t1", "c-1", whatsapp, "conv-1", "Maria",
                List.of(new MessagePart.Text("Oi"), new MessagePart.File(foto)), T0, "wamid-1", 1));

        MongoConversationStore restarted = new MongoConversationStore(database, Clock.systemUTC());

        PendingChannelSwitch pending = restarted.findPendingSwitch("t1", "c-1", "t1-whatsapp").orElseThrow();
        assertThat(pending.channel()).isEqualTo(whatsapp);
        assertThat(pending.parts()).containsExactly(new MessagePart.Text("Oi"), new MessagePart.File(foto));
        assertThat(pending.openConversationId()).isEqualTo("conv-1");
        restarted.deletePendingSwitch("t1", "c-1", "t1-whatsapp");
        assertThat(restarted.findPendingSwitch("t1", "c-1", "t1-whatsapp")).isEmpty();
    }

    @Test
    @DisplayName("Quando dois nós disputam a mesma conversa, então um espera o outro (e o lock é reentrante)")
    void lockSerializesAcrossNodes() throws Exception {
        MongoConversationStore nodeA = new MongoConversationStore(database, Clock.systemUTC());
        MongoConversationStore nodeB = new MongoConversationStore(database, Clock.systemUTC());
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch aInside = new CountDownLatch(1);
        AtomicInteger reentered = new AtomicInteger();

        Thread a = Thread.ofVirtual().start(() -> nodeA.withLock("conv-1", () -> {
            order.add("A-in");
            aInside.countDown();
            nodeA.withLock("conv-1", reentered::incrementAndGet);
            sleep(300);
            order.add("A-out");
            return null;
        }));
        aInside.await();
        nodeB.withLock("conv-1", () -> order.add("B-in"));
        a.join();

        assertThat(order).containsExactly("A-in", "A-out", "B-in");
        assertThat(reentered).hasValue(1);
    }

    @Test
    @DisplayName("Quando guarda um anexo no GridFS, então lê de volta só no mesmo tenant")
    void attachmentsInGridFs() throws Exception {
        GridFsAttachmentStore attachments = new GridFsAttachmentStore(database);
        byte[] bytes = "foto".getBytes(StandardCharsets.UTF_8);

        Attachment stored = attachments.store("t1", "conv-1", "frente.jpg", "image/jpeg", bytes.length,
                new ByteArrayInputStream(bytes));

        assertThat(stored.uri()).startsWith("gridfs://");
        try (InputStream in = attachments.open("t1", stored.uri()).orElseThrow()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
        assertThat(attachments.open("t2", stored.uri())).isEmpty();
        assertThat(attachments.open("t1", "dms://outro")).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
