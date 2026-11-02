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

import io.kikwiflow.agents.model.tool.ToolBinding;
import io.kikwiflow.agents.model.tool.ToolDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catálogo de ferramentas e dos executores por tipo de binding.
 */
public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final Map<Class<?>, BindingExecutor<?>> executors = new ConcurrentHashMap<>();

    public ToolRegistry register(ToolDefinition tool) {
        tools.put(tool.id(), tool);
        return this;
    }

    public ToolRegistry registerAll(Collection<ToolDefinition> definitions) {
        definitions.forEach(this::register);
        return this;
    }

    public ToolRegistry registerExecutor(BindingExecutor<?> executor) {
        executors.put(executor.bindingType(), executor);
        return this;
    }

    public Optional<ToolDefinition> find(String toolId) {
        return Optional.ofNullable(tools.get(toolId));
    }

    /** As definições dos ids pedidos que existem no catálogo, na ordem pedida. */
    public List<ToolDefinition> resolve(Collection<String> toolIds) {
        return toolIds.stream().map(tools::get).filter(t -> t != null).toList();
    }

    public ToolResult execute(ToolDefinition tool, ToolContext context, Map<String, Object> input) {
        return dispatch(tool, tool.binding(), context, input);
    }

    @SuppressWarnings("unchecked")
    private <B extends ToolBinding> ToolResult dispatch(ToolDefinition tool, B binding, ToolContext context,
                                                        Map<String, Object> input) {
        BindingExecutor<B> executor = (BindingExecutor<B>) executors.get(binding.getClass());
        if (executor == null) {
            throw new IllegalStateException("Nenhum executor para o binding " + binding.getClass().getSimpleName()
                    + " (ferramenta " + tool.id() + ")");
        }
        return executor.execute(tool, binding, context, input);
    }
}
