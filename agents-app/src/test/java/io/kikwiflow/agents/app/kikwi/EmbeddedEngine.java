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
package io.kikwiflow.agents.app.kikwi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kikwiflow.KikwiflowEngine;
import io.kikwiflow.config.KikwiflowConfig;
import io.kikwiflow.event.AsynchronousEventPublisher;
import io.kikwiflow.execution.ContinuationService;
import io.kikwiflow.execution.EventThrowExecutor;
import io.kikwiflow.execution.FailureHandler;
import io.kikwiflow.execution.FlowNodeExecutor;
import io.kikwiflow.execution.ProcessExecutionManager;
import io.kikwiflow.execution.TaskAcquirer;
import io.kikwiflow.execution.TaskExecutor;
import io.kikwiflow.execution.api.handler.TaskHandler;
import io.kikwiflow.execution.evaluator.CorrelationKeyResolver;
import io.kikwiflow.execution.evaluator.TimerDueDateEvaluator;
import io.kikwiflow.execution.event.CriticalEventRecorder;
import io.kikwiflow.execution.policy.DefaultRetryPolicyEvaluator;
import io.kikwiflow.model.definition.process.ProcessDefinition;
import io.kikwiflow.model.event.OutboxEventEntity;
import io.kikwiflow.model.security.IdentityContext;
import io.kikwiflow.navigation.Navigator;
import io.kikwiflow.navigation.ProcessDefinitionService;
import io.kikwiflow.parser.jackson.JacksonProcessDefinitionParser;
import io.kikwiflow.parser.jackson.KikwiflowJacksonModule;
import io.kikwiflow.persistence.InMemoryKikwiEngineRepository;
import io.kikwiflow.security.DefaultDeploymentSecurityManager;
import io.kikwiflow.validation.DeployValidator;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

/**
 * O motor Kikwiflow de verdade, em memória e sem Spring, para os testes ponta a ponta do app. Mesma montagem do
 * {@code SingletonsFactory} dos testes do motor (atoxfy/kikwi-core-tests), com o repositório em memória do
 * {@code kikwi-in-memory-addons}. O {@code TaskAcquirer} não é iniciado: o teste dirige tudo.
 */
record EmbeddedEngine(KikwiflowEngine engine, InMemoryKikwiEngineRepository repository,
                      ProcessDefinitionService definitions, Queue<OutboxEventEntity> outbox) {

    static EmbeddedEngine create(Map<String, TaskHandler> handlers) {
        KikwiflowConfig config = new KikwiflowConfig();
        config.setOutboxEventsEnabled(true);
        Queue<OutboxEventEntity> outbox = new ConcurrentLinkedQueue<>();
        InMemoryKikwiEngineRepository repository = new InMemoryKikwiEngineRepository(outbox);

        DeployValidator validator = new DeployValidator(name -> Optional.ofNullable(handlers.get(name)),
                name -> Optional.empty(), name -> Optional.empty());
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new KikwiflowJacksonModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ProcessDefinitionService definitions = new ProcessDefinitionService(new JacksonProcessDefinitionParser(mapper),
                repository, validator, new DefaultDeploymentSecurityManager(true));

        Navigator navigator = new Navigator(name -> Optional.empty());
        CriticalEventRecorder recorder = new CriticalEventRecorder(config);
        CorrelationKeyResolver correlationKeys = new CorrelationKeyResolver(name -> Optional.empty());
        EventThrowExecutor eventThrowExecutor = new EventThrowExecutor(correlationKeys);
        ProcessExecutionManager executionManager = new ProcessExecutionManager(new FlowNodeExecutor(
                new TaskExecutor(name -> Optional.ofNullable(handlers.get(name))), eventThrowExecutor), navigator,
                recorder);
        ContinuationService continuations = new ContinuationService(repository,
                new TimerDueDateEvaluator(name -> Optional.empty()), correlationKeys, config, recorder);
        FailureHandler failureHandler = new FailureHandler(repository, new DefaultRetryPolicyEvaluator(config), recorder);

        KikwiflowEngine engine = new KikwiflowEngine(definitions, navigator, executionManager, repository, config,
                new AsynchronousEventPublisher(Executors.newVirtualThreadPerTaskExecutor()), continuations,
                failureHandler, new TaskAcquirer(repository, config), recorder);
        eventThrowExecutor.setEngine(engine);
        return new EmbeddedEngine(engine, repository, definitions, outbox);
    }

    ProcessDefinition deploy(String classpathResource) {
        try (InputStream is = getClass().getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IllegalArgumentException("Processo não encontrado no classpath: " + classpathResource);
            }
            return definitions.deploy(is.readAllBytes(), IdentityContext.system());
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao implantar " + classpathResource, e);
        }
    }

    /**
     * Executa as tarefas assíncronas pendentes da instância ({@code commitBefore}), o papel do {@code TaskAcquirer}
     * em produção. Repete até não sobrar nenhuma.
     */
    void drainExecutableTasks(String processInstanceId) {
        List<io.kikwiflow.model.execution.node.ExecutableTask> pending;
        while (!(pending = repository.findExecutableTasksByProcessInstanceId(processInstanceId)).isEmpty()) {
            pending.forEach(engine::executeFromTask);
        }
    }

    /** Os nomes dos eventos do outbox, na ordem de gravação. */
    List<String> eventNames() {
        return outbox.stream().map(OutboxEventEntity::getEvent).toList();
    }

    List<OutboxEventEntity> events(String name) {
        return outbox.stream().filter(e -> e.getEvent().equals(name)).toList();
    }
}
