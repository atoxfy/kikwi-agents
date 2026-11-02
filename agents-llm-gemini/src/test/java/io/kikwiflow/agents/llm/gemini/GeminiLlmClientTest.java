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
package io.kikwiflow.agents.llm.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kikwiflow.agents.llm.LlmContent;
import io.kikwiflow.agents.llm.LlmException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiLlmClient client = new GeminiLlmClient(GeminiSettings.of("test"), mapper);

    @Test
    @DisplayName("Dado ferramenta forçada e resultado de ferramenta, quando serializa, então segue o formato do generateContent")
    void serializaRequisicao() {
        LlmRequest request = new LlmRequest("gemini-2.5-flash", "Você é um consultor.",
                List.of(LlmMessage.user("Oi"),
                        new LlmMessage(LlmMessage.Role.ASSISTANT, List.of(new LlmContent.ToolCall("c1",
                                "consultar_fipe", Map.of("placa", "ABC1D23"), "assinatura-1"))),
                        new LlmMessage(LlmMessage.Role.USER, List.of(
                                new LlmContent.ToolResult("c1", "{\"valor\":45000}", false),
                                new LlmContent.Media("image/jpeg", "AAAA", null)))),
                List.of(new LlmTool("concluir_missao", "Conclui", Map.of("type", "object"))),
                ToolChoice.tool("concluir_missao"), 1024, 0.2, null);

        JsonNode body = client.toRequestBody(request);

        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .isEqualTo("Você é um consultor.");
        JsonNode contents = body.path("contents");
        assertThat(contents.get(1).path("role").asText()).isEqualTo("model");
        assertThat(contents.get(1).path("parts").get(0).path("functionCall").path("name").asText()).isEqualTo("consultar_fipe");
        assertThat(contents.get(1).path("parts").get(0).path("thoughtSignature").asText()).isEqualTo("assinatura-1");
        JsonNode response = contents.get(2).path("parts").get(0).path("functionResponse");
        assertThat(response.path("name").asText()).isEqualTo("consultar_fipe");
        assertThat(response.path("response").path("result").asText()).isEqualTo("{\"valor\":45000}");
        assertThat(contents.get(2).path("parts").get(1).path("inlineData").path("mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(body.path("tools").get(0).path("functionDeclarations").get(0).path("parametersJsonSchema")
                .path("type").asText()).isEqualTo("object");
        JsonNode calling = body.path("toolConfig").path("functionCallingConfig");
        assertThat(calling.path("mode").asText()).isEqualTo("ANY");
        assertThat(calling.path("allowedFunctionNames").get(0).asText()).isEqualTo("concluir_missao");
        assertThat(body.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(1024);
        assertThat(body.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("Dado resultado de ferramenta sem a chamada no pedido, quando serializa, então falha em vez de inventar o nome")
    void resultadoSemChamada() {
        LlmRequest request = new LlmRequest("gemini-2.5-flash", null,
                List.of(new LlmMessage(LlmMessage.Role.USER, List.of(new LlmContent.ToolResult("x", "ok", false)))),
                List.of(), null, 100, null, null);

        assertThatThrownBy(() -> client.toRequestBody(request)).isInstanceOf(LlmException.class);
    }

    @Test
    @DisplayName("Dado resposta com texto, raciocínio e chamada de função, quando interpreta, então normaliza e guarda a assinatura")
    void interpretaResposta() throws Exception {
        JsonNode raw = mapper.readTree("""
                {"candidates":[{"content":{"role":"model","parts":[
                   {"text":"pensando...","thought":true},
                   {"text":"Vou verificar."},
                   {"functionCall":{"name":"consultar_horarios","args":{"loja":"centro"}},"thoughtSignature":"sig-9"}]},
                  "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":30,"thoughtsTokenCount":10},
                 "modelVersion":"gemini-2.5-flash-001"}
                """);

        LlmResponse response = client.parseResponse(raw, "gemini-2.5-flash");

        assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
        assertThat(response.text()).isEqualTo("Vou verificar.");
        LlmContent.ToolCall call = response.toolCalls().getFirst();
        assertThat(call.name()).isEqualTo("consultar_horarios");
        assertThat(call.input()).containsEntry("loja", "centro");
        assertThat(call.providerState()).isEqualTo("sig-9");
        assertThat(call.id()).isNotBlank();
        assertThat(response.usage().inputTokens()).isEqualTo(120);
        assertThat(response.usage().outputTokens()).isEqualTo(40);
        assertThat(response.model()).isEqualTo("gemini-2.5-flash-001");
    }

    @Test
    @DisplayName("Dado bloqueio por segurança, quando interpreta, então é recusa")
    void recusa() throws Exception {
        LlmResponse response = client.parseResponse(mapper.readTree(
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}"), "gemini-2.5-flash");

        assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.REFUSAL);
        assertThat(response.content()).isEmpty();
    }
}
