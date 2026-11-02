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
package io.kikwiflow.agents.model.mission;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Uma missão adquirida pelo runtime: a {@code ExternalTask} de um nó {@code AGENT_TASK} sob lease (doc 31, E1/E2).
 *
 * @param agentVersion {@code null} = usar a versão publicada vigente.
 * @param objective    o {@code kikwi:prompt} do nó, ainda com placeholders {@code {{var}}}.
 * @param variables    variáveis da instância entregues pelo fetch-and-lock.
 * @param createdAt    quando o motor criou a missão; o {@code ttl} de {@link MissionLimits} conta daqui, não da
 *                     aquisição, para que uma reaquisição depois de um lease vencido não renove o prazo.
 * @param retries      tentativas restantes depois de uma falha técnica; {@code null} = a missão nunca falhou. O
 *                     worker decrementa a cada falha, como no protocolo de worker do motor.
 */
public record Mission(String taskId,
                      String processInstanceId,
                      String tenantId,
                      String businessKey,
                      String agentKey,
                      Integer agentVersion,
                      List<String> toolIds,
                      String objective,
                      List<OutputField> outputContract,
                      MissionLimits limits,
                      Map<String, Object> variables,
                      Instant lockExpiresAt,
                      Instant createdAt,
                      Integer retries) {
}
