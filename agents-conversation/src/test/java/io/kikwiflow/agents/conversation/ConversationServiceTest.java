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
package io.kikwiflow.agents.conversation;

import io.kikwiflow.agents.channel.InboundMessage;
import io.kikwiflow.agents.conversation.memory.InMemoryConversationStore;
import io.kikwiflow.agents.model.channel.ChannelContext;
import io.kikwiflow.agents.model.channel.ChannelEntry;
import io.kikwiflow.agents.model.channel.ChannelRef;
import io.kikwiflow.agents.model.channel.ChannelType;
import io.kikwiflow.agents.model.channel.ConsentPolicy;
import io.kikwiflow.agents.model.conversation.Attachment;
import io.kikwiflow.agents.model.conversation.Conversation;
import io.kikwiflow.agents.model.conversation.ConversationStatus;
import io.kikwiflow.agents.model.conversation.Message;
import io.kikwiflow.agents.model.conversation.MessageAuthor;
import io.kikwiflow.agents.model.conversation.MessagePart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConversationService")
class ConversationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final String TENANT = "loja-exemplo";
    private static final String LEAD = "lead-1";

    private static final ConsentPolicy CONSENT = new ConsentPolicy(false, List.of("PARAR", "SAIR"), null);
    private static final ChannelContext SITE = new ChannelContext(new ChannelRef(ChannelType.WEBCHAT, "loja-site"),
            "site", new ChannelEntry("pre-vendas", Map.of("origem", "site")), CONSENT);
    private static final ChannelContext WHATSAPP = new ChannelContext(
            new ChannelRef(ChannelType.WHATSAPP, "loja-whatsapp"), "WhatsApp",
            new ChannelEntry("pre-vendas", Map.of("origem", "whatsapp")), CONSENT);

    private InMemoryConversationStore store;
    private List<RouteDecision> routed;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryConversationStore();
        routed = new ArrayList<>();
        service = new ConversationService(store, routed::add, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InboundMessage inbound(ChannelContext channel, String text, String channelMessageId, Instant at) {
        return new InboundMessage(TENANT, LEAD, "Maria", channel, List.of(new MessagePart.Text(text)), at,
                channelMessageId);
    }

    private static InboundMessage inbound(String text, String channelMessageId) {
        return inbound(SITE, text, channelMessageId, NOW);
    }

    private RouteDecision last() {
        return routed.get(routed.size() - 1);
    }

    @Nested
    @DisplayName("Dado um lead sem conversa")
    class LeadNovo {

        @Test
        @DisplayName("Quando chega a primeira mensagem, então inicia o processo de entrada do canal com businessKey = conversa")
        void iniciaProcesso() {
            service.onInbound(inbound("Quero vender meu carro", "m1"));

            assertThat(last()).isInstanceOfSatisfying(RouteDecision.StartProcess.class, start -> {
                assertThat(start.processKey()).isEqualTo("pre-vendas");
                assertThat(start.variables())
                        .containsEntry("origem", "site")
                        .containsEntry("canal", "WEBCHAT")
                        .containsEntry("contatoNome", "Maria")
                        .containsEntry("conversationId", start.conversation().id());
                assertThat(start.conversation().channel()).isEqualTo(SITE.ref());
                assertThat(start.conversation().contactId()).isEqualTo(LEAD);
            });
            assertThat(store.findLast(last().conversation().id(), 10)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Dado uma conversa com missão ativa")
    class ComMissao {

        private Conversation conversation;

        @BeforeEach
        void comMissao() {
            service.onInbound(inbound("Oi", "m1"));
            conversation = store.save(last().conversation().withMission("pi-1", "task-agent-1"));
        }

        @Test
        @DisplayName("Quando o lead responde pelo mesmo canal, então entrega à missão")
        void entregaAMissao() {
            service.onInbound(inbound("É um Onix 2019", "m2"));

            assertThat(last()).isInstanceOfSatisfying(RouteDecision.DeliverToMission.class,
                    d -> assertThat(d.missionTaskId()).isEqualTo("task-agent-1"));
        }

        @Test
        @DisplayName("Quando o webhook reentrega a mesma mensagem, então ignora sem gravar de novo")
        void ignoraDuplicada() {
            service.onInbound(inbound("Oi", "m1"));

            assertThat(last()).isInstanceOf(RouteDecision.Ignored.class);
            assertThat(store.findLast(conversation.id(), 10)).hasSize(1);
        }

        @Test
        @DisplayName("Quando o lead pede para parar, então encerra a conversa e marca o opt-out antes de qualquer agente")
        void optOut() {
            service.onInbound(inbound(" parar ", "m3"));

            assertThat(last()).isInstanceOfSatisfying(RouteDecision.Ignored.class,
                    i -> assertThat(i.reason()).isEqualTo("opt-out"));
            assertThat(store.findById(conversation.id())).get()
                    .extracting(Conversation::status).isEqualTo(ConversationStatus.CLOSED);
            assertThat(store.findContact(LEAD)).get().extracting(c -> c.optedOutAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Quando um humano já assumiu, então vai para a fila humana e não para o agente")
        void humanoAssumiu() {
            store.save(conversation.withStatus(ConversationStatus.HANDED_OFF));

            service.onInbound(inbound("Alô?", "m4"));

            assertThat(last()).isInstanceOf(RouteDecision.HumanInbox.class);
        }
    }

    @Nested
    @DisplayName("Dado o lead com conversa aberta no site escrevendo pelo WhatsApp")
    class TrocaDeCanal {

        private Conversation noSite;

        @BeforeEach
        void conversaNoSite() {
            service.onInbound(inbound("Quero vender meu Gol", "m1"));
            noSite = store.save(last().conversation().withMission("pi-1", "task-agent-1"));
            service.onInbound(inbound(WHATSAPP, "Oi, ainda tem interesse no meu carro?", "w1", NOW.plusSeconds(60)));
        }

        @Test
        @DisplayName("Quando a mensagem chega, então a plataforma pergunta pelo WhatsApp se continua ou é outro assunto")
        void pergunta() {
            assertThat(last()).isInstanceOfSatisfying(RouteDecision.AskChannelSwitch.class, ask -> {
                assertThat(ask.channel()).isEqualTo(WHATSAPP.ref());
                assertThat(ask.conversation().id()).isEqualTo(noSite.id());
                assertThat(ask.question().quickReplies()).containsExactly("Continuar", "Outro assunto");
                assertThat(((MessagePart.Text) ask.question().parts().get(0)).text()).contains("pelo site");
            });
            // Nada foi entregue ao agente ainda: a mensagem espera a escolha.
            assertThat(store.findLast(noSite.id(), 10)).hasSize(1);
        }

        @Test
        @DisplayName("Quando escolhe continuar, então a conversa passa para o WhatsApp, registra a troca e entrega a mensagem guardada")
        void continua() {
            service.onInbound(inbound(WHATSAPP, "Continuar!", "w2", NOW.plusSeconds(90)));

            assertThat(last()).isInstanceOfSatisfying(RouteDecision.DeliverToMission.class, d -> {
                assertThat(d.conversation().id()).isEqualTo(noSite.id());
                assertThat(d.message().text()).isEqualTo("Oi, ainda tem interesse no meu carro?");
            });
            assertThat(store.findById(noSite.id()).orElseThrow().channel()).isEqualTo(WHATSAPP.ref());
            List<Message> transcript = store.findLast(noSite.id(), 10);
            assertThat(transcript).extracting(m -> m.author().type()).containsExactly(MessageAuthor.Type.CONTACT,
                    MessageAuthor.Type.SYSTEM, MessageAuthor.Type.CONTACT);
            assertThat(transcript.get(1).text()).contains("WhatsApp").contains("site");

            // Daqui em diante o WhatsApp é o canal da conversa: segue direto, sem perguntar de novo.
            service.onInbound(inbound(WHATSAPP, "É um 2018", "w3", NOW.plusSeconds(120)));
            assertThat(last()).isInstanceOf(RouteDecision.DeliverToMission.class);
        }

        @Test
        @DisplayName("Quando escolhe outro assunto, então abre conversa nova no WhatsApp e a do site continua aberta")
        void outroAssunto() {
            service.onInbound(inbound(WHATSAPP, "outro assunto", "w2", NOW.plusSeconds(90)));

            assertThat(last()).isInstanceOfSatisfying(RouteDecision.StartProcess.class, start -> {
                assertThat(start.conversation().id()).isNotEqualTo(noSite.id());
                assertThat(start.conversation().channel()).isEqualTo(WHATSAPP.ref());
                assertThat(start.message().text()).isEqualTo("Oi, ainda tem interesse no meu carro?");
                assertThat(start.variables()).containsEntry("origem", "whatsapp");
            });
            assertThat(store.findById(noSite.id()).orElseThrow().channel()).isEqualTo(SITE.ref());
            assertThat(store.findLast(noSite.id(), 10)).last().extracting(Message::text).asString()
                    .contains("outro assunto");
        }

        @Test
        @DisplayName("Quando a resposta não é clara, então pergunta de novo; na segunda vez segue como outro assunto")
        void respostaConfusa() {
            service.onInbound(inbound(WHATSAPP, "hã?", "w2", NOW.plusSeconds(90)));
            assertThat(last()).isInstanceOf(RouteDecision.AskChannelSwitch.class);

            service.onInbound(inbound(WHATSAPP, "não sei", "w3", NOW.plusSeconds(120)));
            assertThat(last()).isInstanceOf(RouteDecision.StartProcess.class);
        }
    }

    @Test
    @DisplayName("Dado conversa parada há mais de 7 dias em outro canal, quando o lead escreve, então abre uma nova sem perguntar")
    void conversaAntigaNaoPergunta() {
        service.onInbound(inbound("Oi", "m1"));

        service.onInbound(inbound(WHATSAPP, "Oi de novo", "w1", NOW.plus(Duration.ofDays(8))));

        assertThat(last()).isInstanceOf(RouteDecision.StartProcess.class);
    }

    @Test
    @DisplayName("Dado o roteador liga dois leads, quando consulta o lead, então a conversa do antigo é dele")
    void ligaLeads() {
        service.onInbound(new InboundMessage(TENANT, "anonimo-1", null, SITE, List.of(new MessagePart.Text("Oi")), NOW, "m1"));
        String conversa = last().conversation().id();

        service.linkLeads(TENANT, "anonimo-1", LEAD);

        assertThat(store.findOpenByLead(TENANT, LEAD)).extracting(Conversation::id).containsExactly(conversa);
        assertThat(store.findOpenByLead(TENANT, "anonimo-1")).isEmpty();
    }

    @Test
    @DisplayName("Dado um pré-processador de áudio, quando chega um áudio, então a conversa guarda o áudio e a transcrição")
    void transcreveAudioNaEntrada() {
        ConversationService comTranscricao = new ConversationService(store, routed::add, Clock.fixed(NOW, ZoneOffset.UTC),
                (tenant, parts) -> {
                    List<MessagePart> withText = new ArrayList<>(parts);
                    withText.add(new MessagePart.Text("[áudio transcrito] Quero vender meu Onix"));
                    return withText;
                });
        Attachment audio = new Attachment("gridfs://audio-1", "audio.ogg", "audio/ogg", 2048);

        comTranscricao.onInbound(new InboundMessage(TENANT, LEAD, "Maria", SITE, List.of(new MessagePart.File(audio)),
                NOW, "a1"));

        assertThat(last()).isInstanceOfSatisfying(RouteDecision.StartProcess.class, start -> assertThat(start.message().parts())
                .containsExactly(new MessagePart.File(audio), new MessagePart.Text("[áudio transcrito] Quero vender meu Onix")));
    }

    @Test
    @DisplayName("Dado processo em passo sem agente, quando o lead escreve, então a mensagem fica em buffer na transcrição")
    void buffer() {
        service.onInbound(inbound("Oi", "m1"));
        store.save(last().conversation().withMission("pi-1", null));

        service.onInbound(inbound("Alguma novidade?", "m2"));

        assertThat(last()).isInstanceOf(RouteDecision.Buffered.class);
    }
}
