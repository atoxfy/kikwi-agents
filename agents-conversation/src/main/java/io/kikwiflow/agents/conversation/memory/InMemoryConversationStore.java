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
package io.kikwiflow.agents.conversation.memory;

import io.kikwiflow.agents.conversation.ConversationStore;
import io.kikwiflow.agents.conversation.PendingChannelSwitch;
import io.kikwiflow.agents.model.conversation.Contact;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.ConversationStatus;
import io.kikwiflow.agents.model.conversation.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Implementação em memória de todas as portas de conversa, para testes e para o perfil de desenvolvimento.
 * O lock só vale dentro de uma JVM.
 */
public class InMemoryConversationStore implements ConversationStore {

    private final Map<String, Contact> contactsById = new ConcurrentHashMap<>();
    private final Map<String, Conversation> conversationsById = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> messagesByConversation = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, PendingChannelSwitch> pendingSwitches = new ConcurrentHashMap<>();

    @Override
    public Contact save(Contact contact) {
        contactsById.put(contact.id(), contact);
        return contact;
    }

    @Override
    public Optional<Conversation> findById(String id) {
        return Optional.ofNullable(conversationsById.get(id));
    }

    @Override
    public List<Conversation> findOpenByLead(String tenantId, String contactId) {
        return conversationsById.values().stream()
                .filter(c -> c.tenantId().equals(tenantId))
                .filter(c -> c.contactId().equals(contactId))
                .filter(c -> c.status() != ConversationStatus.CLOSED)
                .sorted(Comparator.comparing(Conversation::startedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed())
                .toList();
    }

    @Override
    public Optional<PendingChannelSwitch> findPendingSwitch(String tenantId, String leadId, String channelId) {
        return Optional.ofNullable(pendingSwitches.get(tenantId + "|" + leadId + "|" + channelId));
    }

    @Override
    public void savePendingSwitch(PendingChannelSwitch pending) {
        pendingSwitches.put(pending.tenantId() + "|" + pending.leadId() + "|" + pending.channel().ref().channelId(),
                pending);
    }

    @Override
    public void deletePendingSwitch(String tenantId, String leadId, String channelId) {
        pendingSwitches.remove(tenantId + "|" + leadId + "|" + channelId);
    }

    @Override
    public Conversation save(Conversation conversation) {
        conversationsById.put(conversation.id(), conversation);
        return conversation;
    }

    @Override
    public Message append(Message message) {
        messagesByConversation.computeIfAbsent(message.conversationId(), k -> new ArrayList<>()).add(message);
        return message;
    }

    @Override
    public List<Message> findLast(String conversationId, int limit) {
        List<Message> all = messagesByConversation.getOrDefault(conversationId, List.of());
        return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    @Override
    public boolean existsByChannelMessageId(String conversationId, String channelMessageId) {
        return messagesByConversation.getOrDefault(conversationId, List.of()).stream()
                .anyMatch(m -> Objects.equals(m.channelMessageId(), channelMessageId));
    }

    @Override
    public <T> T withLock(String conversationKey, Supplier<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(conversationKey, k -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Contact> findContact(String id) {
        return Optional.ofNullable(contactsById.get(id));
    }
}
