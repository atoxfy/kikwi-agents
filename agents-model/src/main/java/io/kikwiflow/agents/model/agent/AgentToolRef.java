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

/**
 * Ferramenta do agente, por referência. Para {@code PROCESS}, a descrição, as entradas e o retorno vêm do processo
 * ({@code ProcessContract} do motor); aqui fica só o nome que o modelo vê.
 *
 * @param name        nome exposto ao modelo; único no agente e o que {@code AGENT_TASK.tools[]} restringe.
 * @param processKey  PROCESS: a chave do processo.
 * @param description opcional: sobrescreve a descrição que viria do processo.
 */
public record AgentToolRef(String name, AgentToolType type, String processKey, String description) {
}
