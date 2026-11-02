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
package io.kikwiflow.agents.model.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Definição de agente do catálogo — o mesmo formato do {@code .agent.json} do Agent Studio (Craft). Vive fora do
 * motor: o processo só referencia o agente por {@code AGENT_TASK.agentRef} e governa a missão (versão, contrato de
 * saída, prazos, auditoria).
 *
 * <p>{@code instructions} é texto livre com parâmetros {@code {{chave}}}, preenchidos por {@code parameterValues}
 * (ou pelo {@code default} do parâmetro). Os valores são configuração do agente, nunca variáveis do processo — essas
 * chegam ao modelo como dados, separadas das instruções. Personalizar um agente é publicar uma nova versão.
 *
 * @param version 0 = rascunho; versões publicadas começam em 1 e são imutáveis.
 */
public record AgentDefinition(String key,
                              int version,
                              AgentStatus status,
                              String tenantId,
                              String name,
                              String description,
                              String instructions,
                              List<AgentParameter> parameters,
                              Map<String, String> parameterValues,
                              AgentModelSettings model,
                              AgentPerception perception,
                              List<AgentToolRef> tools,
                              List<String> subagents,
                              AgentLimits limits) {

    private static final Pattern PARAMETER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    public AgentDefinition {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        parameterValues = parameterValues == null ? Map.of() : Map.copyOf(parameterValues);
        perception = perception == null ? AgentPerception.none() : perception;
        tools = tools == null ? List.of() : List.copyOf(tools);
        subagents = subagents == null ? List.of() : List.copyOf(subagents);
        limits = limits == null ? new AgentLimits(null) : limits;
        status = status == null ? AgentStatus.DRAFT : status;
    }

    /** O modelo como {@code provider:model}. */
    public ModelRef modelRef() {
        return ModelRef.parse(model.id());
    }

    /** Valor efetivo de cada parâmetro: o preenchido nesta versão, senão o padrão. */
    public Map<String, String> resolvedParameters() {
        Map<String, String> values = new LinkedHashMap<>();
        for (AgentParameter parameter : parameters) {
            String value = parameterValues.getOrDefault(parameter.key(), parameter.defaultValue());
            if (value != null) {
                values.put(parameter.key(), value);
            }
        }
        return values;
    }

    /** As instruções como o modelo as recebe. Parâmetro sem valor fica visível ({@code {{chave}}}). */
    public String renderedInstructions() {
        if (instructions == null) {
            return "";
        }
        Map<String, String> values = resolvedParameters();
        Matcher matcher = PARAMETER.matcher(instructions);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null || value.isEmpty() ? matcher.group() : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public List<String> toolNames() {
        return tools.stream().map(AgentToolRef::name).toList();
    }

    public AgentDefinition withVersion(int newVersion, AgentStatus newStatus) {
        return new AgentDefinition(key, newVersion, newStatus, tenantId, name, description, instructions, parameters,
                parameterValues, model, perception, tools, subagents, limits);
    }

    public AgentDefinition withTenant(String newTenantId) {
        return new AgentDefinition(key, version, status, newTenantId, name, description, instructions, parameters,
                parameterValues, model, perception, tools, subagents, limits);
    }

    public AgentDefinition withParameterValues(Map<String, String> values) {
        return new AgentDefinition(key, version, status, tenantId, name, description, instructions, parameters,
                values, model, perception, tools, subagents, limits);
    }

    public AgentDefinition withModel(AgentModelSettings newModel) {
        return new AgentDefinition(key, version, status, tenantId, name, description, instructions, parameters,
                parameterValues, newModel, perception, tools, subagents, limits);
    }
}
