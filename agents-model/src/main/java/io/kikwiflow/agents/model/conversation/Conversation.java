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
package io.kikwiflow.agents.model.conversation;

import io.kikwiflow.agents.model.channel.ChannelRef;

import java.time.Instant;

/**
 * Uma conversa do lead — omnicanal: {@code channel} é por onde ele está falando agora e muda quando ele troca de
 * canal e escolhe continuar. O {@code id} é o {@code businessKey} da instância Kikwiflow que a orquestra e a
 * {@code conversationRef} gravada na auditoria. A transcrição fica aqui, nunca nas variáveis do processo.
 *
 * @param activeMissionTaskId id da {@code ExternalTask} (AGENT_TASK) cuja missão está respondendo, se houver.
 */
public record Conversation(String id,
                           String tenantId,
                           ChannelRef channel,
                           String contactId,
                           ConversationStatus status,
                           String processInstanceId,
                           String activeMissionTaskId,
                           Instant startedAt,
                           Instant lastMessageAt) {

    public Conversation withMission(String processInstanceId, String missionTaskId) {
        return new Conversation(id, tenantId, channel, contactId, status, processInstanceId, missionTaskId,
                startedAt, lastMessageAt);
    }

    public Conversation withStatus(ConversationStatus newStatus) {
        return new Conversation(id, tenantId, channel, contactId, newStatus, processInstanceId,
                activeMissionTaskId, startedAt, lastMessageAt);
    }

    public Conversation touchedAt(Instant at) {
        return new Conversation(id, tenantId, channel, contactId, status, processInstanceId,
                activeMissionTaskId, startedAt, at);
    }

    /** O lead passou a falar por outro canal e quis continuar esta conversa: as respostas vão pelo novo. */
    public Conversation withChannel(ChannelRef newChannel) {
        return new Conversation(id, tenantId, newChannel, contactId, status, processInstanceId,
                activeMissionTaskId, startedAt, lastMessageAt);
    }

    /** O lead foi ligado a outro (ex.: informou um telefone que já era de um lead do WhatsApp). */
    public Conversation withContact(String newContactId) {
        return new Conversation(id, tenantId, channel, newContactId, status, processInstanceId,
                activeMissionTaskId, startedAt, lastMessageAt);
    }
}
