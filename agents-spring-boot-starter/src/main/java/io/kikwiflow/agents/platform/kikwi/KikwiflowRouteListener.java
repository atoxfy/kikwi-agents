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
import io.kikwiflow.agents.channel.DeliveryResult;
import io.kikwiflow.agents.channel.OutboundGateway;
import io.kikwiflow.agents.conversation.ConversationRepository;
import io.kikwiflow.agents.conversation.RouteDecision;
import io.kikwiflow.agents.conversation.RouteListener;
import io.kikwiflow.agents.model.conversation.ConversationStatus;
import io.kikwiflow.agents.runtime.mission.MissionWorker;
import io.kikwiflow.model.execution.ProcessInstance;
import io.kikwiflow.model.execution.ProcessVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Executa as decisões de roteamento contra o motor embutido e o {@link MissionWorker}:
 * <ul>
 *   <li>conversa nova inicia o processo de entrada do canal ({@code businessKey = conversationId}, tenant do canal)
 *       e já dispara uma aquisição, para o lead não esperar o próximo ciclo do agendador;</li>
 *   <li>mensagem para uma missão roda um turno do agente;</li>
 *   <li>lead que trocou de canal com conversa aberta recebe, pelo canal novo, a pergunta "continuar ou outro
 *       assunto?";</li>
 *   <li>opt-out com missão em andamento encerra a missão pelo erro de negócio {@code LEAD_OPT_OUT}.</li>
 * </ul>
 */
public class KikwiflowRouteListener implements RouteListener {

    private static final Logger log = LoggerFactory.getLogger(KikwiflowRouteListener.class);

    private final KikwiflowEngine engine;
    private final ConversationRepository conversations;
    private final MissionWorker worker;
    private final OutboundGateway outbound;

    public KikwiflowRouteListener(KikwiflowEngine engine, ConversationRepository conversations, MissionWorker worker,
                                  OutboundGateway outbound) {
        this.engine = engine;
        this.conversations = conversations;
        this.worker = worker;
        this.outbound = outbound;
    }

    @Override
    public void onRoute(RouteDecision decision) {
        switch (decision) {
            case RouteDecision.StartProcess start -> {
                Map<String, ProcessVariable> variables = new HashMap<>();
                start.variables().forEach((k, v) -> variables.put(k, new ProcessVariable(k, v)));
                ProcessInstance instance = engine.startProcess()
                        .byKey(start.processKey())
                        .onTenant(start.conversation().tenantId())
                        .withBusinessKey(start.conversation().id())
                        .from("kikwi-agents")
                        .byActor("channel:" + start.conversation().channel().channelId())
                        .withVariables(variables)
                        .execute();
                conversations.save(start.conversation().withMission(instance.id(), null));
                log.info("Conversa {} iniciou a instância {} ({})", start.conversation().id(), instance.id(),
                        start.processKey());
                worker.poll();
            }
            case RouteDecision.DeliverToMission deliver -> {
                if (!worker.onMessage(deliver.conversation().id(), deliver.missionTaskId())) {
                    log.info("Missão {} da conversa {} não está neste nó; a mensagem será lida na reaquisição",
                            deliver.missionTaskId(), deliver.conversation().id());
                }
            }
            case RouteDecision.AskChannelSwitch ask -> {
                DeliveryResult result = outbound.deliver(ask.conversation().tenantId(), ask.leadId(), ask.channel(),
                        null, ask.question());
                log.info("Lead {} escreveu pelo canal {} com conversa aberta em {}; perguntado se continua ({})",
                        ask.leadId(), ask.channel().channelId(), ask.conversation().channel(),
                        result.delivered() ? "entregue" : result.error());
            }
            case RouteDecision.HumanInbox human ->
                    log.info("Conversa {} está com humano; mensagem só na transcrição", human.conversation().id());
            case RouteDecision.Buffered buffered ->
                    log.info("Conversa {} sem missão ativa; mensagem em buffer", buffered.conversation().id());
            case RouteDecision.Ignored ignored -> {
                log.info("Mensagem ignorada na conversa {}: {}", ignored.conversation().id(), ignored.reason());
                if (ignored.conversation().status() == ConversationStatus.CLOSED) {
                    worker.onOptOut(ignored.conversation());
                }
            }
        }
    }
}
