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
package io.kikwiflow.agents.runtime.tool;

import io.kikwiflow.agents.model.agent.AgentToolRef;
import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.model.tool.ToolDefinition;

import java.util.Optional;

/**
 * Descreve um processo Kikwiflow como ferramenta: nome do agente, descrição do processo, schema das entradas do
 * start e binding {@code Process}. Recebe a missão porque as entradas que ela já conhece (ex.: o id do lead criado
 * antes) vêm do contexto da missão, e não do modelo. Porta do runtime; a implementação lê o
 * motor (agents-spring-boot-starter), porque o runtime não depende do motor.
 */
@FunctionalInterface
public interface ProcessToolDescriber {

    /** Vazio quando o processo não existe (ainda não implantado, chave errada). */
    Optional<ToolDefinition> describe(Mission mission, AgentToolRef tool);
}
