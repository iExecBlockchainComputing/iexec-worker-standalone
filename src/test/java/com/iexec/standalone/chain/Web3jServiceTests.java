/*
 * Copyright 2023-2024 IEXEC BLOCKCHAIN TECH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package com.iexec.standalone.chain;

import com.iexec.standalone.config.BlockchainAdapterConfigurationService;
import com.iexec.standalone.config.WorkerConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class Web3jServiceTests {
    @Mock
    private ChainConfig chainConfig;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
        when(chainConfig.getChainId()).thenReturn(134);
        when(chainConfig.getBlockTime()).thenReturn(Duration.ofSeconds(5));
        when(chainConfig.isSidechain()).thenReturn(true);
        when(chainConfig.getPrivateChainAddress()).thenReturn("https://bellecour.iex.ec");
        when(chainConfig.getGasPriceMultiplier()).thenReturn(1.0f);
        when(chainConfig.getGasPriceCap()).thenReturn(22_000_000_000L);
    }

    @Test
    void shouldCreateInstanceWithDefaultNodeAddress() {
        when(chainConfig.getPrivateChainAddress()).thenReturn("");
        assertThat(new Web3jService(chainConfig)).isNotNull();
    }

    @Test
    void shouldCreateInstanceWithOverridenNodeAddress() {
        when(chainConfig.getPrivateChainAddress()).thenReturn("http://localhost:8545");
        assertThat(new Web3jService(chainConfig)).isNotNull();
    }
}
