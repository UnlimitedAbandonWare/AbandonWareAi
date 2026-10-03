package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import com.example.lms.assist.JevDecisionAdvisor.*;

class JevApprovedRecheckContractTest {
    private MockEnvironment env() {
        return new MockEnvironment().withProperty("demo.jev.mode", "on")
                .withProperty("demo.jev.allow-paid", "true")
                .withProperty("demo.jev.decision-wait-ms", "100");
    }
    private Advice recheck(JevEvaluationRuntime runtime, boolean approved) throws Exception {
        var method = assertDoesNotThrow(() -> JevEvaluationRuntime.class.getDeclaredMethod(
                "recheck", String.class, boolean.class));
        method.setAccessible(true);
        return (Advice) method.invoke(runtime, "main", approved);
    }
    @ParameterizedTest @ValueSource(strings={"auth_blocked", "plan_gate"})
    void approvedRecheckRecoversOnlyAfterSuccess(String failure) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = new JevEvaluationRuntime(env(), Clock.systemUTC(), request ->
                calls.incrementAndGet() == 1 ? new EvalResponse(403, null, failure, null)
                        : new EvalResponse(200, Verdict.WEB, null, null), name -> "fixture")) {
            assertFalse(runtime.advise("main", "fixture", "RECENT_ONLY").usable());
            assertFalse(runtime.advise("main", "fixture", "RECENT_ONLY").usable());
            assertEquals(1, calls.get());
            assertEquals("recheck_approval_required", recheck(runtime, false).reasonCode());
            assertEquals(1, calls.get());
            assertTrue(recheck(runtime, true).usable());
            assertEquals("ready", runtime.status().get("reason"));
            assertTrue(runtime.advise("main", "fixture", "RECENT_ONLY").usable());
            assertEquals(3, calls.get());
        }
    }
    @Test void failedRecheckKeepsOriginalBlock() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = new JevEvaluationRuntime(env(), Clock.systemUTC(), request ->
                calls.incrementAndGet() == 1 ? new EvalResponse(401, null, "auth_blocked", null)
                        : new EvalResponse(500, null, "transport_error", null), name -> "fixture")) {
            runtime.advise("main", "fixture", "RECENT_ONLY");
            assertFalse(recheck(runtime, true).usable());
            assertEquals("auth_blocked", runtime.status().get("reason"));
            runtime.advise("main", "fixture", "RECENT_ONLY");
            assertEquals(2, calls.get());
        }
    }
    @Test void offModeHasNoRecheckDispatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = new JevEvaluationRuntime(env().withProperty("demo.jev.mode", "off"),
                Clock.systemUTC(), request -> { calls.incrementAndGet(); return new EvalResponse(200, Verdict.WEB, null, null); },
                name -> "fixture")) {
            assertEquals("off", recheck(runtime, true).mode());
            assertEquals(0, calls.get());
        }
    }
    @Test void runningRecheckRemainsSingleFlightAfterCallerTimeout() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (var runtime = new JevEvaluationRuntime(env().withProperty("demo.jev.decision-wait-ms", "20"),
                Clock.systemUTC(), request -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) return new EvalResponse(401, null, "auth_blocked", null);
                    entered.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    return new EvalResponse(200, Verdict.WEB, null, null);
                }, (request, questions) -> null, name -> "fixture", executor, System::nanoTime)) {
            runtime.advise("main", "fixture", "RECENT_ONLY");
            assertEquals("timeout", recheck(runtime, true).reasonCode());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals("busy", recheck(runtime, true).reasonCode());
            assertEquals(2, calls.get());
            release.countDown();
            assertTrue(runtime.inFlight.tryAcquire(2, 2, TimeUnit.SECONDS));
            runtime.inFlight.release(2);
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }
}
