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

import io.kikwiflow.agents.platform.AgentsProperties;
import io.kikwiflow.agents.runtime.mission.MissionWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.util.concurrent.ScheduledFuture;

/**
 * Agenda a aquisição e a renovação de leases do {@link MissionWorker}. Erros de uma rodada são logados e não
 * param o agendamento; a próxima rodada tenta de novo.
 */
public class MissionScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MissionScheduler.class);

    private final MissionWorker worker;
    private final TaskScheduler scheduler;
    private final AgentsProperties.Worker settings;
    private ScheduledFuture<?> polling;
    private ScheduledFuture<?> renewing;

    public MissionScheduler(MissionWorker worker, TaskScheduler scheduler, AgentsProperties.Worker settings) {
        this.worker = worker;
        this.scheduler = scheduler;
        this.settings = settings;
    }

    @Override
    public void start() {
        polling = scheduler.scheduleWithFixedDelay(() -> safely("aquisição", worker::poll), settings.pollInterval());
        renewing = scheduler.scheduleWithFixedDelay(() -> safely("renovação", worker::renewLeases),
                settings.renewInterval());
    }

    @Override
    public void stop() {
        if (polling != null) {
            polling.cancel(false);
        }
        if (renewing != null) {
            renewing.cancel(false);
        }
        polling = null;
        renewing = null;
    }

    @Override
    public boolean isRunning() {
        return polling != null;
    }

    private static void safely(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("Falha na {} de missões", what, e);
        }
    }
}
