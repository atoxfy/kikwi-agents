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
package io.kikwiflow.agents.dms;

/**
 * @param baseUrl       URL do {@code dms-document-service} (sem {@code /v1}).
 * @param category      categoria DMS dos arquivos recebidos por canais (ex.: {@code lead:anexo-conversa}).
 * @param principalType tipo de dono gravado no DMS (ex.: {@code CONVERSATION}).
 * @param author        autor gravado no DMS (ex.: {@code kikwi-agents}).
 */
public record DmsSettings(String baseUrl, String category, String principalType, String author) {
}
