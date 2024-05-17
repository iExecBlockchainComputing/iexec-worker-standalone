package com.iexec.standalone.feign.config;

import com.iexec.standalone.config.WorkerConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestTemplateConfigTests {

    @Mock
    private WorkerConfigurationService workerConfService;

    @InjectMocks
    private RestTemplateConfig restTemplateConfig;

    private static final String HTTPS_PROXY_HOST = "httpsProxyHost";
    private static final int HTTPS_PROXY_PORT = 443;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        lenient().when(workerConfService.getHttpsProxyHost()).thenReturn(HTTPS_PROXY_HOST);
        lenient().when(workerConfService.getHttpsProxyPort()).thenReturn(HTTPS_PROXY_PORT);
    }

    @Test
    public void testConstructor() {
        RestTemplateConfig config = new RestTemplateConfig(workerConfService);
        assertNotNull(config);
    }

    @Test
    public void testRestTemplateBean() {
        RestTemplate restTemplate = restTemplateConfig.restTemplate();
        assertNotNull(restTemplate);

        HttpComponentsClientHttpRequestFactory factory = (HttpComponentsClientHttpRequestFactory) restTemplate.getRequestFactory();
        assertNotNull(factory.getHttpClient());
    }
}
