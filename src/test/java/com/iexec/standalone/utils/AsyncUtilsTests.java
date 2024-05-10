/*
 * Copyright 2023-2023 IEXEC BLOCKCHAIN TECH
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncUtilsTests {

    @Mock
    private Executor mockExecutor;

    @Test
    void runAsyncTask_shouldRunTaskAsynchronously() {
        String context = "Test Context";
        Runnable task = mock(Runnable.class);
        CompletableFuture<Void> future = AsyncUtils.runAsyncTask(context, task, mockExecutor);
        assertNotNull(future);
        verify(mockExecutor).execute(any(Runnable.class));
        verify(task, never()).run();
    }

    @Test
    void runAsyncTask_shouldHandleNullContext() {
        Runnable task = mock(Runnable.class);
        CompletableFuture<Void> future = AsyncUtils.runAsyncTask(null, task, mockExecutor);
        assertNotNull(future);
        verify(mockExecutor).execute(any(Runnable.class));
    }

    @Test
    void runAsyncTask_shouldHandleNullTask() {
        String context = "Test Context";
        assertThrows(NullPointerException.class, () -> AsyncUtils.runAsyncTask(context, null, mockExecutor));
        verify(mockExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void runAsyncTask_shouldHandleNullExecutor() {
        String context = "Test Context";
        Runnable task = mock(Runnable.class);
        assertThrows(NullPointerException.class, () -> AsyncUtils.runAsyncTask(context, task, null));
        verify(task, never()).run();
    }

    @Test
    void runAsyncTask_shouldHandleFailureGracefully() {
        String context = "Test Context";
        Runnable task = () -> {
            throw new RuntimeException("Execution failure");
        };
        CompletableFuture<Void> future = AsyncUtils.runAsyncTask(context, task, mockExecutor)
                .orTimeout(2, TimeUnit.SECONDS);
        assertNotNull(future);
        assertThrows(ExecutionException.class, future::get);
        assertThrows(RuntimeException.class, task::run);
    }
}
