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

import com.fasterxml.jackson.databind.JsonNode;
import io.kikwiflow.agents.channel.AttachmentStore;
import io.kikwiflow.agents.model.conversation.Attachment;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Guarda anexos no DMS via {@code POST /v1/documents/multipart} e devolve a referência {@code dms://id/versão}.
 *
 * <p>Pendência do lado do DMS: o endpoint hoje exige {@code ROLE_ADMIN}. A plataforma deve usar uma service account
 * (client credentials no Keycloak do DMS) com um papel dedicado de escrita restrito à categoria de anexos de
 * conversa, em vez de admin.
 */
public class DmsAttachmentStore implements AttachmentStore {

    private final RestClient rest;
    private final DmsSettings settings;
    private final Supplier<String> bearerToken;

    /**
     * @param bearerToken fornece um access token válido da service account (renovado por quem implementa).
     */
    public DmsAttachmentStore(RestClient.Builder builder, DmsSettings settings, Supplier<String> bearerToken) {
        this.rest = builder.baseUrl(settings.baseUrl()).build();
        this.settings = settings;
        this.bearerToken = bearerToken;
    }

    @Override
    public Attachment store(String tenantId, String ownerRef, String fileName, String mimeType, long size,
                            InputStream content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("document", new NamedInputStreamResource(content, fileName, size));
        form.add("category", settings.category());
        form.add("metadata", "{\"tenantId\":\"" + escape(tenantId) + "\",\"ownerRef\":\"" + escape(ownerRef) + "\"}");
        form.add("filename", fileName);
        form.add("author", settings.author());
        form.add("principalId", ownerRef);
        form.add("principalType", settings.principalType());
        form.add("isFinal", "true");

        JsonNode id = rest.post()
                .uri("/v1/documents/multipart")
                .header("TransactionId", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + bearerToken.get())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (id == null || !id.hasNonNull("id")) {
            throw new IllegalStateException("DMS não devolveu o id do documento");
        }
        return new Attachment("dms://" + id.get("id").asText() + "/" + id.path("version").asText("1.0"),
                fileName, mimeType, size);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** O DMS lê o nome do arquivo da parte multipart; {@link InputStreamResource} puro não tem nome. */
    private static final class NamedInputStreamResource extends InputStreamResource {

        private final String fileName;
        private final long size;

        NamedInputStreamResource(InputStream content, String fileName, long size) {
            super(content);
            this.fileName = fileName;
            this.size = size;
        }

        @Override
        public String getFilename() {
            return fileName;
        }

        @Override
        public long contentLength() {
            return size;
        }
    }
}
