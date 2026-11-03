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

import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.runtime.agent.AgentCatalog;
import io.kikwiflow.agents.runtime.agent.InvalidAgentDefinitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API do catálogo de agentes, por tenant. É onde o Agent Studio publica (hoje por importação do {@code .agent.json})
 * e onde a personalização do produto cria versões novas. V1 sem autenticação própria: o tenant vem do caminho e a
 * proteção fica com o host (o produto).
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/agents")
public class AgentCatalogController {

    private final AgentCatalog catalog;

    public AgentCatalogController(AgentCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<AgentCatalog.Entry> list(@PathVariable String tenantId) {
        return catalog.list(tenantId);
    }

    @GetMapping("/{key}")
    public ResponseEntity<AgentCatalog.Entry> get(@PathVariable String tenantId, @PathVariable String key) {
        return ResponseEntity.of(catalog.entry(tenantId, key));
    }

    @GetMapping("/{key}/versions/{version}")
    public ResponseEntity<AgentDefinition> version(@PathVariable String tenantId, @PathVariable String key,
                                                   @PathVariable int version) {
        return ResponseEntity.of(catalog.find(tenantId, key, version));
    }

    /** Cria ou substitui o rascunho — inclusive importando o {@code .agent.json} exportado pelo Studio. */
    @PutMapping("/{key}/draft")
    public AgentDefinition saveDraft(@PathVariable String tenantId, @PathVariable String key,
                                     @RequestBody AgentDefinition definition) {
        if (definition.key() != null && !definition.key().equals(key)) {
            throw new InvalidAgentDefinitionException(List.of("key do corpo difere da URL"));
        }
        return catalog.saveDraft(tenantId, definition.key() == null ? withKey(definition, key) : definition);
    }

    @PostMapping("/{key}/publish")
    public ResponseEntity<AgentDefinition> publish(@PathVariable String tenantId, @PathVariable String key) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.publish(tenantId, key));
    }

    /**
     * @param parameters valores dos parâmetros do agente.
     * @param model      {@code provider:modelo} (ex.: {@code gemini:gemini-2.5-flash}); vazio mantém o atual.
     */
    public record PersonalizeRequest(Map<String, String> parameters, String model) {
    }

    /** Personalização: nova versão com outros valores de parâmetro (nome do agente, loja, endereço…) e/ou modelo. */
    @PostMapping("/{key}/personalize")
    public ResponseEntity<AgentDefinition> personalize(@PathVariable String tenantId, @PathVariable String key,
                                                       @RequestBody PersonalizeRequest request) {
        Map<String, String> parameters = request.parameters() == null ? Map.of() : request.parameters();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(catalog.publishPersonalized(tenantId, key, parameters, request.model()));
    }

    @ExceptionHandler(InvalidAgentDefinitionException.class)
    ResponseEntity<Map<String, Object>> invalid(InvalidAgentDefinitionException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("problems", e.problems()));
    }

    private static AgentDefinition withKey(AgentDefinition d, String key) {
        return new AgentDefinition(key, d.version(), d.status(), d.tenantId(), d.name(), d.description(),
                d.instructions(), d.parameters(), d.parameterValues(), d.model(), d.perception(), d.tools(),
                d.subagents(), d.limits(), d.mode());
    }
}
