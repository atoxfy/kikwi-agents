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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** {@link AgentDefinitionStore} em memória, para testes e para rodar sem Mongo. */
public class InMemoryAgentDefinitionStore implements AgentDefinitionStore {

    private final Map<String, AgentDefinition> drafts = new ConcurrentHashMap<>();
    private final Map<String, List<AgentDefinition>> versions = new ConcurrentHashMap<>();

    private static String id(String tenantId, String key) {
        return tenantId + "|" + key;
    }

    @Override
    public Optional<AgentDefinition> findDraft(String tenantId, String key) {
        return Optional.ofNullable(drafts.get(id(tenantId, key)));
    }

    @Override
    public List<AgentDefinition> findVersions(String tenantId, String key) {
        List<AgentDefinition> list = new ArrayList<>(versions.getOrDefault(id(tenantId, key), List.of()));
        list.sort(Comparator.comparingInt(AgentDefinition::version));
        return list;
    }

    @Override
    public List<String> keys(String tenantId) {
        TreeSet<String> keys = new TreeSet<>();
        String prefix = tenantId + "|";
        drafts.keySet().stream().filter(k -> k.startsWith(prefix)).forEach(k -> keys.add(k.substring(prefix.length())));
        versions.keySet().stream().filter(k -> k.startsWith(prefix)).forEach(k -> keys.add(k.substring(prefix.length())));
        return List.copyOf(keys);
    }

    @Override
    public void saveDraft(AgentDefinition draft) {
        drafts.put(id(draft.tenantId(), draft.key()), draft);
    }

    @Override
    public synchronized void insertVersion(AgentDefinition published) {
        List<AgentDefinition> list = versions.computeIfAbsent(id(published.tenantId(), published.key()),
                k -> new ArrayList<>());
        if (list.stream().anyMatch(v -> v.version() == published.version())) {
            throw new IllegalStateException("Versão " + published.version() + " de " + published.key() + " já existe");
        }
        list.add(published);
    }
}
