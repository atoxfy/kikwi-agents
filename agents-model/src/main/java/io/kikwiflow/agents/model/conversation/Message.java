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

import java.time.Instant;
import java.util.List;

/**
 * @param channelMessageId id no provedor do canal (wamid do WhatsApp, Message-ID do e-mail), usado para
 *                         deduplicar reentregas de webhook.
 */
public record Message(String id,
                      String conversationId,
                      Direction direction,
                      MessageAuthor author,
                      List<MessagePart> parts,
                      Instant at,
                      String channelMessageId) {

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    /** Concatena as partes de texto (anexos ficam de fora). */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (MessagePart part : parts) {
            if (part instanceof MessagePart.Text t) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
