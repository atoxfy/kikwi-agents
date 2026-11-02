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
import java.util.Map;

/**
 * @param model           nome do modelo no provedor (a parte depois de {@code provider:} no {@code ModelRef}).
 * @param system          instruções de sistema (persona + regras + objetivo da missão).
 * @param providerOptions opções específicas do provedor, ignoradas pelos demais.
 */
public record LlmRequest(String model,
                         String system,
                         List<LlmMessage> messages,
                         List<LlmTool> tools,
                         ToolChoice toolChoice,
                         int maxTokens,
                         Double temperature,
                         Map<String, Object> providerOptions) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null ? ToolChoice.auto() : toolChoice;
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(providerOptions);
    }
}
