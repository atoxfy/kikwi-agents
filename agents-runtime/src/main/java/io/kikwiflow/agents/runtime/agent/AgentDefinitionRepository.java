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
package io.kikwiflow.agents.runtime.agent;

import io.kikwiflow.agents.model.agent.AgentDefinition;

import java.util.Optional;

/** Onde o runtime busca o agente de uma missão. Implementado pelo {@link AgentCatalog}. */
public interface AgentDefinitionRepository {

    /** @param version {@code null} = a versão publicada mais recente. Rascunhos nunca são devolvidos. */
    Optional<AgentDefinition> find(String tenantId, String agentKey, Integer version);
}
