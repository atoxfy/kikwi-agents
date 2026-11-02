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
package io.kikwiflow.agents.llm;

import java.util.List;

/**
 * @param model o modelo que de fato respondeu (provedores podem resolver aliases); vai para a auditoria.
 */
public record LlmResponse(List<LlmContent> content, StopReason stopReason, LlmUsage usage, String model) {

    public enum StopReason {
        END_TURN,
        TOOL_USE,
        MAX_TOKENS,
        REFUSAL,
        OTHER
    }

    public LlmResponse {
        content = List.copyOf(content);
    }

    public List<LlmContent.ToolCall> toolCalls() {
        return content.stream()
                .filter(LlmContent.ToolCall.class::isInstance)
                .map(LlmContent.ToolCall.class::cast)
                .toList();
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (LlmContent c : content) {
            if (c instanceof LlmContent.Text t) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
