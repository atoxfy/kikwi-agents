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
package io.kikwiflow.agents.model.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Como o agente trabalha numa missão.
 *
 * <ul>
 *   <li>{@link #CONVERSATIONAL} — conversa com uma pessoa (o contato da conversa ligada à missão pelo
 *   {@code businessKey}): cada mensagem dela é um turno, e a missão termina quando o agente conclui o contrato.</li>
 *   <li>{@link #TASK} — trabalho técnico, sem interlocutor: o agente recebe o objetivo e os dados da missão, usa as
 *   ferramentas e conclui o contrato numa execução só (ex.: classificar um documento, enriquecer um cadastro,
 *   conferir uma regra, redigir um parecer). Não há conversa, canal nem mensagem a ninguém; se não conseguir, pede
 *   revisão humana (handoff) ou falha.</li>
 * </ul>
 */
public enum AgentMode {
    CONVERSATIONAL("conversational"),
    TASK("task");

    private final String json;

    AgentMode(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static AgentMode of(String value) {
        if (value == null || value.isBlank()) {
            return CONVERSATIONAL;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (AgentMode mode : values()) {
            if (mode.json.equals(v) || mode.name().toLowerCase(Locale.ROOT).equals(v)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("mode: use conversational ou task ('" + value + "')");
    }
}
