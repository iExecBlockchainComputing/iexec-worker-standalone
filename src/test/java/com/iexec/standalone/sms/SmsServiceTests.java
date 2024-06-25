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

package com.iexec.standalone.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iexec.common.web.ApiResponseBody;
import com.iexec.commons.poco.chain.SignerService;
import com.iexec.commons.poco.chain.WorkerpoolAuthorization;
import com.iexec.commons.poco.security.Signature;
import com.iexec.commons.poco.tee.TeeFramework;
import com.iexec.commons.poco.tee.TeeUtils;
import com.iexec.commons.poco.utils.BytesUtils;
import com.iexec.sms.api.*;
import com.iexec.standalone.chain.SignatureService;
import com.iexec.standalone.registry.PlatformRegistryConfiguration;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.iexec.sms.secret.ReservedSecretKeyName.IEXEC_RESULT_IEXEC_IPFS_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SmsServiceTests {

    private static final String AUTHORIZATION = "authorization";
    private static final String GRAMINE_SMS_URL = "http://gramine-sms";
    private static final String SCONE_SMS_URL = "http://scone-sms";
    private static final String CHAIN_TASK_ID = "chainTaskId";
    private static final String url = "url";
    private static final String HASH = "hash";
    private static final String TOKEN = "token";
    private static final WorkerpoolAuthorization WORKERPOOL_AUTHORIZATION = spy(WorkerpoolAuthorization
            .builder()
            .chainTaskId(CHAIN_TASK_ID)
            .enclaveChallenge("0x2")
            .workerWallet("0x3")
            .build());

    private static final TeeSessionGenerationResponse SESSION = mock(TeeSessionGenerationResponse.class);

    private static final String SIGNATURE = "random-signature";

    @Mock
    private SignatureService signatureService;
    @Mock
    private SignerService signerService;
    @Mock
    private SmsClient smsClient;
    @Mock
    private SmsClientProvider smsClientProvider;
    @Mock
    private PlatformRegistryConfiguration registryConfiguration;

    @InjectMocks
    private SmsService smsService;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
        when(registryConfiguration.getSconeSms()).thenReturn(SCONE_SMS_URL);
        when(registryConfiguration.getGramineSms()).thenReturn(GRAMINE_SMS_URL);
        when(signatureService.createAuthorization("", CHAIN_TASK_ID, ""))
                .thenReturn(WorkerpoolAuthorization.builder().signature(new Signature(AUTHORIZATION)).build());
        doReturn(HASH).when(WORKERPOOL_AUTHORIZATION).getHash();
        when(smsClientProvider.getSmsClient(url)).thenReturn(smsClient);
    }

    // region isSmsClientReady
    static Stream<Arguments> validData() {
        List<String> supportedTeeTags =
                List.of(
                        TeeUtils.TEE_SCONE_ONLY_TAG,
                        TeeUtils.TEE_GRAMINE_ONLY_TAG);
        //Ensure all TeeEnclaveProvider are handled
        // (adding a new one would break assertion)
        Assertions.assertThat(supportedTeeTags)
                .hasSize(TeeFramework.values().length);
        return Stream.of(
                Arguments.of(supportedTeeTags.get(0), SCONE_SMS_URL),
                Arguments.of(supportedTeeTags.get(1), GRAMINE_SMS_URL)
        );
    }

    @ParameterizedTest
    @MethodSource("validData")
    void shouldGetVerifiedSmsUrl(String inputTag, String expectedSmsUrl) {
        when(smsClientProvider.getSmsClient(expectedSmsUrl)).thenReturn(smsClient);
        when(smsClient.getTeeFramework()).thenReturn(TeeUtils.getTeeFramework(inputTag));

        Assertions.assertThat(smsService.getVerifiedSmsUrl(CHAIN_TASK_ID, inputTag))
                .isEqualTo(Optional.of(expectedSmsUrl));

        verify(smsClientProvider).getSmsClient(expectedSmsUrl);
        verify(smsClient).getTeeFramework();
    }

    @Test
    void shouldNotGetVerifiedSmsUrlSinceCannotGetEnclaveProviderFromTag() {
        Assertions.assertThat(smsService.getVerifiedSmsUrl(CHAIN_TASK_ID, "0xabc"))
                .isEmpty();

        verify(smsClientProvider, times(0)).getSmsClient(anyString());
        verify(smsClient, times(0)).getTeeFramework();
    }

    @Test
    void shouldNotGetVerifiedSmsUrlSinceWrongTeeEnclaveProviderOnRemoteSms() {
        when(smsClientProvider.getSmsClient(GRAMINE_SMS_URL)).thenReturn(smsClient);
        when(smsClient.getTeeFramework()).thenReturn(TeeFramework.SCONE);

        Assertions.assertThat(smsService.getVerifiedSmsUrl(CHAIN_TASK_ID, TeeUtils.TEE_GRAMINE_ONLY_TAG))
                .isEmpty();

        verify(smsClientProvider).getSmsClient(GRAMINE_SMS_URL);
        verify(smsClient).getTeeFramework();
    }
    // endregion

    // region getEnclaveChallenge
    @Test
    void shouldGetEmptyAddressForStandardTask() {
        Assertions.assertThat(smsService.getEnclaveChallenge(CHAIN_TASK_ID, ""))
                .isEqualTo(Optional.of(BytesUtils.EMPTY_ADDRESS));

        verifyNoInteractions(smsClientProvider, smsClient);
    }

    @Test
    void shouldGetEnclaveChallengeForTeeTask() {
        String expected = "challenge";
        when(smsClientProvider.getSmsClient(url)).thenReturn(smsClient);
        when(smsClient.generateTeeChallenge(AUTHORIZATION, CHAIN_TASK_ID)).thenReturn(expected);

        Optional<String> received = smsService.getEnclaveChallenge(CHAIN_TASK_ID, url);
        verify(smsClient).generateTeeChallenge(AUTHORIZATION, CHAIN_TASK_ID);
        Assertions.assertThat(received)
                .isEqualTo(Optional.of(expected));
    }

    @Test
    void shouldNotGetEnclaveChallengeForTeeTaskWhenEmptySmsResponse() {
        when(smsClientProvider.getSmsClient(url)).thenReturn(smsClient);
        when(smsClient.generateTeeChallenge(AUTHORIZATION, CHAIN_TASK_ID)).thenReturn("");
        Optional<String> received = smsService.getEnclaveChallenge(CHAIN_TASK_ID, url);
        verify(smsClient).generateTeeChallenge(AUTHORIZATION, CHAIN_TASK_ID);
        Assertions.assertThat(received).isEmpty();
    }
    // endregion

    // region generateEnclaveChallenge
    @Test
    void shouldGenerateEnclaveChallenge() {
        final String expected = "challenge";

        when(smsClientProvider.getSmsClient(url)).thenReturn(smsClient);
        when(smsClient.generateTeeChallenge(AUTHORIZATION, CHAIN_TASK_ID)).thenReturn(expected);

        Optional<String> received = smsService.generateEnclaveChallenge(CHAIN_TASK_ID, url);
        Assertions.assertThat(received)
                .contains(expected);
    }

    @Test
    void shouldNotGenerateEnclaveChallengeSinceNoPublicKeyReturned() {
        when(smsClientProvider.getSmsClient(url)).thenReturn(smsClient);
        when(smsClient.generateTeeChallenge(AUTHORIZATION, CHAIN_TASK_ID)).thenReturn("");

        Optional<String> received = smsService.generateEnclaveChallenge(CHAIN_TASK_ID, url);
        Assertions.assertThat(received)
                .isEmpty();
    }

    @Test
    void shouldNotGenerateEnclaveChallengeSinceFeignException() {
        when(smsClientProvider.getSmsClient(url)).thenReturn(smsClient);
        when(smsClient.generateTeeChallenge(AUTHORIZATION, CHAIN_TASK_ID)).thenThrow(FeignException.GatewayTimeout.class);

        Optional<String> received = smsService.generateEnclaveChallenge(CHAIN_TASK_ID, url);
        Assertions.assertThat(received)
                .isEmpty();
    }

    @Test
    void shouldNotGenerateEnclaveChallengeSinceRuntimeException() {
        when(smsClientProvider.getSmsClient(url)).thenReturn(smsClient);
        when(smsClient.generateTeeChallenge(AUTHORIZATION, CHAIN_TASK_ID)).thenThrow(RuntimeException.class);

        Optional<String> received = smsService.generateEnclaveChallenge(CHAIN_TASK_ID, url);
        Assertions.assertThat(received)
                .isEmpty();
    }
    // endregion

    // region getSmsClient
    @Test
    void shouldGetSmsClient() {
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);
        assertThat(smsService.getSmsClient(CHAIN_TASK_ID)).isEqualTo(smsClient);
    }

    @Test
    void shouldNotAndGetSmsClientIfNoSmsUrlForTask() {
        // no SMS URL attached to taskId
        assertThrows(SmsClientCreationException.class, () -> smsService.getSmsClient(CHAIN_TASK_ID));
    }
    // endregion

    // region pushToken
    @Test
    void shouldPushToken() {
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);
        // send 404 NOT_FOUND on first call then 204 NO_CONTENT
        doThrow(FeignException.NotFound.class).doNothing().when(smsClient).isWeb2SecretSet(anyString(), anyString());
        when(signerService.signMessageHash(anyString())).thenReturn(new Signature(SIGNATURE));
        assertThat(smsService.pushToken(WORKERPOOL_AUTHORIZATION, TOKEN)).isTrue();
    }

    @Test
    void shouldUpdateToken() {
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);
        when(signerService.signMessageHash(anyString())).thenReturn(new Signature(SIGNATURE));
        assertThat(smsService.pushToken(WORKERPOOL_AUTHORIZATION, TOKEN)).isTrue();
    }

    @Test
    void shouldNotPushTokenOnEmptySignature() {
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);
        when(signerService.signMessageHash(anyString())).thenReturn(new Signature(""));
        assertThat(smsService.pushToken(WORKERPOOL_AUTHORIZATION, TOKEN)).isFalse();
    }

    @Test
    void shouldNotPushTokenOnFeignException() {
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);
        when(signerService.signMessageHash(anyString())).thenReturn(new Signature(SIGNATURE));
        doThrow(FeignException.NotFound.class).when(smsClient).isWeb2SecretSet(anyString(), anyString());
        when(smsClient.setWeb2Secret(SIGNATURE, WORKERPOOL_AUTHORIZATION.getWorkerWallet(),
                IEXEC_RESULT_IEXEC_IPFS_TOKEN, TOKEN)).thenThrow(FeignException.InternalServerError.class);
        assertThat(smsService.pushToken(WORKERPOOL_AUTHORIZATION, TOKEN)).isFalse();
    }

    @Test
    void shouldNotUpdateTokenOnFeignException() {
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);
        when(signerService.signMessageHash(anyString())).thenReturn(new Signature(SIGNATURE));
        when(smsClient.updateWeb2Secret(SIGNATURE, WORKERPOOL_AUTHORIZATION.getWorkerWallet(),
                IEXEC_RESULT_IEXEC_IPFS_TOKEN, TOKEN)).thenThrow(FeignException.InternalServerError.class);
        assertThat(smsService.pushToken(WORKERPOOL_AUTHORIZATION, TOKEN)).isFalse();
    }

    @Test
    void shouldNotFindTokenOnFeignException() {
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);
        when(signerService.signMessageHash(anyString())).thenReturn(new Signature(SIGNATURE));
        doThrow(FeignException.class).when(smsClient).isWeb2SecretSet(anyString(), anyString());
        assertThat(smsService.pushToken(WORKERPOOL_AUTHORIZATION, TOKEN)).isFalse();
    }
    // endregion

    // region createTeeSession
    @Test
    void shouldCreateTeeSession() throws TeeSessionGenerationException {
        Signature signatureStub = new Signature(SIGNATURE);
        when(signerService.signMessageHash(WORKERPOOL_AUTHORIZATION.getHash()))
                .thenReturn(signatureStub);
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);
        when(smsClient.generateTeeSession(signatureStub.getValue(), WORKERPOOL_AUTHORIZATION))
                .thenReturn(ApiResponseBody.<TeeSessionGenerationResponse, TeeSessionGenerationError>builder().data(SESSION).build());

        TeeSessionGenerationResponse returnedSessionId = smsService.createTeeSession(WORKERPOOL_AUTHORIZATION);
        assertThat(returnedSessionId).isEqualTo(SESSION);
        verify(smsClient).generateTeeSession(signatureStub.getValue(), WORKERPOOL_AUTHORIZATION);
    }

    @Test
    void shouldNotCreateTeeSessionOnFeignException() throws JsonProcessingException {
        final ObjectMapper mapper = new ObjectMapper();
        final byte[] responseBody = mapper.writeValueAsBytes(ApiResponseBody.<Void, TeeSessionGenerationError>builder().error(TeeSessionGenerationError.NO_SESSION_REQUEST).build());
        final Request request = Request.create(Request.HttpMethod.GET, "url",
                new HashMap<>(), null, new RequestTemplate());

        Signature signatureStub = new Signature(SIGNATURE);
        when(signerService.signMessageHash(WORKERPOOL_AUTHORIZATION.getHash()))
                .thenReturn(signatureStub);
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);
        when(smsClient.generateTeeSession(signatureStub.getValue(), WORKERPOOL_AUTHORIZATION))
                .thenThrow(new FeignException.InternalServerError("", request, responseBody, null));   //FIXME

        final TeeSessionGenerationException exception = Assertions.catchThrowableOfType(() -> smsService.createTeeSession(WORKERPOOL_AUTHORIZATION), TeeSessionGenerationException.class);
        assertThat(exception.getTeeSessionGenerationError()).isEqualTo(TeeSessionGenerationError.NO_SESSION_REQUEST);
        verify(smsClient).generateTeeSession(signatureStub.getValue(), WORKERPOOL_AUTHORIZATION);
    }
    // endregion

    // region purgeTask
    @Test
    void shouldPurgeTask() {
        // Attach sms URL to task
        smsService.attachSmsUrlToTask(CHAIN_TASK_ID, url);

        // Purging the task
        boolean purged = smsService.purgeTask(CHAIN_TASK_ID);
        assertTrue(purged);
    }

    @Test
    void shouldPurgeTaskEvenThoughTaskNeverAccessed() {
        assertTrue(smsService.purgeTask(CHAIN_TASK_ID));
    }
    // endregion

    // region purgeAllTasksData
    @Test
    void shouldPurgeAllTasksData() {
        assertDoesNotThrow(smsService::purgeAllTasksData);
    }
    // endregion
}
