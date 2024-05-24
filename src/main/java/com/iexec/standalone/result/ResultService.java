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

package com.iexec.standalone.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iexec.common.lifecycle.purge.ExpiringTaskMapFactory;
import com.iexec.common.lifecycle.purge.Purgeable;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.common.replicate.ReplicateStatusCause;
import com.iexec.common.result.ComputedFile;
import com.iexec.common.result.ResultModel;
import com.iexec.common.utils.FileHelper;
import com.iexec.common.utils.IexecFileHelper;
import com.iexec.common.worker.result.ResultUtils;
import com.iexec.commons.poco.chain.ChainTask;
import com.iexec.commons.poco.chain.ChainTaskStatus;
import com.iexec.commons.poco.chain.WorkerpoolAuthorization;
import com.iexec.commons.poco.eip712.EIP712Domain;
import com.iexec.commons.poco.eip712.entity.EIP712Challenge;
import com.iexec.commons.poco.task.TaskDescription;
import com.iexec.commons.poco.utils.BytesUtils;
import com.iexec.standalone.chain.ChainConfig;
import com.iexec.standalone.chain.CredentialsService;
import com.iexec.standalone.chain.IexecHubService;
import com.iexec.standalone.chain.SignatureService;
import com.iexec.standalone.config.WorkerConfigurationService;
import com.iexec.standalone.task.Task;
import com.iexec.standalone.task.TaskService;
import com.iexec.resultproxy.api.ResultProxyClient;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static com.iexec.commons.poco.chain.DealParams.DROPBOX_RESULT_STORAGE_PROVIDER;
import static com.iexec.commons.poco.chain.DealParams.IPFS_RESULT_STORAGE_PROVIDER;
import static com.iexec.commons.poco.utils.BytesUtils.EMPTY_ADDRESS;
import static com.iexec.commons.poco.utils.BytesUtils.stringToBytes;

@Slf4j
@Service
public class ResultService implements Purgeable {

    public static final String ERROR_FILENAME = "error.txt";
    public static final String WRITE_COMPUTED_FILE_LOG_ARGS = " [chainTaskId:{}, computedFile:{}]";

    private final ResultProxyClient resultProxyClient;
    private final SignatureService signatureService;
    private final TaskService taskService;
    private final WorkerConfigurationService workerConfigService;
    private final ChainConfig chainConfig;
    private final CredentialsService credentialsService;
    private final IexecHubService iexecHubService;
    private final Map<String, ResultInfo> resultInfoMap
            = ExpiringTaskMapFactory.getExpiringTaskMap();

    public ResultService(final ResultProxyClient resultProxyClient,
                         final SignatureService signatureService,
                         final TaskService taskService,
                         WorkerConfigurationService workerConfigService,
                         ChainConfig chainConfig,
                         CredentialsService credentialsService,
                         IexecHubService iexecHubService) {
        this.resultProxyClient = resultProxyClient;
        this.signatureService = signatureService;
        this.taskService = taskService;
        this.workerConfigService = workerConfigService;
        this.chainConfig = chainConfig;
        this.credentialsService = credentialsService;
        this.iexecHubService = iexecHubService;
    }

    @Retryable(value = FeignException.class)
    public boolean isResultUploaded(final String chainTaskId) {
        final String enclaveChallenge = taskService.getTaskByChainTaskId(chainTaskId).map(Task::getEnclaveChallenge).orElse(EMPTY_ADDRESS);
        final WorkerpoolAuthorization workerpoolAuthorization = signatureService.createAuthorization(signatureService.getAddress(), chainTaskId, enclaveChallenge);
        final String resultProxyToken = resultProxyClient.getJwt(workerpoolAuthorization.getSignature().getValue(), workerpoolAuthorization);
        if (resultProxyToken.isEmpty()) {
            log.error("isResultUploaded failed (getResultProxyToken) [chainTaskId:{}]", chainTaskId);
            return false;
        }

        resultProxyClient.isResultUploaded(resultProxyToken, chainTaskId);
        return true;
    }

    @Recover
    private boolean isResultUploaded(final FeignException e, final String chainTaskId) {
        log.error("Cannot check isResultUploaded after multiple retries [chainTaskId:{}]", chainTaskId, e);
        return false;
    }

    public ResultInfo getResultInfos(String chainTaskId) {
        return resultInfoMap.get(chainTaskId);
    }

    public String getResultFolderPath(String chainTaskId) {
        return workerConfigService.getTaskIexecOutDir(chainTaskId);
    }

    public boolean isResultFolderFound(String chainTaskId) {
        return new File(getResultFolderPath(chainTaskId)).exists();
    }

    public String getResultZipFilePath(String chainTaskId) {
        return getResultFolderPath(chainTaskId) + ".zip";
    }

    public String getEncryptedResultFilePath(String chainTaskId) {
        return getResultFolderPath(chainTaskId) + ".zip";
    }

    public boolean isResultZipFound(String chainTaskId) {
        return new File(getResultZipFilePath(chainTaskId)).exists();
    }

    public boolean isEncryptedResultZipFound(String chainTaskId) {
        return new File(getEncryptedResultFilePath(chainTaskId)).exists();
    }

    public boolean writeErrorToIexecOut(String chainTaskId, ReplicateStatus errorStatus,
                                        ReplicateStatusCause errorCause) {
        String errorContent = String.format("[IEXEC] Error occurred while computing"
                + " the task [error:%s, cause:%s]", errorStatus, errorCause);
        ComputedFile computedFile = ComputedFile.builder()
                .deterministicOutputPath(IexecFileHelper.SLASH_IEXEC_OUT +
                        File.separator + ERROR_FILENAME)
                .build();
        String computedFileJsonAsString;
        try {
            computedFileJsonAsString = new ObjectMapper().writeValueAsString(computedFile);
        } catch (JsonProcessingException e) {
            log.error("Failed to prepare computed file [chainTaskId:{}]", chainTaskId, e);
            return false;
        }
        String hostIexecOutSlash = workerConfigService.getTaskIexecOutDir(chainTaskId)
                + File.separator;
        return FileHelper.createFolder(hostIexecOutSlash)
                && FileHelper.writeFile(hostIexecOutSlash + ERROR_FILENAME,
                errorContent.getBytes())
                && FileHelper.writeFile(hostIexecOutSlash
                + IexecFileHelper.COMPUTED_JSON, computedFileJsonAsString.getBytes());
    }

    public void saveResultInfo(String chainTaskId, TaskDescription taskDescription, ComputedFile computedFile) {
        ResultInfo resultInfo = ResultInfo.builder()
                .image(taskDescription.getAppUri())
                .cmd(taskDescription.getCmd())
                .deterministHash(computedFile != null ? computedFile.getResultDigest() : "")
                .datasetUri(taskDescription.getDatasetUri())
                .build();

        resultInfoMap.put(chainTaskId, resultInfo);
    }

    public ResultModel getResultModelWithZip(String chainTaskId) {
        ResultInfo resultInfo = getResultInfos(chainTaskId);
        byte[] zipResultAsBytes = new byte[0];
        String zipLocation = getResultZipFilePath(chainTaskId);
        try {
            zipResultAsBytes = Files.readAllBytes(Paths.get(zipLocation));
        } catch (IOException e) {
            log.error("Failed to get zip result [chainTaskId:{}, zipLocation:{}]", chainTaskId, zipLocation, e);
        }

        return ResultModel.builder()
                .chainTaskId(chainTaskId)
                .image(resultInfo.getImage())
                .cmd(resultInfo.getCmd())
                .zip(zipResultAsBytes)
                .deterministHash(resultInfo.getDeterministHash())
                .build();
    }

    public void cleanUnusedResultFolders(List<String> recoveredTasks) {
        for (String chainTaskId : getAllChainTaskIdsInResultFolder()) {
            if (!recoveredTasks.contains(chainTaskId)) {
                purgeTask(chainTaskId);
            }
        }
    }

    public List<String> getAllChainTaskIdsInResultFolder() {
        File resultsFolder = new File(workerConfigService.getWorkerBaseDir());
        String[] chainTaskIdFolders = resultsFolder.list((current, name) -> new File(current, name).isDirectory());

        if (chainTaskIdFolders == null || chainTaskIdFolders.length == 0) {
            return Collections.emptyList();
        }
        return Arrays.asList(chainTaskIdFolders);
    }

    /*
     * For Cloud computing basic
     * - upload from worker requested
     * - send link from worker requested
     *
     * But for other work-flows
     * - link could be retrieved from core before finalize
     *
     * */
    public String uploadResultAndGetLink(WorkerpoolAuthorization workerpoolAuthorization) {
        final String chainTaskId = workerpoolAuthorization.getChainTaskId();
        TaskDescription task = iexecHubService.getTaskDescription(chainTaskId);

        // Offchain computing - basic & tee
        if (task.containsCallback()) {
            log.info("Web3 storage, no need to upload [chainTaskId:{}]", chainTaskId);
            return getWeb3ResultLink(chainTaskId);
        }

        // Cloud computing - tee
        if (task.isTeeTask()) {//result is already uploaded
            log.info("Web2 storage, already uploaded (with tee) [chainTaskId:{}]", chainTaskId);
            return getWeb2ResultLink(chainTaskId);
        }

        // Cloud computing - basic
        boolean isIpfsStorageRequest = task.getResultStorageProvider().equals(IPFS_RESULT_STORAGE_PROVIDER);
        boolean isUpload = upload(chainTaskId);
        if (isIpfsStorageRequest && isUpload) {
            log.info("Web2 storage, just uploaded (with basic) [chainTaskId:{}]", chainTaskId);
            return getWeb2ResultLink(chainTaskId);//retrieves ipfs only
        }

        log.info("Cannot uploadResultAndGetLink [chainTaskId:{}, isIpfsStorageRequest:{}, chainTaskId:{}]",
                chainTaskId, isIpfsStorageRequest, isUpload);
        return "";
    }

    private boolean upload(String chainTaskId) {
        String authorizationToken = getIexecUploadToken();
        if (authorizationToken.isEmpty()) {
            log.error("Empty authorizationToken, cannot upload result [chainTaskId:{}]", chainTaskId);
            return false;
        }

        try {
            resultProxyClient.addResult(authorizationToken, getResultModelWithZip(chainTaskId));
            return true;
        } catch (RuntimeException e) {
            log.error("Empty location, cannot upload result [chainTaskId:{}]", chainTaskId, e);
            return false;
        }
    }

    private String getWeb3ResultLink(String chainTaskId) {
        TaskDescription task = iexecHubService.getTaskDescription(chainTaskId);

        if (task == null) {
            log.error("Cannot get web3 result link (task missing) [chainTaskId:{}]", chainTaskId);
            return "";
        }

        return buildResultLink("ethereum", task.getCallback());
    }

    String getWeb2ResultLink(String chainTaskId) {
        TaskDescription task = iexecHubService.getTaskDescription(chainTaskId);

        if (task == null) {
            log.error("Cannot get tee web2 result link (task missing) [chainTaskId:{}]", chainTaskId);
            return "";
        }

        String location;
        String storage = task.getResultStorageProvider();

        switch (storage) {
            case IPFS_RESULT_STORAGE_PROVIDER:
                try {
                    String ipfsHash = resultProxyClient.getIpfsHashForTask(chainTaskId);
                    location = "/ipfs/" + ipfsHash;
                } catch (RuntimeException e) {
                    log.error("Cannot get tee web2 result link (result-proxy issue) [chainTaskId:{}]", chainTaskId, e);
                    return "";
                }
                break;
            case DROPBOX_RESULT_STORAGE_PROVIDER:
                location = "/results/" + chainTaskId;
                break;
            default:
                log.error("Cannot get tee web2 result link (storage missing) [chainTaskId:{}]", chainTaskId);
                return "";
        }

        return buildResultLink(storage, location);
    }

    String buildResultLink(String storage, String location) {
        return String.format("{ \"storage\": \"%s\", \"location\": \"%s\" }", storage, location);
    }

    /**
     * Gets and returns a JWT against a valid {@code WorkerpoolAuthorization}
     *
     * @param workerpoolAuthorization The auhtorization
     * @return The JWT
     */
    // TODO Add JWT validation
    public String getIexecUploadToken(WorkerpoolAuthorization workerpoolAuthorization) {
        try {
            final String hash = workerpoolAuthorization.getHash();
            final String authorization = credentialsService.hashAndSignMessage(hash).getValue();
            if (authorization.isEmpty()) {
                log.error("Couldn't sign hash for an unknown reason [hash:{}]", hash);
                return "";
            }
            return resultProxyClient.getJwt(authorization, workerpoolAuthorization);
        } catch (Exception e) {
            log.error("Failed to get upload token", e);
            return "";
        }
    }

    public String getIexecUploadToken() {
        // get challenge
        Integer chainId = chainConfig.getChainId();
        try {
            final EIP712Challenge eip712Challenge = resultProxyClient.getChallenge(chainId);
            if (eip712Challenge == null) {
                log.error("Couldn't retrieve an EIP712Challenge from Result Proxy");
                return "";
            }

            final EIP712Domain domain = eip712Challenge.getDomain();
            final String expectedDomainName = "iExec Result Repository";
            final String actualDomainName = domain.getName();
            if (!Objects.equals(actualDomainName, expectedDomainName)) {
                log.error("Domain name does not match expected name [expected:{}, actual:{}]",
                        expectedDomainName, actualDomainName);
                return "";
            }

            final long domainChainId = domain.getChainId();
            if (!Objects.equals(domainChainId, chainId.longValue())) {
                log.error("Domain chain id does not match expected chain id [expected:{}, actual:{}]",
                        chainId, domainChainId);
                return "";
            }

            // sign challenge
            String signedEip712Challenge = credentialsService.signEIP712EntityAndBuildToken(eip712Challenge);

            if (signedEip712Challenge.isEmpty()) {
                log.error("Couldn't sign challenge for an unknown reason [challenge:{}]",
                        eip712Challenge);
                return "";
            }

            // login
            return resultProxyClient.login(chainId, signedEip712Challenge);
        } catch (RuntimeException e) {
            log.error("Failed to get upload token", e);
            return "";
        }
    }

    public boolean isResultAvailable(String chainTaskId) {
        return isResultZipFound(chainTaskId);
    }

    public ComputedFile readComputedFile(String chainTaskId) {
        ComputedFile computedFile = IexecFileHelper.readComputedFile(chainTaskId,
                workerConfigService.getTaskOutputDir(chainTaskId));
        if (computedFile == null) {
            log.error("Failed to read computed file (computed.json missing) [chainTaskId:{}]", chainTaskId);
        }
        return computedFile;
    }

    public ComputedFile getComputedFile(String chainTaskId) {
        ComputedFile computedFile = readComputedFile(chainTaskId);
        if (computedFile == null) {
            log.error("Failed to getComputedFile (computed.json missing) [chainTaskId:{}]", chainTaskId);
            return null;
        }
        if (computedFile.getResultDigest() == null || computedFile.getResultDigest().isEmpty()) {
            String resultDigest = computeResultDigest(computedFile);
            if (resultDigest.isEmpty()) {
                log.error("Failed to getComputedFile (resultDigest is empty " +
                                "but cant compute it) [chainTaskId:{}, computedFile:{}]",
                        chainTaskId, computedFile);
                return null;
            }
            computedFile.setResultDigest(resultDigest);
        }
        return computedFile;
    }

    /**
     * Write computed file. Most likely used by tee-post-compute.
     * TODO: check compute stage is successful
     *
     * @param computedFile computed file to be written
     * @return true is computed file is successfully written to disk
     */
    public boolean writeComputedFile(ComputedFile computedFile) {
        if (computedFile == null || StringUtils.isEmpty(computedFile.getTaskId())) {
            log.error("Cannot write computed file [computedFile:{}]", computedFile);
            return false;
        }
        String chainTaskId = computedFile.getTaskId();
        ChainTaskStatus chainTaskStatus =
                iexecHubService.getChainTask(chainTaskId)
                        .map(ChainTask::getStatus)
                        .orElse(null);
        if (chainTaskStatus != ChainTaskStatus.ACTIVE) {
            log.error("Cannot write computed file if task is not active " +
                            "[chainTaskId:{}, computedFile:{}, chainTaskStatus:{}]",
                    chainTaskId, computedFile, chainTaskStatus);
            return false;
        }
        String computedFilePath =
                workerConfigService.getTaskOutputDir(chainTaskId)
                        + IexecFileHelper.SLASH_COMPUTED_JSON;
        if (new File(computedFilePath).exists()) {
            log.error("Cannot write computed file if already written" +
                            WRITE_COMPUTED_FILE_LOG_ARGS,
                    chainTaskId, computedFile);
            return false;
        }
        if (!BytesUtils.isNonZeroedBytes32(computedFile.getResultDigest())) {
            log.error("Cannot write computed file if result digest is invalid [chainTaskId:{}, computedFile:{}]",
                    chainTaskId, computedFile);
            return false;
        }
        boolean isSignatureRequired = iexecHubService.isTeeTask(chainTaskId);
        if (isSignatureRequired &&
                (StringUtils.isEmpty(computedFile.getEnclaveSignature())
                        || stringToBytes(computedFile.getEnclaveSignature()).length != 65)) {
            log.error("Cannot write computed file if TEE signature is invalid [chainTaskId:{}, computedFile:{}]",
                    chainTaskId, computedFile);
            return false;
        }
        ObjectMapper mapper = new ObjectMapper();
        try {
            String json = mapper.writeValueAsString(computedFile);
            Files.write(Paths.get(computedFilePath), json.getBytes());
        } catch (IOException e) {
            log.error("Cannot write computed file if write failed [chainTaskId:{}, computedFile:{}]",
                    chainTaskId, computedFile, e);
            return false;
        }
        return true;
    }

    public String computeResultDigest(ComputedFile computedFile) {
        String chainTaskId = computedFile.getTaskId();
        String resultDigest;
        if (iexecHubService.getTaskDescription(chainTaskId).containsCallback()) {
            resultDigest = ResultUtils.computeWeb3ResultDigest(computedFile);
        } else {
            resultDigest = ResultUtils.computeWeb2ResultDigest(computedFile,
                    workerConfigService.getTaskOutputDir(chainTaskId));
        }
        if (resultDigest.isEmpty()) {
            log.error("Failed to computeResultDigest (resultDigest empty) [chainTaskId:{}, computedFile:{}]",
                    chainTaskId, computedFile);
            return "";
        }
        return resultDigest;
    }

    // region Purge

    /**
     * Purge results from given task, especially its result folder.
     *
     * @param chainTaskId ID of the task to purge.
     * @return {@literal true} if task folder has been deleted
     * and task data has been cleared from this service;
     * {@literal false} otherwise.
     */
    @Override
    public boolean purgeTask(String chainTaskId) {
        final String taskBaseDir = workerConfigService.getTaskBaseDir(chainTaskId);

        resultInfoMap.remove(chainTaskId);
        FileHelper.deleteFolder(taskBaseDir);

        final boolean deletedInMap = !resultInfoMap.containsKey(chainTaskId);
        final boolean deletedTaskFolder = !new File(taskBaseDir).exists();

        boolean deleted = deletedInMap && deletedTaskFolder;
        if (deletedTaskFolder) {
            log.info("The result of the chainTaskId has been deleted [chainTaskId:{}]", chainTaskId);
        } else {
            log.warn("The result of the chainTaskId couldn't be deleted [chainTaskId:{}, deletedInMap:{}, " +
                            "deletedTaskFolder:{}]",
                    chainTaskId, deletedInMap, deletedTaskFolder);
        }

        return deleted;
    }

    /**
     * Purge results from all known tasks, especially their result folders.
     */
    @Override
    public void purgeAllTasksData() {
        final List<String> tasksIds = new ArrayList<>(resultInfoMap.keySet());
        tasksIds.forEach(this::purgeTask);
    }
    // endregion
}
