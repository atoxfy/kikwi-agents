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
package io.kikwiflow.agents.runtime.tool;

/**
 * Ids das ferramentas que o runtime conhece.
 * <ul>
 *   <li>As <b>de controle</b> ({@link #CONCLUIR_MISSAO}, {@link #ESCALAR_HUMANO}) são tratadas pelo próprio loop,
 *       ficam sempre disponíveis e nunca passam pelo {@link ToolRegistry}.</li>
 *   <li>As <b>de negócio</b> precisam constar em {@code AGENT_TASK.tools[]} e no perfil do agente.</li>
 * </ul>
 */
public final class BuiltinTools {

    /** Encerra a missão entregando o contrato de saída (input = outputs). */
    public static final String CONCLUIR_MISSAO = "concluir_missao";
    /** Pede um humano. Input: {@code motivo}. */
    public static final String ESCALAR_HUMANO = "escalar_humano";

    public static final String SOLICITAR_MIDIA = "solicitar_midia";
    public static final String CONSULTAR_FIPE = "consultar_fipe";
    public static final String CONSULTAR_HORARIOS = "consultar_horarios";
    public static final String AGENDAR_VISITA = "agendar_visita";

    private BuiltinTools() {
    }
}
