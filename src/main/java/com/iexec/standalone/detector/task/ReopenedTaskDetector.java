/*
 * Copyright 2020-2023 IEXEC BLOCKCHAIN TECH
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

package com.iexec.standalone.detector.task;

import com.iexec.commons.poco.chain.ChainTask;
import com.iexec.commons.poco.chain.ChainTaskStatus;
import com.iexec.standalone.chain.IexecHubService;
import com.iexec.standalone.detector.Detector;
import com.iexec.standalone.task.Task;
import com.iexec.standalone.task.TaskService;
import com.iexec.standalone.task.TaskStatus;
import com.iexec.standalone.task.update.TaskUpdateRequestManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class ReopenedTaskDetector implements Detector {

    private final TaskService taskService;
    private final TaskUpdateRequestManager taskUpdateRequestManager;
    private final IexecHubService iexecHubService;

    public ReopenedTaskDetector(TaskService taskService,
                                TaskUpdateRequestManager taskUpdateRequestManager,
                                IexecHubService iexecHubService) {
        this.taskService = taskService;
        this.taskUpdateRequestManager = taskUpdateRequestManager;
        this.iexecHubService = iexecHubService;
    }

    /**
     * Detector to detect tasks that are reopening but are not reopened yet.
     */
    @Scheduled(fixedRateString = "#{@cronConfiguration.getFinalize()}")
    @Override
    public void detect() {
        log.debug("Trying to detect reopened tasks");
        for (Task task : taskService.findByCurrentStatus(TaskStatus.REOPENING)) {
            Optional<ChainTask> oChainTask = iexecHubService.getChainTask(task.getChainTaskId());
            if (oChainTask.isEmpty()) {
                continue;
            }

            ChainTask chainTask = oChainTask.get();
            if (chainTask.getStatus().equals(ChainTaskStatus.ACTIVE)) {
                log.info("Detected confirmed missing update (task) [is:{}, should:{}, taskId:{}]",
                        task.getCurrentStatus(), TaskStatus.REOPENED, task.getChainTaskId());
                taskUpdateRequestManager.publishRequest(task.getChainTaskId());
            }
        }
    }
}
