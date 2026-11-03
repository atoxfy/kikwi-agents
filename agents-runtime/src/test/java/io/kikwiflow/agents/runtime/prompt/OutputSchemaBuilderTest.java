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
package io.kikwiflow.agents.runtime.prompt;

import io.kikwiflow.agents.model.mission.OutputField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutputSchemaBuilderTest {

    @Test
    @DisplayName("Dado contrato com select, dinheiro e arquivos, quando gera o schema, então mapeia tipo, enum, required e array de arquivos")
    @SuppressWarnings("unchecked")
    void mapeiaFormatos() {
        Map<String, Object> schema = OutputSchemaBuilder.build(List.of(
                new OutputField("resultado", "Resultado", "Como a missão terminou", "SINGLE_SELECT", true,
                        List.of("AGENDADO", "PERDIDO"), Map.of()),
                new OutputField("expectativaPreco", "Expectativa", null, "MONEY", false, null, Map.of()),
                new OutputField("fotosVeiculo", "Fotos", null, "FILE", true, null, Map.of("kikwi:file.multiple", "true")),
                new OutputField("crlv", "CRLV", null, "FILE", false, null, Map.of())));

        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(schema.get("required")).isEqualTo(List.of("resultado", "fotosVeiculo"));
        assertThat((Map<String, Object>) props.get("resultado"))
                .containsEntry("type", "string")
                .containsEntry("enum", List.of("AGENDADO", "PERDIDO"))
                .containsEntry("description", "Resultado. Como a missão terminou");
        assertThat((Map<String, Object>) props.get("expectativaPreco")).containsEntry("type", "number");
        assertThat((Map<String, Object>) props.get("fotosVeiculo")).containsEntry("type", "array");
        assertThat((Map<String, Object>) props.get("crlv")).containsEntry("type", "object");
    }

    @Test
    @DisplayName("Dado placeholders, quando renderiza, então substitui valores e marca ausentes sem interpretar expressões")
    void renderizaPrompt() {
        String rendered = PromptRenderer.render("Lead {{ contatoNome }} quer vender {{veiculo}}; {{a + b}}",
                Map.of("contatoNome", "Maria"));

        assertThat(rendered).isEqualTo("Lead Maria quer vender (não informado); {{a + b}}");
    }
}
