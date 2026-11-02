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
package io.kikwiflow.agents.platform.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import io.kikwiflow.agents.model.agent.AgentDefinition;
import io.kikwiflow.agents.runtime.agent.AgentDefinitionStore;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Catálogo de agentes no Mongo, coleção {@code agent_definitions}: um documento por (tenant, chave, versão), com
 * versão 0 = rascunho. O índice único garante que uma versão publicada nunca é sobrescrita. A definição vai inteira
 * em {@code definition}, no mesmo JSON do {@code .agent.json}.
 */
public class MongoAgentDefinitionStore implements AgentDefinitionStore {

    static final String COLLECTION = "agent_definitions";

    private final MongoCollection<Document> collection;
    private final ObjectMapper mapper;

    public MongoAgentDefinitionStore(MongoDatabase database, ObjectMapper mapper) {
        this.collection = database.getCollection(COLLECTION);
        this.mapper = mapper;
        collection.createIndex(Indexes.ascending("tenantId", "key", "version"), new IndexOptions().unique(true));
    }

    private static Bson of(String tenantId, String key) {
        return Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("key", key));
    }

    @Override
    public Optional<AgentDefinition> findDraft(String tenantId, String key) {
        return Optional.ofNullable(collection.find(Filters.and(of(tenantId, key), Filters.eq("version", 0))).first())
                .map(this::toDefinition);
    }

    @Override
    public List<AgentDefinition> findVersions(String tenantId, String key) {
        List<AgentDefinition> versions = new ArrayList<>();
        collection.find(Filters.and(of(tenantId, key), Filters.gt("version", 0))).sort(Sorts.ascending("version"))
                .forEach(document -> versions.add(toDefinition(document)));
        return versions;
    }

    @Override
    public List<String> keys(String tenantId) {
        TreeSet<String> keys = new TreeSet<>();
        collection.distinct("key", Filters.eq("tenantId", tenantId), String.class).forEach(keys::add);
        return List.copyOf(keys);
    }

    @Override
    public void saveDraft(AgentDefinition draft) {
        collection.replaceOne(Filters.and(of(draft.tenantId(), draft.key()), Filters.eq("version", 0)), toDocument(draft),
                new ReplaceOptions().upsert(true));
    }

    @Override
    public void insertVersion(AgentDefinition published) {
        try {
            collection.insertOne(toDocument(published));
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new IllegalStateException("Versão " + published.version() + " de " + published.key() + " já existe", e);
            }
            throw e;
        }
    }

    private Document toDocument(AgentDefinition definition) {
        try {
            return new Document("tenantId", definition.tenantId())
                    .append("key", definition.key())
                    .append("version", definition.version())
                    .append("definition", Document.parse(mapper.writeValueAsString(definition)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível gravar o agente " + definition.key(), e);
        }
    }

    private AgentDefinition toDefinition(Document document) {
        try {
            return mapper.readValue(document.get("definition", Document.class).toJson(), AgentDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agente ilegível: " + document.get("key"), e);
        }
    }
}
