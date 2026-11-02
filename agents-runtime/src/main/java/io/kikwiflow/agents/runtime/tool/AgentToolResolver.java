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

import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.model.agent.AgentToolRef;
import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.model.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Transforma as ferramentas do agente (referências) nas {@link ToolDefinition} que o modelo vê, no momento da
 * missão. {@code INLINE} vem do {@link ToolRegistry} (código do runtime); {@code PROCESS} vem do
 * {@link ProcessToolDescriber}, que lê o contrato do processo no motor — descrição, entradas e retorno ficam no
 * processo, então uma nova versão dele muda a ferramenta sem republicar o agente. {@code HTTP} e {@code MCP} ainda
 * não têm executor e são ignorados (com log).
 */
public class AgentToolResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentToolResolver.class);

    private final ToolRegistry registry;
    private final ProcessToolDescriber processes;

    public AgentToolResolver(ToolRegistry registry, ProcessToolDescriber processes) {
        this.registry = registry;
        this.processes = processes;
    }

    /** As ferramentas do agente cujo nome está em {@code allowed}, na ordem do agente. */
    public List<ToolDefinition> resolve(Mission mission, AgentDefinition agent, Collection<String> allowed) {
        List<ToolDefinition> tools = new ArrayList<>();
        for (AgentToolRef ref : agent.tools()) {
            if (!allowed.contains(ref.name())) {
                continue;
            }
            Optional<ToolDefinition> tool = switch (ref.type()) {
                case INLINE -> registry.find(ref.name());
                case PROCESS -> processes == null ? Optional.empty() : processes.describe(mission, ref);
                case HTTP, MCP -> Optional.empty();
            };
            if (tool.isPresent()) {
                tools.add(tool.get());
            } else {
                log.warn("Ferramenta {} ({}) do agente {} indisponível; fica fora desta missão", ref.name(), ref.type(),
                        agent.key());
            }
        }
        return tools;
    }
}
