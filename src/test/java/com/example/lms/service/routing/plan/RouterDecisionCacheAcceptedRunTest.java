package com.example.lms.service.routing.plan;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RouterDecisionCacheAcceptedRunTest {
    @AfterEach
    void cleanup() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @Test
    void acceptedWebPlanPreservesSuccessfulLlmResultBeyondFallbackWait() {
        RouterDecisionCache cache = cache();
        ChatRunRegistry registry = registry();
        var run = registry.beginOrJoin(901L).context();
        AtomicInteger calls = new AtomicInteger();
        TimeBudgetContext.clear();
        TraceStore.put("web.snippets", List.of("synthetic uncertainty principle evidence"));
        try (var scope = ChatRunExecutionContext.bind(run)) {
            String plan = assertDoesNotThrow(() -> cache.getOrCompute("web", "keywords", "slice", String.class, () -> {
                calls.incrementAndGet();
                pause(120);
                return "uncertainty principle";
            }));
            assertEquals("uncertainty principle", plan);
            assertEquals(1, calls.get(), "the successful provider result must not be retried");
            assertEquals(plan, cache.getIfPresent("web", "keywords", "slice", String.class).orElseThrow());
        }
    }

    @Test
    void acceptedFollowerWaitsForHealthyLeaderBeyondFallbackWait() throws Exception {
        RouterDecisionCache cache = cache();
        var registry = registry();
        var leaderRun = registry.beginOrJoin(902L).context();
        var followerRun = registry.beginOrJoin(903L).context();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            Future<String> leader = pool.submit(() -> {
                try (var scope = ChatRunExecutionContext.bind(leaderRun)) {
                    return cache.getOrCompute("web", "shared", "slice", String.class, () -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        await(release);
                        return "shared plan";
                    });
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<String> follower = pool.submit(() -> {
                try (var scope = ChatRunExecutionContext.bind(followerRun)) {
                    return cache.getOrCompute("web", "shared", "slice", String.class, () -> {
                        calls.incrementAndGet();
                        return "unexpected duplicate";
                    });
                }
            });
            pause(120);
            release.countDown();
            assertEquals("shared plan", leader.get(2, TimeUnit.SECONDS));
            assertEquals("shared plan", follower.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationInterruptsAcceptedLeaderAndPreventsCachePublication() throws Exception {
        RouterDecisionCache cache = cache();
        var registry = registry();
        var run = registry.beginOrJoin(904L).context();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<String> result = pool.submit(() -> {
                try (var scope = ChatRunExecutionContext.bind(run)) {
                    return cache.getOrCompute("web", "cancel", "slice", String.class, () -> {
                        entered.countDown();
                        await(release);
                        return "must not be cached";
                    });
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(registry.cancelExact(904L, run.clientToken()));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertTrue(cache.getIfPresent("web", "cancel", "slice", String.class).isEmpty());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static RouterDecisionCache cache() {
        RouterDecisionCache cache = new RouterDecisionCache("router.plan.cache", true, 10, 60);
        ReflectionTestUtils.setField(cache, "fallbackWaitMillis", 20L);
        return cache;
    }

    private static ChatRunRegistry registry() {
        ChatRunRegistry registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 300);
        return registry;
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException("synthetic operation cancelled");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("synthetic supplier not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException("synthetic operation cancelled");
        }
    }
}
