package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class JevEvaluationRuntimePermitLifecycleTest {
    static MockEnvironment env(String mode) {
        return new MockEnvironment().withProperty("demo.jev.mode", mode)
                .withProperty("demo.jev.allow-paid", "true").withProperty("demo.jev.max-in-flight", "1")
                .withProperty("demo.jev.decision-wait-ms", "20").withProperty("demo.jev.budget.enabled", "true")
                .withProperty("demo.jev.budget.daily-max-calls", "10")
                .withProperty("demo.jev.choice.enabled", "true").withProperty("demo.jev.prefetch.enabled", "true");
    }
    static JevEvaluationRuntime runtime(MockEnvironment env, ExecutorService executor, AtomicInteger calls) {
        return new JevEvaluationRuntime(env, Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC),
                req -> { calls.incrementAndGet(); return new JevDecisionAdvisor.EvalResponse(200, JevDecisionAdvisor.Verdict.WEB, null, null); },
                (req, questions) -> { calls.incrementAndGet(); return new JevEvaluationRuntime.ChoiceResponse(JevEvaluationRuntime.empty("ok"), null); },
                name -> "fixture", executor, System::nanoTime);
    }
    @Test void timeoutBeforeStartRefundsOnePermitAndUnsentReservation() {
        var executor = new QueueExecutor(); var calls = new AtomicInteger();
        try (var runtime = runtime(env("on"), executor, calls)) {
            assertEquals("timeout", runtime.advise("main", "synthetic", "RECENT_ONLY").reasonCode());
            assertEquals(1, runtime.inFlight.availablePermits());
            assertEquals(0, runtime.dailyCalls.get().count());
            executor.runNext(); assertEquals(0, calls.get());
            executor.inline = true;
            assertEquals(JevDecisionAdvisor.Verdict.WEB, runtime.advise("main", "next", "RECENT_ONLY").verdict());
            assertEquals(1, calls.get()); assertEquals(1, runtime.inFlight.availablePermits());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"shadow", "prefetch"})
    void shutdownBeforeStartRestoresCapacityAndUnsentQuota(String mode) {
        var executor = new QueueExecutor(); var calls = new AtomicInteger();
        var runtime = runtime(env("prefetch".equals(mode) ? "on" : mode), executor, calls);
        try {
            if ("prefetch".equals(mode)) runtime.prefetch(
                    new JevChoiceAdvisor.QuestionKey(UUID.randomUUID(), 0, "synthetic"), "main", "synthetic",
                    List.of(JevChoiceAdvisor.WEB_NEED),
                    new JevEvaluationRuntime.DecisionAdmission(() -> true, System.nanoTime()+TimeUnit.SECONDS.toNanos(3), true));
            else assertEquals("shadow", runtime.advise("main", "synthetic", "RECENT_ONLY").reasonCode());
            assertEquals(0, runtime.inFlight.availablePermits());
            runtime.close(); runtime.close();
            assertEquals(1, runtime.inFlight.availablePermits());
            assertEquals(0, runtime.dailyCalls.get().count()); assertEquals(0, calls.get());
        } finally { runtime.close(); }
    }
    @Test void rejectionReturnsExactlyOnePermitAndReservation() {
        var executor = new QueueExecutor(); var calls = new AtomicInteger(); executor.reject = true;
        try (var runtime = runtime(env("on"), executor, calls)) {
            assertEquals("busy", runtime.advise("main", "synthetic", "RECENT_ONLY").reasonCode());
            assertEquals(1, runtime.inFlight.availablePermits()); assertEquals(0, runtime.dailyCalls.get().count());
            executor.reject = false; executor.inline = true;
            assertNotNull(runtime.advise("main", "next", "RECENT_ONLY").verdict());
            assertEquals(1, calls.get()); assertEquals(1, runtime.inFlight.availablePermits());
        }
    }
    @Test void duplicateCloseAfterQueuedTimeoutCannotReleaseTwice() {
        var executor = new QueueExecutor(); var calls = new AtomicInteger();
        var runtime = runtime(env("on"), executor, calls);
        runtime.advise("main", "synthetic", "RECENT_ONLY"); runtime.close(); runtime.close();
        assertEquals(1, runtime.inFlight.availablePermits()); assertEquals(0, runtime.dailyCalls.get().count());
        assertEquals(0, calls.get());
    }
    @Test void runningTimeoutRetainsPermitUntilActualTransportTermination() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var active = new AtomicInteger(); var peak = new AtomicInteger(); var calls = new AtomicInteger();
        var runtime = new JevEvaluationRuntime(env("on"), Clock.systemUTC(), req -> {
            calls.incrementAndGet(); int concurrent = active.incrementAndGet(); peak.accumulateAndGet(concurrent, Math::max);
            try {
                started.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS));
                return new JevDecisionAdvisor.EvalResponse(200, JevDecisionAdvisor.Verdict.WEB, null, null);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            finally { active.decrementAndGet(); }
        }, (req, questions) -> null, name -> "fixture", executor, System::nanoTime);
        try {
            assertEquals("timeout", runtime.advise("main", "synthetic", "RECENT_ONLY").reasonCode());
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(0, runtime.inFlight.availablePermits());
            assertEquals("busy", runtime.advise("main", "parallel", "RECENT_ONLY").reasonCode());
            assertEquals(1, calls.get()); assertEquals(1, peak.get());
            release.countDown(); assertTrue(runtime.inFlight.tryAcquire(1, TimeUnit.SECONDS));
            runtime.inFlight.release();
            assertNotNull(runtime.advise("main", "next", "RECENT_ONLY").verdict());
            assertEquals(2, calls.get()); assertEquals(0, active.get()); assertEquals(1, runtime.inFlight.availablePermits());
        } finally {
            release.countDown(); runtime.close(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
    static final class QueueExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>(); boolean reject, inline, stopped;
        public void execute(Runnable runnable) { if (reject || stopped) throw new RejectedExecutionException();
            if (inline) runnable.run(); else tasks.add(runnable); }
        void runNext() { Objects.requireNonNull(tasks.poll()).run(); }
        public void shutdown() { stopped = true; }
        public List<Runnable> shutdownNow() { stopped = true; var pending = new ArrayList<Runnable>();
            for (Runnable r; (r = tasks.poll()) != null;) pending.add(r); return pending; }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped && tasks.isEmpty(); }
        public boolean awaitTermination(long n, TimeUnit u) { return isTerminated(); }
    }
}
