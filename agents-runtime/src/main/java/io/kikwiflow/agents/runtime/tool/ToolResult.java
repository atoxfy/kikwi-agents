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

import java.util.Map;

/**
 * @param content o que o modelo lê de volta (texto ou JSON compacto).
 * @param data    dados estruturados para quem chamou (não vão para o modelo nem para a auditoria).
 */
public record ToolResult(boolean ok, String content, Map<String, Object> data) {

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, Map.of());
    }

    public static ToolResult error(String content) {
        return new ToolResult(false, content, Map.of());
    }
}
