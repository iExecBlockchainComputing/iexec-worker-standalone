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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import java.io.File;
import java.lang.management.ManagementFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WorkerConfigurationService.class)
@TestPropertySource(properties = {
        "worker.gpu-enabled=true",
        "worker.worker-base-dir=/base/dir",
        "worker.name=worker1",
        "worker.override-available-cpu-count=4",
        "${worker.gas-price-multiplier=1.3",
        "worker.gas-price-cap=22000000000",
        "worker.override-blockchain-node-address=null",
        "worker.developer-logger-enabled=false",
        "worker.tee-compute-max-heap-size-gb=8"/*,
        "worker.docker-network-name=iexec-worker1-net"*/
})
class WorkerConfigurationServiceTests {

    @Autowired
    private WorkerConfigurationService workerConfigService;

    private static final String TASK_NAME = "task1";
    private static final String HTTP_PROXY_PORT = "http.proxyPort";
    private static final String HTTPS_PROXY_PORT = "https.proxyPort";

    private void resetSystemPropertiesLikeBeforeTest(String propertyName, String originalValue) {
        if (originalValue != null) {
            System.setProperty(propertyName, originalValue);
            String resetValue = System.getProperty(propertyName);
            assertThat(resetValue).isEqualTo(originalValue);
        } else {
            System.clearProperty(propertyName);
        }

    }

    @Test
    void testIsGpuEnabled() {
        boolean actualIsGpuEnabled = workerConfigService.isGpuEnabled();
        assertThat(actualIsGpuEnabled).isTrue();
    }

    @Test
    void testGetDirs() {
        assertThat(workerConfigService.getWorkerBaseDir()).isEqualTo("/base/dir" + File.separator + "worker1");
        assertThat(workerConfigService.getTaskInputDir(TASK_NAME)).isEqualTo("/base/dir" + File.separator + "worker1" + File.separator + "task1" + File.separator + "input");
        assertThat(workerConfigService.getTaskOutputDir(TASK_NAME)).isEqualTo("/base/dir" + File.separator + "worker1" + File.separator + "task1" + File.separator + "output");
        assertThat(workerConfigService.getTaskIexecOutDir(TASK_NAME)).isEqualTo("/base/dir" + File.separator + "worker1" + File.separator + "task1" + File.separator + "output"+ File.separator + "iexec_out");
        assertThat(workerConfigService.getTaskBaseDir(TASK_NAME)).isEqualTo("/base/dir" + File.separator + "worker1" + File.separator + "task1");
    }

    @Test
    void testGetOS() {
        String originalOs = System.getProperty("os.name");
        String osName = "WorkerOS";
        System.setProperty("os.name", osName);
        assertThat(workerConfigService.getOS()).isEqualTo(osName);
        resetSystemPropertiesLikeBeforeTest("os.name", originalOs);
    }

    @Test
    void testGetCPU() {
        String originalCpus = System.getProperty("os.arch");
        int workerCpus = 5;
        System.setProperty("os.arch", Integer.valueOf(workerCpus).toString());
        assertThat(Integer.valueOf(workerConfigService.getCPU()).intValue()).isEqualTo(workerCpus);
        resetSystemPropertiesLikeBeforeTest("os.arch", originalCpus);
    }

    @Test
    void shouldReturnCorrectCpuCount() {
        int setCpus = 4;
        assertThat(workerConfigService.getCpuCount()).isEqualTo(setCpus);
    }

    @Test
    void testGetMemorySize() {
        try (MockedStatic<ManagementFactory> mocked = Mockito.mockStatic(ManagementFactory.class)) {
            com.sun.management.OperatingSystemMXBean mockOsBean = Mockito.mock(com.sun.management.OperatingSystemMXBean.class);
            mocked.when(ManagementFactory::getOperatingSystemMXBean).thenReturn(mockOsBean);
            long totalMemorySizeBytes = 8L * 1024 * 1024 * 1024; // Assuming the total physical memory size is 8GB for this test
            Mockito.when(mockOsBean.getTotalPhysicalMemorySize()).thenReturn(totalMemorySizeBytes);
            int memorySizeGB = workerConfigService.getMemorySize();
            assertThat(memorySizeGB).isEqualTo(8);
        }
    }

    @Test
    void testGetHttpProxyHost() {
        String originalHttpProxyHost = System.getProperty("http.proxyHost");
        String workerHttpProxyHost = "httpProxy.worker.com";
        System.setProperty("http.proxyHost", workerHttpProxyHost);
        assertThat(workerConfigService.getHttpProxyHost()).isEqualTo(workerHttpProxyHost);
        resetSystemPropertiesLikeBeforeTest("http.proxyHost", originalHttpProxyHost);
    }

    @Test
    void testGetHttpProxyPort() {
        String originalHttpProxyPort = System.getProperty(HTTP_PROXY_PORT);
        if (originalHttpProxyPort == null) {
            assertThat(workerConfigService.getHttpProxyPort()).isNull();
        }
        int httpProxyWorkerPort = 8080;
        System.setProperty(HTTP_PROXY_PORT, Integer.valueOf(httpProxyWorkerPort).toString());
        assertThat(workerConfigService.getHttpProxyPort()).isEqualTo(httpProxyWorkerPort);
        resetSystemPropertiesLikeBeforeTest(HTTP_PROXY_PORT, originalHttpProxyPort);
    }

    @Test
    void testGetHttpsProxyHost() {
        String originalHttpsProxyHost = System.getProperty("https.proxyHost");
        String httpsProxyHost = "httpsProxy.worker.com";
        System.setProperty("https.proxyHost", httpsProxyHost);
        assertThat(workerConfigService.getHttpsProxyHost()).isEqualTo(httpsProxyHost);
        resetSystemPropertiesLikeBeforeTest("https.proxyHost", originalHttpsProxyHost);
    }

    @Test
    void testGetHttpsProxyPort() {
        String originalHttpsProxyPort = System.getProperty(HTTPS_PROXY_PORT);
        if (originalHttpsProxyPort == null) {
            assertThat(workerConfigService.getHttpsProxyPort()).isNull();
        }
        int httpsProxyPort = 8008;
        System.setProperty(HTTPS_PROXY_PORT, Integer.valueOf(httpsProxyPort).toString());
        assertThat(workerConfigService.getHttpsProxyPort()).isEqualTo(httpsProxyPort);
        resetSystemPropertiesLikeBeforeTest(HTTPS_PROXY_PORT, originalHttpsProxyPort);
    }

}
