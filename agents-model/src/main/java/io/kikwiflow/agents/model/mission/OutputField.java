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
package io.kikwiflow.agents.model.mission;

import java.util.List;
import java.util.Map;

/**
 * Uma variável do contrato de saída da missão, já resolvida contra o catálogo ({@code variableDeclarations}) do
 * processo.
 *
 * @param format              o {@code VariableFormat} do Kikwiflow como texto (SHORT_TEXT, SINGLE_SELECT, FILE...).
 * @param extensionProperties da declaração (ex.: {@code kikwi:file.multiple}, {@code kikwi:numberFormat}).
 */
public record OutputField(String key,
                          String label,
                          String description,
                          String format,
                          boolean required,
                          List<String> options,
                          Map<String, String> extensionProperties) {
}
