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
package io.kikwiflow.agents.runtime.mission;

import java.time.Duration;
import java.util.List;

/**
 * @param workerId           identidade deste nó no lease; única por instância do app.
 * @param leaseDuration      quanto o lease dura sem renovação. O renovador roda bem antes disso; se o app cair, a
 *                           missão volta à fila quando o lease vence.
 * @param transcriptWindow   quantas mensagens da transcrição vão para o LLM.
 * @param handoffOutputKey   variável de saída que leva o handoff ao processo (doc 01: {@code resultado = HUMANO});
 *                           a rota em si é do gateway do processo.
 * @param handoffOutputValue o valor dessa variável no handoff.
 * @param handoffMessage     enviado ao lead quando o agente não escreveu nada ao escalar.
 * @param failureRetries     tentativas de uma missão com falha técnica antes do incidente.
 * @param failureRetryDelay  espera entre essas tentativas.
 */
public record MissionWorkerSettings(String workerId,
                                    List<String> topics,
                                    int maxTasks,
                                    Duration leaseDuration,
                                    int transcriptWindow,
                                    String handoffOutputKey,
                                    String handoffOutputValue,
                                    String handoffMessage,
                                    int failureRetries,
                                    Duration failureRetryDelay) {

    public MissionWorkerSettings {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId é obrigatório");
        }
        if (topics == null || topics.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um tópico");
        }
        topics = List.copyOf(topics);
    }
}
