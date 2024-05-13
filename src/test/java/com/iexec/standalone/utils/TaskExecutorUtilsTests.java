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

package com.iexec.standalone.utils;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.*;

class TaskExecutorUtilsTests {

    final String THREAD_NAME_PREFIX = "testTaskExecutorUtils-";

    @Test
    void newThreadPoolTaskExecutor_withThreadNamePrefix() {
        ThreadPoolTaskExecutor executor = TaskExecutorUtils.newThreadPoolTaskExecutor(THREAD_NAME_PREFIX);

        assertNotNull(executor);
        assertEquals(Runtime.getRuntime().availableProcessors(), executor.getCorePoolSize());
        assertTrue(executor.getThreadNamePrefix().startsWith(THREAD_NAME_PREFIX));
    }

    @Test
    void newThreadPoolTaskExecutor_withThreadNamePrefixAndPoolSize() {
        int poolSize = 5;
        ThreadPoolTaskExecutor executor = TaskExecutorUtils.newThreadPoolTaskExecutor(THREAD_NAME_PREFIX, poolSize);

        assertNotNull(executor);
        assertEquals(poolSize, executor.getCorePoolSize());
        assertTrue(executor.getThreadNamePrefix().startsWith(THREAD_NAME_PREFIX));
    }
}
