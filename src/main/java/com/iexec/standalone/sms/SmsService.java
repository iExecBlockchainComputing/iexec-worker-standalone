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

package com.iexec.standalone.sms;

import com.iexec.common.lifecycle.purge.ExpiringTaskMapFactory;
import com.iexec.common.lifecycle.purge.Purgeable;
import com.iexec.common.web.ApiResponseBodyDecoder;
import com.iexec.commons.poco.chain.SignerService;
import com.iexec.commons.poco.chain.WorkerpoolAuthorization;
import com.iexec.commons.poco.tee.TeeFramework;
import com.iexec.commons.poco.tee.TeeUtils;
import com.iexec.commons.poco.utils.BytesUtils;
import com.iexec.commons.poco.utils.HashUtils;
import com.iexec.sms.api.*;
import com.iexec.standalone.chain.SignatureService;
import com.iexec.standalone.registry.PlatformRegistryConfiguration;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Hash;

import java.util.Map;
import java.util.Optional;

import static com.iexec.sms.secret.ReservedSecretKeyName.IEXEC_RESULT_IEXEC_IPFS_TOKEN;

@Slf4j
@Service
public class SmsService implements Purgeable {

    private final PlatformRegistryConfiguration registryConfiguration;
    private final SignatureService signatureService;
    private final SignerService signerService;
    private final SmsClientProvider smsClientProvider;
    private final Map<String, String> taskIdToSmsUrl = ExpiringTaskMapFactory.getExpiringTaskMap();

    public SmsService(PlatformRegistryConfiguration registryConfiguration,
                      SignatureService signatureService,
                      SignerService signerService,
                      SmsClientProvider smsClientProvider) {
        this.registryConfiguration = registryConfiguration;
        this.signatureService = signatureService;
        this.signerService = signerService;
        this.smsClientProvider = smsClientProvider;
    }

    /**
     * Checks the following conditions:
     * <ul>
     *     <li>Given deal exists on-chain;</li>
     *     <li>The {@link SmsClient} can be created, based on the on-chain deal definition;</li>
     *     <li>The targeted SMS is configured to run with the task's TEE framework.</li>
     * </ul>
     * <p>
     * If any of these conditions is wrong, then the {@link SmsClient} is considered to be not-ready.
     *
     * @param chainTaskId ID of the on-chain task.
     * @param tag         Tag of the deal.
     * @return SMS url if TEE types of tag &amp; SMS match.
     */
    public Optional<String> getVerifiedSmsUrl(String chainTaskId, String tag) {
        final TeeFramework teeFrameworkForDeal = TeeUtils.getTeeFramework(tag);
        if (teeFrameworkForDeal == null) {
            log.error("Can't get verified SMS url with invalid TEE framework " +
                    "from tag [chainTaskId:{}]", chainTaskId);
            return Optional.empty();
        }
        Optional<String> smsUrl = retrieveSmsUrl(teeFrameworkForDeal);
        if (smsUrl.isEmpty()) {
            log.error("Can't get verified SMS url since type of tag is not " +
                            "supported [chainTaskId:{},teeFrameworkForDeal:{}]",
                    chainTaskId, teeFrameworkForDeal);
            return Optional.empty();
        }
        final SmsClient smsClient = smsClientProvider.getSmsClient(smsUrl.get());
        if (!checkSmsTeeFramework(smsClient, teeFrameworkForDeal, chainTaskId)) {
            log.error("Can't get verified SMS url since tag TEE type " +
                            "does not match SMS TEE type [chainTaskId:{},teeFrameworkForDeal:{}]",
                    chainTaskId, teeFrameworkForDeal);
            return Optional.empty();
        }
        return smsUrl;
    }

    private Optional<String> retrieveSmsUrl(TeeFramework teeFramework) {
        Optional<String> smsUrl = Optional.empty();
        if (teeFramework == TeeFramework.SCONE) {
            smsUrl = Optional.of(registryConfiguration.getSconeSms());
        } else if (teeFramework == TeeFramework.GRAMINE) {
            smsUrl = Optional.of(registryConfiguration.getGramineSms());
        }
        return smsUrl;
    }

    private boolean checkSmsTeeFramework(SmsClient smsClient,
                                         TeeFramework teeFrameworkForDeal,
                                         String chainTaskId) {
        final TeeFramework smsTeeFramework;
        try {
            smsTeeFramework = smsClient.getTeeFramework();
        } catch (FeignException e) {
            log.error("Can't retrieve SMS TEE framework [chainTaskId:{}]",
                    chainTaskId, e);
            return false;
        }

        if (smsTeeFramework != teeFrameworkForDeal) {
            log.error("SMS is configured for another TEE framework " +
                            "[chainTaskId:{}, teeFrameworkForDeal:{}, smsTeeFramework:{}]",
                    chainTaskId, teeFrameworkForDeal, smsTeeFramework);
            return false;
        }
        return true;
    }

    public Optional<String> getEnclaveChallenge(String chainTaskId, String smsUrl) {
        return StringUtils.isEmpty(smsUrl)
                ? Optional.of(BytesUtils.EMPTY_ADDRESS)
                : generateEnclaveChallenge(chainTaskId, smsUrl);
    }

    Optional<String> generateEnclaveChallenge(String chainTaskId, String smsUrl) {
        // SMS client should already have been created once before.
        // If it couldn't be created, then the task would have been aborted.
        // So the following won't throw an exception.
        final SmsClient smsClient = smsClientProvider.getSmsClient(smsUrl);

        try {
            final String teeChallengePublicKey = smsClient.generateTeeChallenge(
                    signatureService.createAuthorization("", chainTaskId, "").getSignature().getValue(),
                    chainTaskId);

            if (StringUtils.isEmpty(teeChallengePublicKey)) {
                log.error("An error occurred while getting teeChallengePublicKey [chainTaskId:{}, smsUrl:{}]",
                        chainTaskId, smsUrl);
                return Optional.empty();
            }

            return Optional.of(teeChallengePublicKey);
        } catch (FeignException e) {
            log.error("Failed to get enclaveChallenge from SMS: unexpected return code [chainTaskId:{}, smsUrl:{}, statusCode:{}]",
                    chainTaskId, smsUrl, e.status(), e);
        } catch (RuntimeException e) {
            log.error("Failed to get enclaveChallenge from SMS: unexpected exception [chainTaskId:{}, smsUrl:{}]",
                    chainTaskId, smsUrl, e);
        }
        return Optional.empty();
    }

    public void attachSmsUrlToTask(String chainTaskId, String smsUrl) {
        taskIdToSmsUrl.put(chainTaskId, smsUrl);
    }

    public SmsClient getSmsClient(String chainTaskId) {
        String url = taskIdToSmsUrl.get(chainTaskId);
        if (StringUtils.isEmpty(url)) {
            // if url is not here anymore, worker might hit core on GET /tasks
            // to retrieve SMS URL
            throw new SmsClientCreationException("No SMS URL defined for " +
                    "given task [chainTaskId: " + chainTaskId + "]");
        }
        return smsClientProvider.getSmsClient(url);
    }

    /**
     * Checks if a JWT is present to upload results for TEE tasks.
     *
     * @param workerpoolAuthorization Authorization
     * @return {@literal true} if an entry was found, {@literal false} if the secret was not found or an error happened
     */
    private boolean isTokenPresent(WorkerpoolAuthorization workerpoolAuthorization) {
        final SmsClient smsClient = getSmsClient(workerpoolAuthorization.getChainTaskId());
        try {
            smsClient.isWeb2SecretSet(workerpoolAuthorization.getWorkerWallet(), IEXEC_RESULT_IEXEC_IPFS_TOKEN);
            return true;
        } catch (FeignException.NotFound e) {
            log.info("Worker Result Proxy JWT does not exist in SMS");
        } catch (FeignException e) {
            log.error("Worker Result Proxy JWT existence check failed with error", e);
        }
        return false;
    }

    /**
     * Push a JWT as a Web2 secret in the SMS.
     *
     * @param workerpoolAuthorization Authorization
     * @param token                   JWT to push in the SMS
     * @return {@literal true} if secret is in SMS, {@literal false} otherwise
     */
    public boolean pushToken(WorkerpoolAuthorization workerpoolAuthorization, String token) {
        final SmsClient smsClient = getSmsClient(workerpoolAuthorization.getChainTaskId());
        try {
            final String challenge = HashUtils.concatenateAndHash(
                    Hash.sha3String("IEXEC_SMS_DOMAIN"),
                    workerpoolAuthorization.getWorkerWallet(),
                    Hash.sha3String(IEXEC_RESULT_IEXEC_IPFS_TOKEN),
                    Hash.sha3String(token));
            final String authorization = signerService.signMessageHash(challenge).getValue();
            if (authorization.isEmpty()) {
                log.error("Couldn't sign challenge for an unknown reason [hash:{}]", challenge);
                return false;
            }

            if (isTokenPresent(workerpoolAuthorization)) {
                smsClient.updateWeb2Secret(authorization, workerpoolAuthorization.getWorkerWallet(), IEXEC_RESULT_IEXEC_IPFS_TOKEN, token);
            } else {
                smsClient.setWeb2Secret(authorization, workerpoolAuthorization.getWorkerWallet(), IEXEC_RESULT_IEXEC_IPFS_TOKEN, token);
            }
            smsClient.isWeb2SecretSet(workerpoolAuthorization.getWorkerWallet(), IEXEC_RESULT_IEXEC_IPFS_TOKEN);
            return true;
        } catch (Exception e) {
            log.error("Failed to push Web2 secret to SMS", e);
        }
        return false;
    }

    // TODO: use the below method with retry.
    public TeeSessionGenerationResponse createTeeSession(WorkerpoolAuthorization workerpoolAuthorization) throws TeeSessionGenerationException {
        String chainTaskId = workerpoolAuthorization.getChainTaskId();
        log.info("Creating TEE session [chainTaskId:{}]", chainTaskId);
        String authorization = getAuthorizationString(workerpoolAuthorization);

        // SMS client should already have been created once before.
        // If it couldn't be created, then the task would have been aborted.
        // So the following won't throw an exception.
        final SmsClient smsClient = getSmsClient(chainTaskId);

        try {
            TeeSessionGenerationResponse session = smsClient
                    .generateTeeSession(authorization, workerpoolAuthorization)
                    .getData();
            log.info("Created TEE session [chainTaskId:{}, session:{}]",
                    chainTaskId, session);
            return session;
        } catch (FeignException e) {
            log.error("SMS failed to create TEE session [chainTaskId:{}]",
                    chainTaskId, e);
            final Optional<TeeSessionGenerationError> error = ApiResponseBodyDecoder.getErrorFromResponse(e.contentUTF8(), TeeSessionGenerationError.class);
            throw new TeeSessionGenerationException(error.orElse(TeeSessionGenerationError.UNKNOWN_ISSUE));
        }
    }

    private String getAuthorizationString(WorkerpoolAuthorization workerpoolAuthorization) {
        final String challenge = workerpoolAuthorization.getHash();
        return signerService.signMessageHash(challenge).getValue();
    }

    @Override
    public boolean purgeTask(String chainTaskId) {
        taskIdToSmsUrl.remove(chainTaskId);
        return !taskIdToSmsUrl.containsKey(chainTaskId);
    }

    @Override
    public void purgeAllTasksData() {
        taskIdToSmsUrl.clear();
    }
}
