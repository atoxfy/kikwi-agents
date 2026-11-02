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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter da Messages API da Anthropic sem SDK: {@link HttpClient} + Jackson, o mesmo caminho do
 * {@code LlmTaskHandler} do demo de KYC. O mapeamento de ida e volta fica em {@link #toRequestBody} e
 * {@link #parseResponse} para ser testável sem rede.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final AnthropicSettings settings;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public AnthropicLlmClient(AnthropicSettings settings, ObjectMapper mapper) {
        this(settings, HttpClient.newBuilder().connectTimeout(settings.timeout()).build(), mapper);
    }

    AnthropicLlmClient(AnthropicSettings settings, HttpClient http, ObjectMapper mapper) {
        this.settings = settings;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String provider() {
        return "anthropic";
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
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/v1/messages"))
                .timeout(settings.timeout())
                .header("content-type", "application/json")
                .header("x-api-key", settings.apiKey())
                .header("anthropic-version", settings.apiVersion())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("Falha de rede ao chamar a Anthropic", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Chamada à Anthropic interrompida", true, e);
        }
        int status = response.statusCode();
        if (status / 100 != 2) {
            boolean retryable = status == 429 || status == 529 || status >= 500;
            throw new LlmException("Anthropic respondeu HTTP " + status + ": " + response.body(), retryable);
        }
        try {
            return parseResponse(mapper.readTree(response.body()));
        } catch (JsonProcessingException e) {
            throw new LlmException("Resposta da Anthropic ilegível", false, e);
        }
    }

    ObjectNode toRequestBody(LlmRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());
        if (request.system() != null && !request.system().isBlank()) {
            root.put("system", request.system());
        }
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        ArrayNode messages = root.putArray("messages");
        for (LlmMessage message : request.messages()) {
            ObjectNode m = messages.addObject();
            m.put("role", message.role() == LlmMessage.Role.USER ? "user" : "assistant");
            ArrayNode content = m.putArray("content");
            for (LlmContent part : message.content()) {
                content.add(toContentBlock(part));
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (LlmTool tool : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("name", tool.name());
                t.put("description", tool.description());
                t.set("input_schema", mapper.valueToTree(tool.inputSchema()));
            }
            ObjectNode choice = root.putObject("tool_choice");
            switch (request.toolChoice().mode()) {
                case AUTO -> choice.put("type", "auto");
                case ANY -> choice.put("type", "any");
                case NONE -> choice.put("type", "none");
                case TOOL -> {
                    choice.put("type", "tool");
                    choice.put("name", request.toolChoice().toolName());
                }
            }
        }
        request.providerOptions().forEach((k, v) -> root.set(k, mapper.valueToTree(v)));
        return root;
    }

    private ObjectNode toContentBlock(LlmContent part) {
        ObjectNode block = mapper.createObjectNode();
        switch (part) {
            case LlmContent.Text t -> {
                block.put("type", "text");
                block.put("text", t.text());
            }
            case LlmContent.Media media -> {
                block.put("type", "application/pdf".equals(media.mimeType()) ? "document" : "image");
                ObjectNode source = block.putObject("source");
                if (media.base64Data() != null) {
                    source.put("type", "base64");
                    source.put("media_type", media.mimeType());
                    source.put("data", media.base64Data());
                } else {
                    source.put("type", "url");
                    source.put("url", media.url());
                }
            }
            case LlmContent.ToolCall call -> {
                block.put("type", "tool_use");
                block.put("id", call.id());
                block.put("name", call.name());
                block.set("input", mapper.valueToTree(call.input()));
            }
            case LlmContent.ToolResult result -> {
                block.put("type", "tool_result");
                block.put("tool_use_id", result.toolCallId());
                block.put("content", result.content());
                if (result.error()) {
                    block.put("is_error", true);
                }
            }
        }
        return block;
    }

    LlmResponse parseResponse(JsonNode root) {
        List<LlmContent> content = new ArrayList<>();
        for (JsonNode block : root.path("content")) {
            switch (block.path("type").asText()) {
                case "text" -> content.add(new LlmContent.Text(block.path("text").asText()));
                case "tool_use" -> content.add(new LlmContent.ToolCall(
                        block.path("id").asText(),
                        block.path("name").asText(),
                        mapper.convertValue(block.path("input"), MAP)));
                default -> {
                    // blocos que o runtime não usa (thinking, etc.) são ignorados
                }
            }
        }
        LlmResponse.StopReason stopReason = switch (root.path("stop_reason").asText()) {
            case "end_turn", "stop_sequence" -> LlmResponse.StopReason.END_TURN;
            case "tool_use" -> LlmResponse.StopReason.TOOL_USE;
            case "max_tokens" -> LlmResponse.StopReason.MAX_TOKENS;
            case "refusal" -> LlmResponse.StopReason.REFUSAL;
            default -> LlmResponse.StopReason.OTHER;
        };
        JsonNode usage = root.path("usage");
        return new LlmResponse(content, stopReason,
                new LlmUsage(usage.path("input_tokens").asLong(), usage.path("output_tokens").asLong()),
                root.path("model").asText(null));
    }
}
