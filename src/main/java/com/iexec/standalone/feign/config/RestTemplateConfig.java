/*
 * Copyright 2020 IEXEC BLOCKCHAIN TECH
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

package com.iexec.standalone.feign.config;

import com.iexec.standalone.config.WorkerConfigurationService;
import org.apache.http.HttpHost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;


@Configuration
public class RestTemplateConfig {

    private final WorkerConfigurationService workerConfService;

    public RestTemplateConfig(WorkerConfigurationService workerConfService) {
        this.workerConfService = workerConfService;
    }
    @Bean
    public RestTemplate restTemplate() {
        HttpClientBuilder clientBuilder = HttpClientBuilder.create();
        setProxy(clientBuilder);
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setHttpClient(clientBuilder.build());
        return new RestTemplate(factory);
    }

    /*
    * TODO
    * Set multiple proxies
    * Use HttpRoutePlanner to support both http & https proxies at the same time
    * https://stackoverflow.com/a/34432952
    * */
    private void setProxy(HttpClientBuilder clientBuilder) {
        HttpHost proxy = null;
        if (workerConfService.getHttpsProxyHost() != null && workerConfService.getHttpsProxyPort() != null) {
            proxy = new HttpHost(workerConfService.getHttpsProxyHost(), workerConfService.getHttpsProxyPort(), "https");
        } else if (workerConfService.getHttpProxyHost() != null && workerConfService.getHttpProxyPort() != null) {
            proxy = new HttpHost(workerConfService.getHttpProxyHost(), workerConfService.getHttpProxyPort(), "http");
        }
        if (proxy != null){
            clientBuilder.setProxy(proxy);
        }
    }
}
