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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kikwiflow.agents.llm.LlmCapability;
import io.kikwiflow.agents.llm.LlmClient;
import io.kikwiflow.agents.llm.LlmContent;
import io.kikwiflow.agents.llm.LlmException;
import io.kikwiflow.agents.llm.LlmMessage;
import io.kikwiflow.agents.llm.LlmRequest;
import io.kikwiflow.agents.llm.LlmResponse;
import io.kikwiflow.agents.llm.LlmTool;
import io.kikwiflow.agents.llm.LlmUsage;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter da API Gemini ({@code models/{modelo}:generateContent}) sem SDK: {@link HttpClient} + Jackson, como o da
 * Anthropic. Diferenças que o adapter absorve:
 * <ul>
 *   <li>papéis {@code user}/{@code model}; instruções em {@code systemInstruction};</li>
 *   <li>ferramentas como {@code functionDeclarations} com {@code parametersJsonSchema} (JSON Schema completo);</li>
 *   <li>o resultado de uma ferramenta ({@code functionResponse}) leva o <i>nome</i> da função, não o id — o nome é
 *       recuperado da chamada correspondente nas mensagens do próprio pedido;</li>
 *   <li>a assinatura de raciocínio ({@code thoughtSignature}) de uma chamada precisa voltar junto com ela no pedido
 *       seguinte: vai e volta em {@link LlmContent.ToolCall#providerState()}.</li>
 * </ul>
 * O mapeamento fica em {@link #toRequestBody} e {@link #parseResponse} para ser testável sem rede.
 */
public class GeminiLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final GeminiSettings settings;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GeminiLlmClient(GeminiSettings settings, ObjectMapper mapper) {
        this(settings, HttpClient.newBuilder().connectTimeout(settings.timeout()).build(), mapper);
    }

    GeminiLlmClient(GeminiSettings settings, HttpClient http, ObjectMapper mapper) {
        this.settings = settings;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public Set<LlmCapability> capabilities() {
        return EnumSet.of(LlmCapability.TOOL_USE, LlmCapability.VISION, LlmCapability.DOCUMENTS);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        String body;
        try {
            body = mapper.writeValueAsString(toRequestBody(request));
        } catch (JsonProcessingException e) {
            throw new LlmException("Falha ao serializar a requisição", false, e);
        }
        URI uri = URI.create(settings.baseUrl() + "/v1beta/models/"
                + URLEncoder.encode(request.model(), StandardCharsets.UTF_8) + ":generateContent");
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(settings.timeout())
                .header("content-type", "application/json")
                .header("x-goog-api-key", settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("Falha de rede ao chamar o Gemini", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Chamada ao Gemini interrompida", true, e);
        }
        int status = response.statusCode();
        if (status / 100 != 2) {
            boolean retryable = status == 429 || status >= 500;
            throw new LlmException("Gemini respondeu HTTP " + status + ": " + response.body(), retryable);
        }
        try {
            return parseResponse(mapper.readTree(response.body()), request.model());
        } catch (JsonProcessingException e) {
            throw new LlmException("Resposta do Gemini ilegível", false, e);
        }
    }

    ObjectNode toRequestBody(LlmRequest request) {
        ObjectNode root = mapper.createObjectNode();
        if (request.system() != null && !request.system().isBlank()) {
            root.putObject("systemInstruction").putArray("parts").addObject().put("text", request.system());
        }
        Map<String, String> callNames = new HashMap<>();
        ArrayNode contents = root.putArray("contents");
        for (LlmMessage message : request.messages()) {
            ObjectNode m = contents.addObject();
            m.put("role", message.role() == LlmMessage.Role.USER ? "user" : "model");
            ArrayNode parts = m.putArray("parts");
            for (LlmContent part : message.content()) {
                if (part instanceof LlmContent.ToolCall call) {
                    callNames.put(call.id(), call.name());
                }
                parts.add(toPart(part, callNames));
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode declarations = root.putArray("tools").addObject().putArray("functionDeclarations");
            for (LlmTool tool : request.tools()) {
                ObjectNode d = declarations.addObject();
                d.put("name", tool.name());
                d.put("description", tool.description());
                d.set("parametersJsonSchema", mapper.valueToTree(tool.inputSchema()));
            }
            ObjectNode config = root.putObject("toolConfig").putObject("functionCallingConfig");
            switch (request.toolChoice().mode()) {
                case AUTO -> config.put("mode", "AUTO");
                case ANY -> config.put("mode", "ANY");
                case NONE -> config.put("mode", "NONE");
                case TOOL -> {
                    config.put("mode", "ANY");
                    config.putArray("allowedFunctionNames").add(request.toolChoice().toolName());
                }
            }
        }
        ObjectNode generation = root.putObject("generationConfig");
        generation.put("maxOutputTokens", request.maxTokens());
        if (request.temperature() != null) {
            generation.put("temperature", request.temperature());
        }
        request.providerOptions().forEach((k, v) -> root.set(k, mapper.valueToTree(v)));
        return root;
    }

    private ObjectNode toPart(LlmContent content, Map<String, String> callNames) {
        ObjectNode part = mapper.createObjectNode();
        switch (content) {
            case LlmContent.Text t -> part.put("text", t.text());
            case LlmContent.Media media -> {
                if (media.base64Data() != null) {
                    ObjectNode inline = part.putObject("inlineData");
                    inline.put("mimeType", media.mimeType());
                    inline.put("data", media.base64Data());
                } else {
                    ObjectNode file = part.putObject("fileData");
                    file.put("mimeType", media.mimeType());
                    file.put("fileUri", media.url());
                }
            }
            case LlmContent.ToolCall call -> {
                ObjectNode fc = part.putObject("functionCall");
                fc.put("name", call.name());
                fc.set("args", mapper.valueToTree(call.input()));
                if (call.providerState() != null) {
                    part.put("thoughtSignature", call.providerState());
                }
            }
            case LlmContent.ToolResult result -> {
                ObjectNode fr = part.putObject("functionResponse");
                String name = callNames.get(result.toolCallId());
                if (name == null) {
                    throw new LlmException("Resultado de ferramenta sem a chamada correspondente: "
                            + result.toolCallId(), false);
                }
                fr.put("name", name);
                fr.putObject("response").put(result.error() ? "error" : "result", result.content());
            }
        }
        return part;
    }

    LlmResponse parseResponse(JsonNode root, String requestedModel) {
        JsonNode candidate = root.path("candidates").path(0);
        List<LlmContent> content = new ArrayList<>();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) {
                continue; // resumo de raciocínio: não é fala do agente
            }
            if (part.has("functionCall")) {
                JsonNode call = part.path("functionCall");
                String id = call.hasNonNull("id") ? call.path("id").asText() : "call_" + UUID.randomUUID();
                content.add(new LlmContent.ToolCall(id, call.path("name").asText(),
                        mapper.convertValue(call.path("args"), MAP),
                        part.hasNonNull("thoughtSignature") ? part.path("thoughtSignature").asText() : null));
            } else if (part.has("text")) {
                content.add(new LlmContent.Text(part.path("text").asText()));
            }
        }
        boolean calledTools = content.stream().anyMatch(LlmContent.ToolCall.class::isInstance);
        String finish = candidate.path("finishReason").asText(root.path("promptFeedback").path("blockReason").asText(""));
        LlmResponse.StopReason stopReason = calledTools ? LlmResponse.StopReason.TOOL_USE : switch (finish) {
            case "STOP" -> LlmResponse.StopReason.END_TURN;
            case "MAX_TOKENS" -> LlmResponse.StopReason.MAX_TOKENS;
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "OTHER" ->
                    LlmResponse.StopReason.REFUSAL;
            default -> LlmResponse.StopReason.OTHER;
        };
        JsonNode usage = root.path("usageMetadata");
        return new LlmResponse(content, stopReason,
                new LlmUsage(usage.path("promptTokenCount").asLong(),
                        usage.path("candidatesTokenCount").asLong() + usage.path("thoughtsTokenCount").asLong()),
                root.path("modelVersion").asText(requestedModel));
    }
}
