package com.example.lms.infra.resilience;

import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightmareBreakerCallPermitTest {

    @Test
    void lateSuccessFromCallAdmittedBeforeOpenCannotCloseNewOpen() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setEnabled(true);
        props.setFailureThreshold(1);
        props.setOpenDuration(Duration.ofMinutes(1));

        NightmareBreaker breaker = new NightmareBreaker(props);
        String key = "test:nightmare:late-success";

        assertDoesNotThrow(() -> breaker.checkOpenOrThrow(key),
                "the first call must be admitted while the breaker is CLOSED");

        breaker.recordFailure(
                key,
                NightmareBreaker.FailureKind.UNKNOWN,
                new IllegalStateException("trip"),
                "concurrent-failure");
        assertTrue(breaker.isOpen(key), "the concurrent failure must open the breaker");

        breaker.recordSuccess(key, 1L);

        assertTrue(breaker.isOpen(key),
                "a late success from the pre-OPEN call must not close the newer OPEN generation");
    }

    @Test
    void lateClosedGenerationPermitSuccessCannotCloseNewOpenGeneration() {
        TestBreaker fixture = breaker(2, 1);
        String key = "test:nightmare:permit-late-success";
        NightmareBreaker.CallPermit slow = fixture.breaker.acquire(key);
        NightmareBreaker.CallPermit failing = fixture.breaker.acquire(key);

        failing.completeFailure(NightmareBreaker.FailureKind.UNKNOWN,
                new IllegalStateException("provider failed"), "failure-context");
        assertTrue(fixture.breaker.isOpen(key));

        slow.completeSuccess(1L);

        assertTrue(fixture.breaker.isOpen(key));
    }

    @Test
    @Timeout(10)
    void concurrentExpiredOpenAdmissionsNeverExceedHalfOpenCap() throws Exception {
        TestBreaker fixture = breaker(3, 3);
        String key = "test:nightmare:half-open-cap";
        open(fixture.breaker, key);
        fixture.advance(Duration.ofSeconds(10));

        int callers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<NightmareBreaker.CallPermit>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        return fixture.breaker.acquire(key);
                    } catch (NightmareBreaker.OpenCircuitException expected) {
                        return null;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<NightmareBreaker.CallPermit> admitted = new ArrayList<>();
            for (Future<NightmareBreaker.CallPermit> future : futures) {
                NightmareBreaker.CallPermit permit = future.get(5, TimeUnit.SECONDS);
                if (permit != null) {
                    admitted.add(permit);
                }
            }
            assertEquals(3, admitted.size());
            admitted.forEach(permit -> permit.completeAbandoned("test", "cleanup"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void externalSignalSurvivesEvictionAfterItCapturedAStaleClosedGate() throws Exception {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setEnabled(true);
        props.setFailureThreshold(1);
        props.setOpenDuration(Duration.ofSeconds(10));
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        BlockingTicker ticker = new BlockingTicker();
        NightmareBreaker breaker = new NightmareBreaker(props, clock, ticker::getAsLong);
        String key = "test:nightmare:external-signal-eviction";

        NightmareBreaker.CallPermit initial = breaker.acquire(key);
        initial.completeSuccess(1L);
        ticker.advance(Duration.ofMinutes(6));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch armed = new CountDownLatch(1);
        try {
            Future<?> signal = pool.submit(() -> {
                ticker.armForCurrentThread();
                armed.countDown();
                breaker.signalFailure(key, NightmareBreaker.FailureKind.UNKNOWN,
                        new IllegalStateException("external failure"), "test");
            });
            assertTrue(armed.await(5, TimeUnit.SECONDS));
            assertTrue(ticker.awaitSignalEvolution(5, TimeUnit.SECONDS));

            breaker.evictStaleStates();
            ticker.releaseSignalEvolution();
            signal.get(5, TimeUnit.SECONDS);
        } finally {
            ticker.releaseSignalEvolution();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertTrue(breaker.isOpen(key),
                "an external failure captured before stale eviction must be replayed to the current Gate");
    }

    @Test
    void outstandingHalfOpenFailureWinsAfterSuccessTargetIsReached() {
        TestBreaker fixture = breaker(2, 1);
        String key = "test:nightmare:failure-priority";
        open(fixture.breaker, key);
        fixture.advance(Duration.ofSeconds(10));
        NightmareBreaker.CallPermit success = fixture.breaker.acquire(key);
        NightmareBreaker.CallPermit outstanding = fixture.breaker.acquire(key);

        success.completeSuccess(1L);
        assertEquals(NightmareBreaker.BreakerMode.HALF_OPEN, fixture.breaker.inspect(key).mode);

        outstanding.completeFailure(NightmareBreaker.FailureKind.TIMEOUT,
                new RuntimeException("late half-open failure"), "test");

        assertTrue(fixture.breaker.isOpen(key));
    }

    @Test
    void outstandingNeutralClosesSealedSuccessfulHalfOpenCohort() {
        TestBreaker fixture = breaker(2, 1);
        String key = "test:nightmare:neutral-after-target";
        open(fixture.breaker, key);
        fixture.advance(Duration.ofSeconds(10));
        NightmareBreaker.CallPermit success = fixture.breaker.acquire(key);
        NightmareBreaker.CallPermit outstanding = fixture.breaker.acquire(key);

        success.completeSuccess(1L);
        assertEquals(NightmareBreaker.BreakerMode.HALF_OPEN, fixture.breaker.inspect(key).mode);
        outstanding.completeAbandoned("test", "neutral");

        assertFalse(fixture.breaker.isOpenOrHalfOpen(key));
    }

    @Test
    void cancelledHalfOpenTrialReturnsSlotForReplacement() {
        TestBreaker fixture = breaker(1, 1);
        String key = "test:nightmare:neutral-slot";
        open(fixture.breaker, key);
        fixture.advance(Duration.ofSeconds(10));

        NightmareBreaker.CallPermit cancelled = fixture.breaker.acquire(key);
        cancelled.completeCancelled(null, "caller-cancelled");
        assertEquals(NightmareBreaker.BreakerMode.HALF_OPEN, fixture.breaker.inspect(key).mode);

        NightmareBreaker.CallPermit replacement = assertDoesNotThrow(() -> fixture.breaker.acquire(key));
        replacement.completeSuccess(1L);

        assertFalse(fixture.breaker.isOpenOrHalfOpen(key));
    }

    @Test
    void firstTerminalWinsAndLaterDuplicateTerminalIsNoOp() {
        TestBreaker fixture = breaker(1, 1);
        String key = "test:nightmare:single-terminal";
        NightmareBreaker.CallPermit permit = fixture.breaker.acquire(key);

        permit.completeFailure(NightmareBreaker.FailureKind.UNKNOWN,
                new RuntimeException("first failure"), "test");
        long openUntil = fixture.breaker.inspect(key).openUntilMs;
        permit.completeSuccess(1L);
        permit.completeFailure(NightmareBreaker.FailureKind.UNKNOWN,
                new RuntimeException("duplicate failure"), "test");

        assertTrue(fixture.breaker.isOpen(key));
        assertEquals(openUntil, fixture.breaker.inspect(key).openUntilMs);
    }

    @Test
    void completionUsesPolicySnapshotCapturedAtAdmission() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setFailureThreshold(2);
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        AtomicLong ticker = new AtomicLong();
        NightmareBreaker breaker = new NightmareBreaker(props, clock, ticker::get);
        String key = "test:nightmare:policy-snapshot";
        NightmareBreaker.CallPermit admittedUnderThresholdTwo = breaker.acquire(key);

        props.setFailureThreshold(1);
        admittedUnderThresholdTwo.completeFailure(NightmareBreaker.FailureKind.UNKNOWN,
                new RuntimeException("first"), "test");

        assertFalse(breaker.isOpen(key),
                "an in-flight completion must use the policy captured when it was admitted");
        NightmareBreaker.CallPermit admittedUnderThresholdOne = breaker.acquire(key);
        admittedUnderThresholdOne.completeFailure(NightmareBreaker.FailureKind.UNKNOWN,
                new RuntimeException("second"), "test");
        assertTrue(breaker.isOpen(key));
    }

    @Test
    void halfOpenCohortKeepsFirstTrialPolicySnapshot() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setFailureThreshold(1);
        props.setOpenDuration(Duration.ofSeconds(10));
        props.setHalfOpenMaxCalls(2);
        props.setHalfOpenSuccessThreshold(2);
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        AtomicLong ticker = new AtomicLong();
        NightmareBreaker breaker = new NightmareBreaker(props, clock, ticker::get);
        NightmareBreaker.CallPermit trip = breaker.acquire("test:nightmare:cohort-policy");
        trip.completeFailure(NightmareBreaker.FailureKind.UNKNOWN, new RuntimeException("trip"), "test");
        clock.advance(Duration.ofSeconds(10));
        ticker.addAndGet(Duration.ofSeconds(10).toNanos());

        NightmareBreaker.CallPermit first = breaker.acquire("test:nightmare:cohort-policy");
        props.setHalfOpenMaxCalls(1);
        props.setHalfOpenSuccessThreshold(1);
        NightmareBreaker.CallPermit second = assertDoesNotThrow(
                () -> breaker.acquire("test:nightmare:cohort-policy"));
        first.completeSuccess(1L);
        assertEquals(NightmareBreaker.BreakerMode.HALF_OPEN,
                breaker.inspect("test:nightmare:cohort-policy").mode);
        second.completeSuccess(1L);
        assertFalse(breaker.isOpenOrHalfOpen("test:nightmare:cohort-policy"));
    }

    @Test
    void disabledPermitIsOpaqueAndDoesNotAllocateState() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setEnabled(false);
        NightmareBreaker breaker = new NightmareBreaker(props);
        String rawKey = "secret owner_token=raw-private-key";

        NightmareBreaker.CallPermit permit = breaker.acquire(rawKey, "raw private query");
        assertEquals("CallPermit[opaque]", permit.toString());
        assertFalse(permit.toString().contains(rawKey));
        permit.completeFailure(NightmareBreaker.FailureKind.UNKNOWN,
                new RuntimeException("credential=raw-private-value"), "raw private query");

        assertTrue(breaker.snapshot().isEmpty());
    }

    @Test
    void callPermitExposesNoLifecycleIdentityOrAutoCloseContract() {
        Set<String> forbidden = Set.of("key", "generation", "getKey", "getGeneration");

        assertEquals(0, NightmareBreaker.CallPermit.class.getConstructors().length);
        assertFalse(AutoCloseable.class.isAssignableFrom(NightmareBreaker.CallPermit.class));
        assertFalse(Arrays.stream(NightmareBreaker.CallPermit.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .anyMatch(forbidden::contains));
    }

    @Test
    void failSoftBypassSuccessCannotRelaxOpenAndAdverseOutcomeOnlyTightens() {
        TestBreaker fixture = breaker(1, 1);
        String key = "test:nightmare:fail-soft-monotonic";
        open(fixture.breaker, key);
        NightmareBreaker.StateView opened = fixture.breaker.inspect(key);

        GuardContext context = new GuardContext();
        context.putPlanOverride("breaker.failSoft", true);
        GuardContextHolder.set(context);
        try {
            NightmareBreaker.CallPermit bypassSuccess = fixture.breaker.acquire(key, "bypass");
            bypassSuccess.completeSuccess(1L);
            NightmareBreaker.StateView afterSuccess = fixture.breaker.inspect(key);
            assertTrue(afterSuccess.open);
            assertEquals(opened.openUntilMs, afterSuccess.openUntilMs);

            NightmareBreaker.CallPermit bypassFailure = fixture.breaker.acquire(key, "bypass");
            bypassFailure.completeFailure(
                    NightmareBreaker.FailureKind.UNKNOWN,
                    new IllegalStateException("adverse"),
                    "bypass");
            NightmareBreaker.StateView afterFailure = fixture.breaker.inspect(key);
            assertTrue(afterFailure.open);
            assertTrue(afterFailure.openUntilMs >= afterSuccess.openUntilMs);
            assertTrue(afterFailure.consecutiveFailures >= afterSuccess.consecutiveFailures);
        } finally {
            GuardContextHolder.clear();
        }
    }

    @Test
    void evictionCannotDetachGateWithInflightPermit() {
        TestBreaker fixture = breaker(1, 1);
        String key = "test:nightmare:inflight-eviction";
        NightmareBreaker.CallPermit permit = fixture.breaker.acquire(key);
        fixture.advance(Duration.ofMinutes(6));

        fixture.breaker.evictStaleStates();
        assertNotNull(fixture.breaker.snapshot().get(key));

        permit.completeAbandoned("test", "done");
        fixture.advance(Duration.ofMinutes(6));
        fixture.breaker.evictStaleStates();
        assertNull(fixture.breaker.snapshot().get(key));
    }

    private static void open(NightmareBreaker breaker, String key) {
        NightmareBreaker.CallPermit permit = breaker.acquire(key);
        permit.completeFailure(NightmareBreaker.FailureKind.UNKNOWN,
                new RuntimeException("trip"), "test");
        assertTrue(breaker.isOpen(key));
    }

    private static TestBreaker breaker(int halfOpenMaxCalls, int halfOpenSuccessThreshold) {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setEnabled(true);
        props.setFailureThreshold(1);
        props.setOpenDuration(Duration.ofSeconds(10));
        props.setHalfOpenEnabled(true);
        props.setHalfOpenMaxCalls(halfOpenMaxCalls);
        props.setHalfOpenSuccessThreshold(halfOpenSuccessThreshold);
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        AtomicLong ticker = new AtomicLong();
        return new TestBreaker(new NightmareBreaker(props, clock, ticker::get), clock, ticker);
    }

    private record TestBreaker(NightmareBreaker breaker, MutableClock clock, AtomicLong ticker) {
        private void advance(Duration duration) {
            clock.advance(duration);
            ticker.addAndGet(duration.toNanos());
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong epochMs;

        private MutableClock(long initialEpochMs) {
            this.epochMs = new AtomicLong(initialEpochMs);
        }

        private void advance(Duration duration) {
            epochMs.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMs.get());
        }
    }

    private static final class BlockingTicker {
        private final AtomicLong value = new AtomicLong();
        private final CountDownLatch signalEvolutionEntered = new CountDownLatch(1);
        private final CountDownLatch allowSignalEvolution = new CountDownLatch(1);
        private volatile Thread blockedThread;
        private volatile boolean blockNextCall;

        private void advance(Duration duration) {
            value.addAndGet(duration.toNanos());
        }

        private void armForCurrentThread() {
            blockedThread = Thread.currentThread();
            blockNextCall = true;
        }

        private boolean awaitSignalEvolution(long timeout, TimeUnit unit) throws InterruptedException {
            return signalEvolutionEntered.await(timeout, unit);
        }

        private void releaseSignalEvolution() {
            allowSignalEvolution.countDown();
        }

        private long getAsLong() {
            if (blockNextCall && Thread.currentThread() == blockedThread) {
                synchronized (this) {
                    if (blockNextCall && Thread.currentThread() == blockedThread) {
                        blockNextCall = false;
                        signalEvolutionEntered.countDown();
                        try {
                            if (!allowSignalEvolution.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("signal evolution was not released");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("signal evolution interrupted", interrupted);
                        }
                    }
                }
            }
            return value.get();
        }
    }
}
