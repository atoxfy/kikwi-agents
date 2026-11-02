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

import java.util.Map;

public sealed interface LlmContent {

    record Text(String text) implements LlmContent {
    }

    /**
     * Imagem ou PDF. O runtime resolve a referência {@code dms://} e entrega os bytes em base64; adapters de
     * provedores que aceitam URL podem usar {@code url} no lugar.
     */
    record Media(String mimeType, String base64Data, String url) implements LlmContent {
    }

    /**
     * Pedido do modelo para executar uma ferramenta.
     *
     * @param providerState dado opaco que o provedor exige de volta junto com a chamada no próximo pedido (ex.: a
     *                      assinatura de raciocínio do Gemini); {@code null} para os demais. O runtime só repassa.
     */
    record ToolCall(String id, String name, Map<String, Object> input, String providerState) implements LlmContent {

        public ToolCall(String id, String name, Map<String, Object> input) {
            this(id, name, input, null);
        }
    }

    /** Resultado de uma ferramenta, devolvido ao modelo numa mensagem {@code USER}. */
    record ToolResult(String toolCallId, String content, boolean error) implements LlmContent {
    }
}
