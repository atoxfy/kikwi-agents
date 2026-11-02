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

import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.runtime.audit.AgentActivity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * O lado do Kikwiflow visto pelo runtime: o protocolo de worker com lease do doc 31 (E2) mais a API de atividades
 * (E3). Implementações:
 * <ul>
 *   <li>in-process, sobre a API Java do {@code KikwiflowEngine} embutido no app ({@code EngineMissionSource});</li>
 *   <li>REST, para rodar o runtime separado do motor (ainda não existe).</li>
 * </ul>
 *
 * <p>Toda operação sobre uma missão já adquirida lança {@link MissionLeaseLostException} quando o lease não é mais
 * deste worker ou a missão sumiu (um boundary do processo a interrompeu). O runtime trata os dois casos igual:
 * solta a conversa.
 */
public interface MissionSource {

    List<Mission> fetchAndLock(String workerId, List<String> topics, int maxTasks, Duration lockDuration);

    void extendLock(String taskId, String workerId, Duration lockDuration);

    /**
     * Conclui a missão. O motor valida {@code outputs} contra o contrato; violação lança
     * {@link MissionOutputRejectedException} e a missão continua sob lease.
     *
     * @param report resumo do runtime (turnos, uso de tokens), gravado no {@code AGENT_MISSION_COMPLETED}
     */
    void complete(String taskId, String workerId, Map<String, Object> outputs, Map<String, Object> report);

    /** Falha técnica: com {@code retries > 0} a missão volta à fila depois de {@code retryTimeout}; com 0, incidente. */
    void fail(String taskId, String workerId, String message, int retries, Duration retryTimeout);

    /** Erro de negócio (ex.: {@code LEAD_OPT_OUT}), roteado para o {@code BOUNDARY_ERROR_HANDLER} do nó. */
    void throwBusinessError(String taskId, String workerId, String errorCode, String message,
                            Map<String, Object> variables);

    void reportActivities(String taskId, String workerId, List<AgentActivity> activities);
}
