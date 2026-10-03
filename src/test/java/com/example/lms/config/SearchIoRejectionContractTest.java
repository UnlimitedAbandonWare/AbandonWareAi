package com.example.lms.config;

import ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration;
import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import ai.abandonware.nova.boot.exec.CancelShieldExecutorServicePostProcessor;
import ai.abandonware.nova.boot.exec.ExecutorServiceContextPropagationPostProcessor;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SearchIoRejectionContractTest {
    @AfterEach
    void cleanupContext() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void decoratedBeanRejectsAtCapacityWithoutCallingWorkInline(boolean fallback) throws Exception {
        var environment = new MockEnvironment()
                .withProperty("search.io.executor.corePoolSize", "1")
                .withProperty("search.io.executor.maxPoolSize", "2")
                .withProperty("search.io.executor.queueCapacity", "1");
        ExecutorService raw;
        if (fallback) {
            raw = new NovaOrchestrationAutoConfiguration().searchIoExecutorFallback(environment);
        } else {
            var config = new SearchExecutorConfig();
            ReflectionTestUtils.setField(config, "searchIoCoreSize", 1);
            ReflectionTestUtils.setField(config, "searchIoMaxSize", 2);
            ReflectionTestUtils.setField(config, "searchIoQueueCapacity", 1);
            ReflectionTestUtils.setField(config, "searchIoKeepAliveSeconds", 60L);
            raw = config.searchIoExecutor();
        }
        var propagation = new ExecutorServiceContextPropagationPostProcessor(environment, (DebugEventStore) null);
        var beans = new DefaultListableBeanFactory();
        var shield = new CancelShieldExecutorServicePostProcessor(environment,
                beans.getBeanProvider(DebugEventStore.class));
        ExecutorService executor = (ExecutorService) shield.postProcessAfterInitialization(
                propagation.postProcessAfterInitialization(raw, "searchIoExecutor"), "searchIoExecutor");
        assertInstanceOf(CancelShieldExecutorService.class, executor);
        var firstStarted = new CountDownLatch(1);
        var twoRunning = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(3);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var rejectedCalls = new AtomicInteger();
        Runnable admittedWork = () -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            firstStarted.countDown();
            twoRunning.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
                completed.countDown();
            }
        };
        try {
            executor.submit(admittedWork);
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            executor.submit(admittedWork);
            executor.submit(admittedWork);
            assertTrue(twoRunning.await(1, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.submit(rejectedCalls::incrementAndGet));
            assertEquals(0, rejectedCalls.get());
            assertEquals(2, active.get());
            release.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(2, peak.get(), "the bounded executor must never expand beyond its configured maximum");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
