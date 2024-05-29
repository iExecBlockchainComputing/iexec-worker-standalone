/*
 * Copyright 2022-2023 IEXEC BLOCKCHAIN TECH
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

package com.iexec.standalone.tee;

import com.iexec.commons.poco.tee.TeeFramework;
import com.iexec.standalone.tee.scone.TeeSconeService;
import com.iexec.standalone.tee.gramine.TeeGramineService;
import com.iexec.standalone.tee.scone.TeeSconeService;
import org.springframework.stereotype.Service;

@Service
public class TeeServicesManager {

    private final TeeSconeService teeSconeService;
    private final TeeGramineService teeGramineService;

    public TeeServicesManager(TeeSconeService teeSconeService, TeeGramineService teeGramineService) {
        this.teeSconeService = teeSconeService;
        this.teeGramineService = teeGramineService;
    }

    public TeeService getTeeService(TeeFramework teeFramework) {
        if (teeFramework == null) {
            throw new IllegalArgumentException("TEE framework can't be null.");
        }

        switch (teeFramework) {
            case SCONE:
                return teeSconeService;
            case GRAMINE:
                return teeGramineService;
            default:
                throw new IllegalArgumentException("No TEE service defined for this TEE framework.");
        }
    }
}
