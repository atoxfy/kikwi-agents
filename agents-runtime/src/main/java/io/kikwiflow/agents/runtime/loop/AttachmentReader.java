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
package io.kikwiflow.agents.runtime.loop;

import io.kikwiflow.agents.model.conversation.Attachment;

import java.util.Optional;

/**
 * Lê os bytes de um anexo da conversa para entregá-lo ao modelo como imagem ou documento. A implementação do host
 * lê do mesmo armazenamento onde o canal guardou ({@code AttachmentStore}) e confere o tenant.
 */
@FunctionalInterface
public interface AttachmentReader {

    /** Nenhum anexo é lido: o modelo vê só a referência em texto. */
    AttachmentReader NONE = (tenantId, attachment) -> Optional.empty();

    Optional<byte[]> read(String tenantId, Attachment attachment);
}
