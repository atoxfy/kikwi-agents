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

import io.kikwiflow.agents.model.agent.ModelRef;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Escolhe o {@link LlmClient} pelo provedor do {@link ModelRef}. Trocar de provedor é trocar o texto
 * {@code provider:model} no perfil do agente; nada no runtime muda.
 */
public class LlmRouter {

    private final Map<String, LlmClient> byProvider = new HashMap<>();

    public LlmRouter(Collection<LlmClient> clients) {
        for (LlmClient client : clients) {
            LlmClient previous = byProvider.putIfAbsent(client.provider(), client);
            if (previous != null) {
                throw new IllegalStateException("Dois LlmClient para o provedor '" + client.provider() + "'");
            }
        }
    }

    public Optional<LlmClient> find(String provider) {
        return Optional.ofNullable(byProvider.get(provider));
    }

    public LlmClient forModel(ModelRef model) {
        return find(model.provider())
                .orElseThrow(() -> new IllegalArgumentException("Nenhum LlmClient registrado para o provedor '"
                        + model.provider() + "' (modelo " + model + ")"));
    }
}
