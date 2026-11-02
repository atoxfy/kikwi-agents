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
package io.kikwiflow.agents.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuração da plataforma ({@code kikwi-agents.*}).
 *
 * @param demo canal de demonstração semeado em memória até existir o cadastro de canais por tenant.
 */
@ConfigurationProperties("kikwi-agents")
public record AgentsProperties(Anthropic anthropic, Gemini gemini, Dms dms, Worker worker, Catalog catalog,
                               Storage storage) {

    /**
     * @param store {@code mongo} (padrão: conversas, transcrição e anexos no Mongo/GridFS, sobrevivem a reinícios) ou
     *              {@code memory} (testes e desenvolvimento sem Mongo).
     */
    public record Storage(String store) {

        public boolean memory() {
            return "memory".equalsIgnoreCase(store);
        }
    }

    /** @param store {@code mongo} (padrão) ou {@code memory} para o catálogo de agentes. */
    public record Catalog(String store) {
    }


    /** @param apiKey sem chave, só o provedor {@code fake} fica disponível. */
    public record Anthropic(String apiKey, String baseUrl) {
    }

    /** @param apiKey chave do Google AI Studio; sem chave, o provedor {@code gemini} só existe por tenant. */
    public record Gemini(String apiKey, String baseUrl) {
    }

    /**
     * @param enabled {@code true} manda os anexos para o DMS; {@code false} usa o armazenamento da plataforma
     *                ({@code kikwi-agents.storage}: GridFS ou memória).
     * @param token   access token fixo da service account. Provisório até o fluxo client credentials.
     */
    public record Dms(boolean enabled, String baseUrl, String category, String token) {
    }

    /**
     * Worker de missões (doc 31, E2).
     *
     * @param workerId      vazio = gerado na subida ({@code kikwi-agents-<uuid>}); fixe por nó para ler a auditoria.
     * @param pollInterval  intervalo da aquisição. Uma conversa nova não espera por ele: o início do processo já
     *                      dispara uma aquisição.
     * @param renewInterval intervalo da renovação dos leases; bem menor que {@code leaseDuration}.
     */
    public record Worker(String workerId, List<String> topics, int maxTasks, Duration leaseDuration,
                         Duration pollInterval, Duration renewInterval, int transcriptWindow, int failureRetries,
                         Duration failureRetryDelay, String handoffMessage) {
    }

}
