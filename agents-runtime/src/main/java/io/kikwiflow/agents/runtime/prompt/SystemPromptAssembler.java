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

import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.model.mission.Mission;
import io.kikwiflow.agents.runtime.tool.BuiltinTools;

/**
 * Monta as instruções de sistema em camadas fixas, sempre na mesma ordem:
 * <ol>
 *   <li>instruções do agente (com os parâmetros da versão preenchidos);</li>
 *   <li>objetivo da missão (o {@code kikwi:prompt} do nó) — os {@code {{variavel}}} viram referências;</li>
 *   <li>dados do atendimento: os valores dessas variáveis, num bloco à parte, como dados;</li>
 *   <li>regras de encerramento.</li>
 * </ol>
 * Variável do processo nunca é colada no meio de uma instrução: fica no bloco de dados. Ainda não é defesa contra
 * injeção, mas é a separação que permite endurecer depois sem mudar processos nem agentes. O hash do resultado vai
 * para a auditoria ({@code promptHash}).
 */
public final class SystemPromptAssembler {

    private SystemPromptAssembler() {
    }

    public static String assemble(AgentDefinition agent, Mission mission) {
        StringBuilder sb = new StringBuilder();
        section(sb, "Instruções", agent.renderedInstructions());
        section(sb, "Sua missão agora", PromptRenderer.withReferences(mission.objective()));
        section(sb, "Dados do atendimento", PromptRenderer.dataBlock(mission.objective(), mission.variables()));
        section(sb, "Encerramento", """
                Converse até cumprir a missão ou concluir que ela não pode ser cumprida.
                Quando tiver todas as informações, chame %s com os campos pedidos, sem inventar valores: \
                se o lead não informou algo obrigatório, pergunte.
                Se o caso pedir uma pessoa (o lead pediu, irritação, assunto fora do seu alcance), chame %s.
                Para anexos (fotos, documentos), use exatamente a referência mostrada na conversa (ex.: gridfs://...).
                Nunca diga ao lead que você está "concluindo uma missão"; despeça-se de forma natural."""
                .formatted(BuiltinTools.CONCLUIR_MISSAO, BuiltinTools.ESCALAR_HUMANO));
        return sb.toString().trim();
    }

    private static void section(StringBuilder sb, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        sb.append("## ").append(title).append("\n").append(body.trim()).append("\n\n");
    }
}
