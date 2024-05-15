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
import com.iexec.common.lifecycle.purge.Purgeable;
import com.iexec.commons.poco.chain.*;
import com.iexec.commons.poco.contract.generated.IexecHubContract;
import com.iexec.commons.poco.utils.BytesUtils;
import com.iexec.standalone.config.BlockchainAdapterConfigurationService;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.datatypes.Event;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.iexec.commons.poco.chain.ChainContributionStatus.CONTRIBUTED;
import static com.iexec.commons.poco.chain.ChainContributionStatus.REVEALED;
import static com.iexec.commons.poco.contract.generated.IexecHubContract.*;
import static com.iexec.commons.poco.utils.BytesUtils.bytesToString;
import static com.iexec.commons.poco.utils.BytesUtils.stringToBytes;

@Slf4j
@Service
public class IexecHubService extends IexecHubAbstractService implements Purgeable {

    private static final String PENDING_RECEIPT_STATUS = "pending";
    private final ThreadPoolExecutor executor;
    private final CredentialsService credentialsService;
    private final Web3jService web3jService;

    public IexecHubService(CredentialsService credentialsService,
                           Web3jService web3jService,
                           ChainConfig chainConfig) {
        super(
                credentialsService.getCredentials(),
                web3jService,
                chainConfig.getHubAddress());
        this.credentialsService = credentialsService;
        this.web3jService = web3jService;
        this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        if (!hasEnoughGas()) {
            System.exit(0);
        }
    }

    @Autowired
    public IexecHubService(CredentialsService credentialsService,
                           Web3jService web3jService,
                           BlockchainAdapterConfigurationService blockchainAdapterConfigurationService) {
        super(credentialsService.getCredentials(),
                web3jService,
                blockchainAdapterConfigurationService.getIexecHubContractAddress(),
                1,
                5);
        this.credentialsService = credentialsService;
        this.web3jService = web3jService;
        this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
    }

    /**
     * Check if the task is defined onchain and
     * has the status {@link ChainTaskStatus#UNSET}.
     *
     * @param chainDealId
     * @param taskIndex
     * @return true if the task is found with the status UNSET, false otherwise.
     */
    //TODO Migrate to common
    public boolean isTaskInUnsetStatusOnChain(String chainDealId, int taskIndex) {
        String generatedChainTaskId = ChainUtils.generateChainTaskId(chainDealId, taskIndex);
        Optional<ChainTask> chainTask = getChainTask(generatedChainTaskId);
        return chainTask.isEmpty()
                || ChainTaskStatus.UNSET.equals(chainTask.get().getStatus());
    }


    public boolean isTaskInCompletedStatusOnChain(String chainTaskId) {
        return getChainTask(chainTaskId)
                .filter(chainTask -> ChainTaskStatus.COMPLETED == chainTask.getStatus())
                .isPresent();
    }

    /**
     * Check if a deal's contribution deadline
     * is still not reached.
     *
     * @param chainDealId
     * @return true if deadline is not reached, false otherwise.
     */
    public boolean isBeforeContributionDeadline(String chainDealId) {
        return getChainDeal(chainDealId)
                .map(this::isBeforeContributionDeadline)
                .orElse(false);
    }

    /**
     * Check if a deal's contribution deadline
     * is still not reached.
     *
     * @param chainDeal
     * @return true if deadline is not reached, false otherwise.
     */
    public boolean isBeforeContributionDeadline(ChainDeal chainDeal) {
        return getChainDealContributionDeadline(chainDeal)
                .after(new Date());
    }

    /**
     * <p> Get deal's contribution deadline date. The deadline
     * is calculated as follow:
     * start + maxCategoryTime * maxNbOfPeriods.
     *
     * <ul>
     * <li> start: the start time of the deal.
     * <li> maxCategoryTime: duration of the deal's category.
     * <li> nbOfCategoryUnits: number of category units dedicated
     *      for the contribution phase.
     * </ul>
     *
     * @param chainDeal
     * @return
     */
    public Date getChainDealContributionDeadline(ChainDeal chainDeal) {
        long startTime = chainDeal.getStartTime().longValue() * 1000;
        long maxTime = chainDeal.getChainCategory().getMaxExecutionTime();
        long maxNbOfPeriods = getMaxNbOfPeriodsForConsensus();
        maxNbOfPeriods = (maxNbOfPeriods == -1) ? 10 : maxNbOfPeriods;
        return new Date(startTime + maxTime * maxNbOfPeriods);
    }

    /**
     * <p> Get deal's final deadline date. The deadline
     * is calculated as follow:
     * start + maxCategoryTime * 10.
     *
     * <ul>
     * <li> start: the start time of the deal.
     * <li> maxCategoryTime: duration of the deal's category.
     * <li> 10: number of category units dedicated
     *      for the hole execution.
     * </ul>
     *
     * @param chainDeal
     * @return
     */
    public Date getChainDealFinalDeadline(ChainDeal chainDeal) {
        long startTime = chainDeal.getStartTime().longValue() * 1000;
        long maxTime = chainDeal.getChainCategory().getMaxExecutionTime();
        return new Date(startTime + maxTime * 10);
    }

    public boolean canFinalize(String chainTaskId) {
        final ChainTask chainTask = getChainTask(chainTaskId).orElse(null);
        if (chainTask == null) {
            return false;
        }

        final boolean isChainTaskStatusRevealing = chainTask.getStatus() == ChainTaskStatus.REVEALING;
        final boolean isFinalDeadlineInFuture = Instant.now().toEpochMilli() < chainTask.getFinalDeadline();
        final boolean hasEnoughRevealors = chainTask.getRevealCounter() == chainTask.getWinnerCounter()
                || (chainTask.getRevealCounter() > 0 && chainTask.getRevealDeadline() <= Instant.now().toEpochMilli());
        final boolean ret = isChainTaskStatusRevealing && isFinalDeadlineInFuture && hasEnoughRevealors;

        if (ret) {
            log.info("Finalizable onchain [chainTaskId:{}]", chainTaskId);
        } else {
            log.warn("Can't finalize [chainTaskId:{}, " +
                            "isChainTaskStatusRevealing:{}, isFinalDeadlineInFuture:{}, hasEnoughRevealors:{}]", chainTaskId,
                    isChainTaskStatusRevealing, isFinalDeadlineInFuture, hasEnoughRevealors);
        }
        return ret;
    }

    public boolean canReopen(String chainTaskId) {
        final ChainTask chainTask = getChainTask(chainTaskId).orElse(null);
        if (chainTask == null) {
            return false;
        }

        boolean isChainTaskStatusRevealing = chainTask.getStatus() == ChainTaskStatus.REVEALING;
        boolean isBeforeFinalDeadline = Instant.now().toEpochMilli() < chainTask.getFinalDeadline();
        boolean isAfterRevealDeadline = chainTask.getRevealDeadline() <= Instant.now().toEpochMilli();
        boolean revealCounterEqualsZero = chainTask.getRevealCounter() == 0;

        boolean check = isChainTaskStatusRevealing && isBeforeFinalDeadline && isAfterRevealDeadline
                && revealCounterEqualsZero;
        if (check) {
            log.info("Reopenable onchain [chainTaskId:{}]", chainTaskId);
        } else {
            log.warn("Can't reopen [chainTaskId:{}, " +
                            "isChainTaskStatusRevealing:{}, isBeforeFinalDeadline:{}, " +
                            "isAfterRevealDeadline:{}, revealCounterEqualsZero:{}]", chainTaskId,
                    isChainTaskStatusRevealing, isBeforeFinalDeadline, isAfterRevealDeadline, revealCounterEqualsZero);
        }
        return check;
    }

    public Optional<ChainReceipt> reOpen(String chainTaskId) {
        log.info("Requested reopen [chainTaskId:{}, waitingTxCount:{}]", chainTaskId, getWaitingTransactionCount());
        try {
            return CompletableFuture.supplyAsync(() -> sendReopenTransaction(chainTaskId), executor).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("reOpen asynchronous execution did not complete", e);
        }
        return Optional.empty();
    }

    private Optional<ChainReceipt> sendReopenTransaction(String chainTaskId) {
        TransactionReceipt receipt;
        try {
            receipt = iexecHubContract.reopen(stringToBytes(chainTaskId)).send();
        } catch (Exception e) {
            log.error("Failed reopen [chainTaskId:{}, error:{}]", chainTaskId, e.getMessage());
            return Optional.empty();
        }

        List<TaskReopenEventResponse> eventsList = IexecHubContract.getTaskReopenEvents(receipt);
        if (eventsList.isEmpty()) {
            log.error("Failed to get reopen event [chainTaskId:{}]", chainTaskId);
            return Optional.empty();
        }

        ChainReceipt chainReceipt = buildChainReceipt(receipt);
        log.info("Reopened [chainTaskId:{}, gasUsed:{}, block:{}]",
                chainTaskId, receipt.getGasUsed(), chainReceipt.getBlockNumber());
        return Optional.of(chainReceipt);
    }

    private long getWaitingTransactionCount() {
        return executor.getTaskCount() - executor.getCompletedTaskCount();
    }

    Flowable<SchedulerNoticeEventResponse> getDealEventObservable(EthFilter filter) {
        return iexecHubContract.schedulerNoticeEventFlowable(filter);
    }

    public boolean hasEnoughGas() {
        final boolean hasEnoughGas = hasEnoughGas(credentialsService.getCredentials().getAddress());
        log.debug("Gas status [hasEnoughGas:{}]", hasEnoughGas);
        return hasEnoughGas;
    }

    private ChainReceipt buildChainReceipt(TransactionReceipt receipt) {
        return ChainReceipt.builder()
                .txHash(receipt.getTransactionHash())
                .blockNumber(receipt.getBlockNumber() != null ?
                        receipt.getBlockNumber().longValue() : 0)
                .build();
    }

    // region check contribution status
    public boolean repeatIsContributedTrue(String chainTaskId, String walletAddress) {
        return web3jService.repeatCheck(NB_BLOCKS_TO_WAIT_PER_RETRY, MAX_RETRIES,
                "isContributedTrue", this::isContributed, chainTaskId, walletAddress);
    }

    public boolean repeatIsRevealedTrue(String chainTaskId, String walletAddress) {
        return web3jService.repeatCheck(NB_BLOCKS_TO_WAIT_PER_RETRY, MAX_RETRIES,
                "isRevealedTrue", this::isRevealed, chainTaskId, walletAddress);
    }

    public boolean isContributed(String... args) {
        return getChainContribution(args[0], args[1])
                .map(ChainContribution::getStatus)
                .filter(chainStatus -> chainStatus == CONTRIBUTED || chainStatus == REVEALED)
                .isPresent();
    }

    public boolean isRevealed(String... args) {
        return getChainContribution(args[0], args[1])
                .map(ChainContribution::getStatus)
                .filter(chainStatus -> chainStatus == REVEALED)
                .isPresent();
    }
    // endregion

    // region get event blocks
    public ChainReceipt getContributionBlock(String chainTaskId,
                                             String workerWallet,
                                             long fromBlock) {
        long latestBlock = web3jService.getLatestBlockNumber();
        if (fromBlock > latestBlock) {
            return ChainReceipt.builder().build();
        }

        EthFilter ethFilter = createContributeEthFilter(fromBlock, latestBlock);

        // filter only taskContribute events for the chainTaskId and the worker's wallet
        // and retrieve the block number of the event
        return iexecHubContract.taskContributeEventFlowable(ethFilter)
                .filter(eventResponse ->
                        chainTaskId.equals(BytesUtils.bytesToString(eventResponse.taskid)) &&
                                workerWallet.equals(eventResponse.worker)
                )
                .map(eventResponse -> ChainReceipt.builder()
                        .blockNumber(eventResponse.log.getBlockNumber().longValue())
                        .txHash(eventResponse.log.getTransactionHash())
                        .build())
                .blockingFirst();
    }

    public ChainReceipt getConsensusBlock(String chainTaskId, long fromBlock) {
        long latestBlock = web3jService.getLatestBlockNumber();
        if (fromBlock > latestBlock) {
            return ChainReceipt.builder().build();
        }

        EthFilter ethFilter = createConsensusEthFilter(fromBlock, latestBlock);

        // filter only taskConsensus events for the chainTaskId (there should be only one)
        // and retrieve the block number of the event
        return iexecHubContract.taskConsensusEventFlowable(ethFilter)
                .filter(eventResponse -> chainTaskId.equals(BytesUtils.bytesToString(eventResponse.taskid)))
                .map(eventResponse -> ChainReceipt.builder()
                        .blockNumber(eventResponse.log.getBlockNumber().longValue())
                        .txHash(eventResponse.log.getTransactionHash())
                        .build())
                .blockingFirst();
    }

    public ChainReceipt getRevealBlock(String chainTaskId,
                                       String workerWallet,
                                       long fromBlock) {
        long latestBlock = web3jService.getLatestBlockNumber();
        if (fromBlock > latestBlock) {
            return ChainReceipt.builder().build();
        }

        EthFilter ethFilter = createRevealEthFilter(fromBlock, latestBlock);

        // filter only taskReveal events for the chainTaskId and the worker's wallet
        // and retrieve the block number of the event
        return iexecHubContract.taskRevealEventFlowable(ethFilter)
                .filter(eventResponse ->
                        chainTaskId.equals(BytesUtils.bytesToString(eventResponse.taskid)) &&
                                workerWallet.equals(eventResponse.worker)
                )
                .map(eventResponse -> ChainReceipt.builder()
                        .blockNumber(eventResponse.log.getBlockNumber().longValue())
                        .txHash(eventResponse.log.getTransactionHash())
                        .build())
                .blockingFirst();
    }

    public ChainReceipt getFinalizeBlock(String chainTaskId, long fromBlock) {
        long latestBlock = web3jService.getLatestBlockNumber();
        if (fromBlock > latestBlock) {
            return ChainReceipt.builder().build();
        }

        EthFilter ethFilter = createFinalizeEthFilter(fromBlock, latestBlock);

        // filter only taskFinalize events for the chainTaskId (there should be only one)
        // and retrieve the block number of the event
        return iexecHubContract.taskFinalizeEventFlowable(ethFilter)
                .filter(eventResponse ->
                        chainTaskId.equals(BytesUtils.bytesToString(eventResponse.taskid))
                )
                .map(eventResponse -> ChainReceipt.builder()
                        .blockNumber(eventResponse.log.getBlockNumber().longValue())
                        .txHash(eventResponse.log.getTransactionHash())
                        .build())
                .blockingFirst();
    }

    private EthFilter createContributeEthFilter(long fromBlock, long toBlock) {
        return createEthFilter(fromBlock, toBlock, TASKCONTRIBUTE_EVENT);
    }

    private EthFilter createConsensusEthFilter(long fromBlock, long toBlock) {
        return createEthFilter(fromBlock, toBlock, TASKCONSENSUS_EVENT);
    }

    private EthFilter createRevealEthFilter(long fromBlock, long toBlock) {
        return createEthFilter(fromBlock, toBlock, TASKREVEAL_EVENT);
    }

    private EthFilter createFinalizeEthFilter(long fromBlock, long toBlock) {
        return createEthFilter(fromBlock, toBlock, TASKFINALIZE_EVENT);
    }

    private EthFilter createEthFilter(long fromBlock, long toBlock, Event event) {
        IexecHubContract iexecHub = getHubContract();
        DefaultBlockParameter startBlock =
                DefaultBlockParameter.valueOf(BigInteger.valueOf(fromBlock));
        DefaultBlockParameter endBlock =
                DefaultBlockParameter.valueOf(BigInteger.valueOf(toBlock));

        // define the filter
        EthFilter ethFilter = new EthFilter(
                startBlock,
                endBlock,
                iexecHub.getContractAddress()
        );
        ethFilter.addSingleTopic(EventEncoder.encode(event));

        return ethFilter;
    }
    // endregion

    @Override
    public boolean purgeTask(String chainTaskId) {
        return super.purgeTask(chainTaskId);
    }

    @Override
    public void purgeAllTasksData() {
        super.purgeAllTasksData();
    }

    // region contribute
    IexecHubContract.TaskContributeEventResponse contribute(Contribution contribution) {
        log.info("contribute request [chainTaskId:{}, waitingTxCount:{}]", contribution.getChainTaskId(), getWaitingTransactionCount());
        return sendContributeTransaction(contribution);
    }

    private IexecHubContract.TaskContributeEventResponse sendContributeTransaction(Contribution contribution) {
        String chainTaskId = contribution.getChainTaskId();

        RemoteCall<TransactionReceipt> contributeCall = iexecHubContract.contribute(
                stringToBytes(chainTaskId),
                stringToBytes(contribution.getResultHash()),
                stringToBytes(contribution.getResultSeal()),
                contribution.getEnclaveChallenge(),
                stringToBytes(contribution.getEnclaveSignature()),
                stringToBytes(contribution.getWorkerPoolSignature()));
        log.info("Sent contribute [chainTaskId:{}, contribution:{}]", chainTaskId, contribution);

        TransactionReceipt contributeReceipt = submit(chainTaskId, "contribute", contributeCall);

        List<IexecHubContract.TaskContributeEventResponse> contributeEvents =
                IexecHubContract.getTaskContributeEvents(contributeReceipt).stream()
                        .filter(event -> Objects.equals(bytesToString(event.taskid), chainTaskId)
                                && Objects.equals(event.worker, credentialsService.getCredentials().getAddress()))
                        .collect(Collectors.toList());
        log.debug("contributeEvents count {} [chainTaskId: {}]", contributeEvents.size(), chainTaskId);

        if (!contributeEvents.isEmpty()) {
            IexecHubContract.TaskContributeEventResponse contributeEvent = contributeEvents.get(0);
            if (isSuccessTx(chainTaskId, contributeEvent.log, CONTRIBUTED)) {
                log.info("Contributed [chainTaskId:{}, contribution:{}, gasUsed:{}, log:{}]",
                        chainTaskId, contribution, contributeReceipt.getGasUsed(), contributeEvent.log);
                return contributeEvent;
            }
        }

        log.error("Failed to contribute [chainTaskId:{}]", chainTaskId);
        return null;
    }
    // endregion

    // region reveal
    IexecHubContract.TaskRevealEventResponse reveal(String chainTaskId, String resultDigest) {
        log.info("reveal request [chainTaskId:{}, waitingTxCount:{}]", chainTaskId, getWaitingTransactionCount());
        return sendRevealTransaction(chainTaskId, resultDigest);
    }

    private IexecHubContract.TaskRevealEventResponse sendRevealTransaction(String chainTaskId, String resultDigest) {
        RemoteCall<TransactionReceipt> revealCall = iexecHubContract.reveal(
                stringToBytes(chainTaskId),
                stringToBytes(resultDigest));
        log.info("Sent reveal [chainTaskId:{}, resultDigest:{}]", chainTaskId, resultDigest);

        TransactionReceipt revealReceipt = submit(chainTaskId, "reveal", revealCall);

        List<IexecHubContract.TaskRevealEventResponse> revealEvents =
                IexecHubContract.getTaskRevealEvents(revealReceipt).stream()
                        .filter(event -> Objects.equals(bytesToString(event.taskid), chainTaskId)
                                && Objects.equals(event.worker, credentialsService.getCredentials().getAddress()))
                        .collect(Collectors.toList());
        log.debug("revealEvents count {} [chainTaskId:{}]", revealEvents.size(), chainTaskId);

        if (!revealEvents.isEmpty()) {
            IexecHubContract.TaskRevealEventResponse revealEvent = revealEvents.get(0);
            if (isSuccessTx(chainTaskId, revealEvent.log, REVEALED)) {
                log.info("Revealed [chainTaskId:{}, resultDigest:{}, gasUsed:{}, log:{}]",
                        chainTaskId, resultDigest, revealReceipt.getGasUsed(), revealEvent.log);
                return revealEvent;
            }
        }

        log.error("Failed to reveal [chainTaskId:{}]", chainTaskId);
        return null;
    }
    // endregion reveal

    // region contributeAndFinalize
    public Optional<ChainReceipt> contributeAndFinalize(Contribution contribution, String resultLink, String callbackData) {
        log.info("contributeAndFinalize request [chainTaskId:{}, waitingTxCount:{}]",
                contribution.getChainTaskId(), getWaitingTransactionCount());
        IexecHubContract.TaskFinalizeEventResponse finalizeEvent = sendContributeAndFinalizeTransaction(contribution, resultLink, callbackData);
        return Optional.ofNullable(finalizeEvent)
                .map(event -> ChainUtils.buildChainReceipt(event.log, contribution.getChainTaskId(), getLatestBlockNumber()));
    }

    private IexecHubContract.TaskFinalizeEventResponse sendContributeAndFinalizeTransaction(Contribution contribution, String resultLink, String callbackData) {
        String chainTaskId = contribution.getChainTaskId();

        RemoteCall<TransactionReceipt> contributeAndFinalizeCall = iexecHubContract.contributeAndFinalize(
                stringToBytes(chainTaskId),
                stringToBytes(contribution.getResultDigest()),
                StringUtils.isNotEmpty(resultLink) ? resultLink.getBytes(StandardCharsets.UTF_8) : new byte[0],
                StringUtils.isNotEmpty(callbackData) ? stringToBytes(callbackData) : new byte[0],
                contribution.getEnclaveChallenge(),
                stringToBytes(contribution.getEnclaveSignature()),
                stringToBytes(contribution.getWorkerPoolSignature()));
        log.info("Sent contributeAndFinalize [chainTaskId:{}, contribution:{}, resultLink:{}, callbackData:{}]",
                chainTaskId, contribution, resultLink, callbackData);

        TransactionReceipt receipt = submit(chainTaskId, "contributeAndFinalize", contributeAndFinalizeCall);

        List<IexecHubContract.TaskFinalizeEventResponse> finalizeEvents =
                IexecHubContract.getTaskFinalizeEvents(receipt).stream()
                        .filter(event -> Objects.equals(bytesToString(event.taskid), chainTaskId))
                        .collect(Collectors.toList());
        log.debug("finalizeEvents count {} [chainTaskId:{}]", finalizeEvents.size(), chainTaskId);

        if (!finalizeEvents.isEmpty()) {
            IexecHubContract.TaskFinalizeEventResponse finalizeEvent = finalizeEvents.get(0);
            if (isSuccessTx(chainTaskId, finalizeEvent.log, REVEALED)) {
                log.info("contributeAndFinalize done [chainTaskId:{}, contribution:{}, gasUsed:{}, log:{}]",
                        chainTaskId, contribution, receipt.getGasUsed(), finalizeEvent.log);
                return finalizeEvent;
            }
        }

        log.error("contributeAndFinalize failed [chainTaskId:{}]", chainTaskId);
        return null;
    }
    // endregion

    // region isSuccessTx
    boolean isSuccessTx(String chainTaskId, Log eventLog, ChainContributionStatus pretendedStatus) {
        if (eventLog == null) {
            return false;
        }

        log.info("event log type {}", eventLog.getType());
        if (PENDING_RECEIPT_STATUS.equals(eventLog.getType())) {
            return isStatusValidOnChainAfterPendingReceipt(chainTaskId, pretendedStatus);
        }

        return true;
    }

    private boolean isContributionStatusValidOnChain(String chainTaskId, ChainContributionStatus chainContributionStatus) {
        Optional<ChainContribution> chainContribution = getChainContribution(chainTaskId);
        return chainContribution.isPresent() && chainContribution.get().getStatus() == chainContributionStatus;
    }

    private boolean isStatusValidOnChainAfterPendingReceipt(String chainTaskId, ChainContributionStatus onchainStatus) {
        long maxWaitingTime = 10 * web3jService.getBlockTime().toMillis();
        log.info("Waiting for on-chain status after pending receipt " +
                        "[chainTaskId:{}, status:{}, maxWaitingTime:{}]",
                chainTaskId, onchainStatus, maxWaitingTime);

        final long startTime = System.currentTimeMillis();
        long duration = 0;
        while (duration < maxWaitingTime) {
            try {
                if (isContributionStatusValidOnChain(chainTaskId, onchainStatus)) {
                    return true;
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                log.error("Error in checking the latest block number", e);
                Thread.currentThread().interrupt();
            }
            duration = System.currentTimeMillis() - startTime;
        }

        log.error("Timeout reached after waiting for on-chain status " +
                        "[chainTaskId:{}, maxWaitingTime:{}]",
                chainTaskId, maxWaitingTime);
        return false;
    }
    // endregion

    Optional<ChainContribution> getChainContribution(String chainTaskId) {
        return getChainContribution(chainTaskId, credentialsService.getCredentials().getAddress());
    }

    Optional<ChainAccount> getChainAccount() {
        return getChainAccount(credentialsService.getCredentials().getAddress());
    }

    public long getLatestBlockNumber() {
        return web3jService.getLatestBlockNumber();
    }

    boolean isChainTaskActive(String chainTaskId) {
        Optional<ChainTask> chainTask = getChainTask(chainTaskId);
        return chainTask.filter(task -> task.getStatus() == ChainTaskStatus.ACTIVE).isPresent();
    }

    boolean isChainTaskRevealing(String chainTaskId) {
        Optional<ChainTask> chainTask = getChainTask(chainTaskId);
        return chainTask.filter(task -> task.getStatus() == ChainTaskStatus.REVEALING).isPresent();
    }

    TransactionReceipt submit(String chainTaskId, String transactionType, RemoteCall<TransactionReceipt> remoteCall) {
        try {
            final RemoteCallTask remoteCallSend = new RemoteCallTask(chainTaskId, transactionType, remoteCall);
            return submit(remoteCallSend);
        } catch (ExecutionException e) {
            log.error("{} asynchronous execution did not complete", transactionType, e);
        } catch (InterruptedException e) {
            log.error("{} thread has been interrupted", transactionType, e);
            Thread.currentThread().interrupt();
        }
        // return non-null receipt with empty logs on failure
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setLogs(List.of());
        return receipt;
    }

    TransactionReceipt submit(RemoteCallTask remoteCallTask) throws ExecutionException, InterruptedException {
        Future<TransactionReceipt> future = executor.submit(remoteCallTask);
        return future.get();
    }

    /**
     * Sends a transaction to the blockchain and returns its receipt.
     */
    static class RemoteCallTask implements Callable<TransactionReceipt> {
        private final String chainTaskId;
        private final String transactionType;
        private final RemoteCall<TransactionReceipt> remoteCall;

        public RemoteCallTask(String chainTaskId, String transactionType, RemoteCall<TransactionReceipt> remoteCall) {
            this.chainTaskId = chainTaskId;
            this.transactionType = transactionType;
            this.remoteCall = remoteCall;
        }

        @Override
        public TransactionReceipt call() throws Exception {
            TransactionReceipt receipt = remoteCall.send();
            log.debug("{} transaction hash {} at block {} [chainTaskId:{}]",
                    transactionType, receipt.getTransactionHash(), receipt.getBlockNumber(), chainTaskId);
            log.info("{} receipt [chainTaskId:{}]", transactionType, chainTaskId);
            return receipt;
        }
    }
}
