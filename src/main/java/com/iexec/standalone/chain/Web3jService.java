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

import com.iexec.commons.poco.chain.Web3jAbstractService;
import com.iexec.standalone.config.BlockchainAdapterConfigurationService;
import com.iexec.standalone.config.WorkerConfigurationService;
import org.springframework.stereotype.Service;

@Service
public class Web3jService extends Web3jAbstractService {

    public Web3jService(ChainConfig chainConfig) {
        super(
                chainConfig.getChainId(),
                chainConfig.getPrivateChainAddress(),
                chainConfig.getBlockTime(),
                chainConfig.getGasPriceMultiplier(),
                chainConfig.getGasPriceCap(),
                chainConfig.isSidechain()
        );
    }

    public Web3jService(BlockchainAdapterConfigurationService blockchainAdapterConfigurationService,
                        WorkerConfigurationService workerConfService) {
        super(
                blockchainAdapterConfigurationService.getChainId(),
                !workerConfService.getOverrideBlockchainNodeAddress().isEmpty() ?
                        workerConfService.getOverrideBlockchainNodeAddress() :
                        blockchainAdapterConfigurationService.getChainNodeUrl(),
                blockchainAdapterConfigurationService.getBlockTime(),
                workerConfService.getGasPriceMultiplier(),
                workerConfService.getGasPriceCap(),
                blockchainAdapterConfigurationService.isSidechain()
        );
    }

}
