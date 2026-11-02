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
import io.kikwiflow.agents.model.agent.AgentModelSettings;
import io.kikwiflow.agents.model.agent.AgentParameter;
import io.kikwiflow.agents.model.agent.AgentStatus;
import io.kikwiflow.agents.model.agent.AgentToolRef;
import io.kikwiflow.agents.model.agent.AgentToolType;
import io.kikwiflow.agents.model.agent.ModelRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Catálogo de agentes por tenant: rascunho editável e versões publicadas imutáveis. É o que o {@code MissionWorker}
 * consulta ao adquirir uma missão e o que a API REST do catálogo expõe (o Agent Studio do Craft publica aqui).
 *
 * <p>Publicar valida o mínimo que, faltando, só quebraria em runtime: instruções, modelo {@code provider:model},
 * parâmetros citados e declarados, nomes de ferramenta válidos e únicos, processo nas ferramentas {@code PROCESS}.
 */
public class AgentCatalog implements AgentDefinitionRepository {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern PARAMETER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    private final AgentDefinitionStore store;

    public AgentCatalog(AgentDefinitionStore store) {
        this.store = Objects.requireNonNull(store);
    }

    /** O agente da missão: a versão pedida, ou a publicada mais recente. Rascunho nunca roda. */
    @Override
    public Optional<AgentDefinition> find(String tenantId, String key, Integer version) {
        List<AgentDefinition> versions = store.findVersions(tenantId, key);
        if (version == null) {
            return versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast());
        }
        return versions.stream().filter(v -> v.version() == version).findFirst();
    }

    /** Rascunho e versões de uma chave. */
    public Optional<Entry> entry(String tenantId, String key) {
        Optional<AgentDefinition> draft = store.findDraft(tenantId, key);
        List<AgentDefinition> versions = store.findVersions(tenantId, key);
        if (draft.isEmpty() && versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Entry(key, draft.orElse(null), versions));
    }

    public List<Entry> list(String tenantId) {
        return store.keys(tenantId).stream().map(key -> entry(tenantId, key)).flatMap(Optional::stream).toList();
    }

    /** Grava o rascunho (criação, edição ou importação de um {@code .agent.json}). */
    public AgentDefinition saveDraft(String tenantId, AgentDefinition definition) {
        if (definition.key() == null || !KEY.matcher(definition.key()).matches()) {
            throw new InvalidAgentDefinitionException(List.of("key: use minúsculas, números, ponto, hífen ou _"));
        }
        AgentDefinition draft = definition.withTenant(tenantId).withVersion(0, AgentStatus.DRAFT);
        store.saveDraft(draft);
        return draft;
    }

    /** Congela o rascunho como a próxima versão. */
    public AgentDefinition publish(String tenantId, String key) {
        AgentDefinition draft = store.findDraft(tenantId, key)
                .orElseThrow(() -> new InvalidAgentDefinitionException(List.of("rascunho do agente '" + key + "' não existe")));
        List<String> problems = problems(draft);
        if (!problems.isEmpty()) {
            throw new InvalidAgentDefinitionException(problems);
        }
        int next = store.findVersions(tenantId, key).stream().mapToInt(AgentDefinition::version).max().orElse(0) + 1;
        AgentDefinition published = draft.withVersion(next, AgentStatus.PUBLISHED);
        store.insertVersion(published);
        return published;
    }

    /**
     * Personaliza: nova versão a partir da publicada mais recente, só com valores de parâmetro diferentes. É o que o
     * produto chama quando o cliente muda o nome do agente, a loja ou o endereço.
     */
    public AgentDefinition publishWithParameters(String tenantId, String key, Map<String, String> values) {
        return publishPersonalized(tenantId, key, values, null);
    }

    /**
     * Como {@link #publishWithParameters}, podendo trocar também o modelo ({@code provider:modelo}) — cada cliente usa
     * o provedor de LLM em que tem conta. {@code modelId} vazio mantém o da versão anterior.
     */
    public AgentDefinition publishPersonalized(String tenantId, String key, Map<String, String> values, String modelId) {
        AgentDefinition base = find(tenantId, key, null)
                .orElseThrow(() -> new InvalidAgentDefinitionException(List.of("agente '" + key + "' sem versão publicada")));
        Set<String> declared = new HashSet<>(base.parameters().stream().map(AgentParameter::key).toList());
        List<String> unknown = values.keySet().stream().filter(k -> !declared.contains(k)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidAgentDefinitionException(unknown.stream().map(k -> "parâmetro não declarado: " + k).toList());
        }
        AgentDefinition personalized = base.withParameterValues(values);
        if (modelId != null && !modelId.isBlank()) {
            AgentModelSettings current = base.model();
            personalized = personalized.withModel(new AgentModelSettings(modelId.trim(),
                    current == null ? null : current.temperature(), current == null ? null : current.maxOutputTokens()));
        }
        saveDraft(tenantId, personalized);
        return publish(tenantId, key);
    }

    static List<String> problems(AgentDefinition agent) {
        List<String> problems = new ArrayList<>();
        if (agent.name() == null || agent.name().isBlank()) {
            problems.add("name: obrigatório");
        }
        if (agent.instructions() == null || agent.instructions().isBlank()) {
            problems.add("instructions: obrigatório");
        }
        try {
            if (agent.model() == null) {
                problems.add("model.id: obrigatório");
            } else {
                ModelRef.parse(agent.model().id());
            }
        } catch (IllegalArgumentException e) {
            problems.add("model.id: use provider:model");
        }
        Set<String> declared = new HashSet<>();
        for (AgentParameter parameter : agent.parameters()) {
            if (!declared.add(parameter.key())) {
                problems.add("parameters: '" + parameter.key() + "' repetido");
            }
        }
        var matcher = PARAMETER.matcher(agent.instructions() == null ? "" : agent.instructions());
        while (matcher.find()) {
            if (!declared.contains(matcher.group(1))) {
                problems.add("instructions: {{" + matcher.group(1) + "}} não declarado em parameters");
            }
        }
        Set<String> names = new HashSet<>();
        for (AgentToolRef tool : agent.tools()) {
            if (tool.name() == null || !TOOL_NAME.matcher(tool.name()).matches()) {
                problems.add("tools: nome inválido '" + tool.name() + "'");
            } else if (!names.add(tool.name())) {
                problems.add("tools: '" + tool.name() + "' repetido");
            }
            if (tool.type() == AgentToolType.PROCESS && (tool.processKey() == null || tool.processKey().isBlank())) {
                problems.add("tools: '" + tool.name() + "' sem processKey");
            }
        }
        if (agent.limits().maxTurns() != null && agent.limits().maxTurns() < 1) {
            problems.add("limits.maxTurns: precisa ser 1 ou mais");
        }
        return problems;
    }

    /** @param draft {@code null} quando só há versões publicadas. */
    public record Entry(String key, AgentDefinition draft, List<AgentDefinition> versions) {
    }
}
