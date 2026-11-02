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

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import io.kikwiflow.agents.channel.AttachmentStore;
import io.kikwiflow.agents.model.conversation.Attachment;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.InputStream;
import java.util.Optional;

/**
 * Anexos no Mongo (GridFS, bucket {@code agents_attachments}), no lugar do disco local: sobrevivem a reinícios,
 * valem entre nós e ficam junto do resto da conversa. A referência é {@code gridfs://<id>}; tenant, dono e tipo vão
 * nos metadados, e a leitura confere o tenant.
 */
public class GridFsAttachmentStore implements AttachmentStore {

    static final String SCHEME = "gridfs://";

    private final GridFSBucket bucket;

    public GridFsAttachmentStore(MongoDatabase database) {
        this.bucket = GridFSBuckets.create(database, "agents_attachments");
    }

    @Override
    public Attachment store(String tenantId, String ownerRef, String fileName, String mimeType, long size,
                            InputStream content) {
        GridFSUploadOptions options = new GridFSUploadOptions().metadata(new Document("tenantId", tenantId)
                .append("ownerRef", ownerRef).append("mimeType", mimeType));
        ObjectId id = bucket.uploadFromStream(fileName == null ? "anexo" : fileName, content, options);
        return new Attachment(SCHEME + id.toHexString(), fileName, mimeType, size);
    }

    @Override
    public Optional<InputStream> open(String tenantId, String uri) {
        if (uri == null || !uri.startsWith(SCHEME)) {
            return Optional.empty();
        }
        String hex = uri.substring(SCHEME.length());
        if (!ObjectId.isValid(hex)) {
            return Optional.empty();
        }
        ObjectId id = new ObjectId(hex);
        GridFSFile file = bucket.find(Filters.eq("_id", id)).first();
        if (file == null || file.getMetadata() == null || !tenantId.equals(file.getMetadata().getString("tenantId"))) {
            return Optional.empty();
        }
        return Optional.of(bucket.openDownloadStream(id));
    }
}
