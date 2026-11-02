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

/**
 * Trabalho feito uma vez sobre cada mensagem recebida, antes de ela entrar na conversa — hoje, transcrever áudio
 * (o áudio fica como anexo e a transcrição entra como texto, que o agente e as pessoas leem). Roda fora do lock da
 * conversa, porque pode demorar. O host implementa com a credencial do tenant; o padrão não faz nada.
 */
@FunctionalInterface
public interface InboundPreprocessor {

    InboundPreprocessor NONE = (tenantId, parts) -> parts;

    List<MessagePart> process(String tenantId, List<MessagePart> parts);
}
