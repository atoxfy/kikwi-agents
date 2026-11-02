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

import java.util.Map;

/**
 * Executa ferramentas de um tipo de {@link ToolBinding}. Hoje existe o de {@code Inline}; {@code Http},
 * {@code Process} e, no futuro, {@code Activity} (ad-hoc) entram como novos {@code BindingExecutor}, sem tocar no
 * loop do agente.
 */
public interface BindingExecutor<B extends ToolBinding> {

    Class<B> bindingType();

    ToolResult execute(ToolDefinition tool, B binding, ToolContext context, Map<String, Object> input);
}
