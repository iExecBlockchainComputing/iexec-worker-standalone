/*
 * Copyright 2024 IEXEC BLOCKCHAIN TECH
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

package com.iexec.standalone.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iexec.common.result.ComputedFile;
import com.iexec.common.utils.FileHelper;
import com.iexec.common.utils.IexecFileHelper;
import com.iexec.commons.poco.chain.ChainTask;
import com.iexec.commons.poco.chain.ChainTaskStatus;
import com.iexec.commons.poco.chain.WorkerpoolAuthorization;
import com.iexec.commons.poco.security.Signature;
import com.iexec.commons.poco.task.TaskDescription;
import com.iexec.commons.poco.utils.BytesUtils;
import com.iexec.commons.poco.utils.HashUtils;
import com.iexec.standalone.chain.IexecHubService;
import com.iexec.standalone.chain.SignatureService;
import com.iexec.standalone.config.WorkerConfigurationService;
import com.iexec.standalone.task.Task;
import com.iexec.standalone.task.TaskService;
import com.iexec.resultproxy.api.ResultProxyClient;
import feign.FeignException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import java.io.File;
import java.util.Optional;

import static com.iexec.commons.poco.tee.TeeUtils.TEE_SCONE_ONLY_TAG;
import static com.iexec.commons.poco.utils.BytesUtils.EMPTY_ADDRESS;
import static com.iexec.standalone.task.TaskTestsUtils.CHAIN_TASK_ID;
import static com.iexec.standalone.task.TaskTestsUtils.getStubTask;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
class ResultServiceTests {

    public static final String RESULT_DIGEST = "0x0000000000000000000000000000000000000000000000000000000000000001";
    // 32 + 32 + 1 = 65 bytes
    public static final String ENCLAVE_SIGNATURE = "0x000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000b0c";
    private static final String IEXEC_WORKER_TMP_FOLDER = "./src/test/resources/tmp/test-worker/";
    private static final String CALLBACK = "0x0000000000000000000000000000000000000abc";

    @Mock
    private ResultProxyClient resultProxyClient;
    @Mock
    private SignatureService signatureService;
    @Mock
    private TaskService taskService;
    @Mock
    private IexecHubService iexecHubService;
    @Mock
    private WorkerConfigurationService workerConfigurationService;

    @InjectMocks
    private ResultService resultService;

    private Credentials enclaveCreds;
    private Credentials schedulerCreds;
    private Signature signature;
    private WorkerpoolAuthorization workerpoolAuthorization;

    @TempDir
    public File folderRule;
    private String tmp;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
        enclaveCreds = createCredentials();
        schedulerCreds = createCredentials();
        final String hash = HashUtils.concatenateAndHash(schedulerCreds.getAddress(), CHAIN_TASK_ID, enclaveCreds.getAddress());
        signature = new Signature(Sign.signPrefixedMessage(BytesUtils.stringToBytes(hash), schedulerCreds.getEcKeyPair()));
        workerpoolAuthorization = WorkerpoolAuthorization.builder()
                .workerWallet(schedulerCreds.getAddress())
                .chainTaskId(CHAIN_TASK_ID)
                .enclaveChallenge(enclaveCreds.getAddress())
                .signature(signature)
                .build();
        when(signatureService.getAddress()).thenReturn(schedulerCreds.getAddress());
        tmp = folderRule.getAbsolutePath();
    }

    @Test
    void shouldReturnFalseWhenEmptyToken() {
        final Task task = getStubTask();
        task.setEnclaveChallenge(EMPTY_ADDRESS);
        when(taskService.getTaskByChainTaskId(CHAIN_TASK_ID)).thenReturn(Optional.of(task));
        when(signatureService.createAuthorization(schedulerCreds.getAddress(), CHAIN_TASK_ID, EMPTY_ADDRESS))
                .thenReturn(workerpoolAuthorization);
        when(resultProxyClient.getJwt(anyString(), any())).thenReturn("");
        assertThat(resultService.isResultUploaded(CHAIN_TASK_ID)).isFalse();
        verify(signatureService).createAuthorization(schedulerCreds.getAddress(), CHAIN_TASK_ID, EMPTY_ADDRESS);
        verify(resultProxyClient).getJwt(signature.getValue(), workerpoolAuthorization);
    }

    @Test
    void shouldReturnFalseWhenUnauthorizedToUpload() {
        final Task task = getStubTask();
        task.setTag(TEE_SCONE_ONLY_TAG);
        task.setEnclaveChallenge(enclaveCreds.getAddress());
        when(taskService.getTaskByChainTaskId(CHAIN_TASK_ID)).thenReturn(Optional.of(task));
        when(signatureService.createAuthorization(schedulerCreds.getAddress(), CHAIN_TASK_ID, enclaveCreds.getAddress()))
                .thenReturn(workerpoolAuthorization);
        when(resultProxyClient.getJwt(anyString(), any())).thenReturn("token");
        when(resultProxyClient.isResultUploaded("token", CHAIN_TASK_ID)).thenThrow(FeignException.Unauthorized.class);
        assertThatThrownBy(() -> resultService.isResultUploaded(CHAIN_TASK_ID))
                .isInstanceOf(FeignException.Unauthorized.class);
        verify(signatureService).createAuthorization(schedulerCreds.getAddress(), CHAIN_TASK_ID, enclaveCreds.getAddress());
    }

    @Test
    void shouldReturnTrueWhenStandardTaskResultUploaded() {
        final Task task = getStubTask();
        task.setEnclaveChallenge(EMPTY_ADDRESS);
        when(taskService.getTaskByChainTaskId(CHAIN_TASK_ID)).thenReturn(Optional.of(task));
        when(signatureService.createAuthorization(schedulerCreds.getAddress(), CHAIN_TASK_ID, EMPTY_ADDRESS))
                .thenReturn(workerpoolAuthorization);
        when(resultProxyClient.getJwt(anyString(), any())).thenReturn("token");
        assertThat(resultService.isResultUploaded(CHAIN_TASK_ID)).isTrue();
        verify(signatureService).createAuthorization(schedulerCreds.getAddress(), CHAIN_TASK_ID, EMPTY_ADDRESS);
    }

    @Test
    void shouldReturnTrueWhenTeeTaskResultUploaded() {
        final Task task = getStubTask();
        task.setTag(TEE_SCONE_ONLY_TAG);
        task.setEnclaveChallenge(enclaveCreds.getAddress());
        when(taskService.getTaskByChainTaskId(CHAIN_TASK_ID)).thenReturn(Optional.of(task));
        when(signatureService.createAuthorization(schedulerCreds.getAddress(), CHAIN_TASK_ID, enclaveCreds.getAddress()))
                .thenReturn(workerpoolAuthorization);
        when(resultProxyClient.getJwt(anyString(), any())).thenReturn("token");
        assertThat(resultService.isResultUploaded(CHAIN_TASK_ID)).isTrue();
        verify(signatureService).createAuthorization(schedulerCreds.getAddress(), CHAIN_TASK_ID, enclaveCreds.getAddress());
    }

    @SneakyThrows
    private Credentials createCredentials() {
        return Credentials.create(Keys.createEcKeyPair());
    }

    //region writeComputedFile
    @Test
    void shouldWriteComputedFile() throws JsonProcessingException {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.ACTIVE).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        boolean isWritten = resultService.writeComputedFile(computedFile);

        Assertions.assertThat(isWritten).isTrue();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        ComputedFile writtenComputeFile = new ObjectMapper()
                .readValue(writtenComputeFileAsString, ComputedFile.class);
        Assertions.assertThat(writtenComputeFile).isEqualTo(computedFile);
    }

    @Test
    void shouldNotWriteComputedFileSinceNothingToWrite() {
        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.ACTIVE).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        boolean isWritten = resultService.writeComputedFile(null);

        Assertions.assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        Assertions.assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceNoChainTaskId() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId("")
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.ACTIVE).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        boolean isWritten = resultService.writeComputedFile(computedFile);

        Assertions.assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        Assertions.assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceNotActive() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.UNSET).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        boolean isWritten = resultService.writeComputedFile(computedFile);

        Assertions.assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        Assertions.assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceAlreadyWritten() throws JsonProcessingException {
        ComputedFile newComputedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest("0x0000000000000000000000000000000000000000000000000000000000000003")
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.ACTIVE).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        //mock old file already written
        resultService.writeComputedFile(ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build());
        //write new file
        boolean isWritten = resultService.writeComputedFile(newComputedFile);

        Assertions.assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        ComputedFile writtenComputeFile = new ObjectMapper()
                .readValue(writtenComputeFileAsString, ComputedFile.class);
        Assertions.assertThat(writtenComputeFile).isNotEqualTo(newComputedFile);
    }

    @Test
    void shouldNotWriteComputedFileSinceResultDigestIsEmpty() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest("")
                .enclaveSignature(BytesUtils.EMPTY_HEX_STRING_32)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.ACTIVE).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        boolean isWritten = resultService.writeComputedFile(computedFile);

        Assertions.assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        Assertions.assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceResultDigestIsInvalid() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest("0x01")
                .enclaveSignature(BytesUtils.EMPTY_HEX_STRING_32)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.ACTIVE).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        boolean isWritten = resultService.writeComputedFile(computedFile);

        Assertions.assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        Assertions.assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceSignatureIsRequiredAndEmpty() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature("")
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.ACTIVE).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        boolean isWritten = resultService.writeComputedFile(computedFile);

        Assertions.assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        Assertions.assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceSignatureIsRequiredAndInvalid() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature("0x01")
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.ACTIVE).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        boolean isWritten = resultService.writeComputedFile(computedFile);

        Assertions.assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        Assertions.assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceWriteFailed() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder()
                        .status(ChainTaskStatus.ACTIVE).build()));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(":somewhere");
        when(iexecHubService.isTeeTask(CHAIN_TASK_ID)).thenReturn(true);

        boolean isWritten = resultService.writeComputedFile(computedFile);

        Assertions.assertThat(isWritten).isFalse();
    }
    //endregion
}
