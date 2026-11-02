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
package io.kikwiflow.agents.platform.channel;

import io.kikwiflow.agents.channel.AttachmentStore;
import io.kikwiflow.agents.model.conversation.Attachment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Anexos em memória ({@code mem://}), para testes e para rodar sem Mongo. Somem ao reiniciar. */
public class InMemoryAttachmentStore implements AttachmentStore {

    private record Stored(String tenantId, byte[] bytes) {
    }

    private final Map<String, Stored> files = new ConcurrentHashMap<>();

    @Override
    public Attachment store(String tenantId, String ownerRef, String fileName, String mimeType, long size,
                            InputStream content) {
        String uri = "mem://" + UUID.randomUUID();
        try {
            files.put(uri, new Stored(tenantId, content.readAllBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Attachment(uri, fileName, mimeType, size);
    }

    @Override
    public Optional<InputStream> open(String tenantId, String uri) {
        Stored stored = files.get(uri);
        return stored == null || !stored.tenantId().equals(tenantId) ? Optional.empty()
                : Optional.of(new ByteArrayInputStream(stored.bytes()));
    }
}
