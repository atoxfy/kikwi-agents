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
package io.kikwiflow.agents.platform.kikwi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kikwiflow.KikwiflowEngine;
import io.kikwiflow.agents.model.agent.AgentToolRef;
import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.model.mission.OutputField;
import io.kikwiflow.agents.model.tool.ToolBinding;
import io.kikwiflow.agents.model.tool.ToolDefinition;
import io.kikwiflow.agents.runtime.prompt.OutputSchemaBuilder;
import io.kikwiflow.agents.runtime.tool.BindingExecutor;
import io.kikwiflow.agents.runtime.tool.ProcessToolDescriber;
import io.kikwiflow.agents.runtime.tool.ToolContext;
import io.kikwiflow.agents.runtime.tool.ToolResult;
import io.kikwiflow.exception.ProcessErrorException;
import io.kikwiflow.exception.VariableValidationException;
import io.kikwiflow.model.definition.process.ProcessContract;
import io.kikwiflow.model.definition.process.ProcessDefinition;
import io.kikwiflow.model.definition.process.variable.VariableDeclaration;
import io.kikwiflow.model.definition.process.variable.VariableOption;
import io.kikwiflow.model.execution.ProcessInstance;
import io.kikwiflow.model.execution.ProcessVariable;
import io.kikwiflow.model.execution.enumerated.ProcessInstanceStatus;
import io.kikwiflow.navigation.ProcessDefinitionService;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Processos Kikwiflow como ferramentas de agente (binding {@code Process}), sobre o motor embutido.
 *
 * <ul>
 *   <li><b>Descrever:</b> o nome vem do agente; a descrição, do processo (ou da sobrescrita no agente); o schema
 *   de entrada, dos {@code inputs} do start resolvidos no catálogo de variáveis — o mesmo {@link OutputSchemaBuilder}
 *   do {@code concluir_missao}.</li>
 *   <li><b>Contexto da missão:</b> uma entrada que a missão já tem (mesma chave numa variável da instância, ex.: o
 *   {@code leadId} criado antes da missão) sai do schema e é preenchida pelo runtime. O modelo não a vê nem a
 *   sobrescreve — o agente não consegue agir sobre outro lead.</li>
 *   <li><b>Executar:</b> inicia uma instância com o {@code businessKey} da missão, autor {@code agent:<chave>} e
 *   origem {@code agent-task:<id>}, e devolve ao modelo só o <b>retorno</b> do processo
 *   ({@link ProcessContract#project}) — nunca o estado interno. Entradas fora do contrato (422) voltam como erro
 *   de ferramenta, para o modelo corrigir e tentar de novo; erro de negócio sem tratamento também.</li>
 * </ul>
 *
 * <p>V1: o processo precisa terminar na mesma chamada (tarefas síncronas, como o {@code kikwiHttp}). Se ele parar
 * numa espera, o modelo recebe {@code RUNNING} com o id da instância e o agente segue a conversa sem o retorno.
 */
public class EngineProcessTools implements ProcessToolDescriber, BindingExecutor<ToolBinding.Process> {

    private final KikwiflowEngine engine;
    private final ProcessDefinitionService definitions;
    private final ObjectMapper mapper;

    public EngineProcessTools(KikwiflowEngine engine, ProcessDefinitionService definitions, ObjectMapper mapper) {
        this.engine = engine;
        this.definitions = definitions;
        this.mapper = mapper;
    }

    @Override
    public Optional<ToolDefinition> describe(Mission mission, AgentToolRef tool) {
        return definitions.getByKey(tool.processKey()).map(definition -> {
            ProcessContract contract = ProcessContract.of(definition);
            List<OutputField> inputs = contract.inputs().stream()
                    .filter(field -> !fromMission(mission, field.variable()))
                    .map(EngineProcessTools::field).toList();
            String description = firstNonBlank(tool.description(), definition.description(), definition.name(),
                    definition.key());
            List<String> returns = contract.outputs().stream().map(ProcessContract.Field::variable).toList();
            if (!returns.isEmpty()) {
                description += " Retorna: " + String.join(", ", returns) + ".";
            }
            return new ToolDefinition(tool.name(), description, OutputSchemaBuilder.build(inputs),
                    new ToolBinding.Process(definition.key(), returns), true);
        });
    }

    @Override
    public Class<ToolBinding.Process> bindingType() {
        return ToolBinding.Process.class;
    }

    @Override
    public ToolResult execute(ToolDefinition tool, ToolBinding.Process binding, ToolContext context,
                              Map<String, Object> input) {
        Mission mission = context.mission();
        ProcessDefinition definition = definitions.getByKeyOrElseThrow(binding.processKey());
        ProcessContract contract = ProcessContract.of(definition);

        Map<String, ProcessVariable> variables = new HashMap<>();
        if (input != null) {
            input.forEach((name, value) -> {
                if (value != null && !fromMission(mission, name)) {
                    variables.put(name, new ProcessVariable(name, value));
                }
            });
        }
        for (ProcessContract.Field field : contract.inputs()) {
            if (fromMission(mission, field.variable())) {
                variables.put(field.variable(), new ProcessVariable(field.variable(),
                        mission.variables().get(field.variable())));
            }
        }

        ProcessInstance instance;
        try {
            instance = engine.startProcess()
                    .byKey(definition.key())
                    .withBusinessKey(mission.businessKey())
                    .onTenant(mission.tenantId())
                    .byActor("agent:" + mission.agentKey())
                    .from("agent-task:" + mission.taskId())
                    .withVariables(variables)
                    .execute();
        } catch (VariableValidationException e) {
            List<String> problems = e.getErrors().stream()
                    .map(error -> error.variable() + ": " + error.code()
                            + (error.message() != null ? " (" + error.message() + ")" : ""))
                    .toList();
            return ToolResult.error("Entradas recusadas: " + String.join("; ", problems)
                    + ". Corrija com o lead e tente de novo.");
        } catch (ProcessErrorException e) {
            return ToolResult.error("O processo terminou com o erro de negócio " + e.getErrorCode()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "") + ".");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (instance.status() == ProcessInstanceStatus.COMPLETED) {
            Map<String, Object> returned = new LinkedHashMap<>();
            contract.project(instance.variables()).forEach((name, variable) -> returned.put(name, variable.value()));
            result.put("status", "CONCLUIDO");
            result.put("retorno", returned);
        } else {
            result.put("status", "EM_ANDAMENTO");
            result.put("processInstanceId", instance.id());
        }
        return new ToolResult(true, toJson(result), Map.of("processInstanceId", instance.id()));
    }

    private static boolean fromMission(Mission mission, String variable) {
        return mission != null && mission.variables() != null && mission.variables().get(variable) != null;
    }

    private static OutputField field(ProcessContract.Field field) {
        VariableDeclaration declaration = field.declaration();
        return new OutputField(declaration.key(), declaration.label(), declaration.description(),
                declaration.format() == null ? null : declaration.format().name(), field.required(),
                declaration.options().stream().map(VariableOption::value).toList(),
                declaration.extensionProperties() == null ? Map.of() : declaration.extensionProperties());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
