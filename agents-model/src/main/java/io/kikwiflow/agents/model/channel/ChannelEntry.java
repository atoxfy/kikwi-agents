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
package io.kikwiflow.agents.model.channel;

import java.util.Map;

/**
 * Ponto de entrada de um canal: qual processo Kikwiflow iniciar para uma nova conversa.
 *
 * @param processKey     chave da definição. No MVP leva o prefixo do tenant (ex. {@code pre-vendas}) até
 *                       o motor ter definições por tenant (doc 31, E6).
 * @param startVariables variáveis fixas acrescentadas ao início (ex.: {@code origem=site}).
 */
public record ChannelEntry(String processKey, Map<String, Object> startVariables) {
}
