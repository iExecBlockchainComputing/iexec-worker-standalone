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

package com.iexec.standalone.feign;

import com.iexec.common.config.PublicConfiguration;
import com.iexec.common.config.WorkerModel;
import com.iexec.common.replicate.ReplicateStatusUpdate;
import com.iexec.commons.poco.notification.TaskNotification;
import com.iexec.commons.poco.notification.TaskNotificationType;
import com.iexec.standalone.feign.client.CoreClient;
import com.iexec.standalone.replicate.ReplicateTaskSummary;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.*;


@Slf4j
@Service
public class CustomCoreFeignClient extends BaseFeignClient {

    public static final String JWTOKEN = "jwtoken";
    public static final String BLOCK_NUMBER = "blockNumber";
    private final LoginService loginService;
    private final CoreClient coreClient;

    public CustomCoreFeignClient(CoreClient coreClient, LoginService loginService) {
        this.loginService = loginService;
        this.coreClient = coreClient;
    }

    /**
     * Log in the Scheduler.
     * Caution: this is NOT thread-safe.
     *
     * @return An authentication token
     */
    @Override
    String login() {
        return loginService.login();
    }

    /*
     * How does it work?
     * We create an HttpCall<T>, T being the type of the response
     * body which can be Void. We send it along with the arguments
     * to the generic "makeHttpCall()" method. If the call was
     * successful, we return a ResponseEntity<T> with the response
     * body, otherwise, we return a ResponseEntity with the call's failure
     * status.
     *
     * How to pass call args?
     * We put call params in a Map<String, Object> (see below)
     * and we pass the Map as an argument to the lambda expression.
     * Inside the lambda expression we cast the arguments into their
     * original types required by the method to be called.
     * (Casting arguments is safe).
     */

    public PublicConfiguration getPublicConfiguration() {
        HttpCall<PublicConfiguration> httpCall = args -> coreClient.getPublicConfiguration();
        ResponseEntity<PublicConfiguration> response = makeHttpCall(httpCall, null, "getPublicConfig");
        return is2xxSuccess(response) ? response.getBody() : null;
    }

    public String getCoreVersion() {
        HttpCall<String> httpCall = args -> coreClient.getCoreVersion();
        ResponseEntity<String> response = makeHttpCall(httpCall, null, "getCoreVersion");
        return is2xxSuccess(response) ? response.getBody() : null;
    }

    //TODO: Make registerWorker return Worker
    public boolean registerWorker(WorkerModel model) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put(JWTOKEN, loginService.getToken());
        arguments.put("model", model);
        HttpCall<Void> httpCall = args -> coreClient.registerWorker((String) args.get(JWTOKEN), (WorkerModel) args.get("model"));
        ResponseEntity<Void> response = makeHttpCall(httpCall, arguments, "registerWorker");
        return is2xxSuccess(response);
    }

    public List<String> getComputingTasks() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put(JWTOKEN, loginService.getToken());

        HttpCall<List<String>> httpCall = args ->
                coreClient.getComputingTasks((String) args.get(JWTOKEN));

        ResponseEntity<List<String>> response = makeHttpCall(httpCall, arguments, "getComputingTasks");
        return is2xxSuccess(response) ? response.getBody() : Collections.emptyList();
    }

    public List<TaskNotification> getMissedTaskNotifications(long lastAvailableBlockNumber) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put(JWTOKEN, loginService.getToken());
        arguments.put(BLOCK_NUMBER, lastAvailableBlockNumber);

        HttpCall<List<TaskNotification>> httpCall = args ->
                coreClient.getMissedTaskNotifications((String) args.get(JWTOKEN), (long) args.get(BLOCK_NUMBER));

        ResponseEntity<List<TaskNotification>> response = makeHttpCall(httpCall, arguments, "getMissedNotifications");
        return is2xxSuccess(response) ? response.getBody() : Collections.emptyList();
    }

    public Optional<ReplicateTaskSummary> getAvailableReplicateTaskSummary(long lastAvailableBlockNumber) {
        try {
            return Optional.ofNullable(coreClient.getAvailableReplicateTaskSummary(
                    loginService.getToken(),
                    lastAvailableBlockNumber
            ));
        } catch (FeignException e) {
            log.error("Failed to retrieve work from scheduler [httpStatus:{}]", e.status());
            if (e instanceof FeignException.Unauthorized) {
                login();
            }
        }
        return Optional.empty();
    }

    public TaskNotificationType updateReplicateStatus(String chainTaskId, ReplicateStatusUpdate replicateStatusUpdate) {
        try {
            final ResponseEntity<TaskNotificationType> response = coreClient.updateReplicateStatus(
                    loginService.getToken(),
                    chainTaskId,
                    replicateStatusUpdate
            );
            if (response.getStatusCode() == HttpStatus.ALREADY_REPORTED) {
                log.info("Replicate status already reported [status:{}, chainTaskId:{}]",
                        replicateStatusUpdate.getStatus().toString(), chainTaskId);
            } else {
                log.info("Updated replicate status [status:{}, chainTaskId:{}]",
                        replicateStatusUpdate.getStatus().toString(), chainTaskId);
            }
            return response.getBody();
        } catch (FeignException.Unauthorized e) {
            login();
            return null;
        } catch (FeignException e) {
            log.error("Exception while trying to update replicate status" +
                    " [chainTaskId:{}, statusUpdate:{}, httpStatus:{}]",
                    chainTaskId, replicateStatusUpdate, e.status());
            return null;
        }
    }
}
