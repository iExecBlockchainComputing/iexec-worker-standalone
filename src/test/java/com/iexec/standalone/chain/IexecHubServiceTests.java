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

import com.iexec.common.contribution.Contribution;
import com.iexec.commons.poco.chain.*;
import com.iexec.commons.poco.contract.generated.IexecHubContract;
import com.iexec.commons.poco.utils.BytesUtils;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
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
import org.web3j.utils.Numeric;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.stream.Stream;

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
    private SignerService signerService;
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

    private IexecHubService iexecHubService;
    private Credentials credentials;

    @BeforeEach
    void init() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(chainConfig.getHubAddress()).thenReturn("0x748e091bf16048cb5103E0E10F9D5a8b7fBDd860");
        when(chainConfig.getBlockTime()).thenReturn(Duration.ofSeconds(5L));
        when(chainConfig.getChainId()).thenReturn(65535);
        credentials = Credentials.create(Keys.createEcKeyPair());
        when(signerService.getCredentials()).thenReturn(credentials);
        when(signerService.getAddress()).thenReturn(credentials.getAddress());
        when(web3jService.hasEnoughGas(any())).thenReturn(true);
        when(chainConfig.getHubAddress()).thenReturn("0x748e091bf16048cb5103E0E10F9D5a8b7fBDd860");
        when(web3jService.getWeb3j()).thenReturn(web3jClient);
        iexecHubService = spy(new IexecHubService(signerService, web3jService, chainConfig));
        ReflectionTestUtils.setField(iexecHubService, "iexecHubContract", iexecHubContract);
    }

    // region isTaskInCompletedStatusOnChain
    @Test
    void shouldTaskBeInCompletedStatusOnChain() {
        final ChainTask task = ChainTask.builder().status(ChainTaskStatus.COMPLETED).build();
        doReturn(Optional.of(task)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);

        assertThat(iexecHubService.isTaskInCompletedStatusOnChain(CHAIN_TASK_ID)).isTrue();
    }

    @Test
    void shouldTaskNotBeInCompletedStatusOnChain() {
        final ChainTask task = ChainTask.builder().status(ChainTaskStatus.REVEALING).build();
        doReturn(Optional.of(task)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);

        assertThat(iexecHubService.isTaskInCompletedStatusOnChain(CHAIN_TASK_ID)).isFalse();
    }
    // endregion

    // region canFinalize
    @Test
    void canNotFinalizeWhenChainTaskNotFound() {
        doReturn(Optional.empty()).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canFinalize(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotFinalizeWhenNotRevealing() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.ACTIVE)
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canFinalize(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotFinalizeWhenFinalDeadlineReached() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .finalDeadline(Instant.now().minus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canFinalize(CHAIN_TASK_ID)).isFalse();
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
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canFinalize(CHAIN_TASK_ID)).isFalse();
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
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canFinalize(CHAIN_TASK_ID)).isTrue();
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
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canFinalize(CHAIN_TASK_ID)).isTrue();
    }
    // endregion

    // region canReopen
    @Test
    void canNotRepoenWhenChainTaskNotFound() {
        doReturn(Optional.empty()).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotReopenWhenNotRevealing() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.ACTIVE)
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotReopenWhenFinalDeadlineReached() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .finalDeadline(Instant.now().minus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotReopenWhenBeforeRevealDeadline() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .revealDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canNotReopenWhenSomeWinnersRevealed() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .revealCounter(1)
                .finalDeadline(Instant.now().minus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canReopen(CHAIN_TASK_ID)).isFalse();
    }

    @Test
    void canReopenWhenRevealDeadlineReachedAndNoReveal() {
        final ChainTask chainTask = ChainTask.builder()
                .status(ChainTaskStatus.REVEALING)
                .revealDeadline(Instant.now().minus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .finalDeadline(Instant.now().plus(TIME_INTERVAL_IN_MS, ChronoUnit.MILLIS).toEpochMilli())
                .build();
        doReturn(Optional.of(chainTask)).when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        assertThat(iexecHubService.canReopen(CHAIN_TASK_ID)).isTrue();
    }
    // endregion

    // region isContributed
    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class, mode = EnumSource.Mode.INCLUDE, names = {"CONTRIBUTED", "REVEALED"})
    void shouldBeContributed(ChainContributionStatus status) {
        final ChainContribution chainContribution = ChainContribution.builder().status(status).build();
        doReturn(Optional.of(chainContribution)).when(iexecHubService).getChainContribution(anyString(), anyString());
        assertThat(iexecHubService.isContributed(CHAIN_TASK_ID, WORKER_ADDRESS)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"CONTRIBUTED", "REVEALED"})
    void shouldNotBeContributed(ChainContributionStatus status) {
        final ChainContribution chainContribution = ChainContribution.builder().status(status).build();
        doReturn(Optional.of(chainContribution)).when(iexecHubService).getChainContribution(anyString(), anyString());
        assertThat(iexecHubService.isContributed(CHAIN_TASK_ID, WORKER_ADDRESS)).isFalse();
    }
    // endregion

    // region isRevealed
    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class, mode = EnumSource.Mode.INCLUDE, names = {"REVEALED"})
    void shouldBeRevealed(ChainContributionStatus status) {
        final ChainContribution chainContribution = ChainContribution.builder().status(status).build();
        doReturn(Optional.of(chainContribution)).when(iexecHubService).getChainContribution(anyString(), anyString());
        assertThat(iexecHubService.isRevealed(CHAIN_TASK_ID, WORKER_ADDRESS)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"REVEALED"})
    void shouldNotBeRevealed(ChainContributionStatus status) {
        final ChainContribution chainContribution = ChainContribution.builder().status(status).build();
        doReturn(Optional.of(chainContribution)).when(iexecHubService).getChainContribution(anyString(), anyString());
        assertThat(iexecHubService.isRevealed(CHAIN_TASK_ID, WORKER_ADDRESS)).isFalse();
    }
    // endregion

    // region get event blocks
    @Test
    void shouldGetContributionBlock() {
        final int fromBlock = 0;
        final long latestBlock = 1;
        when(web3jService.getLatestBlockNumber()).thenReturn(latestBlock);

        final IexecHubContract hubContract = mock(IexecHubContract.class);
        ReflectionTestUtils.setField(iexecHubService, "iexecHubContract", hubContract);

        final IexecHubContract.TaskContributeEventResponse taskContributeEventResponse = getTaskContributeEventResponse(latestBlock);
        when(hubContract.taskContributeEventFlowable(any()))
                .thenReturn(Flowable.fromArray(taskContributeEventResponse));

        final ChainReceipt chainReceipt = iexecHubService.getContributionBlock(CHAIN_TASK_ID, WORKER_ADDRESS, fromBlock);

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
        ReflectionTestUtils.setField(iexecHubService, "iexecHubContract", hubContract);

        final IexecHubContract.TaskConsensusEventResponse taskConsensusEventResponse = getTaskConsensusEventResponse(latestBlock);
        when(hubContract.taskConsensusEventFlowable(any()))
                .thenReturn(Flowable.fromArray(taskConsensusEventResponse));

        final ChainReceipt chainReceipt = iexecHubService.getConsensusBlock(CHAIN_TASK_ID, fromBlock);

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
        ReflectionTestUtils.setField(iexecHubService, "iexecHubContract", hubContract);

        final IexecHubContract.TaskRevealEventResponse taskRevealEventResponse = getTaskRevealEventResponse(latestBlock);
        when(hubContract.taskRevealEventFlowable(any()))
                .thenReturn(Flowable.fromArray(taskRevealEventResponse));

        final ChainReceipt chainReceipt = iexecHubService.getRevealBlock(CHAIN_TASK_ID, WORKER_ADDRESS, fromBlock);

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
        ReflectionTestUtils.setField(iexecHubService, "iexecHubContract", hubContract);

        final IexecHubContract.TaskFinalizeEventResponse taskFinalizeEventResponse = getTaskFinalizeEventResponse(latestBlock);
        when(hubContract.taskFinalizeEventFlowable(any()))
                .thenReturn(Flowable.fromArray(taskFinalizeEventResponse));

        final ChainReceipt chainReceipt = iexecHubService.getFinalizeBlock(CHAIN_TASK_ID, fromBlock);

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
                (iexecHubService, fromBlock) -> iexecHubService.getContributionBlock(CHAIN_TASK_ID, WORKER_ADDRESS, fromBlock),
                (iexecHubService, fromBlock) -> iexecHubService.getConsensusBlock(CHAIN_TASK_ID, fromBlock),
                (iexecHubService, fromBlock) -> iexecHubService.getRevealBlock(CHAIN_TASK_ID, WORKER_ADDRESS, fromBlock),
                (iexecHubService, fromBlock) -> iexecHubService.getFinalizeBlock(CHAIN_TASK_ID, fromBlock)
        );
    }

    @ParameterizedTest
    @MethodSource("eventBlockGetters")
    void shouldNotGetEventBlockWhenFromBlockInFuture(BiFunction<IexecHubService, Long, ChainReceipt> eventBlockGetter) {
        final long fromBlock = 2;
        final long latestBlock = 1;
        when(web3jService.getLatestBlockNumber()).thenReturn(latestBlock);

        final ChainReceipt chainReceipt = eventBlockGetter.apply(iexecHubService, fromBlock);
        assertThat(chainReceipt)
                .isEqualTo(ChainReceipt.builder().build());
    }
    // endregion

    private TransactionReceipt createReceiptWithoutLogs(List<Log> web3Logs) {
        TransactionReceipt transactionReceipt = new TransactionReceipt();
        transactionReceipt.setBlockNumber("0x1");
        transactionReceipt.setGasUsed("0x186a0");
        transactionReceipt.setLogs(web3Logs);
        return transactionReceipt;
    }

    // region contribute
    @Test
    void shouldContribute() throws Exception {
        String workerAddress = Numeric.toHexStringNoPrefixZeroPadded(
                Numeric.toBigInt(credentials.getAddress()), 64);
        Log web3Log = new Log();
        web3Log.setData("0x1a538512b510ee384ce649b58a938d5c2df4ace50ef51d33f353276501e95662");
        web3Log.setTopics(List.of(TASK_CONTRIBUTE_NOTICE, CHAIN_TASK_ID, workerAddress));
        TransactionReceipt transactionReceipt = createReceiptWithoutLogs(List.of(web3Log));
        when(iexecHubContract.contribute(any(), any(), any(), any(), any(), any())).thenReturn(remoteFunctionCall);
        when(remoteFunctionCall.send()).thenReturn(transactionReceipt);
        doReturn(true).when(iexecHubService).isSuccessTx(any(), any(), any());

        final Contribution contribution = Contribution.builder()
                .chainTaskId(CHAIN_TASK_ID)
                .enclaveChallenge("enclaveChallenge")
                .enclaveSignature("enclaveSignature")
                .resultHash("resultHash")
                .resultSeal("resultSeal")
                .workerPoolSignature("workerPoolSignature")
                .build();
        IexecHubContract.TaskContributeEventResponse response = iexecHubService.contribute(contribution);
        Assertions.assertThat(response).isNotNull();
    }

    @Test
    void shouldNotContributeOnExecutionException() throws ExecutionException, InterruptedException {
        final Contribution contribution = Contribution.builder()
                .chainTaskId(CHAIN_TASK_ID)
                .enclaveChallenge("enclaveChallenge")
                .enclaveSignature("enclaveSignature")
                .resultHash("resultHash")
                .resultSeal("resultSeal")
                .workerPoolSignature("workerPoolSignature")
                .build();
        doThrow(ExecutionException.class).when(iexecHubService).submit(any());
        IexecHubContract.TaskContributeEventResponse response = iexecHubService.contribute(contribution);
        Assertions.assertThat(response).isNull();
    }

    @Test
    void shouldNotContributeWhenInterrupted() throws ExecutionException, InterruptedException {
        final Contribution contribution = Contribution.builder()
                .chainTaskId(CHAIN_TASK_ID)
                .enclaveChallenge("enclaveChallenge")
                .enclaveSignature("enclaveSignature")
                .resultHash("resultHash")
                .resultSeal("resultSeal")
                .workerPoolSignature("workerPoolSignature")
                .build();
        doThrow(InterruptedException.class).when(iexecHubService).submit(any());
        IexecHubContract.TaskContributeEventResponse response = iexecHubService.contribute(contribution);
        Assertions.assertThat(response).isNull();
        Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
    // endregion

    // region reveal
    @Test
    void shouldReveal() throws Exception {
        String workerAddress = Numeric.toHexStringNoPrefixZeroPadded(
                Numeric.toBigInt(credentials.getAddress()), 64);
        Log web3Log = new Log();
        web3Log.setData("0x88f79ce47dc9096bab83327fb3ae0cd99694fd36db6b5f22a4e4e7bf72e79989");
        web3Log.setTopics(List.of(TASK_REVEAL_NOTICE, CHAIN_TASK_ID, workerAddress));
        TransactionReceipt transactionReceipt = createReceiptWithoutLogs(List.of(web3Log));
        when(iexecHubContract.reveal(any(), any())).thenReturn(remoteFunctionCall);
        when(remoteFunctionCall.send()).thenReturn(transactionReceipt);
        doReturn(true).when(iexecHubService).isSuccessTx(any(), any(), any());

        IexecHubContract.TaskRevealEventResponse response = iexecHubService.reveal(CHAIN_TASK_ID, "resultDigest");
        Assertions.assertThat(response).isNotNull();
    }

    @Test
    void shouldNotRevealOnExecutionException() throws ExecutionException, InterruptedException {
        doThrow(ExecutionException.class).when(iexecHubService).submit(any());
        IexecHubContract.TaskRevealEventResponse response = iexecHubService.reveal(CHAIN_TASK_ID, "resultDigest");
        Assertions.assertThat(response).isNull();
    }

    @Test
    void shouldNotRevealWhenInterrupted() throws ExecutionException, InterruptedException {
        doThrow(InterruptedException.class).when(iexecHubService).submit(any());
        IexecHubContract.TaskRevealEventResponse response = iexecHubService.reveal(CHAIN_TASK_ID, "resultDigest");
        Assertions.assertThat(response).isNull();
        Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
    // endregion

    // region contributeAndFinalize
    @Test
    void shouldContributeAndFinalize() throws Exception {
        Log web3Log = new Log();
        web3Log.setData("0x000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000597b202273746f72616765223a202269706673222c20226c6f636174696f6e223a20222f697066732f516d6435763668723848385642444644746f777332786978466f76314833576f704a317645707758756d5a37325522207d00000000000000");
        web3Log.setTopics(List.of(TASK_FINALIZE_NOTICE, CHAIN_TASK_ID));
        TransactionReceipt transactionReceipt = createReceiptWithoutLogs(List.of(web3Log));
        when(iexecHubContract.contributeAndFinalize(any(), any(), any(), any(), any(), any(), any())).thenReturn(remoteFunctionCall);
        when(remoteFunctionCall.send()).thenReturn(transactionReceipt);
        doReturn(true).when(iexecHubService).isSuccessTx(any(), any(), any());

        final Contribution contribution = Contribution.builder()
                .chainTaskId(CHAIN_TASK_ID)
                .enclaveChallenge("enclaveChallenge")
                .enclaveSignature("enclaveSignature")
                .resultDigest("resultDigest")
                .workerPoolSignature("workerPoolSignature")
                .build();
        Optional<ChainReceipt> chainReceipt = iexecHubService.contributeAndFinalize(contribution, "resultLink", "callbackData");
        Assertions.assertThat(chainReceipt).isNotEmpty();
    }

    @Test
    void shouldNotContributeAndFinalizeOnExecutionException() throws Exception {
        final Contribution contribution = Contribution.builder()
                .chainTaskId(CHAIN_TASK_ID)
                .enclaveChallenge("enclaveChallenge")
                .enclaveSignature("enclaveSignature")
                .resultDigest("resultDigest")
                .workerPoolSignature("workerPoolSignature")
                .build();
        doThrow(ExecutionException.class).when(iexecHubService).submit(any());
        Optional<ChainReceipt> chainReceipt = iexecHubService.contributeAndFinalize(contribution, "resultLink", "callbackData");
        Assertions.assertThat(chainReceipt).isEmpty();
    }

    @Test
    void shouldNotContributeAndFinalizeWhenInterrupted() throws Exception {
        final Contribution contribution = Contribution.builder()
                .chainTaskId(CHAIN_TASK_ID)
                .enclaveChallenge("enclaveChallenge")
                .enclaveSignature("enclaveSignature")
                .resultDigest("resultDigest")
                .workerPoolSignature("workerPoolSignature")
                .build();
        doThrow(InterruptedException.class).when(iexecHubService).submit(any());
        Optional<ChainReceipt> chainReceipt = iexecHubService.contributeAndFinalize(contribution, "resultLink", "callbackData");
        Assertions.assertThat(chainReceipt).isEmpty();
        Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
    // endregion

    // region isSuccessTx
    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class)
    void shouldTxBeSuccess(ChainContributionStatus chainContributionStatus) {
        Log log = new Log();
        log.setType("");
        Assertions.assertThat(iexecHubService.isSuccessTx(CHAIN_TASK_ID, log, chainContributionStatus)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class)
    void shouldTxNotBeSuccessWhenLogIsNull(ChainContributionStatus chainContributionStatus) {
        Assertions.assertThat(iexecHubService.isSuccessTx(CHAIN_TASK_ID, null, chainContributionStatus)).isFalse();
    }

    @Test
    void shouldTxNotBeSuccessWhenTimeout() {
        Log log = new Log();
        log.setType("pending");
        when(web3jService.getBlockTime()).thenReturn(Duration.ofMillis(100L));
        doReturn(Optional.empty()).when(iexecHubService).getChainContribution(CHAIN_TASK_ID);
        Assertions.assertThat(iexecHubService.isSuccessTx(CHAIN_TASK_ID, log, ChainContributionStatus.CONTRIBUTED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ChainContributionStatus.class)
    void test(ChainContributionStatus chainContributionStatus) {
        Log log = new Log();
        log.setType("pending");
        ChainContribution chainContribution = ChainContribution.builder().status(chainContributionStatus).build();
        when(web3jService.getBlockTime()).thenReturn(Duration.ofMillis(100L));
        doReturn(Optional.of(chainContribution)).when(iexecHubService).getChainContribution(CHAIN_TASK_ID);
        Assertions.assertThat(iexecHubService.isSuccessTx(CHAIN_TASK_ID, log, chainContributionStatus)).isTrue();
    }
    // endregion

    // region ChainTask status
    @ParameterizedTest
    @EnumSource(value = ChainTaskStatus.class, names = "ACTIVE")
    void shouldChainTaskBeActive(ChainTaskStatus chainTaskStatus) {
        doReturn(Optional.of(ChainTask.builder().status(chainTaskStatus).build()))
                .when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        Assertions.assertThat(iexecHubService.isChainTaskActive(CHAIN_TASK_ID)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ChainTaskStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void shouldChainTaskNotBeActive(ChainTaskStatus chainTaskStatus) {
        doReturn(Optional.of(ChainTask.builder().status(chainTaskStatus).build()))
                .when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        Assertions.assertThat(iexecHubService.isChainTaskActive(CHAIN_TASK_ID)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ChainTaskStatus.class, names = "REVEALING")
    void shouldChainTaskBeRevealing(ChainTaskStatus chainTaskStatus) {
        doReturn(Optional.of(ChainTask.builder().status(chainTaskStatus).build()))
                .when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        Assertions.assertThat(iexecHubService.isChainTaskRevealing(CHAIN_TASK_ID)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ChainTaskStatus.class, names = "REVEALING", mode = EnumSource.Mode.EXCLUDE)
    void shouldChainTaskNotBeRevealing(ChainTaskStatus chainTaskStatus) {
        doReturn(Optional.of(ChainTask.builder().status(chainTaskStatus).build()))
                .when(iexecHubService).getChainTask(CHAIN_TASK_ID);
        Assertions.assertThat(iexecHubService.isChainTaskRevealing(CHAIN_TASK_ID)).isFalse();
    }
    // endregion
}
