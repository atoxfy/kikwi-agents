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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kikwiflow.agents.llm.LlmContent;
import io.kikwiflow.agents.llm.LlmMessage;
import io.kikwiflow.agents.llm.LlmRequest;
import io.kikwiflow.agents.llm.LlmRouter;
import io.kikwiflow.agents.llm.ToolChoice;
import io.kikwiflow.agents.llm.fake.ScriptedLlmClient;
import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.model.agent.AgentLimits;
import io.kikwiflow.agents.model.agent.AgentModelSettings;
import io.kikwiflow.agents.model.agent.AgentParameter;
import io.kikwiflow.agents.model.agent.AgentPerception;
import io.kikwiflow.agents.model.agent.AgentStatus;
import io.kikwiflow.agents.model.agent.AgentToolRef;
import io.kikwiflow.agents.model.agent.AgentToolType;
import io.kikwiflow.agents.model.channel.ChannelRef;
import io.kikwiflow.agents.model.channel.ChannelType;
import io.kikwiflow.agents.model.conversation.Attachment;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.ConversationStatus;
import io.kikwiflow.agents.model.conversation.Message;
import io.kikwiflow.agents.model.conversation.MessageAuthor;
import io.kikwiflow.agents.model.conversation.MessagePart;
import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.model.mission.MissionLimits;
import io.kikwiflow.agents.model.mission.OutputField;
import io.kikwiflow.agents.model.tool.ToolBinding;
import io.kikwiflow.agents.model.tool.ToolDefinition;
import io.kikwiflow.agents.runtime.audit.AgentActivity;
import io.kikwiflow.agents.runtime.audit.AgentActivityType;
import io.kikwiflow.agents.runtime.tool.AgentToolResolver;
import io.kikwiflow.agents.runtime.tool.InlineBindingExecutor;
import io.kikwiflow.agents.runtime.tool.ToolRegistry;
import io.kikwiflow.agents.runtime.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentTurnProcessor")
class AgentTurnProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private final List<OutputField> contract = List.of(
            new OutputField("resultado", "Resultado", null, "SINGLE_SELECT", true,
                    List.of("AGENDADO", "PERDIDO", "HUMANO"), Map.of()),
            new OutputField("dataAgendamento", "Data da visita", null, "DATETIME", false, null, Map.of()),
            new OutputField("fotosVeiculo", "Fotos", null, "FILE", false, null, Map.of("kikwi:file.multiple", "true")));

    private final Mission mission = new Mission("task-1", "pi-1", "loja-exemplo", "conv-1", "pre-vendas", null,
            List.of("consultar_fipe", "agendar_visita"),
            "Qualifique o veículo de {{contatoNome}} e agende uma visita.", contract, MissionLimits.none(),
            Map.of("contatoNome", "Maria"), NOW.plusSeconds(300), NOW, null);

    private final AgentDefinition profile = new AgentDefinition("pre-vendas", 3, AgentStatus.PUBLISHED,
            "loja-exemplo", "Pré-vendas", null,
            "Você é a {{nome}}, consultora da Loja Exemplo. Seja breve.\nChame escalar_humano imediatamente se: pedido de humano.",
            List.of(new AgentParameter("nome", "Nome", null, "Ana", null)), Map.of(),
            new AgentModelSettings("fake:roteiro", null, null), null,
            List.of(new AgentToolRef("consultar_fipe", AgentToolType.INLINE, null, null),
                    new AgentToolRef("consultar_horarios", AgentToolType.INLINE, null, null)),
            List.of(), new AgentLimits(40));

    private final Conversation conversation = new Conversation("conv-1", "loja-exemplo",
            new ChannelRef(ChannelType.WEBCHAT, "site-loja"), "contact-1",
            ConversationStatus.OPEN, "pi-1", "task-1", NOW, NOW);

    private ScriptedLlmClient llm;
    private List<AgentActivity> audit;
    private AgentTurnProcessor processor;

    @BeforeEach
    void setUp() {
        llm = new ScriptedLlmClient();
        audit = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry()
                .register(new ToolDefinition("consultar_fipe", "Valor FIPE pela placa",
                        Map.of("type", "object"), new ToolBinding.Inline("fipe"), false))
                .register(new ToolDefinition("agendar_visita", "Agenda visita",
                        Map.of("type", "object"), new ToolBinding.Inline("agenda"), true))
                .registerExecutor(new InlineBindingExecutor()
                        .register("fipe", (ctx, input) -> ToolResult.ok("{\"valorFipe\":45000}"))
                        .register("agenda", (ctx, input) -> ToolResult.ok("agendado")));
        processor = new AgentTurnProcessor(new LlmRouter(List.of(llm)), new AgentToolResolver(registry, null), registry,
                (m, a) -> audit.add(a), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), 4);
    }

    private static Message fromLead(String text) {
        return new Message("m", "conv-1", Message.Direction.INBOUND,
                new MessageAuthor(MessageAuthor.Type.CONTACT, "contact-1"), List.of(new MessagePart.Text(text)), NOW,
                null);
    }

    @Test
    @DisplayName("Dado o modelo devolve um turno vazio, quando é conversa, então insiste uma vez e entrega a resposta")
    void insisteNoTurnoVazio() {
        llm.thenReply("").thenReply("Pode ser sábado às 11h, sim!");

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("E sábado?")));

        assertThat(outcome).isInstanceOfSatisfying(TurnOutcome.Reply.class,
                r -> assertThat(r.text()).isEqualTo("Pode ser sábado às 11h, sim!"));
        assertThat(llm.received()).hasSize(2);
        assertThat(llm.received().get(1).messages().getLast().content().getFirst())
                .isEqualTo(new LlmContent.Text(AgentTurnProcessor.EMPTY_REPLY_NUDGE));
    }

    @Test
    @DisplayName("Dado o modelo insiste no vazio, quando já pediu de novo uma vez, então devolve Reply vazio sem loop")
    void naoInsisteParaSempre() {
        llm.thenReply("").thenReply("");

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("E sábado?")));

        assertThat(outcome).isInstanceOfSatisfying(TurnOutcome.Reply.class, r -> assertThat(r.text()).isBlank());
        assertThat(llm.received()).hasSize(2);
    }

    @Test
    @DisplayName("Dado qualquer turno, quando monta o pedido, então o modelo sabe a data, a hora e o dia da semana")
    void sabeQueDiaEHoje() {
        llm.thenReply("Oi!");

        processor.processTurn(mission, profile, conversation, List.of(fromLead("Oi")));

        assertThat(llm.received().get(0).system()).contains("Data e hora agora: domingo, 25/10/2026, 12:00 (Z).");
    }

    @Test
    @DisplayName("Dado o lead pergunta algo, quando o modelo só responde, então devolve Reply com o texto")
    void respondeTexto() {
        llm.thenReply("Oi Maria! Qual o modelo e ano do carro?");

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("Oi")));

        assertThat(outcome).isInstanceOfSatisfying(TurnOutcome.Reply.class,
                r -> assertThat(r.text()).contains("modelo e ano"));
        assertThat(llm.received().get(0).system())
                .contains("Você é a Ana")
                .contains("agende uma visita")
                .contains("escalar_humano imediatamente se: pedido de humano")
                // A variável do processo não entra na instrução: vira referência e vai para o bloco de dados.
                .contains("Qualifique o veículo de «contatoNome»")
                .contains("## Dados do atendimento")
                .contains("- contatoNome: Maria");
    }

    @Test
    @DisplayName("Dado o nó não restringe ferramentas, quando o turno roda, então o modelo recebe todas as do agente")
    void semRestricaoNoNoUsaTodasAsDoAgente() {
        Mission semRestricao = new Mission("task-1", "pi-1", "loja-exemplo", "conv-1", "pre-vendas", null, List.of(),
                "Atenda.", contract, MissionLimits.none(), Map.of(), NOW.plusSeconds(300), NOW, null);
        llm.thenReply("Oi!");

        processor.processTurn(semRestricao, profile, conversation, List.of(fromLead("Oi")));

        assertThat(llm.received().get(0).tools()).extracting(t -> t.name())
                .contains("consultar_fipe")
                .doesNotContain("consultar_horarios"); // do agente, mas sem definição no registro: fica de fora
    }

    @Test
    @DisplayName("Dado ferramenta permitida, quando o modelo a chama, então executa, audita e devolve o resultado ao modelo")
    void executaFerramentaPermitida() {
        llm.thenCallTool("consultar_fipe", Map.of("placa", "ABC1D23"))
                .thenReply("Pela FIPE, cerca de R$ 45 mil.");

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("ABC1D23")));

        assertThat(outcome).isInstanceOf(TurnOutcome.Reply.class);
        assertThat(audit).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(AgentActivityType.AGENT_TOOL_INVOKED);
            assertThat(a.data()).containsEntry("toolId", "consultar_fipe")
                    .containsEntry("outcome", "OK")
                    .containsEntry("binding", "INLINE")
                    .containsKey("inputHash");
        });
        LlmMessage toolResults = llm.received().get(1).messages().get(2);
        assertThat(toolResults.content()).singleElement()
                .isInstanceOfSatisfying(LlmContent.ToolResult.class, r -> assertThat(r.content()).contains("45000"));
    }

    @Test
    @DisplayName("Dado ferramenta fora da interseção missão x perfil, quando o modelo a chama, então nega e audita DENIED")
    void negaFerramentaForaDaMissao() {
        // agendar_visita está na missão mas não no perfil; consultar_horarios está no perfil mas não na missão
        llm.thenCallTool("agendar_visita", Map.of("data", "2026-10-27T10:00:00-03:00"))
                .thenReply("Vou verificar com a equipe.");

        processor.processTurn(mission, profile, conversation, List.of(fromLead("Pode agendar?")));

        assertThat(audit).singleElement()
                .satisfies(a -> assertThat(a.data()).containsEntry("outcome", "DENIED"));
        assertThat(llm.received().get(0).tools()).extracting(t -> t.name())
                .containsExactly("consultar_fipe", "concluir_missao", "escalar_humano");
    }

    @Test
    @DisplayName("Dado conclusão sem campo obrigatório, quando o modelo tenta concluir, então devolve erro e só conclui na segunda tentativa")
    void concluiSoComObrigatorios() {
        llm.thenCallTool("concluir_missao", Map.of("dataAgendamento", "2026-10-27T10:00:00-03:00"))
                .thenCallTool("concluir_missao", Map.of("resultado", "AGENDADO",
                        "dataAgendamento", "2026-10-27T10:00:00-03:00"));

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("Fechado!")));

        assertThat(outcome).isInstanceOfSatisfying(TurnOutcome.Completed.class,
                c -> assertThat(c.outputs()).containsEntry("resultado", "AGENDADO"));
        LlmContent firstResult = llm.received().get(1).messages().get(2).content().get(0);
        assertThat(firstResult).isInstanceOfSatisfying(LlmContent.ToolResult.class, r -> {
            assertThat(r.error()).isTrue();
            assertThat(r.content()).contains("resultado");
        });
    }

    @Test
    @DisplayName("Dado o modelo escreve ao lead junto da ferramenta e conclui depois sem texto, então a despedida é o que escreveu")
    void despedidaEscritaJuntoDaFerramenta() {
        llm.thenSayAndCallTool("Combinado! Te espero terça às 10h.", "consultar_fipe", Map.of("placa", "ABC1D23"))
                .thenCallTool("concluir_missao", Map.of("resultado", "AGENDADO",
                        "dataAgendamento", "2026-10-27T10:00:00-03:00"));

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("Pode ser")));

        assertThat(outcome).isInstanceOfSatisfying(TurnOutcome.Completed.class,
                c -> assertThat(c.farewell()).isEqualTo("Combinado! Te espero terça às 10h."));
    }

    @Test
    @DisplayName("Dado o modelo conclui sem escrever nada ao lead, então pede a última mensagem sem ferramentas")
    void pedeDespedidaQuandoConcluiCalado() {
        llm.thenCallTool("consultar_fipe", Map.of("placa", "ABC1D23"))
                .thenCallTool("concluir_missao", Map.of("resultado", "AGENDADO",
                        "dataAgendamento", "2026-10-27T10:00:00-03:00"))
                .thenReply("Fechado! Te espero terça às 10h na loja.");

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("Pode ser")));

        assertThat(outcome).isInstanceOfSatisfying(TurnOutcome.Completed.class, c -> {
            assertThat(llm.received()).hasSize(2); // a despedida só é pedida depois do aceite do motor
            assertThat(c.outputs()).containsEntry("resultado", "AGENDADO");
            assertThat(c.resolveFarewell()).isEqualTo("Fechado! Te espero terça às 10h na loja.");
        });
        LlmRequest closing = llm.received().get(2);
        assertThat(closing.toolChoice().mode()).isEqualTo(ToolChoice.Mode.NONE);
        assertThat(closing.messages().getLast().content()).last()
                .isInstanceOfSatisfying(LlmContent.ToolResult.class,
                        r -> assertThat(r.content()).isEqualTo(AgentTurnProcessor.CLOSING_REQUEST));
    }

    @Test
    @DisplayName("Dado o pedido da despedida falha, então a missão conclui assim mesmo, sem mensagem")
    void concluiMesmoSemDespedida() {
        llm.thenCallTool("concluir_missao", Map.of("resultado", "AGENDADO",
                "dataAgendamento", "2026-10-27T10:00:00-03:00"));  // roteiro acaba: a chamada extra falha

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("Pode ser")));

        assertThat(outcome).isInstanceOfSatisfying(TurnOutcome.Completed.class, c -> assertThat(c.resolveFarewell()).isEmpty());
    }

    @Test
    @DisplayName("Dado o modelo escreve antes e depois de uma ferramenta, quando responde, então o lead recebe os dois textos")
    void respostaJuntaTextosDoTurno() {
        llm.thenSayAndCallTool("Deixa eu consultar.", "consultar_fipe", Map.of("placa", "ABC1D23"))
                .thenReply("Pela FIPE, cerca de R$ 45 mil.");

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("ABC1D23")));

        assertThat(outcome).isInstanceOfSatisfying(TurnOutcome.Reply.class,
                r -> assertThat(r.text()).isEqualTo("Deixa eu consultar.\n\nPela FIPE, cerca de R$ 45 mil."));
    }

    @Test
    @DisplayName("Dado o lead pede um humano, quando o modelo escala, então devolve HandedOff e audita o handoff")
    void escala() {
        llm.thenCallTool("escalar_humano", Map.of("motivo", "lead pediu atendente"));

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation,
                List.of(fromLead("Quero falar com uma pessoa")));

        assertThat(outcome).isInstanceOfSatisfying(TurnOutcome.HandedOff.class,
                h -> assertThat(h.reason()).isEqualTo("lead pediu atendente"));
        assertThat(audit).singleElement().extracting(AgentActivity::type).isEqualTo(AgentActivityType.AGENT_HANDOFF);
    }

    @Test
    @DisplayName("Dado o modelo em loop de ferramentas, quando atinge o limite, então devolve Exhausted")
    void limiteDeIteracoes() {
        for (int i = 0; i < 4; i++) {
            llm.thenCallTool("consultar_fipe", Map.of("placa", "ABC1D23"));
        }

        TurnOutcome outcome = processor.processTurn(mission, profile, conversation, List.of(fromLead("?")));

        assertThat(outcome).isInstanceOf(TurnOutcome.Exhausted.class);
        assertThat(outcome.usage().inputTokens()).isEqualTo(40);
    }

    @Test
    @DisplayName("Dado transcrição com anexo e mensagens seguidas do lead, quando converte, então une o papel e mostra a referência dms://")
    void converteTranscricao() {
        Message foto = new Message("m2", "conv-1", Message.Direction.INBOUND,
                new MessageAuthor(MessageAuthor.Type.CONTACT, "contact-1"),
                List.of(new MessagePart.File(new Attachment("dms://doc-9/1.0", "frente.jpg", "image/jpeg", 1000))),
                NOW, null);
        Message resposta = new Message("m3", "conv-1", Message.Direction.OUTBOUND,
                new MessageAuthor(MessageAuthor.Type.AGENT, "pre-vendas@3"),
                List.of(new MessagePart.Text("Recebi!")), NOW, null);

        List<LlmMessage> messages = AgentTurnProcessor.toLlmMessages(List.of(fromLead("Segue a foto"), foto, resposta));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).hasSize(2);
        assertThat(messages.get(0).content().get(1))
                .isInstanceOfSatisfying(LlmContent.Text.class, t -> assertThat(t.text()).contains("dms://doc-9/1.0"));
        assertThat(messages.get(1).role()).isEqualTo(LlmMessage.Role.ASSISTANT);
    }

    @Test
    @DisplayName("Dado o agente entende imagens, quando o lead manda uma foto, então o modelo recebe a imagem junto da referência")
    void entregaImagemAoModelo() {
        AgentDefinition comVisao = new AgentDefinition(profile.key(), profile.version(), profile.status(),
                profile.tenantId(), profile.name(), null, profile.instructions(), profile.parameters(), Map.of(),
                profile.model(), new AgentPerception(true, false, false), profile.tools(), List.of(), profile.limits());
        Attachment jpg = new Attachment("gridfs://foto-1", "frente.jpg", "image/jpeg", 4);
        Attachment pdf = new Attachment("gridfs://crlv-1", "crlv.pdf", "application/pdf", 4);
        Message foto = new Message("m2", "conv-1", Message.Direction.INBOUND,
                new MessageAuthor(MessageAuthor.Type.CONTACT, "contact-1"),
                List.of(new MessagePart.File(jpg), new MessagePart.File(pdf)), NOW, null);
        List<String> lidos = new ArrayList<>();
        AgentTurnProcessor comLeitor = new AgentTurnProcessor(LlmClientResolver.of(new LlmRouter(List.of(llm))),
                new AgentToolResolver(new ToolRegistry(), null), new ToolRegistry(), (m, a) -> audit.add(a),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), 4, (tenant, attachment) -> {
                    lidos.add(tenant + "|" + attachment.uri());
                    return Optional.of(new byte[]{1, 2, 3, 4});
                });
        llm.thenReply("Que carro bonito!");

        comLeitor.processTurn(mission, comVisao, conversation, List.of(fromLead("Segue"), foto));

        List<LlmContent> content = llm.received().get(0).messages().get(0).content();
        assertThat(content).filteredOn(LlmContent.Media.class::isInstance).singleElement()
                .isInstanceOfSatisfying(LlmContent.Media.class, m -> {
                    assertThat(m.mimeType()).isEqualTo("image/jpeg");
                    assertThat(m.base64Data()).isEqualTo("AQIDBA==");
                });
        // Documentos desligados: o PDF fica só como referência, nem é lido.
        assertThat(lidos).containsExactly("loja-exemplo|gridfs://foto-1");
        assertThat(content.toString()).contains("gridfs://crlv-1");
    }

    @Test
    @DisplayName("Dado início de missão, quando gera a atividade, então registra versão do agente, modelo e hash do prompt")
    void atividadeDeInicio() {
        AgentActivity started = processor.missionStarted(mission, profile);

        assertThat(started.type()).isEqualTo(AgentActivityType.AGENT_MISSION_STARTED);
        assertThat(started.data())
                .containsEntry("agentVersion", 3)
                .containsEntry("model", "fake:roteiro")
                .containsEntry("conversationRef", "conv-1");
        assertThat((String) started.data().get("promptHash")).hasSize(12);
    }
}
