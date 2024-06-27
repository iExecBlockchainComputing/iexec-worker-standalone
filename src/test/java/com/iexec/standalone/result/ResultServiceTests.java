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
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.common.replicate.ReplicateStatusCause;
import com.iexec.common.result.ComputedFile;
import com.iexec.common.result.ResultModel;
import com.iexec.common.utils.FileHelper;
import com.iexec.common.utils.IexecFileHelper;
import com.iexec.commons.poco.chain.ChainDeal;
import com.iexec.commons.poco.chain.ChainTask;
import com.iexec.commons.poco.chain.ChainTaskStatus;
import com.iexec.commons.poco.chain.SignerService;
import com.iexec.commons.poco.chain.WorkerpoolAuthorization;
import com.iexec.commons.poco.eip712.EIP712Domain;
import com.iexec.commons.poco.eip712.entity.EIP712Challenge;
import com.iexec.commons.poco.security.Signature;
import com.iexec.commons.poco.task.TaskDescription;
import com.iexec.commons.poco.tee.TeeUtils;
import com.iexec.commons.poco.utils.BytesUtils;
import com.iexec.commons.poco.utils.HashUtils;
import com.iexec.standalone.chain.ChainConfig;
import com.iexec.standalone.chain.IexecHubService;
import com.iexec.standalone.chain.SignatureService;
import com.iexec.standalone.config.WorkerConfigurationService;
import com.iexec.standalone.task.Task;
import com.iexec.standalone.task.TaskService;
import com.iexec.resultproxy.api.ResultProxyClient;
import feign.FeignException;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

import static com.iexec.commons.poco.chain.DealParams.DROPBOX_RESULT_STORAGE_PROVIDER;
import static com.iexec.commons.poco.chain.DealParams.IPFS_RESULT_STORAGE_PROVIDER;
import static com.iexec.commons.poco.tee.TeeUtils.TEE_SCONE_ONLY_TAG;
import static com.iexec.commons.poco.utils.BytesUtils.EMPTY_ADDRESS;
import static com.iexec.standalone.task.TaskTestsUtils.getStubTask;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.times;

//@Slf4j
class ResultServiceTests {

    public static final String RESULT_DIGEST = "0x0000000000000000000000000000000000000000000000000000000000000001";
    // 32 + 32 + 1 = 65 bytes
    public static final String ENCLAVE_SIGNATURE = "0x000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000b0c";
    private static final String CHAIN_DEAL_ID = "0xe1f3b96f58be8d5d1958ac14b6a3e93497ad9985ea44ac8c79f613129fff79a0";
    private static final String CHAIN_TASK_ID = "0x7602291763f60943833c39a11b7e81f1f372f29b102bffad5b23c62bde0ef70e";
    private static final String CHAIN_TASK_ID_2 = "taskId2";
    private static final String IEXEC_WORKER_TMP_FOLDER = "src"
                                                        + File.separator + "test"
                                                        + File.separator + "resources"
                                                        + File.separator + "tmp"
                                                        + File.separator + "test-worker"
                                                        + File.separator;
    private static final String TMP_FILE = IEXEC_WORKER_TMP_FOLDER + "computed.zip";
    private static final String CALLBACK = "0x0000000000000000000000000000000000000abc";

    private static final String AUTHORIZATION = "0x4";
    private static final ChainTask CHAIN_TASK = ChainTask.builder()
            .dealid(CHAIN_DEAL_ID)
            .status(ChainTaskStatus.ACTIVE)
            .build();
    private static final ChainDeal CHAIN_DEAL = ChainDeal.builder()
            .chainDealId(CHAIN_DEAL_ID)
            .tag(TeeUtils.TEE_SCONE_ONLY_TAG)
            .build();
    private static final WorkerpoolAuthorization WORKERPOOL_AUTHORIZATION = WorkerpoolAuthorization.builder()
            .chainTaskId(CHAIN_TASK_ID)
            .enclaveChallenge("0x2")
            .workerWallet("0x3")
            .build();

    private final String pathSeparator = Pattern.compile("Window")
                                            .matcher(System.getProperty("os.name"))
                                            .find() ? File.separator + File.separator : File.separator;

    @TempDir
    public File folderRule;
    @Mock
    private IexecHubService iexecHubService;
    @Mock
    private ResultProxyClient resultProxyClient;
    @Mock
    private SignatureService signatureService;
    @Mock
    private TaskService taskService;
    @Mock
    private WorkerpoolAuthorization workerpoolAuthorization;
    @Mock
    private WorkerConfigurationService workerConfigurationService;
    @Mock
    private ChainConfig chainConfig;
    @Mock
    private SignerService signerService;

    @InjectMocks
    @Spy
    private ResultService resultService;

    private Credentials enclaveCreds;
    private Credentials schedulerCreds;
    private Signature signature;
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

    @Test
    void testIsResultUploaded() throws Exception {
        Method method = ResultService.class.getDeclaredMethod("isResultUploaded", FeignException.class, String.class);
        method.setAccessible(true);
        FeignException feignException = mock(FeignException.class);
        boolean result = (boolean) method.invoke(resultService, feignException, CHAIN_TASK_ID);
        assertThat(result).isFalse();
    }

    @Test
    void testGetResultFolderPath() {
        when(workerConfigurationService.getTaskIexecOutDir(CHAIN_TASK_ID)).thenReturn(IEXEC_WORKER_TMP_FOLDER);
        assertThat(resultService.getResultFolderPath(CHAIN_TASK_ID)).isEqualTo(IEXEC_WORKER_TMP_FOLDER);
    }

    @Test
    void testGetResultZipFilePath() {
        when(workerConfigurationService.getTaskIexecOutDir(CHAIN_TASK_ID)).thenReturn(IEXEC_WORKER_TMP_FOLDER);
        assertThat(resultService.getResultZipFilePath(CHAIN_TASK_ID)).isEqualTo(IEXEC_WORKER_TMP_FOLDER + ".zip");
    }

    @Test
    void testGetEncryptedResultFilePath() {
        when(workerConfigurationService.getTaskIexecOutDir(CHAIN_TASK_ID)).thenReturn(IEXEC_WORKER_TMP_FOLDER);
        assertThat(resultService.getEncryptedResultFilePath(CHAIN_TASK_ID)).isEqualTo(IEXEC_WORKER_TMP_FOLDER + ".zip");
    }

    @Test
    void shouldWriteErrorToIexecOut() {
        when(workerConfigurationService.getTaskIexecOutDir(CHAIN_TASK_ID))
                .thenReturn(tmp);

        boolean isErrorWritten = resultService.writeErrorToIexecOut(CHAIN_TASK_ID,
                ReplicateStatus.DATA_DOWNLOAD_FAILED,
                ReplicateStatusCause.INPUT_FILES_DOWNLOAD_FAILED);

        assertThat(isErrorWritten).isTrue();
        String errorFileAsString = FileHelper.readFile(tmp + "/"
                + ResultService.ERROR_FILENAME);
        assertThat(errorFileAsString).contains("[IEXEC] Error occurred while " +
                "computing the task");
        String computedFileAsString = FileHelper.readFile(tmp + File.separator
                + IexecFileHelper.COMPUTED_JSON);
        assertThat(computedFileAsString).isEqualTo("{" +
                "\"deterministic-output-path\":\"" + pathSeparator + "iexec_out" + pathSeparator + "error.txt\"," +
                "\"callback-data\":null," +
                "\"task-id\":null," +
                "\"result-digest\":null," +
                "\"enclave-signature\":null," +
                "\"error-message\":null" +
                "}");
    }

    @Test
    void shouldNotWriteErrorToIexecOutSince() {
        when(workerConfigurationService.getTaskIexecOutDir(CHAIN_TASK_ID))
                .thenReturn(File.separator + "null");

        boolean isErrorWritten = resultService.writeErrorToIexecOut(CHAIN_TASK_ID,
                ReplicateStatus.DATA_DOWNLOAD_FAILED,
                ReplicateStatusCause.INPUT_FILES_DOWNLOAD_FAILED);

        assertThat(isErrorWritten).isFalse();
    }

    @Test
    void testSaveResultInfo() {
        TaskDescription taskDescription = mock(TaskDescription.class);
        ComputedFile computedFile = mock(ComputedFile.class);

        when(taskDescription.getAppUri()).thenReturn("appUri");
        when(taskDescription.getCmd()).thenReturn("cmd");
        when(computedFile.getResultDigest()).thenReturn("digest");
        when(taskDescription.getDatasetUri()).thenReturn("datasetUri");

        resultService.saveResultInfo(CHAIN_TASK_ID, taskDescription, computedFile);

        ResultInfo resultInfo = resultService.getResultInfos(CHAIN_TASK_ID);
        assertThat(resultInfo).isNotNull();
        assertThat(resultInfo.getImage()).isEqualTo("appUri");
        assertThat(resultInfo.getCmd()).isEqualTo("cmd");
        assertThat(resultInfo.getDeterministHash()).isEqualTo("digest");
        assertThat(resultInfo.getDatasetUri()).isEqualTo("datasetUri");
    }

    @Test
    void testGetResultModelWithZip() throws IOException {
        ResultInfo resultInfo = mock(ResultInfo.class);
        when(resultInfo.getImage()).thenReturn("image");
        when(resultInfo.getCmd()).thenReturn("cmd");
        when(resultInfo.getDeterministHash()).thenReturn("hash");
        doReturn(resultInfo).when(resultService).getResultInfos(CHAIN_TASK_ID);
        doReturn(TMP_FILE).when(resultService).getResultZipFilePath(CHAIN_TASK_ID);
        ResultModel resultModel = resultService.getResultModelWithZip(CHAIN_TASK_ID);
        assertThat(resultModel).isNotNull();
        assertThat(resultModel.getChainTaskId()).isEqualTo(CHAIN_TASK_ID);
        assertThat(resultModel.getImage()).isEqualTo("image");
        assertThat(resultModel.getCmd()).isEqualTo("cmd");
        assertThat(resultModel.getZip()).isEqualTo(Files.readAllBytes(Paths.get(TMP_FILE)));
        assertThat(resultModel.getDeterministHash()).isEqualTo("hash");
    }

    @Test
    void testGetResultModelWithZip_FileNotFound() {
        ResultInfo resultInfo = mock(ResultInfo.class);
        when(resultInfo.getImage()).thenReturn("image");
        when(resultInfo.getCmd()).thenReturn("cmd");
        when(resultInfo.getDeterministHash()).thenReturn("hash");
        doReturn(resultInfo).when(resultService).getResultInfos(CHAIN_TASK_ID);
        ResultModel resultModel = resultService.getResultModelWithZip(CHAIN_TASK_ID);
        assertThat(resultModel).isNotNull();
        assertThat(resultModel.getChainTaskId()).isEqualTo(CHAIN_TASK_ID);
        assertThat(resultModel.getImage()).isEqualTo("image");
        assertThat(resultModel.getCmd()).isEqualTo("cmd");
        assertThat(resultModel.getZip()).isEqualTo(new byte[0]);
        assertThat(resultModel.getDeterministHash()).isEqualTo("hash");
    }

    @Test
    void testCleanUnusedResultFolders() {
        List<String> recoveredTasks = Arrays.asList("task1", "task2");
        doReturn(Arrays.asList("task1", "task3")).when(resultService).getAllChainTaskIdsInResultFolder();
        doReturn(true).when(resultService).purgeTask("task3");
        resultService.cleanUnusedResultFolders(recoveredTasks);
        verify(resultService, times(1)).purgeTask("task3");
    }

    @Test
    void testGetAllChainTaskIdsInResultFolder_Empty() {
        when(workerConfigurationService.getWorkerBaseDir()).thenReturn(tmp);
        List<String> result = resultService.getAllChainTaskIdsInResultFolder();

        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void testUploadResultAndGetLinkCallback() {
        WorkerpoolAuthorization wpAuthorization = mock(WorkerpoolAuthorization.class);
        TaskDescription taskDescription = mock(TaskDescription.class);
        when(wpAuthorization.getChainTaskId()).thenReturn(CHAIN_TASK_ID);
        when(iexecHubService.getTaskDescription(CHAIN_TASK_ID)).thenReturn(taskDescription);
        when(taskDescription.containsCallback()).thenReturn(true);
        String resultLink = resultService.uploadResultAndGetLink(wpAuthorization);
        assertThat(resultLink).isEqualTo("{ \"storage\": \"ethereum\", \"location\": \"null\" }");
    }

    @Test
    void testUploadResultAndGetLinkTeeTask() {
        WorkerpoolAuthorization wpAuthorization = mock(WorkerpoolAuthorization.class);
        TaskDescription taskDescription = mock(TaskDescription.class);
        when(wpAuthorization.getChainTaskId()).thenReturn(CHAIN_TASK_ID);
        when(iexecHubService.getTaskDescription(CHAIN_TASK_ID)).thenReturn(taskDescription);
        when(taskDescription.containsCallback()).thenReturn(false);
        when(taskDescription.isTeeTask()).thenReturn(true);
        when(taskDescription.getResultStorageProvider()).thenReturn("dropbox");
        String resultLink = resultService.uploadResultAndGetLink(wpAuthorization);
        assertThat(resultLink).isEqualTo("{ \"storage\": \"dropbox\", \"location\": \"/results/" + CHAIN_TASK_ID + "\" }");
    }

    @Test
    void testNotUploadResultAndGetLink() {
        WorkerpoolAuthorization wpAuthorization = mock(WorkerpoolAuthorization.class);
        TaskDescription taskDescription = mock(TaskDescription.class);
        when(wpAuthorization.getChainTaskId()).thenReturn(CHAIN_TASK_ID);
        when(iexecHubService.getTaskDescription(CHAIN_TASK_ID)).thenReturn(taskDescription);
        when(taskDescription.containsCallback()).thenReturn(false);
        when(taskDescription.isTeeTask()).thenReturn(false);
        when(taskDescription.getResultStorageProvider()).thenReturn("notIpfs");
        String resultLink = resultService.uploadResultAndGetLink(wpAuthorization);
        assertThat(resultLink).isEmpty();
    }

    @Test
    void shouldGetTeeWeb2ResultLinkSinceIpfs() {
        String storage = IPFS_RESULT_STORAGE_PROVIDER;
        String ipfsHash = "QmcipfsHash";

        when(iexecHubService.getTaskDescription(CHAIN_TASK_ID)).thenReturn(
                TaskDescription.builder().resultStorageProvider(storage).build());
        when(resultProxyClient.getIpfsHashForTask(CHAIN_TASK_ID)).thenReturn(ipfsHash);

        String resultLink = resultService.getWeb2ResultLink(CHAIN_TASK_ID);

        assertThat(resultLink).isEqualTo(resultService.buildResultLink(storage, "/ipfs/" + ipfsHash));
    }

    @Test
    void shouldGetTeeWeb2ResultLinkSinceDropbox() {
        String storage = DROPBOX_RESULT_STORAGE_PROVIDER;

        when(iexecHubService.getTaskDescription(CHAIN_TASK_ID)).thenReturn(
                TaskDescription.builder().resultStorageProvider(storage).build());

        String resultLink = resultService.getWeb2ResultLink(CHAIN_TASK_ID);

        assertThat(resultLink).isEqualTo(resultService.buildResultLink(storage, "/results/" + CHAIN_TASK_ID));
    }

    @Test
    void shouldNotGetTeeWeb2ResultLinkSinceBadStorage() {
        String storage = "some-unsupported-third-party-storage";

        when(iexecHubService.getTaskDescription(CHAIN_TASK_ID)).thenReturn(
                TaskDescription.builder().resultStorageProvider(storage).build());

        String resultLink = resultService.getWeb2ResultLink(CHAIN_TASK_ID);

        assertThat(resultLink).isEmpty();
    }


    @Test
    void shouldNotGetTeeWeb2ResultLinkSinceNoTask() {
        when(iexecHubService.getTaskDescription(CHAIN_TASK_ID)).thenReturn(null);

        String resultLink = resultService.getWeb2ResultLink(CHAIN_TASK_ID);

        assertThat(resultLink).isEmpty();
    }

    //region getComputedFile
    @Test
    void shouldGetComputedFileWithWeb2ResultDigestSinceFile() {
        String chainTaskId = "deterministic-output-file";

        when(workerConfigurationService.getTaskIexecOutDir(chainTaskId))
                .thenReturn(IEXEC_WORKER_TMP_FOLDER + chainTaskId +
                        File.separator + "output" + File.separator + "iexec_out");
        when(workerConfigurationService.getTaskOutputDir(chainTaskId))
                .thenReturn(IEXEC_WORKER_TMP_FOLDER + chainTaskId +
                        File.separator + "output");
        when(iexecHubService.getTaskDescription(chainTaskId)).thenReturn(
                TaskDescription.builder().callback(BytesUtils.EMPTY_ADDRESS).build());

        ComputedFile computedFile =
                resultService.getComputedFile(chainTaskId);
        String hash = computedFile.getResultDigest();
        // should be equal to the content of the file since it is a byte32
        assertThat(hash).isEqualTo(
                "0x09b727883db89fa3b3504f83e0c67d04a0d4fc35a9670cc4517c49d2a27ad171");
    }

    @Test
    void shouldGetComputedFileWithWeb2ResultDigestSinceFileTree() {
        String chainTaskId = "deterministic-output-directory";

        when(workerConfigurationService.getTaskIexecOutDir(chainTaskId))
                .thenReturn(IEXEC_WORKER_TMP_FOLDER + chainTaskId +
                        "/output/iexec_out");
        when(workerConfigurationService.getTaskOutputDir(chainTaskId))
                .thenReturn(IEXEC_WORKER_TMP_FOLDER + chainTaskId +
                        "/output");
        when(iexecHubService.getTaskDescription(chainTaskId)).thenReturn(
                TaskDescription.builder().callback(BytesUtils.EMPTY_ADDRESS).build());

        ComputedFile computedFile =
                resultService.getComputedFile(chainTaskId);
        String hash = computedFile.getResultDigest();
        // should be equal to the content of the file since it is a byte32
        assertThat(hash).isEqualTo(
                "0xc6114778cc5c33db5fbbd4d0f9be116ed0232961045341714aba5a72d3ef7402");
    }

    @Test
    void shouldGetComputedFileWithWeb3ResultDigest() {
        String chainTaskId = "callback-directory";

        when(workerConfigurationService.getTaskOutputDir(chainTaskId))
                .thenReturn(IEXEC_WORKER_TMP_FOLDER + chainTaskId + "/output");
        when(iexecHubService.getTaskDescription(chainTaskId)).thenReturn(
                TaskDescription.builder().callback(CALLBACK).build());

        ComputedFile computedFile =
                resultService.getComputedFile(chainTaskId);
        String hash = computedFile.getResultDigest();
        // should be equal to the content of the file since it is a byte32
        assertThat(hash).isEqualTo(
                "0xb10e2d527612073b26eecdfd717e6a320cf44b4afac2b0732d9fcbe2b7fa0cf6");
    }

    @Test
    void shouldNotGetComputedFileWhenFileNotFound() {
        String chainTaskId = "does-not-exist";
        when(workerConfigurationService.getTaskOutputDir(chainTaskId))
                .thenReturn(IEXEC_WORKER_TMP_FOLDER + chainTaskId + "/output");
        ComputedFile computedFile = resultService.getComputedFile(chainTaskId);
        assertThat(computedFile).isNull();
    }

    @Test
    void shouldNotComputeWeb2ResultDigestWhenFileTreeNotFound() {
        String chainTaskId = "deterministic-output-directory-missing";
        when(workerConfigurationService.getTaskOutputDir(chainTaskId))
                .thenReturn(IEXEC_WORKER_TMP_FOLDER + chainTaskId + File.separator + "output");
        when(iexecHubService.getTaskDescription(chainTaskId)).thenReturn(
                TaskDescription.builder().callback(BytesUtils.EMPTY_ADDRESS).build());
        ComputedFile computedFile = resultService.getComputedFile(chainTaskId);
        assertThat(computedFile).isNull();
        computedFile = resultService.readComputedFile(chainTaskId);
        assertThat(computedFile).isNotNull();
        String resultDigest = resultService.computeResultDigest(computedFile);
        assertThat(resultDigest).isEmpty();
    }

    @Test
    void shouldNotComputeWeb3ResultDigestWhenNoCallbackData() {
        String chainTaskId = "callback-no-data";
        when(workerConfigurationService.getTaskOutputDir(chainTaskId))
                .thenReturn(IEXEC_WORKER_TMP_FOLDER + chainTaskId + "/output");
        when(iexecHubService.getTaskDescription(chainTaskId)).thenReturn(
                TaskDescription.builder().callback(CALLBACK).build());
        ComputedFile computedFile = resultService.getComputedFile(chainTaskId);
        assertThat(computedFile).isNull();
        computedFile = resultService.readComputedFile(chainTaskId);
        assertThat(computedFile).isNotNull();
        String resultDigest = resultService.computeResultDigest(computedFile);
        assertThat(resultDigest).isEmpty();
    }
    //endregion
  
    //region writeComputedFile
    @Test
    void shouldWriteComputedFile() throws JsonProcessingException {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(CHAIN_TASK));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.getChainDeal(CHAIN_DEAL_ID)).thenReturn(Optional.of(CHAIN_DEAL));

        boolean isWritten = resultService.writeComputedFile(computedFile);

        assertThat(isWritten).isTrue();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        ComputedFile writtenComputeFile = new ObjectMapper()
                .readValue(writtenComputeFileAsString, ComputedFile.class);
        assertThat(writtenComputeFile).isEqualTo(computedFile);
    }

    @Test
    void shouldNotWriteComputedFileSinceNothingToWrite() {
        boolean isWritten = resultService.writeComputedFile(null);

        assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        assertThat(writtenComputeFileAsString).isEmpty();
        verifyNoInteractions(iexecHubService, workerConfigurationService);

    }

    @Test
    void shouldNotWriteComputedFileSinceNoChainTaskId() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId("")
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        boolean isWritten = resultService.writeComputedFile(computedFile);

        assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        assertThat(writtenComputeFileAsString).isEmpty();
        verifyNoInteractions(iexecHubService, workerConfigurationService);
    }

    @Test
    void shouldNotWriteComputedFileSinceNotActive() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(ChainTask.builder().status(ChainTaskStatus.UNSET).build()));

        boolean isWritten = resultService.writeComputedFile(computedFile);

        assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        assertThat(writtenComputeFileAsString).isEmpty();
        verifyNoInteractions(workerConfigurationService);
    }

    @Test
    void shouldNotWriteComputedFileSinceAlreadyWritten() throws JsonProcessingException {
        ComputedFile newComputedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest("0x0000000000000000000000000000000000000000000000000000000000000003")
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(CHAIN_TASK));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.getChainDeal(CHAIN_DEAL_ID)).thenReturn(Optional.of(CHAIN_DEAL));

        //mock old file already written
        resultService.writeComputedFile(ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build());
        //write new file
        boolean isWritten = resultService.writeComputedFile(newComputedFile);

        assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        ComputedFile writtenComputeFile = new ObjectMapper()
                .readValue(writtenComputeFileAsString, ComputedFile.class);
        assertThat(writtenComputeFile).isNotEqualTo(newComputedFile);
    }

    @Test
    void shouldNotWriteComputedFileSinceResultDigestIsEmpty() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest("")
                .enclaveSignature(BytesUtils.EMPTY_HEX_STRING_32)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(CHAIN_TASK));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.getChainDeal(CHAIN_DEAL_ID)).thenReturn(Optional.of(CHAIN_DEAL));

        boolean isWritten = resultService.writeComputedFile(computedFile);

        assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceResultDigestIsInvalid() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest("0x01")
                .enclaveSignature(BytesUtils.EMPTY_HEX_STRING_32)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(CHAIN_TASK));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.getChainDeal(CHAIN_DEAL_ID)).thenReturn(Optional.of(CHAIN_DEAL));

        boolean isWritten = resultService.writeComputedFile(computedFile);

        assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceNotTeeTask() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(BytesUtils.EMPTY_HEX_STRING_32)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(CHAIN_TASK));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.getChainDeal(CHAIN_DEAL_ID)).thenReturn(Optional.of(ChainDeal.builder()
                .chainDealId(CHAIN_DEAL_ID)
                .tag(BytesUtils.EMPTY_HEX_STRING_32)
                .build()));

        boolean isWritten = resultService.writeComputedFile(computedFile);

        assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceSignatureIsRequiredAndEmpty() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature("")
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(CHAIN_TASK));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.getChainDeal(CHAIN_DEAL_ID)).thenReturn(Optional.of(CHAIN_DEAL));

        boolean isWritten = resultService.writeComputedFile(computedFile);

        assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceSignatureIsRequiredAndInvalid() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature("0x01")
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(CHAIN_TASK));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(iexecHubService.getChainDeal(CHAIN_DEAL_ID)).thenReturn(Optional.of(CHAIN_DEAL));

        boolean isWritten = resultService.writeComputedFile(computedFile);

        assertThat(isWritten).isFalse();
        String writtenComputeFileAsString = FileHelper.readFile(tmp +
                IexecFileHelper.SLASH_COMPUTED_JSON);
        assertThat(writtenComputeFileAsString).isEmpty();
    }

    @Test
    void shouldNotWriteComputedFileSinceWriteFailed() {
        ComputedFile computedFile = ComputedFile.builder()
                .taskId(CHAIN_TASK_ID)
                .resultDigest(RESULT_DIGEST)
                .enclaveSignature(ENCLAVE_SIGNATURE)
                .build();

        when(iexecHubService.getChainTask(CHAIN_TASK_ID))
                .thenReturn(Optional.of(CHAIN_TASK));
        when(workerConfigurationService.getTaskOutputDir(CHAIN_TASK_ID))
                .thenReturn("somewhere");
        when(iexecHubService.getChainDeal(CHAIN_DEAL_ID)).thenReturn(Optional.of(CHAIN_DEAL));

        boolean isWritten = resultService.writeComputedFile(computedFile);

        assertThat(isWritten).isFalse();
    }
    //endregion

    //region getIexecUploadToken
    @Test
    void shouldGetIexecUploadTokenFromWorkerpoolAuthorization() {
        final String uploadToken = "uploadToken";
        when(signerService.signMessageHash(anyString())).thenReturn(new Signature(AUTHORIZATION));
        when(resultProxyClient.getJwt(AUTHORIZATION, WORKERPOOL_AUTHORIZATION)).thenReturn(uploadToken);
        assertThat(resultService.getIexecUploadToken(WORKERPOOL_AUTHORIZATION)).isEqualTo(uploadToken);
        verify(signerService).signMessageHash(anyString());
        verify(resultProxyClient).getJwt(AUTHORIZATION, WORKERPOOL_AUTHORIZATION);
    }

    @Test
    void shouldNotGetIexecUploadTokenWorkerpoolAuthorizationSinceSigningReturnsEmpty() {
        when(signerService.signMessageHash(anyString())).thenReturn(new Signature(""));
        assertThat(resultService.getIexecUploadToken(WORKERPOOL_AUTHORIZATION)).isEmpty();
        verify(signerService).signMessageHash(anyString());
        verifyNoInteractions(resultProxyClient);
    }

    @Test
    void shouldNotGetIexecUploadTokenFromWorkerpoolAuthorizationSinceFeignException() {
        when(signerService.signMessageHash(anyString())).thenReturn(new Signature(AUTHORIZATION));
        when(resultProxyClient.getJwt(AUTHORIZATION, WORKERPOOL_AUTHORIZATION)).thenThrow(FeignException.Unauthorized.class);
        assertThat(resultService.getIexecUploadToken(WORKERPOOL_AUTHORIZATION)).isEmpty();
    }

    @Test
    void shouldGetIexecUploadToken() {
        final int chainId = 1;
        final EIP712Domain domain = spy(new EIP712Domain("iExec Result Repository", "1", chainId, null));
        final EIP712Challenge challenge = spy(new EIP712Challenge(domain, null));
        final String signedChallenge = "signedChallenge";
        final String uploadToken = "uploadToken";

        when(chainConfig.getChainId()).thenReturn(chainId);
        when(resultProxyClient.getChallenge(chainId)).thenReturn(challenge);
        when(signerService.signEIP712EntityAndBuildToken(challenge)).thenReturn(signedChallenge);
        when(resultProxyClient.login(chainId, signedChallenge)).thenReturn(uploadToken);

        assertThat(resultService.getIexecUploadToken()).isEqualTo(uploadToken);

        verify(chainConfig, times(1)).getChainId();
        verify(resultProxyClient, times(1)).getChallenge(chainId);
        verify(signerService, times(1)).signEIP712EntityAndBuildToken(challenge);
        verify(resultProxyClient, times(1)).login(chainId, signedChallenge);

        verify(challenge, times(1)).getDomain();
        verify(domain, times(1)).getName();
        verify(domain, times(1)).getChainId();
    }

    @Test
    void shouldNotGetIexecUploadTokenSinceNoResultChallenge() {
        final int chainId = 1;

        when(chainConfig.getChainId()).thenReturn(chainId);
        when(resultProxyClient.getChallenge(chainId)).thenReturn(null);

        assertThat(resultService.getIexecUploadToken()).isEmpty();

        verify(chainConfig, times(1)).getChainId();
        verify(resultProxyClient, times(1)).getChallenge(chainId);
        verify(signerService, never()).signEIP712EntityAndBuildToken(any());
        verify(resultProxyClient, never()).login(anyInt(), any());
    }

    @Test
    void shouldNotGetIexecUploadTokenSinceGetResultChallengeThrows() {
        final int chainId = 1;

        when(chainConfig.getChainId()).thenReturn(chainId);
        when(resultProxyClient.getChallenge(chainId)).thenThrow(RuntimeException.class);

        assertThat(resultService.getIexecUploadToken()).isEmpty();

        verify(chainConfig, times(1)).getChainId();
        verify(resultProxyClient, times(1)).getChallenge(chainId);
        verify(signerService, never()).signEIP712EntityAndBuildToken(any());
        verify(resultProxyClient, never()).login(anyInt(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "wrong name"})
    void shouldNotGetIexecUploadTokenSinceWrongDomainName(String wrongDomainName) {
        final int chainId = 1;
        final EIP712Domain domain = spy(new EIP712Domain(wrongDomainName, "1", chainId, null));
        final EIP712Challenge challenge = spy(new EIP712Challenge(domain, null));

        when(chainConfig.getChainId()).thenReturn(chainId);
        when(resultProxyClient.getChallenge(chainId)).thenReturn(challenge);

        assertThat(resultService.getIexecUploadToken()).isEmpty();

        verify(chainConfig, times(1)).getChainId();
        verify(resultProxyClient, times(1)).getChallenge(chainId);
        verify(signerService, never()).signEIP712EntityAndBuildToken(any());
        verify(resultProxyClient, never()).login(anyInt(), any());

        verify(challenge, times(1)).getDomain();
        verify(domain, times(1)).getName();
        verify(domain, times(0)).getChainId();
    }

    @Test
    void shouldNotGetIexecUploadTokenSinceWrongChainId() {
        final int expectedChainId = 1;
        final long wrongChainId = 42;
        final EIP712Domain domain = spy(new EIP712Domain("iExec Result Repository", "1", wrongChainId, null));
        final EIP712Challenge challenge = spy(new EIP712Challenge(domain, null));

        when(chainConfig.getChainId()).thenReturn(expectedChainId);
        when(resultProxyClient.getChallenge(expectedChainId)).thenReturn(challenge);

        assertThat(resultService.getIexecUploadToken()).isEmpty();

        verify(chainConfig, times(1)).getChainId();
        verify(resultProxyClient, times(1)).getChallenge(expectedChainId);
        verify(signerService, never()).signEIP712EntityAndBuildToken(any());
        verify(resultProxyClient, never()).login(anyInt(), any());

        verify(challenge, times(1)).getDomain();
        verify(domain, times(1)).getName();
        verify(domain, times(1)).getChainId();
    }

    @Test
    void shouldNotGetIexecUploadTokenSinceSigningReturnsEmpty() {
        final int chainId = 1;
        final EIP712Domain domain = spy(new EIP712Domain("iExec Result Repository", "1", chainId, null));
        final EIP712Challenge challenge = spy(new EIP712Challenge(domain, null));

        when(chainConfig.getChainId()).thenReturn(chainId);
        when(resultProxyClient.getChallenge(chainId)).thenReturn(challenge);
        when(signerService.signEIP712EntityAndBuildToken(challenge)).thenReturn("");

        assertThat(resultService.getIexecUploadToken()).isEmpty();

        verify(chainConfig, times(1)).getChainId();
        verify(resultProxyClient, times(1)).getChallenge(chainId);
        verify(signerService, times(1)).signEIP712EntityAndBuildToken(challenge);
        verify(resultProxyClient, never()).login(anyInt(), any());

        verify(challenge, times(1)).getDomain();
        verify(domain, times(1)).getName();
        verify(domain, times(1)).getChainId();
    }
    //endregion

    // region purgeTask
    @Test
    void shouldPurgeTask() {
        when(workerConfigurationService.getTaskBaseDir(CHAIN_TASK_ID))
                .thenReturn(tmp);

        assertThat(resultService.purgeTask(CHAIN_TASK_ID))
                .isTrue();
    }

    @Test
    void shouldNotPurgeTaskSinceDirDeletionFailed() {
        when(workerConfigurationService.getTaskBaseDir(CHAIN_TASK_ID))
                .thenReturn(tmp);

        try (MockedStatic<FileHelper> fileHelper = Mockito.mockStatic(FileHelper.class)) {
            fileHelper.when(() -> FileHelper.deleteFolder(tmp))
                    .thenReturn(false);

            assertThat(resultService.purgeTask(CHAIN_TASK_ID))
                    .isFalse();
        }
    }
    // endregion

    // region purgeAllTasksData
    @Test
    void shouldPurgeAllTasksData() {
        final Map<String, ResultInfo> resultInfoMap = new HashMap<>(Map.of(
                CHAIN_TASK_ID, ResultInfo.builder().build(),
                CHAIN_TASK_ID_2, ResultInfo.builder().build()
        ));
        ReflectionTestUtils.setField(resultService, "resultInfoMap", resultInfoMap);

        when(workerConfigurationService.getTaskBaseDir(CHAIN_TASK_ID))
                .thenReturn(tmp);
        when(workerConfigurationService.getTaskBaseDir(CHAIN_TASK_ID_2))
                .thenReturn(tmp);

        resultService.purgeAllTasksData();

        assertThat(resultInfoMap.isEmpty()).isTrue();
        assertThat(new File(tmp)).doesNotExist();
    }
    // endregion
}
