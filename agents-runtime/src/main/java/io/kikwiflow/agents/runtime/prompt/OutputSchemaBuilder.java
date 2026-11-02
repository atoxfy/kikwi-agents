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
package io.kikwiflow.agents.runtime.prompt;

import io.kikwiflow.agents.model.mission.OutputField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converte o contrato de saída da missão (outputs do nó resolvidos contra o catálogo) no JSON Schema do input da
 * ferramenta {@code concluir_missao}. É o mesmo princípio do {@code LlmTaskHandler} do demo de KYC: o formato vira
 * o tipo, as opções viram {@code enum} e o obrigatório vira {@code required}.
 *
 * <p>O schema só orienta o modelo. Quem decide se a saída vale é o motor ({@code VariableContractValidator}) no
 * complete.
 */
public final class OutputSchemaBuilder {

    private OutputSchemaBuilder() {
    }

    public static Map<String, Object> build(List<OutputField> contract) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (OutputField field : contract) {
            properties.put(field.key(), fieldSchema(field));
            if (field.required()) {
                required.add(field.key());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> fieldSchema(OutputField field) {
        Map<String, Object> schema = new LinkedHashMap<>();
        String format = field.format() == null ? "SHORT_TEXT" : field.format();
        switch (format) {
            case "INTEGER" -> schema.put("type", "integer");
            case "DECIMAL", "MONEY" -> schema.put("type", "number");
            case "BOOLEAN" -> schema.put("type", "boolean");
            case "DATE" -> {
                schema.put("type", "string");
                schema.put("format", "date");
            }
            case "DATETIME" -> {
                schema.put("type", "string");
                schema.put("format", "date-time");
            }
            case "SINGLE_SELECT" -> {
                schema.put("type", "string");
                schema.put("enum", field.options());
            }
            case "MULTI_SELECT" -> {
                schema.put("type", "array");
                schema.put("items", Map.of("type", "string", "enum", field.options()));
            }
            case "FILE" -> {
                if (isMultipleFile(field)) {
                    schema.put("type", "array");
                    schema.put("items", fileSchema());
                } else {
                    schema.putAll(fileSchema());
                }
            }
            default -> schema.put("type", "string");
        }
        String description = describe(field);
        if (!description.isBlank()) {
            schema.put("description", description);
        }
        return schema;
    }

    private static boolean isMultipleFile(OutputField field) {
        return field.extensionProperties() != null
                && "true".equalsIgnoreCase(field.extensionProperties().get("kikwi:file.multiple"));
    }

    private static Map<String, Object> fileSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("uri", Map.of("type", "string", "description", "A referência do anexo exatamente como aparece na conversa (ex.: gridfs://...)."));
        props.put("name", Map.of("type", "string"));
        props.put("mimeType", Map.of("type", "string"));
        props.put("size", Map.of("type", "integer"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("uri", "mimeType"));
        return schema;
    }

    private static String describe(OutputField field) {
        StringBuilder sb = new StringBuilder();
        if (field.label() != null) {
            sb.append(field.label());
        }
        if (field.description() != null && !field.description().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(". ");
            }
            sb.append(field.description());
        }
        return sb.toString();
    }
}
