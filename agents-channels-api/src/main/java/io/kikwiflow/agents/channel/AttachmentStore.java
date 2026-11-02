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
package io.kikwiflow.agents.channel;

import io.kikwiflow.agents.model.conversation.Attachment;

import java.io.InputStream;
import java.util.Optional;

/**
 * Onde os arquivos recebidos pelos canais são guardados (claim check). A conversa e as variáveis do processo só
 * carregam o {@link Attachment} devolvido, nunca os bytes. Implementação de produção: DMS ({@code dms://}).
 *
 * @see #store(String, String, String, String, long, InputStream)
 */
public interface AttachmentStore {

    /**
     * @param ownerRef referência de negócio do dono do arquivo (ex.: id da conversa), gravada como metadado.
     */
    Attachment store(String tenantId, String ownerRef, String fileName, String mimeType, long size, InputStream content);

    /**
     * Lê de volta um arquivo guardado por este store — o runtime usa para entregar fotos e PDFs ao modelo. Vazio
     * quando a referência não é deste store, não existe ou é de outro tenant. Quem chama fecha o stream.
     */
    default Optional<InputStream> open(String tenantId, String uri) {
        return Optional.empty();
    }
}
