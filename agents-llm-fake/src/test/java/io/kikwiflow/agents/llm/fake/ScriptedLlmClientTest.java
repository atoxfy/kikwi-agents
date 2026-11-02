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

import io.kikwiflow.agents.llm.LlmException;
import io.kikwiflow.agents.llm.LlmMessage;
import io.kikwiflow.agents.llm.LlmRequest;
import io.kikwiflow.agents.llm.LlmResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptedLlmClientTest {

    private static LlmRequest request(String text) {
        return new LlmRequest("roteiro", null, List.of(LlmMessage.user(text)), null, null, 256, null, null);
    }

    @Test
    @DisplayName("Dado um roteiro, quando gera, então devolve as respostas em ordem e registra as requisições")
    void devolveEmOrdem() {
        ScriptedLlmClient client = new ScriptedLlmClient()
                .thenCallTool("consultar_fipe", Map.of("placa", "ABC1D23"))
                .thenReply("Seu carro vale em torno de R$ 45 mil na FIPE.");

        LlmResponse first = client.generate(request("Quanto vale?"));
        LlmResponse second = client.generate(request("ok"));

        assertThat(first.toolCalls()).singleElement().extracting(c -> c.name()).isEqualTo("consultar_fipe");
        assertThat(second.text()).contains("FIPE");
        assertThat(client.received()).hasSize(2);
        assertThat(client.remaining()).isZero();
    }

    @Test
    @DisplayName("Dado roteiro esgotado, quando gera, então falha sem retry")
    void roteiroEsgotado() {
        assertThatThrownBy(() -> new ScriptedLlmClient().generate(request("oi")))
                .isInstanceOf(LlmException.class)
                .matches(e -> !((LlmException) e).retryable());
    }
}
