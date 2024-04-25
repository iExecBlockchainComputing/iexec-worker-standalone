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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerConfigurationServiceTests {

    @Mock
    private Environment environment;

    @InjectMocks
    private WorkerConfigurationService workerConfigService;

    private static final String TASK_NAME = "task1";
    private static final String HTTP_PROXY_PORT = "http.proxyPort";
    private static final String HTTPS_PROXY_PORT = "https.proxyPort";

    @BeforeEach
    void setUp() {
        when(environment.getProperty("worker.worker-base-dir")).thenReturn("/base/dir");
        when(environment.getProperty("worker.name")).thenReturn("worker1");
        when(environment.getProperty("worker.override-available-cpu-count")).thenReturn("4"); // Default for other tests
    }

    @AfterEach
    void tearDown() {
        String[] testedProperties = {
                "os.name",
                "os.arch",
                "http.proxyHost",
                HTTP_PROXY_PORT,
                "https.proxyHost",
                HTTPS_PROXY_PORT };
        for (String property : testedProperties) {
            System.clearProperty(property);
        }
    }

    /* postConstruct() -> private : how to test it ?
    @Test
    void shouldThrowIllegalArgumentExceptionIfCpuCountIsLessThan1() {
        when(environment.getProperty("worker.override-available-cpu-count")).thenReturn("-1");
        assertThatThrownBy(() -> workerConfigService.postConstruct())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Override available CPU count must not be less or equal to 0");
    }*/

    @Test
    void testIsGpuEnabled() {
        when(environment.getProperty("worker.gpu-enabled", Boolean.class)).thenReturn(true);
        assertThat(workerConfigService.isGpuEnabled()).isTrue();

        when(environment.getProperty("worker.gpu-enabled", Boolean.class)).thenReturn(false);
        assertThat(workerConfigService.isGpuEnabled()).isFalse();
    }

    @Test
    void testGetDirs() {
        assertThat(workerConfigService.getWorkerBaseDir()).isEqualTo("/base/dir/worker1");
        assertThat(workerConfigService.getTaskInputDir(TASK_NAME)).isEqualTo("/base/dir/worker1/task1/input");
        assertThat(workerConfigService.getTaskOutputDir(TASK_NAME)).isEqualTo("/base/dir/worker1/task1/output");
        assertThat(workerConfigService.getTaskIexecOutDir(TASK_NAME)).isEqualTo("/base/dir/worker1/task1/iexec_out");
        assertThat(workerConfigService.getTaskBaseDir(TASK_NAME)).isEqualTo("/base/dir/worker1/task1");
    }

    @Test
    void testGetOS() {
        String osName = "WorkerOS";
        System.setProperty("os.name", osName);
        assertThat(workerConfigService.getOS()).isEqualTo(osName);
    }

    @Test
    void testGetCPU() {
        int workerCpus = 5;
        System.setProperty("os.arch", Integer.valueOf(workerCpus).toString());
        assertThat(Integer.valueOf(workerConfigService.getCPU()).intValue()).isEqualTo(workerCpus);
    }

    @Test
    void shouldReturnCorrectCpuCount() {
        int setCpus = 4;
        when(environment.getProperty("worker.override-available-cpu-count")).thenReturn(String.valueOf(setCpus));
        assertThat(workerConfigService.getCpuCount()).isEqualTo(setCpus);

        when(environment.getProperty("worker.override-available-cpu-count")).thenReturn(null);
        int availableCpus = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);
        assertThat(workerConfigService.getCpuCount()).isEqualTo(availableCpus);
    }

    @Test
    void testGetMemorySize() {
        OperatingSystemMXBean osBean = mock(OperatingSystemMXBean.class);
        when(osBean.getTotalPhysicalMemorySize()).thenReturn(8L * 1024 * 1024 * 1024);
        when(ManagementFactory.getOperatingSystemMXBean()).thenReturn(osBean);
        assertThat(workerConfigService.getMemorySize()).isEqualTo(8);
    }

    @Test
    void testGetHttpProxyHost() {
        String httpProxyHost = "httpProxy.worker.com";
        System.setProperty("http.proxyHost", httpProxyHost);
        assertThat(workerConfigService.getHttpProxyHost()).isEqualTo(httpProxyHost);
    }

    @Test
    void testGetHttpProxyPort() {
        int httpProxyPort = 8080;
        System.setProperty(HTTP_PROXY_PORT, Integer.valueOf(httpProxyPort).toString());
        assertThat(workerConfigService.getHttpProxyPort()).isEqualTo(httpProxyPort);
    }

    @Test
    void testGetHttpProxyPortNotDefined() {
        System.clearProperty(HTTP_PROXY_PORT);
        assertThat(workerConfigService.getHttpProxyPort()).isNull();
    }

    @Test
    void testGetHttpsProxyHost() {
        String httpsProxyHost = "httpsProxy.worker.com";
        System.setProperty("https.proxyHost", httpsProxyHost);
        assertThat(workerConfigService.getHttpsProxyHost()).isEqualTo(httpsProxyHost);
    }

    @Test
    void testGetHttpsProxyPort() {
        int httpsProxyPort = 8008;
        System.setProperty(HTTPS_PROXY_PORT, Integer.valueOf(httpsProxyPort).toString());
        assertThat(workerConfigService.getHttpsProxyPort()).isEqualTo(httpsProxyPort);
    }

    @Test
    void testGetHttpsProxyPortNotDefined() {
        System.clearProperty(HTTPS_PROXY_PORT);
        assertThat(workerConfigService.getHttpsProxyPort()).isNull();
    }
}
