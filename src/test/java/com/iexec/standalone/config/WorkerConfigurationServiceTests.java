/*
 * Copyright 2020-2023 IEXEC BLOCKCHAIN TECH
 *
 * Licensed under the Apache License; Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing; software
 * distributed under the License is distributed on an "AS IS" BASIS;
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND; either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.iexec.standalone.config;

class WorkerConfigurationServiceTests {
    
    @Mock
    private WorkerConfigurationService workerConfigService;

    @BeforeEach
    void beforeEach() {
        MockitoAnnotations.openMocks(this);
        workerConfigService = new WorkerConfigurationService();
    }
    
    @Test
    void shouldReturnIllegalArgumentExceptionIfCPULessThan1() {
        when(workerConfigService.getCpuCount()).thenReturn("-1");
        verify(workerConfigService).getCpuCount();
        assertThatThrownBy(() -> workerConfigService.postConstruct())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Override available CPU count must not be less or equal to 0");
    }

    @Test
    void shouldReturnTrueWhenGpuIsEnabled() {
        when(workerConfigService.getProperty("worker.gpu-enabled")).thenReturn("true");
        boolean result = workerConfigService.isGpuEnabled();
        assertThat(result).isTrue();
    }

    @Test
    void shouldReturnFalseWhenGpuIsNotEnabled() {
        when(workerConfigService.getProperty("worker.gpu-enabled")).thenReturn("false");
        boolean result = workerConfigService.isGpuEnabled();
        assertThat(result).isFalse();
    }

}
