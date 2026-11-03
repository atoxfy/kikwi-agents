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
import java.util.function.Supplier;

/**
 * Resultado de processar uma mensagem do lead dentro de uma missão.
 */
public sealed interface TurnOutcome {

    LlmUsage usage();

    /** O agente respondeu e aguarda o lead. Enviar {@code text} pelo canal. */
    record Reply(String text, LlmUsage usage) implements TurnOutcome {
    }

    /**
     * O agente entregou o contrato: chamar {@code MissionSource.complete} com {@code outputs}. Se o modelo concluiu
     * sem escrever ao lead, {@code farewell} vem vazio e {@code lateFarewell} pede a última mensagem ao modelo — só
     * depois que o motor aceitar a conclusão (uma conclusão recusada não pode virar "combinado!" para o lead).
     */
    record Completed(Map<String, Object> outputs, String farewell, LlmUsage usage, Supplier<String> lateFarewell)
            implements TurnOutcome {

        public Completed(Map<String, Object> outputs, String farewell, LlmUsage usage) {
            this(outputs, farewell, usage, null);
        }

        /** A mensagem final ao lead: a que o modelo escreveu, ou a pedida agora; vazio se não houver. */
        public String resolveFarewell() {
            if (farewell != null && !farewell.isBlank()) {
                return farewell;
            }
            String late = lateFarewell == null ? null : lateFarewell.get();
            return late == null ? "" : late;
        }
    }

    /** O agente pediu um humano. A conversa vai para {@code HANDED_OFF} e o processo é avisado. */
    record HandedOff(String reason, LlmUsage usage) implements TurnOutcome {
    }

    /** Limite de iterações de ferramenta atingido sem resposta; tratar como falha técnica da missão. */
    record Exhausted(String reason, LlmUsage usage) implements TurnOutcome {
    }
}
