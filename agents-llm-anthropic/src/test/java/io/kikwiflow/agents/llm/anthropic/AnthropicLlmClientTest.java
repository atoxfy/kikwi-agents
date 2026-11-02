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
package io.kikwiflow.agents.llm.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kikwiflow.agents.llm.LlmContent;
import io.kikwiflow.agents.llm.LlmMessage;
import io.kikwiflow.agents.llm.LlmRequest;
import io.kikwiflow.agents.llm.LlmResponse;
import io.kikwiflow.agents.llm.LlmTool;
import io.kikwiflow.agents.llm.ToolChoice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicLlmClient client = new AnthropicLlmClient(AnthropicSettings.of("test"), mapper);

    @Test
    @DisplayName("Dado ferramenta forçada e resultado de ferramenta, quando serializa, então segue o formato da Messages API")
    void serializaRequisicao() {
        LlmRequest request = new LlmRequest("claude-sonnet-5", "Você é um consultor.",
                List.of(LlmMessage.user("Oi"),
                        new LlmMessage(LlmMessage.Role.ASSISTANT, List.of(
                                new LlmContent.ToolCall("tu_1", "consultar_fipe", Map.of("placa", "ABC1D23")))),
                        new LlmMessage(LlmMessage.Role.USER, List.of(
                                new LlmContent.ToolResult("tu_1", "{\"valor\":45000}", false)))),
                List.of(new LlmTool("concluir_missao", "Conclui", Map.of("type", "object"))),
                ToolChoice.tool("concluir_missao"), 1024, null, null);

        JsonNode body = client.toRequestBody(request);

        assertThat(body.path("system").asText()).isEqualTo("Você é um consultor.");
        assertThat(body.path("tool_choice").path("type").asText()).isEqualTo("tool");
        assertThat(body.path("tool_choice").path("name").asText()).isEqualTo("concluir_missao");
        assertThat(body.path("tools").get(0).path("input_schema").path("type").asText()).isEqualTo("object");
        assertThat(body.path("messages").get(1).path("content").get(0).path("type").asText()).isEqualTo("tool_use");
        assertThat(body.path("messages").get(2).path("content").get(0).path("tool_use_id").asText()).isEqualTo("tu_1");
        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    @DisplayName("Dado resposta com texto e tool_use, quando interpreta, então normaliza conteúdo, motivo e uso")
    void interpretaResposta() throws Exception {
        JsonNode raw = mapper.readTree("""
                {"model":"claude-sonnet-5-20260901","stop_reason":"tool_use",
                 "content":[{"type":"text","text":"Vou verificar."},
                            {"type":"tool_use","id":"tu_9","name":"consultar_horarios","input":{"agenciaId":"curitiba-01"}}],
                 "usage":{"input_tokens":120,"output_tokens":30}}
                """);

        LlmResponse response = client.parseResponse(raw);

        assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
        assertThat(response.text()).isEqualTo("Vou verificar.");
        assertThat(response.toolCalls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.name()).isEqualTo("consultar_horarios");
                    assertThat(call.input()).containsEntry("agenciaId", "curitiba-01");
                });
        assertThat(response.usage().inputTokens()).isEqualTo(120);
        assertThat(response.model()).isEqualTo("claude-sonnet-5-20260901");
    }
}
