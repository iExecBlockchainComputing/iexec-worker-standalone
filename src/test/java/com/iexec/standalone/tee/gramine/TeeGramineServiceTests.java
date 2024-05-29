/*
 * Copyright 2022-2023 IEXEC BLOCKCHAIN TECH
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

package com.iexec.standalone.tee.gramine;

import com.iexec.commons.poco.task.TaskDescription;
import com.iexec.sms.api.SmsClientProvider;
import com.iexec.sms.api.TeeSessionGenerationResponse;
import com.iexec.standalone.sgx.SgxService;
import com.iexec.standalone.tee.TeeServicesPropertiesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

class TeeGramineServiceTests {
    private static final String SESSION_ID = "0x123_session_id";
    private static final String SPS_URL = "http://spsUrl";
    private static final TeeSessionGenerationResponse TEE_SESSION_GENERATION_RESPONSE = new TeeSessionGenerationResponse(
            SESSION_ID,
            SPS_URL
    );

    @Mock
    SgxService sgxService;
    @Mock
    SmsClientProvider smsClientProvider;
    @Mock
    TeeServicesPropertiesService teeServicesPropertiesService;

    @InjectMocks
    TeeGramineService teeGramineService;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
    }

    // region prepareTeeForTask
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0x123", "chainTaskId"})
    void shouldPrepareTeeForTask(String chainTaskId) {
        assertTrue(teeGramineService.prepareTeeForTask(chainTaskId));

        verifyNoInteractions(sgxService, smsClientProvider, teeServicesPropertiesService);
    }
    // endregion

    // region buildPreComputeDockerEnv
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0x123", "chainTaskId"})
    void shouldBuildPreComputeDockerEnv(String chainTaskId) {
        final TaskDescription taskDescription = TaskDescription.builder().chainTaskId(chainTaskId).build();
        final List<String> env = teeGramineService.buildPreComputeDockerEnv(taskDescription, TEE_SESSION_GENERATION_RESPONSE);

        assertEquals(2, env.size());
        assertTrue(env.containsAll(List.of(
                "sps=http://spsUrl",
                "session=0x123_session_id"
        )));
    }
    // endregion

    // region buildComputeDockerEnv
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0x123", "chainTaskId"})
    void shouldBuildComputeDockerEnv(String chainTaskId) {
        final TaskDescription taskDescription = TaskDescription.builder().chainTaskId(chainTaskId).build();
        final List<String> env = teeGramineService.buildComputeDockerEnv(taskDescription, TEE_SESSION_GENERATION_RESPONSE);

        assertEquals(2, env.size());
        assertTrue(env.containsAll(List.of(
                "sps=http://spsUrl",
                "session=0x123_session_id"
        )));
    }
    // endregion

    // region buildPostComputeDockerEnv
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0x123", "chainTaskId"})
    void shouldBuildPostComputeDockerEnv(String chainTaskId) {
        final TaskDescription taskDescription = TaskDescription.builder().chainTaskId(chainTaskId).build();
        final List<String> env = teeGramineService.buildPostComputeDockerEnv(taskDescription, TEE_SESSION_GENERATION_RESPONSE);

        assertEquals(2, env.size());
        assertTrue(env.containsAll(List.of(
                "sps=http://spsUrl",
                "session=0x123_session_id"
        )));
    }
    // endregion

    // region getAdditionalBindings
    @Test
    void shouldGetAdditionalBindings() {
        final Collection<String> bindings = teeGramineService.getAdditionalBindings();

        assertEquals(1, bindings.size());
        assertTrue(bindings.contains("/var/run/aesmd/aesm.socket:/var/run/aesmd/aesm.socket"));
    }
    // endregion
}