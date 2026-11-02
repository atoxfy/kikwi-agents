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

import java.util.List;
import java.util.Optional;

/**
 * Persistência do catálogo de agentes: um rascunho editável por chave e as versões publicadas, imutáveis. Só guarda;
 * versionamento e validação ficam no {@link AgentCatalog}. Implementações: em memória (testes) e Mongo (app).
 */
public interface AgentDefinitionStore {

    Optional<AgentDefinition> findDraft(String tenantId, String key);

    /** Versões publicadas, da mais antiga para a mais nova. */
    List<AgentDefinition> findVersions(String tenantId, String key);

    /** Chaves com rascunho ou versão publicada. */
    List<String> keys(String tenantId);

    void saveDraft(AgentDefinition draft);

    /** Grava uma versão nova. Falha se a versão já existe: versão publicada nunca é sobrescrita. */
    void insertVersion(AgentDefinition published);
}
