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
package io.kikwiflow.agents.runtime.mission;

import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.runtime.audit.ActivityRecorder;
import io.kikwiflow.agents.runtime.audit.AgentActivity;

import java.util.List;

/**
 * Envia cada atividade ao motor na hora, sob o lease deste worker. Uma por chamada: o volume por turno é pequeno
 * (uma por ferramenta) e assim a trilha não depende de um flush que um crash poderia perder.
 *
 * <p>Se o lease já foi perdido, a {@link MissionLeaseLostException} sobe: o turno é interrompido e o
 * {@link MissionWorker} solta a conversa.
 */
public class MissionSourceActivityRecorder implements ActivityRecorder {

    private final MissionSource source;
    private final String workerId;

    public MissionSourceActivityRecorder(MissionSource source, String workerId) {
        this.source = source;
        this.workerId = workerId;
    }

    @Override
    public void record(Mission mission, AgentActivity activity) {
        source.reportActivities(mission.taskId(), workerId, List.of(activity));
    }
}
