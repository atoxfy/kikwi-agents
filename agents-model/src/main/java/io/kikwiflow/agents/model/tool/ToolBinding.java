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
package io.kikwiflow.agents.model.tool;

import java.util.List;
import java.util.Map;

/**
 * Como uma ferramenta é executada. É o ponto de extensão que prepara o caminho "ad-hoc" (doc 31 §6): um futuro
 * binding {@code Activity} (atividade de um {@code AD_HOC_SUBPROCESS}) entra aqui sem mudar o runtime nem o
 * evento de auditoria {@code AGENT_TOOL_INVOKED}.
 */
public sealed interface ToolBinding {

    /** Código do próprio runtime, registrado no {@code ToolRegistry} com este nome. */
    record Inline(String executorName) implements ToolBinding {
    }

    /**
     * Chamada HTTP declarativa.
     *
     * @param urlTemplate    com placeholders {@code {campo}} preenchidos a partir do input da ferramenta.
     * @param credentialsRef segredo (fora do banco) usado para autenticar.
     */
    record Http(String method, String urlTemplate, String credentialsRef, Map<String, String> headers) implements ToolBinding {
    }

    /**
     * Inicia um processo Kikwiflow filho com o input como variáveis e devolve as variáveis listadas quando ele
     * termina. A execução vira uma instância auditável por si só.
     */
    record Process(String processKey, List<String> outputVariables) implements ToolBinding {
    }
}
