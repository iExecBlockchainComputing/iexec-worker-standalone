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

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorUtilsTest {

    final String THREAD_NAME_PREFIX = "testExecutorUtils-";

    @Test
    void newSingleThreadExecutorWithFixedSizeQueue_createsExecutorWithExpectedConfiguration() {
        int queueSize = 10;
        Executor executor = ExecutorUtils.newSingleThreadExecutorWithFixedSizeQueue(queueSize, THREAD_NAME_PREFIX);
        assertInstanceOf(ThreadPoolTaskExecutor.class, executor);
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
        assertEquals(1, taskExecutor.getCorePoolSize());
        assertEquals(1, taskExecutor.getMaxPoolSize());
        assertEquals(0, taskExecutor.getKeepAliveSeconds());
        assertEquals(queueSize, taskExecutor.getQueueCapacity());
        assertEquals(THREAD_NAME_PREFIX, taskExecutor.getThreadNamePrefix());
    }

    @Test
    void newSingleThreadExecutorWithFixedSizeQueue_executesSubmittedTasks() throws InterruptedException {
        int queueSize = 10;
        AtomicInteger counter = new AtomicInteger();
        Executor executor = ExecutorUtils.newSingleThreadExecutorWithFixedSizeQueue(queueSize, THREAD_NAME_PREFIX);
        CountDownLatch latch = new CountDownLatch(queueSize);
        for (int i = 0; i < queueSize; i++) {
            if (i == queueSize - 3) {
                assertFalse(latch.await(1, TimeUnit.SECONDS));
            }
            executor.execute(() -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(queueSize, counter.get());
    }

    @Test
    void newSingleThreadExecutorWithFixedSizeQueue_discardsTasksWhenQueueIsFull() {
        Executor executor = ExecutorUtils.newSingleThreadExecutorWithFixedSizeQueue(1, THREAD_NAME_PREFIX);
        AtomicInteger counter = new AtomicInteger(0);
        executor.execute(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        executor.execute(() -> {
            counter.incrementAndGet();
        });
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(0, counter.get());
    }

}
