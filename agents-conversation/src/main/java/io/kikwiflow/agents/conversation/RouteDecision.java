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

import io.kikwiflow.agents.channel.OutboundMessage;
import io.kikwiflow.agents.model.channel.ChannelRef;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.Message;

import java.util.Map;

/**
 * O que fazer com uma mensagem recebida, já gravada na transcrição.
 */
public sealed interface RouteDecision {

    Conversation conversation();

    /** Há uma missão de agente ativa: acordar o agente para responder. */
    record DeliverToMission(Conversation conversation, String missionTaskId, Message message) implements RouteDecision {
    }

    /** Conversa nova: iniciar o processo de entrada do canal, com {@code businessKey = conversation.id()}. */
    record StartProcess(Conversation conversation, String processKey, Map<String, Object> variables,
                        Message message) implements RouteDecision {
    }

    /** Um humano assumiu: só notificar a fila humana, o agente não responde. */
    record HumanInbox(Conversation conversation, Message message) implements RouteDecision {
    }

    /**
     * Há processo, mas nenhuma missão ativa (ex.: o lead responde enquanto o processo está num passo sem agente).
     * A mensagem fica na transcrição e será lida pela próxima missão.
     */
    record Buffered(Conversation conversation, Message message) implements RouteDecision {
    }

    /**
     * O lead escreveu por um canal novo e tem conversa aberta em outro: perguntar, pelo canal novo, se continua de
     * onde parou ou se é outro assunto. {@code conversation} é a conversa que pode ser continuada.
     */
    record AskChannelSwitch(Conversation conversation, String leadId, ChannelRef channel,
                            OutboundMessage question) implements RouteDecision {
    }

    /** Não processar (reentrega duplicada, opt-out). */
    record Ignored(Conversation conversation, String reason) implements RouteDecision {
    }
}
