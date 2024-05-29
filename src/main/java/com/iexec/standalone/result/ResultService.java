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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iexec.common.lifecycle.purge.ExpiringTaskMapFactory;
import com.iexec.common.result.ComputedFile;
import com.iexec.common.utils.IexecFileHelper;
import com.iexec.common.worker.result.ResultUtils;
import com.iexec.commons.poco.chain.ChainTask;
import com.iexec.commons.poco.chain.ChainTaskStatus;
import com.iexec.commons.poco.chain.WorkerpoolAuthorization;
import com.iexec.commons.poco.task.TaskDescription;
import com.iexec.commons.poco.utils.BytesUtils;
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
import java.util.Map;

import static com.iexec.commons.poco.utils.BytesUtils.EMPTY_ADDRESS;
import static com.iexec.commons.poco.utils.BytesUtils.stringToBytes;

@Slf4j
@Service
public class ResultService {

    public static final String WRITE_COMPUTED_FILE_LOG_ARGS = " [chainTaskId:{}, computedFile:{}]";

    private final ResultProxyClient resultProxyClient;
    private final SignatureService signatureService;
    private final TaskService taskService;
    private final IexecHubService iexecHubService;
    private final WorkerConfigurationService workerConfigService;
    private final Map<String, ResultInfo> resultInfoMap
            = ExpiringTaskMapFactory.getExpiringTaskMap();

    public ResultService(final ResultProxyClient resultProxyClient, final SignatureService signatureService, final TaskService taskService,
                         final IexecHubService iexecHubService, final WorkerConfigurationService workerConfigService) {
        this.resultProxyClient = resultProxyClient;
        this.signatureService = signatureService;
        this.taskService = taskService;
        this.iexecHubService = iexecHubService;
        this.workerConfigService = workerConfigService;
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

    public ComputedFile readComputedFile(String chainTaskId) {
        ComputedFile computedFile = IexecFileHelper.readComputedFile(chainTaskId,
                workerConfigService.getTaskOutputDir(chainTaskId));
        if (computedFile == null) {
            log.error("Failed to read computed file (computed.json missing) [chainTaskId:{}]", chainTaskId);
        }
        return computedFile;
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

    public void saveResultInfo(String chainTaskId, TaskDescription taskDescription, ComputedFile computedFile) {
        ResultInfo resultInfo = ResultInfo.builder()
                .image(taskDescription.getAppUri())
                .cmd(taskDescription.getCmd())
                .deterministHash(computedFile != null ? computedFile.getResultDigest() : "")
                .datasetUri(taskDescription.getDatasetUri())
                .build();

        resultInfoMap.put(chainTaskId, resultInfo);
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
}
