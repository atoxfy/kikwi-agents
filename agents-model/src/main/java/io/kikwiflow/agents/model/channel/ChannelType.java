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
package io.kikwiflow.agents.model.channel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * O tipo de um canal ({@code WEBCHAT}, {@code WHATSAPP}, {@code INSTAGRAM}…). É aberto: o host cria os tipos dos
 * conectores que tiver com {@link #of(String)}, sem mudar a plataforma. Os valores são únicos por nome, então
 * {@code ==} continua valendo, e a forma serializada é só o nome, como era quando o tipo era um enum.
 */
public final class ChannelType {

    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final ConcurrentMap<String, ChannelType> TYPES = new ConcurrentHashMap<>();

    public static final ChannelType WEBCHAT = of("WEBCHAT");
    public static final ChannelType WHATSAPP = of("WHATSAPP");
    public static final ChannelType EMAIL = of("EMAIL");

    private final String name;

    private ChannelType(String name) {
        this.name = name;
    }

    /**
     * O tipo com esse nome (maiúsculas, números e {@code _}, começando por letra). Minúsculas são aceitas e
     * convertidas: {@code instagram} e {@code INSTAGRAM} são o mesmo tipo.
     */
    @JsonCreator
    public static ChannelType of(String name) {
        Objects.requireNonNull(name, "name");
        String normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
        if (!NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Tipo de canal inválido: '" + name + "'");
        }
        return TYPES.computeIfAbsent(normalized, ChannelType::new);
    }

    /** O mesmo que {@link #of(String)}; mantém o código que usava o enum. */
    public static ChannelType valueOf(String name) {
        return of(name);
    }

    @JsonValue
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
