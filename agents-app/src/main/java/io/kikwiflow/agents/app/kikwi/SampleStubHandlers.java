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

import io.kikwiflow.execution.api.handler.TaskHandler;
import io.kikwiflow.model.execution.ProcessVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dublês dos dois {@code EXECUTABLE_TASK} do processo {@code pre-vendas}, para o fluxo rodar de ponta a
 * ponta em desenvolvimento. A integração real é configurada por cliente. Só sobem com
 * {@code kikwi-agents.sample-stubs.enabled=true}.
 */
public final class SampleStubHandlers {

    private static final Logger log = LoggerFactory.getLogger(SampleStubHandlers.class);

    private SampleStubHandlers() {
    }

    /** Dublê de {@code criarNegocioCrm}: devolve um id {@code stub:} em vez de criar o negócio no CRM. */
    public static TaskHandler criarNegocioCrm() {
        return execution -> {
            String id = "stub:" + execution.getProcessInstanceId();
            log.warn("[stub] criarNegocioCrm na instância {}: nenhum negócio criado no CRM", execution.getProcessInstanceId());
            execution.setVariable("negocioCrmId", new ProcessVariable("negocioCrmId", id));
        };
    }

    /** Dublê de {@code registrarPerda}: só registra em log. */
    public static TaskHandler registrarPerda() {
        return execution -> log.warn("[stub] registrarPerda na instância {}: nada registrado no CRM",
                execution.getProcessInstanceId());
    }
}
