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

package com.iexec.standalone.chain;

import com.iexec.commons.poco.chain.*;
import com.iexec.commons.poco.contract.generated.IexecHubContract;
import com.iexec.commons.poco.utils.BytesUtils;
import com.iexec.standalone.config.BlockchainAdapterConfigurationService;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.iexec.commons.poco.utils.TestUtils.CHAIN_TASK_ID;
import static com.iexec.commons.poco.utils.TestUtils.WORKER_ADDRESS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Slf4j
class IexecHubServiceTests {

    private static final String TRANSACTION_HASH = "transactionHash";
    private static final long TIME_INTERVAL_IN_MS = 100L;
    private static final String TASK_CONTRIBUTE_NOTICE = Hash.sha3String("TaskContribute(bytes32,address,bytes32)");
    private static final String TASK_REVEAL_NOTICE = Hash.sha3String("TaskReveal(bytes32,address,bytes32)");
    private static final String TASK_FINALIZE_NOTICE = Hash.sha3String("TaskFinalize(bytes32,bytes)");
    private static final String CHAIN_TASK_ID = "0x5125c4ca7176e40d8c5386072a6f262029609a5d3a896fbf592cd965e65098d9";

    @Mock
    private BlockchainAdapterConfigurationService blockchainAdapterConfigurationService;
    @Mock
    private CredentialsService credentialsService;
    @Mock
    private Web3jService web3jService;
    @Mock
    private IexecHubContract iexecHubContract;
    @Mock
    private RemoteFunctionCall<TransactionReceipt> remoteFunctionCall;
    @Mock
    private ChainConfig chainConfig;
    @Mock
    private Web3j web3jClient;

    private IexecHubService chainConfigIexecHubService;
    private IexecHubService BACSIexecHubService;
    private Credentials credentials;

    @BeforeEach
    void init() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(blockchainAdapterConfigurationService.getIexecHubContractAddress()).thenReturn("0x748e091bf16048cb5103E0E10F9D5a8b7fBDd860");
        when(blockchainAdapterConfigurationService.getBlockTime()).thenReturn(Duration.ofSeconds(5L));
        when(blockchainAdapterConfigurationService.getChainId()).thenReturn(65535);
        credentials = Credentials.create(Keys.createEcKeyPair());
        when(credentialsService.getCredentials()).thenReturn(credentials);
        when(web3jService.hasEnoughGas(any())).thenReturn(true);
        when(chainConfig.getHubAddress()).thenReturn("0x748e091bf16048cb5103E0E10F9D5a8b7fBDd860");
        when(web3jService.getWeb3j()).thenReturn(web3jClient);
        chainConfigIexecHubService = spy(new IexecHubService(credentialsService, web3jService, chainConfig));
        BACSIexecHubService = spy(new IexecHubService(credentialsService, web3jService, blockchainAdapterConfigurationService));
        ReflectionTestUtils.setField(BACSIexecHubService, "iexecHubContract", iexecHubContract);
    }

    // region isTaskInCompletedStatusOnChain
    @Test
    void shouldTaskBeInCompletedStatusOnChain() {
        final ChainTask task = ChainTask.builder().status(ChainTaskStatus.COMPLETED).build();
        doReturn(Optional.of(task)).when(chainConfigIexecHubService).getChainTask(CHAIN_TASK_ID);

        assertThat(chainConfigIexecHubService.isTaskInCompletedStatusOnChain(CHAIN_TASK_ID)).isTrue();
    }

    @Test
    void shouldTaskNotBeInCompletedStatusOnChain() {
        final ChainTask task = ChainTask.builder().status(ChainTaskStatus.REVEALING).build();
        doReturn(Optional.of(task)).when(chainConfigIexecHubService).getChainTask(CHAIN_TASK_ID);

        assertThat(chainConfigIexecHubService.isTaskInCompletedStatusOnChain(CHAIN_TASK_ID)).isFalse();
    }
    // endregion

    // region canFinalize
    @Test
    void canNotFinalizeWhenChainTaskNotFound() {
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.empty());
        assertThat(chainConfigIexecHubService.canFinalize(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotFinalizeWhenNotRevealing() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.ACTIVE)
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canFinalize(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotFinalizeWhenFinalDeadlineReached() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .finalDeadline(Instant.now().minus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canFinalize(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotFinalizeWhenNotEnoughReveals() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .revealCounter(1)
                .winnerCounter(2)
                .revealDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canFinalize(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canFinalizeWhenRevealDeadlineReached() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .revealCounter(1)
                .winnerCounter(2)
                .revealDeadline(Instant.now().minus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canFinalize(CHAIN_TASK_ID)).isTrue();
    }

    @Test
    void canFinalizeWhenAllWinnersRevealed() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .revealCounter(1)
                .winnerCounter(1)
                .revealDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canFinalize(CHAIN_TASK_ID)).isTrue();
    }
    // endregion

    // region canReopen
    @Test
    void canNotRepoenWhenChainTaskNotFound() {
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.empty());
        assertThat(chainConfigIexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotReopenWhenNotRevealing() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.ACTIVE)
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotReopenWhenFinalDeadlineReached() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .finalDeadline(Instant.now().minus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotReopenWhenBeforeRevealDeadline() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .revealDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotReopenWhenSomeWinnersRevealed() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .revealCounter(1)
                .finalDeadline(Instant.now().minus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canReopenWhenRevealDeadlineReachedAndNoReveal() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .revealDeadline(Instant.now().minus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        when(chainConfigIexecHubService.getChainTask(CHAIN_TASK_ID)).thenReturn(Optional.of(chainTask));
        assertThat(chainConfigIexecHubService.canReopen(CHAIN_TASK_ID)).isTrue();
    }
    // endregion

    // region isContributed
    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class, mode = EnumSource.Mode.INCLUDE, names = {"CONTRIBUTED", "REVEALED"})
    void shouldBeContributed(ChainContributionStatus status) {
        final ChainContribution chainContribution = ChainContribution.builder().status(status).build();
        when(chainConfigIexecHubService.getChainContribution(anyString(), anyString())).thenReturn(Optional.of(chainContribution));
        assertThat(chainConfigIexecHubService.isContributed(CHAIN_TASK_ID, WORKER_ADDRESS)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"CONTRIBUTED", "REVEALED"})
    void shouldNotBeContributed(ChainContributionStatus status) {
        final ChainContribution chainContribution = ChainContribution.builder().status(status).build();
        when(chainConfigIexecHubService.getChainContribution(anyString(), anyString())).thenReturn(Optional.of(chainContribution));
        assertThat(chainConfigIexecHubService.isContributed(CHAIN_TASK_ID, WORKER_ADDRESS)).isFalse();
    }
    // endregion

    // region isRevealed
    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class, mode = EnumSource.Mode.INCLUDE, names = {"REVEALED"})
    void shouldBeRevealed(ChainContributionStatus status) {
        final ChainContribution chainContribution = ChainContribution.builder().status(status).build();
        when(chainConfigIexecHubService.getChainContribution(anyString(), anyString())).thenReturn(Optional.of(chainContribution));
        assertThat(chainConfigIexecHubService.isRevealed(CHAIN_TASK_ID, WORKER_ADDRESS)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"REVEALED"})
    void shouldNotBeRevealed(ChainContributionStatus status) {
        final ChainContribution chainContribution = ChainContribution.builder().status(status).build();
        when(chainConfigIexecHubService.getChainContribution(anyString(), anyString())).thenReturn(Optional.of(chainContribution));
        assertThat(chainConfigIexecHubService.isRevealed(CHAIN_TASK_ID, WORKER_ADDRESS)).isFalse();
    }
    // endregion

    // region get event blocks
    @Test
    void shouldGetContributionBlock() {
        final int fromBlock = 0;
        final long latestBlock = 1;
        when(web3jService.getLatestBlockNumber()).thenReturn(latestBlock);

        final IexecHubContract hubContract = mock(IexecHubContract.class);
        ReflectionTestUtils.setField(chainConfigIexecHubService, "iexecHubContract", hubContract);

        final IexecHubContract.TaskContributeEventResponse taskContributeEventResponse = getTaskContributeEventResponse(latestBlock);
        when(hubContract.taskContributeEventFlowable(any()))
                .thenReturn(Flowable.fromArray(taskContributeEventResponse));

        final ChainReceipt chainReceipt = chainConfigIexecHubService.getContributionBlock(CHAIN_TASK_ID, WORKER_ADDRESS, fromBlock);

        assertThat(chainReceipt)
                .isEqualTo(ChainReceipt.builder()
                        .blockNumber(latestBlock)
                        .txHash(TRANSACTION_HASH)
                        .build());
    }

    private static IexecHubContract.TaskContributeEventResponse getTaskContributeEventResponse(long latestBlock) {
        final IexecHubContract.TaskContributeEventResponse taskContributeEventResponse =
                new IexecHubContract.TaskContributeEventResponse();
        taskContributeEventResponse.taskid = BytesUtils.stringToBytes(CHAIN_TASK_ID);
        taskContributeEventResponse.worker = WORKER_ADDRESS;
        taskContributeEventResponse.log = new Log();
        taskContributeEventResponse.log.setBlockNumber(String.valueOf(latestBlock));
        taskContributeEventResponse.log.setTransactionHash(TRANSACTION_HASH);
        return taskContributeEventResponse;
    }

    @Test
    void shouldGetConsensusBlock() {
        final int fromBlock = 0;
        final long latestBlock = 1;
        when(web3jService.getLatestBlockNumber()).thenReturn(latestBlock);

        final IexecHubContract hubContract = mock(IexecHubContract.class);
        ReflectionTestUtils.setField(chainConfigIexecHubService, "iexecHubContract", hubContract);

        final IexecHubContract.TaskConsensusEventResponse taskConsensusEventResponse = getTaskConsensusEventResponse(latestBlock);
        when(hubContract.taskConsensusEventFlowable(any()))
                .thenReturn(Flowable.fromArray(taskConsensusEventResponse));

        final ChainReceipt chainReceipt = chainConfigIexecHubService.getConsensusBlock(CHAIN_TASK_ID, fromBlock);

        assertThat(chainReceipt)
                .isEqualTo(ChainReceipt.builder()
                        .blockNumber(latestBlock)
                        .txHash(TRANSACTION_HASH)
                        .build());
    }

    private static IexecHubContract.TaskConsensusEventResponse getTaskConsensusEventResponse(long latestBlock) {
        final IexecHubContract.TaskConsensusEventResponse taskContributeEventResponse =
                new IexecHubContract.TaskConsensusEventResponse();
        taskContributeEventResponse.taskid = BytesUtils.stringToBytes(CHAIN_TASK_ID);
        taskContributeEventResponse.log = new Log();
        taskContributeEventResponse.log.setBlockNumber(String.valueOf(latestBlock));
        taskContributeEventResponse.log.setTransactionHash(TRANSACTION_HASH);
        return taskContributeEventResponse;
    }

    @Test
    void shouldGetRevealBlock() {
        final int fromBlock = 0;
        final long latestBlock = 1;
        when(web3jService.getLatestBlockNumber()).thenReturn(latestBlock);

        final IexecHubContract hubContract = mock(IexecHubContract.class);
        ReflectionTestUtils.setField(chainConfigIexecHubService, "iexecHubContract", hubContract);

        final IexecHubContract.TaskRevealEventResponse taskRevealEventResponse = getTaskRevealEventResponse(latestBlock);
        when(hubContract.taskRevealEventFlowable(any()))
                .thenReturn(Flowable.fromArray(taskRevealEventResponse));

        final ChainReceipt chainReceipt = chainConfigIexecHubService.getRevealBlock(CHAIN_TASK_ID, WORKER_ADDRESS, fromBlock);

        assertThat(chainReceipt)
                .isEqualTo(ChainReceipt.builder()
                        .blockNumber(latestBlock)
                        .txHash(TRANSACTION_HASH)
                        .build());
    }

    private static IexecHubContract.TaskRevealEventResponse getTaskRevealEventResponse(long latestBlock) {
        final IexecHubContract.TaskRevealEventResponse taskContributeEventResponse =
                new IexecHubContract.TaskRevealEventResponse();
        taskContributeEventResponse.taskid = BytesUtils.stringToBytes(CHAIN_TASK_ID);
        taskContributeEventResponse.worker = WORKER_ADDRESS;
        taskContributeEventResponse.log = new Log();
        taskContributeEventResponse.log.setBlockNumber(String.valueOf(latestBlock));
        taskContributeEventResponse.log.setTransactionHash(TRANSACTION_HASH);
        return taskContributeEventResponse;
    }

    @Test
    void shouldGetFinalizeBlock() {
        final int fromBlock = 0;
        final long latestBlock = 1;
        when(web3jService.getLatestBlockNumber()).thenReturn(latestBlock);

        final IexecHubContract hubContract = mock(IexecHubContract.class);
        ReflectionTestUtils.setField(chainConfigIexecHubService, "iexecHubContract", hubContract);

        final IexecHubContract.TaskFinalizeEventResponse taskFinalizeEventResponse = getTaskFinalizeEventResponse(latestBlock);
        when(hubContract.taskFinalizeEventFlowable(any()))
                .thenReturn(Flowable.fromArray(taskFinalizeEventResponse));

        final ChainReceipt chainReceipt = chainConfigIexecHubService.getFinalizeBlock(CHAIN_TASK_ID, fromBlock);

        assertThat(chainReceipt)
                .isEqualTo(ChainReceipt.builder()
                        .blockNumber(latestBlock)
                        .txHash(TRANSACTION_HASH)
                        .build());
    }

    private static IexecHubContract.TaskFinalizeEventResponse getTaskFinalizeEventResponse(long latestBlock) {
        final IexecHubContract.TaskFinalizeEventResponse taskContributeEventResponse =
                new IexecHubContract.TaskFinalizeEventResponse();
        taskContributeEventResponse.taskid = BytesUtils.stringToBytes(CHAIN_TASK_ID);
        taskContributeEventResponse.log = new Log();
        taskContributeEventResponse.log.setBlockNumber(String.valueOf(latestBlock));
        taskContributeEventResponse.log.setTransactionHash(TRANSACTION_HASH);
        return taskContributeEventResponse;
    }

    static Stream<BiFunction<IexecHubService, Long, ChainReceipt>> eventBlockGetters() {
        return Stream.of(
                (chainConfigIexecHubService, fromBlock) -> chainConfigIexecHubService.getContributionBlock(CHAIN_TASK_ID, WORKER_ADDRESS, fromBlock),
                (chainConfigIexecHubService, fromBlock) -> chainConfigIexecHubService.getConsensusBlock(CHAIN_TASK_ID, fromBlock),
                (chainConfigIexecHubService, fromBlock) -> chainConfigIexecHubService.getRevealBlock(CHAIN_TASK_ID, WORKER_ADDRESS, fromBlock),
                (chainConfigIexecHubService, fromBlock) -> chainConfigIexecHubService.getFinalizeBlock(CHAIN_TASK_ID, fromBlock)
        );
    }

    @ParameterizedTest
    @MethodSource("eventBlockGetters")
    void shouldNotGetEventBlockWhenFromBlockInFuture(BiFunction<IexecHubService, Long, ChainReceipt> eventBlockGetter) {
        final long fromBlock = 2;
        final long latestBlock = 1;
        when(web3jService.getLatestBlockNumber()).thenReturn(latestBlock);

        final ChainReceipt chainReceipt = eventBlockGetter.apply(chainConfigIexecHubService, fromBlock);
        assertThat(chainReceipt)
                .isEqualTo(ChainReceipt.builder().build());
    }
    // endregion
}
