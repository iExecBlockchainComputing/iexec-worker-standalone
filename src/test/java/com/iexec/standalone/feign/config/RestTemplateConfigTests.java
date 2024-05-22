package com.iexec.standalone.feign.config;

import com.iexec.standalone.config.WorkerConfigurationService;
import org.apache.http.HttpHost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestTemplateConfigTests {

    @Mock
    private WorkerConfigurationService workerConfService;

    @InjectMocks
    private RestTemplateConfig restTemplateConfig;

    private final String HTTPS_PROXY_HOST_PROPERTY_NAME = "https.proxyHost";
    private final String HTTPS_PROXY_PORT_PROPERTY_NAME = "https.proxyPort";

    private static final String HTTPS_PROXY_HOST_VALUE = "httpsProxyHost";
    private static final int HTTPS_PROXY_PORT_VALUE = 443;
    private static final String HTTP_PROXY_HOST_VALUE = "httpProxyHost";
    private static final int HTTP_PROXY_PORT_VALUE = 80;

    /*@BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }*/

    @Test
    void testConstructor() {
        RestTemplateConfig config = new RestTemplateConfig(workerConfService);
        assertNotNull(config);
    }

    @Test
    void testRestTemplateBean() {
        RestTemplate restTemplate = restTemplateConfig.restTemplate();
        assertNotNull(restTemplate);

        HttpComponentsClientHttpRequestFactory factory = (HttpComponentsClientHttpRequestFactory) restTemplate.getRequestFactory();
        assertNotNull(factory.getHttpClient());
    }

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
    void testSetProxy_HttpsProxy() {
        HttpClientBuilder clientBuilder = mock(HttpClientBuilder.class);

        String originalHttpsProxyHost = System.getProperty(HTTPS_PROXY_HOST_PROPERTY_NAME);
        System.setProperty(HTTPS_PROXY_HOST_PROPERTY_NAME, HTTPS_PROXY_HOST_VALUE);
        when(workerConfService.getHttpsProxyHost()).thenReturn(HTTPS_PROXY_HOST_VALUE);

        String originalHttpsProxyPort = System.getProperty(HTTPS_PROXY_PORT_PROPERTY_NAME);
        System.setProperty(HTTPS_PROXY_PORT_PROPERTY_NAME, Integer.valueOf(HTTPS_PROXY_PORT_VALUE).toString());
        when(workerConfService.getHttpsProxyPort()).thenReturn(HTTPS_PROXY_PORT_VALUE);

        restTemplateConfig.setProxy(clientBuilder);

        HttpHost expectedProxy = new HttpHost(HTTPS_PROXY_HOST_VALUE, HTTPS_PROXY_PORT_VALUE, "https");
        verify(clientBuilder).setProxy(any());

        resetSystemPropertiesLikeBeforeTest(HTTPS_PROXY_HOST_PROPERTY_NAME, originalHttpsProxyHost);
        resetSystemPropertiesLikeBeforeTest(HTTPS_PROXY_PORT_PROPERTY_NAME, originalHttpsProxyPort);
    }

    @Test
    void testSetProxy_HttpProxy() {
        HttpClientBuilder clientBuilder = mock(HttpClientBuilder.class);
        when(workerConfService.getHttpsProxyHost()).thenReturn(null);
        when(workerConfService.getHttpsProxyPort()).thenReturn(null);
        when(workerConfService.getHttpProxyHost()).thenReturn(HTTP_PROXY_HOST_VALUE);
        when(workerConfService.getHttpProxyPort()).thenReturn(HTTP_PROXY_PORT_VALUE);
        restTemplateConfig.setProxy(clientBuilder);
        HttpHost expectedProxy = new HttpHost(HTTP_PROXY_HOST_VALUE, HTTP_PROXY_PORT_VALUE, "http");
        verify(clientBuilder).setProxy(expectedProxy);
    }

    @Test
    void testSetProxy_NoProxy() {
        HttpClientBuilder clientBuilder = mock(HttpClientBuilder.class);
        when(workerConfService.getHttpsProxyHost()).thenReturn(null);
        when(workerConfService.getHttpsProxyPort()).thenReturn(null);
        when(workerConfService.getHttpProxyHost()).thenReturn(null);
        when(workerConfService.getHttpProxyPort()).thenReturn(null);
        restTemplateConfig.setProxy(clientBuilder);
        verify(clientBuilder, never()).setProxy(any(HttpHost.class));
    }
}
