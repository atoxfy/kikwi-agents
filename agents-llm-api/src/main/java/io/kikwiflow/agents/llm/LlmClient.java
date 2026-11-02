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

import java.util.Set;

/**
 * Porta única para provedores de LLM. O runtime de agentes só conhece este contrato; cada provedor é um adapter
 * (Anthropic, compatível com OpenAI, Bedrock...) registrado no {@link LlmRouter} pelo seu {@link #provider()}.
 *
 * <p>O contrato é deliberadamente o mínimo comum: mensagens com texto/imagem, ferramentas com JSON Schema e
 * escolha de ferramenta. Recursos específicos de um provedor entram por {@link LlmRequest#providerOptions()},
 * nunca por novos métodos aqui.
 */
public interface LlmClient {

    /** Prefixo usado no {@code ModelRef} (ex.: {@code anthropic}). */
    String provider();

    Set<LlmCapability> capabilities();

    LlmResponse generate(LlmRequest request);
}
