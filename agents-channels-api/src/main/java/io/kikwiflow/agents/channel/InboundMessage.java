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
package io.kikwiflow.agents.channel;

import io.kikwiflow.agents.model.channel.ChannelContext;
import io.kikwiflow.agents.model.conversation.MessagePart;

import java.time.Instant;
import java.util.List;

/**
 * Mensagem recebida, já normalizada e com o lead já identificado pelo roteador de canais (fora do kikwi-agents).
 * Daqui para dentro ninguém sabe de webhook, sessão de navegador ou assinatura de provedor — só de quem é o lead e
 * por qual canal ele está falando.
 *
 * @param leadId           o lead, estável entre canais (o roteador liga as identidades de cada canal a ele).
 * @param channelMessageId id do provedor, para deduplicar reentregas.
 */
public record InboundMessage(String tenantId,
                             String leadId,
                             String displayName,
                             ChannelContext channel,
                             List<MessagePart> parts,
                             Instant receivedAt,
                             String channelMessageId) {
}
