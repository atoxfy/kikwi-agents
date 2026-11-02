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
package io.kikwiflow.agents.runtime.agent;

import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.model.agent.AgentLimits;
import io.kikwiflow.agents.model.agent.AgentModelSettings;
import io.kikwiflow.agents.model.agent.AgentParameter;
import io.kikwiflow.agents.model.agent.AgentStatus;
import io.kikwiflow.agents.model.agent.AgentToolRef;
import io.kikwiflow.agents.model.agent.AgentToolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Dado o catálogo de agentes de um tenant")
class AgentCatalogTest {

    private final AgentCatalog catalog = new AgentCatalog(new InMemoryAgentDefinitionStore());

    private static AgentDefinition agent(String instructions, List<AgentToolRef> tools) {
        return new AgentDefinition("pre-vendas", 0, AgentStatus.DRAFT, null, "Pré-vendas", null, instructions,
                List.of(new AgentParameter("nomeAgente", "Nome", null, "Ana", null),
                        new AgentParameter("loja", "Loja", null, null, null)),
                Map.of(), new AgentModelSettings("anthropic:claude-sonnet-5-5", null, null), null, tools, List.of(),
                new AgentLimits(80));
    }

    @Test
    @DisplayName("Quando publica duas vezes, então as versões crescem e a missão sem versão pega a mais nova")
    void publishesImmutableVersions() {
        catalog.saveDraft("t1", agent("Você é {{nomeAgente}}, da {{loja}}.", List.of()));
        catalog.publish("t1", "pre-vendas");
        catalog.saveDraft("t1", agent("Você é {{nomeAgente}}, da {{loja}}. Seja breve.", List.of()));
        AgentDefinition v2 = catalog.publish("t1", "pre-vendas");

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo(AgentStatus.PUBLISHED);
        assertThat(catalog.find("t1", "pre-vendas", null)).contains(v2);
        assertThat(catalog.find("t1", "pre-vendas", 1).orElseThrow().instructions()).doesNotContain("breve");
        assertThat(catalog.find("t2", "pre-vendas", null)).isEmpty();
    }

    @Test
    @DisplayName("Quando o rascunho tem problemas, então não publica e diz o que corrigir")
    void rejectsInvalidDraft() {
        catalog.saveDraft("t1", agent("Oi {{cidade}}", List.of(
                new AgentToolRef("agendar", AgentToolType.PROCESS, null, null),
                new AgentToolRef("agendar", AgentToolType.INLINE, null, null))));

        assertThatThrownBy(() -> catalog.publish("t1", "pre-vendas"))
                .isInstanceOfSatisfying(InvalidAgentDefinitionException.class, e -> assertThat(e.problems())
                        .anyMatch(p -> p.contains("{{cidade}}"))
                        .anyMatch(p -> p.contains("'agendar' repetido"))
                        .anyMatch(p -> p.contains("sem processKey")));
        assertThat(catalog.find("t1", "pre-vendas", null)).isEmpty();
    }

    @Test
    @DisplayName("Quando personaliza, então publica nova versão só com outros valores de parâmetro")
    void personalizesWithParameters() {
        catalog.saveDraft("t1", agent("Você é {{nomeAgente}}, da {{loja}}.", List.of()));
        catalog.publish("t1", "pre-vendas");

        AgentDefinition v2 = catalog.publishWithParameters("t1", "pre-vendas", Map.of("nomeAgente", "Bruno", "loja", "Loja Centro"));

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.renderedInstructions()).isEqualTo("Você é Bruno, da Loja Centro.");
        assertThatThrownBy(() -> catalog.publishWithParameters("t1", "pre-vendas", Map.of("cidade", "POA")))
                .isInstanceOf(InvalidAgentDefinitionException.class);
    }

    @Test
    @DisplayName("Dado um agente publicado, quando personaliza trocando o modelo, então a nova versão usa o outro provedor")
    void personalizesModel() {
        catalog.saveDraft("t1", agent("Você é {{nomeAgente}}, da {{loja}}.", List.of()));
        catalog.publish("t1", "pre-vendas");

        AgentDefinition v2 = catalog.publishPersonalized("t1", "pre-vendas", Map.of(), "gemini:gemini-2.5-flash");

        assertThat(v2.model().id()).isEqualTo("gemini:gemini-2.5-flash");
        assertThat(catalog.publishPersonalized("t1", "pre-vendas", Map.of("loja", "Centro"), null).model().id())
                .isEqualTo("gemini:gemini-2.5-flash");
    }
}
