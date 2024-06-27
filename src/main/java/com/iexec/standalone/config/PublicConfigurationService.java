/*
 * Copyright 2020-2024 IEXEC BLOCKCHAIN TECH
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

package com.iexec.standalone.config;

import com.iexec.common.config.ConfigServerClient;
import com.iexec.common.config.ConfigServerClientBuilder;
import com.iexec.standalone.api.SchedulerClient;
import com.iexec.resultproxy.api.ResultProxyClient;
import com.iexec.resultproxy.api.ResultProxyClientBuilder;
import feign.Logger;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

@Getter
@Service
public class PublicConfigurationService {

    private final PublicConfiguration publicConfiguration;

    public PublicConfigurationService(SchedulerClient schedulerClient) {
        this.publicConfiguration = schedulerClient.getPublicConfiguration();
    }

    public String getSchedulerPublicAddress() {
        return publicConfiguration.getSchedulerPublicAddress();
    }

    public String getRequiredWorkerVersion() {
        return publicConfiguration.getRequiredWorkerVersion();
    }

    @Bean
    public ConfigServerClient configServerClient() {
        final String configServerURL = StringUtils.isBlank(publicConfiguration.getConfigServerUrl()) ?
                publicConfiguration.getBlockchainAdapterUrl() : publicConfiguration.getConfigServerUrl();
        return ConfigServerClientBuilder.getInstance(
                Logger.Level.NONE,
                configServerURL);
    }

    @Bean
    public ResultProxyClient resultProxyClient() {
        return ResultProxyClientBuilder.getInstance(
                Logger.Level.NONE,
                publicConfiguration.getResultRepositoryURL());
    }
}
