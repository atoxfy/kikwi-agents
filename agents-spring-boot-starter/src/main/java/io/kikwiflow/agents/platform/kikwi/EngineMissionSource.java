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

import io.kikwiflow.KikwiflowEngine;
import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.model.mission.MissionLimits;
import io.kikwiflow.agents.model.mission.OutputField;
import io.kikwiflow.agents.runtime.audit.AgentActivity;
import io.kikwiflow.agents.runtime.mission.MissionLeaseLostException;
import io.kikwiflow.agents.runtime.mission.MissionOutputRejectedException;
import io.kikwiflow.agents.runtime.mission.MissionSource;
import io.kikwiflow.exception.ExternalTaskLockException;
import io.kikwiflow.exception.TaskNotFoundException;
import io.kikwiflow.exception.VariableValidationException;
import io.kikwiflow.model.definition.process.elements.AgentTaskDefinition;
import io.kikwiflow.model.definition.process.variable.VariableBinding;
import io.kikwiflow.model.definition.process.variable.VariableDeclaration;
import io.kikwiflow.model.definition.process.variable.VariableOption;
import io.kikwiflow.model.execution.AgentActivityReport;
import io.kikwiflow.model.execution.LockedExternalTask;
import io.kikwiflow.model.execution.ProcessVariable;
import io.kikwiflow.model.security.IdentityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link MissionSource} in-process, sobre a API de worker do {@code KikwiflowEngine} embutido (doc 31, E2/E3).
 *
 * <p>Usa {@link IdentityContext#system()}: o runtime embutido atende todos os tenants. O isolamento continua
 * garantido porque cada missão só é ligada à conversa do mesmo tenant ({@code MissionWorker}).
 *
 * <p>Traduz as exceções do motor para as do runtime: lease perdido (409) e missão interrompida por boundary (404)
 * viram {@link MissionLeaseLostException}; contrato violado (422) vira {@link MissionOutputRejectedException}.
 */
public class EngineMissionSource implements MissionSource {

    static final String PROMPT_PROPERTY = "kikwi:prompt";

    private static final Logger log = LoggerFactory.getLogger(EngineMissionSource.class);

    private final KikwiflowEngine engine;

    public EngineMissionSource(KikwiflowEngine engine) {
        this.engine = engine;
    }

    @Override
    public List<Mission> fetchAndLock(String workerId, List<String> topics, int maxTasks, Duration lockDuration) {
        List<Mission> missions = new ArrayList<>();
        for (LockedExternalTask locked : engine.fetchAndLock(workerId, topics, maxTasks, lockDuration,
                IdentityContext.system())) {
            Mission mission = toMission(locked);
            if (mission != null) {
                missions.add(mission);
            } else {
                // Hoje só AGENT_TASK tem tópico; se um dia outro nó tiver, a tarefa volta à fila quando o lease vencer.
                log.warn("Tarefa {} do tópico {} não é um AGENT_TASK; ignorada", locked.task().id(),
                        locked.task().topicName());
            }
        }
        return missions;
    }

    @Override
    public void extendLock(String taskId, String workerId, Duration lockDuration) {
        leased(taskId, () -> engine.extendLock(taskId, workerId, lockDuration, IdentityContext.system()));
    }

    @Override
    public void complete(String taskId, String workerId, Map<String, Object> outputs, Map<String, Object> report) {
        leased(taskId, () -> {
            try {
                return engine.completeExternalTask(taskId, workerId, toVariables(outputs), report,
                        IdentityContext.system());
            } catch (VariableValidationException e) {
                List<String> problems = e.getErrors().stream()
                        .map(error -> error.variable() + ": " + error.code()
                                + (error.message() != null ? " (" + error.message() + ")" : ""))
                        .toList();
                throw new MissionOutputRejectedException(problems, e);
            }
        });
    }

    @Override
    public void fail(String taskId, String workerId, String message, int retries, Duration retryTimeout) {
        leased(taskId, () -> {
            engine.handleFailure(taskId, workerId, message, null, retries, retryTimeout, IdentityContext.system());
            return null;
        });
    }

    @Override
    public void throwBusinessError(String taskId, String workerId, String errorCode, String message,
                                   Map<String, Object> variables) {
        leased(taskId, () -> engine.throwBusinessError(taskId, workerId, errorCode, message, toVariables(variables),
                IdentityContext.system()));
    }

    @Override
    public void reportActivities(String taskId, String workerId, List<AgentActivity> activities) {
        List<AgentActivityReport> reports = activities.stream()
                .map(a -> new AgentActivityReport(a.type().name(), a.at(), withoutNulls(a.data())))
                .toList();
        leased(taskId, () -> {
            engine.reportActivities(taskId, workerId, reports, IdentityContext.system());
            return null;
        });
    }

    /**
     * {@code LockedExternalTask} → {@link Mission}. O contrato de saída sai dos {@code outputs} do nó resolvidos
     * contra as declarações que o motor já entrega junto. {@code null} se o nó não é um {@code AGENT_TASK}.
     */
    static Mission toMission(LockedExternalTask locked) {
        if (!(locked.node() instanceof AgentTaskDefinition node)) {
            return null;
        }
        Map<String, VariableDeclaration> declarations = new HashMap<>();
        if (locked.declarations() != null) {
            locked.declarations().forEach(d -> declarations.put(d.key(), d));
        }
        List<OutputField> contract = new ArrayList<>();
        List<VariableBinding> outputs = node.variableBindings() == null || node.variableBindings().outputs() == null
                ? List.of() : node.variableBindings().outputs();
        for (VariableBinding binding : outputs) {
            VariableDeclaration declaration = declarations.get(binding.variable());
            contract.add(declaration == null
                    ? new OutputField(binding.variable(), binding.variable(), null, null,
                            Boolean.TRUE.equals(binding.required()), List.of(), Map.of())
                    : new OutputField(declaration.key(), declaration.label(), declaration.description(),
                            declaration.format() == null ? null : declaration.format().name(),
                            Boolean.TRUE.equals(binding.required()),
                            declaration.options().stream().map(VariableOption::value).toList(),
                            declaration.extensionProperties() == null ? Map.of() : declaration.extensionProperties()));
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        if (locked.variables() != null) {
            locked.variables().forEach((name, variable) -> variables.put(name, variable.value()));
        }

        MissionLimits limits = node.limits() == null ? MissionLimits.none()
                : new MissionLimits(node.limits().maxTurns(),
                        node.limits().ttl() == null ? null : Duration.parse(node.limits().ttl()));
        String objective = node.extensionProperties() == null ? null : node.extensionProperties().get(PROMPT_PROPERTY);

        return new Mission(locked.task().id(), locked.task().processInstanceId(), locked.task().tenantId(),
                locked.businessKey(), node.agentRef().key(), node.agentRef().version(), node.tools(), objective,
                contract, limits, variables, locked.task().lockExpiresAt(), locked.task().createdAt(),
                locked.task().retries());
    }

    private static Map<String, ProcessVariable> toVariables(Map<String, Object> values) {
        Map<String, ProcessVariable> variables = new HashMap<>();
        if (values != null) {
            values.forEach((name, value) -> variables.put(name, new ProcessVariable(name, value)));
        }
        return variables;
    }

    /** Campo nulo não diz nada na trilha (ex.: {@code binding} de uma ferramenta negada); fica de fora. */
    private static Map<String, Object> withoutNulls(Map<String, Object> data) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (data != null) {
            data.forEach((key, value) -> {
                if (value != null) {
                    clean.put(key, value);
                }
            });
        }
        return clean;
    }

    private static <T> T leased(String taskId, Supplier<T> call) {
        try {
            return call.get();
        } catch (ExternalTaskLockException | TaskNotFoundException e) {
            throw new MissionLeaseLostException(taskId, e.getMessage(), e);
        }
    }
}
