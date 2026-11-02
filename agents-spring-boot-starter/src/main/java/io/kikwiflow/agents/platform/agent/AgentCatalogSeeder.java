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
package io.kikwiflow.agents.platform.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.runtime.agent.AgentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Publica agentes de exemplo (`.agent.json`) num tenant quando ele ainda não tem a chave. Só para desenvolvimento:
 * agentes de verdade chegam pelo catálogo (Agent Studio, API, produto). Nunca cria versão nova de um agente que já
 * existe — o seed não sobrescreve personalização.
 */
public class AgentCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogSeeder.class);

    private final AgentCatalog catalog;
    private final ObjectMapper mapper;

    public AgentCatalogSeeder(AgentCatalog catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** @return a versão publicada, ou a existente quando a chave já estava no catálogo. */
    public AgentDefinition seed(String tenantId, InputStream agentJson) throws IOException {
        AgentDefinition definition = mapper.readValue(agentJson, AgentDefinition.class);
        return catalog.find(tenantId, definition.key(), null).orElseGet(() -> {
            catalog.saveDraft(tenantId, definition);
            AgentDefinition published = catalog.publish(tenantId, definition.key());
            log.info("Agente de exemplo {} v{} publicado no tenant {}", published.key(), published.version(), tenantId);
            return published;
        });
    }
}
