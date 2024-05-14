/*
 * Copyright 2020 IEXEC BLOCKCHAIN TECH
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

package com.iexec.standalone.metric;

import com.iexec.standalone.chain.DealWatcherService;
import com.iexec.standalone.task.TaskStatus;
import com.iexec.standalone.task.event.TaskStatusesCountUpdatedEvent;
import com.iexec.standalone.worker.WorkerService;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MetricsService {
    private final DealWatcherService dealWatcherService;
    private final WorkerService workerService;
    private LinkedHashMap<TaskStatus, Long> currentTaskStatusesCount;
    private final ComputeDurationsService preComputeDurationsService;
    private final ComputeDurationsService appComputeDurationsService;
    private final ComputeDurationsService postComputeDurationsService;

    public MetricsService(DealWatcherService dealWatcherService,
                          WorkerService workerService,
                          ComputeDurationsService preComputeDurationsService,
                          ComputeDurationsService appComputeDurationsService,
                          ComputeDurationsService postComputeDurationsService) {
        this.dealWatcherService = dealWatcherService;
        this.workerService = workerService;

        this.preComputeDurationsService = preComputeDurationsService;
        this.appComputeDurationsService = appComputeDurationsService;
        this.postComputeDurationsService = postComputeDurationsService;

        this.currentTaskStatusesCount = new LinkedHashMap<>();
    }

    public PlatformMetric getPlatformMetrics() {
        return PlatformMetric.builder()
                .aliveWorkers(workerService.getAliveWorkers().size())
                .aliveTotalCpu(workerService.getAliveTotalCpu())
                .aliveAvailableCpu(workerService.getAliveAvailableCpu())
                .aliveTotalGpu(workerService.getAliveTotalGpu())
                .aliveAvailableGpu(workerService.getAliveAvailableGpu())
                .currentTaskStatusesCount(currentTaskStatusesCount)
                .dealEventsCount(dealWatcherService.getDealEventsCount())
                .dealsCount(dealWatcherService.getDealsCount())
                .replayDealsCount(dealWatcherService.getReplayDealsCount())
                .latestBlockNumberWithDeal(dealWatcherService.getLatestBlockNumberWithDeal())
                .build();
    }

    @EventListener
    void onTaskStatusesCountUpdateEvent(TaskStatusesCountUpdatedEvent event) {
        this.currentTaskStatusesCount = event.getCurrentTaskStatusesCount();
    }

    public WorkerMetrics getWorkerMetrics() {
        return new WorkerMetrics(
                preComputeDurationsService.getAggregatedDurations(),
                appComputeDurationsService.getAggregatedDurations(),
                postComputeDurationsService.getAggregatedDurations(),
                getCompleteComputeMetrics()
        );
    }

    // region Complete compute duration
    AggregatedDurations getCompleteComputeMetrics() {
        final List<Double> durations = getCompleteComputeDurations();
        final DescriptiveStatistics descriptiveStatistics = new DescriptiveStatistics();
        durations.forEach(descriptiveStatistics::addValue);

        return new AggregatedDurations(
                descriptiveStatistics.getN(),
                descriptiveStatistics.getMin(),
                descriptiveStatistics.getMax(),
                descriptiveStatistics.getMean()
        );
    }

    /**
     * For each task whose app-compute duration is known,
     * computes its complete compute duration
     * and returns all of them as a {@link List}.
     */
    List<Double> getCompleteComputeDurations() {
        return appComputeDurationsService
                .getChainTaskIds()
                .stream()
                .map(this::getCompleteComputeDuration)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * Computes and returns the complete compute duration for a task.
     * If any of app or post-compute duration is unknown, then returns an {@link Optional#empty()}.
     */
    Optional<Double> getCompleteComputeDuration(String chainTaskId) {
        final Optional<Long> preComputeDuration = preComputeDurationsService.getDurationForTask(chainTaskId);
        final Optional<Long> appComputeDuration = appComputeDurationsService.getDurationForTask(chainTaskId);
        final Optional<Long> postComputeDuration = postComputeDurationsService.getDurationForTask(chainTaskId);

        // Should check whether appComputeDuration is still known.
        // It could have been purged if max number of durations has been reached
        // and new durations have been added.
        if (appComputeDuration.isEmpty() || postComputeDuration.isEmpty()) {
            return Optional.empty();
        }

        final double completeDuration = (double)
                preComputeDuration.orElse(0L)
                + appComputeDuration.get()
                + postComputeDuration.get();
        return Optional.of(completeDuration);
    }
    // endregion
}
