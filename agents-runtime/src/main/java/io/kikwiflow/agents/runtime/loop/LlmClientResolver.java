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
package io.kikwiflow.agents.runtime.loop;

import io.kikwiflow.agents.llm.LlmClient;
import io.kikwiflow.agents.llm.LlmRouter;
import io.kikwiflow.agents.model.agent.ModelRef;

/**
 * Escolhe o cliente de LLM de uma missão. O padrão ({@link #of(LlmRouter)}) usa clientes globais por provedor; um
 * host multi-tenant implementa a sua versão para usar a credencial do provedor de cada tenant.
 */
@FunctionalInterface
public interface LlmClientResolver {

    LlmClient clientFor(String tenantId, ModelRef model);

    static LlmClientResolver of(LlmRouter router) {
        return (tenantId, model) -> router.forModel(model);
    }
}
