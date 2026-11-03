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
package io.kikwiflow.agents.llm.fake;

import io.kikwiflow.agents.llm.LlmCapability;
import io.kikwiflow.agents.llm.LlmClient;
import io.kikwiflow.agents.llm.LlmContent;
import io.kikwiflow.agents.llm.LlmException;
import io.kikwiflow.agents.llm.LlmRequest;
import io.kikwiflow.agents.llm.LlmResponse;
import io.kikwiflow.agents.llm.LlmUsage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link LlmClient} roteirizado: devolve, em ordem, as respostas enfileiradas. Serve para testes do runtime e
 * para demos sem chave de API (provedor {@code fake}, ex.: {@code fake:roteiro}). Guarda as requisições recebidas
 * para asserções.
 */
public class ScriptedLlmClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> received = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public Set<LlmCapability> capabilities() {
        return EnumSet.allOf(LlmCapability.class);
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        received.add(request);
        LlmResponse next = script.pollFirst();
        if (next == null) {
            throw new LlmException("Roteiro do ScriptedLlmClient esgotado", false);
        }
        return next;
    }

    public ScriptedLlmClient thenReply(String text) {
        script.addLast(new LlmResponse(List.of(new LlmContent.Text(text)), LlmResponse.StopReason.END_TURN,
                new LlmUsage(10, 5), "fake"));
        return this;
    }

    public ScriptedLlmClient thenCallTool(String toolName, Map<String, Object> input) {
        script.addLast(new LlmResponse(
                List.of(new LlmContent.ToolCall("call_" + UUID.randomUUID(), toolName, input)),
                LlmResponse.StopReason.TOOL_USE, new LlmUsage(10, 5), "fake"));
        return this;
    }

    /** Texto ao lead e chamada de ferramenta na mesma resposta, como os modelos costumam fazer. */
    public ScriptedLlmClient thenSayAndCallTool(String text, String toolName, Map<String, Object> input) {
        script.addLast(new LlmResponse(
                List.of(new LlmContent.Text(text), new LlmContent.ToolCall("call_" + UUID.randomUUID(), toolName, input)),
                LlmResponse.StopReason.TOOL_USE, new LlmUsage(10, 5), "fake"));
        return this;
    }

    public List<LlmRequest> received() {
        return List.copyOf(received);
    }

    public int remaining() {
        return script.size();
    }
}
