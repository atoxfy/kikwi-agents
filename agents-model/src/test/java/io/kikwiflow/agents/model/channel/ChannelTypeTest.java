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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Dado o tipo de canal aberto")
class ChannelTypeTest {

    @Test
    @DisplayName("quando o host cria um tipo novo pelo nome, então é sempre a mesma instância, em qualquer caixa")
    void novoTipo() {
        ChannelType instagram = ChannelType.of("instagram");

        assertThat(instagram).isSameAs(ChannelType.of("INSTAGRAM")).isSameAs(ChannelType.valueOf("Instagram"));
        assertThat(instagram.name()).isEqualTo("INSTAGRAM");
        assertThat(instagram).hasToString("INSTAGRAM");
    }

    @Test
    @DisplayName("quando lê um tipo que existia no enum, então é a constante de sempre")
    void constantes() {
        assertThat(ChannelType.valueOf("WHATSAPP")).isSameAs(ChannelType.WHATSAPP);
        assertThat(ChannelType.of("webchat")).isSameAs(ChannelType.WEBCHAT);
    }

    @Test
    @DisplayName("quando o nome não é um identificador, então recusa")
    void invalido() {
        assertThatThrownBy(() -> ChannelType.of("meu canal")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChannelType.of("1abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChannelType.of(null)).isInstanceOf(NullPointerException.class);
    }
}
