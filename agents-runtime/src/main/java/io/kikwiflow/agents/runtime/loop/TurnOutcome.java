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
package io.kikwiflow.agents.runtime.loop;

import io.kikwiflow.agents.llm.LlmUsage;

import java.util.Map;

/**
 * Resultado de processar uma mensagem do lead dentro de uma missão.
 */
public sealed interface TurnOutcome {

    LlmUsage usage();

    /** O agente respondeu e aguarda o lead. Enviar {@code text} pelo canal. */
    record Reply(String text, LlmUsage usage) implements TurnOutcome {
    }

    /** O agente entregou o contrato: chamar {@code MissionSource.complete} com {@code outputs}. */
    record Completed(Map<String, Object> outputs, String farewell, LlmUsage usage) implements TurnOutcome {
    }

    /** O agente pediu um humano. A conversa vai para {@code HANDED_OFF} e o processo é avisado. */
    record HandedOff(String reason, LlmUsage usage) implements TurnOutcome {
    }

    /** Limite de iterações de ferramenta atingido sem resposta; tratar como falha técnica da missão. */
    record Exhausted(String reason, LlmUsage usage) implements TurnOutcome {
    }
}
