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

import java.time.Duration;

/**
 * @param apiKey  chave do Google AI Studio (aistudio.google.com) — o plano gratuito serve para testes.
 * @param baseUrl padrão {@code https://generativelanguage.googleapis.com}; trocável para gateway/proxy.
 */
public record GeminiSettings(String apiKey, String baseUrl, Duration timeout) {

    public static GeminiSettings of(String apiKey) {
        return new GeminiSettings(apiKey, "https://generativelanguage.googleapis.com", Duration.ofSeconds(120));
    }
}
