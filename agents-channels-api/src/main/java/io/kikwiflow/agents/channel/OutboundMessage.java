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

import io.kikwiflow.agents.model.conversation.MessagePart;

import java.util.List;
import java.util.Map;

/**
 * @param templateName   modelo pré-aprovado pelo provedor, obrigatório no WhatsApp fora da janela de 24h desde a
 *                       última mensagem do contato. {@code null} = mensagem livre.
 * @param templateParams parâmetros do modelo.
 * @param quickReplies   respostas sugeridas (botões no site, botões interativos no WhatsApp). O lead também pode
 *                       responder escrevendo.
 */
public record OutboundMessage(List<MessagePart> parts, String templateName, Map<String, String> templateParams,
                              List<String> quickReplies) {

    public OutboundMessage {
        templateParams = templateParams == null ? Map.of() : Map.copyOf(templateParams);
        quickReplies = quickReplies == null ? List.of() : List.copyOf(quickReplies);
    }

    public static OutboundMessage text(String text) {
        return new OutboundMessage(List.of(new MessagePart.Text(text)), null, Map.of(), List.of());
    }

    public static OutboundMessage choice(String text, List<String> options) {
        return new OutboundMessage(List.of(new MessagePart.Text(text)), null, Map.of(), options);
    }
}
