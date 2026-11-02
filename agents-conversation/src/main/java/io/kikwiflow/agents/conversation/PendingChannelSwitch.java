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

import io.kikwiflow.agents.model.channel.ChannelContext;
import io.kikwiflow.agents.model.conversation.MessagePart;

import java.time.Instant;
import java.util.List;

/**
 * O lead escreveu por um canal novo enquanto tinha uma conversa aberta em outro, e a plataforma perguntou se ele quer
 * continuar de onde parou ou tratar de outro assunto. Guarda a mensagem que motivou a pergunta, para entregá-la
 * depois da escolha — nada do que o lead disse se perde.
 *
 * @param openConversationId a conversa que pode ser continuada.
 * @param asks               quantas vezes já perguntamos (resposta que não entendemos pergunta de novo, até um limite).
 */
public record PendingChannelSwitch(String tenantId,
                                   String leadId,
                                   ChannelContext channel,
                                   String openConversationId,
                                   String displayName,
                                   List<MessagePart> parts,
                                   Instant receivedAt,
                                   String channelMessageId,
                                   int asks) {

    public PendingChannelSwitch {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public PendingChannelSwitch askedAgain() {
        return new PendingChannelSwitch(tenantId, leadId, channel, openConversationId, displayName, parts, receivedAt,
                channelMessageId, asks + 1);
    }
}
