package ai.abandonware.nova.orch.timebudget;

import ai.abandonware.nova.orch.zero100.Zero100BranchScheduler;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeBudgetGuardTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void quantifiesOverrunAsPenaltyAndFallbackSignal() {
        TimeBudgetGuard.Decision decision = TimeBudgetGuard.evaluate(
                "RC",
                1_950L,
                800L,
                2_500L,
                0.58d,
                TimeBudgetGuard.Signals.builder()
                        .timeoutCount(1)
                        .rateLimitCount(1)
                        .qtxSoftCooldownActive(true)
                        .remainingMs(25L)
                        .build());

        assertEquals("RC", decision.lane());
        assertTrue(decision.overrunLogit() > 0.40d, () -> decision.toString());
        assertTrue(decision.latencyPenalty() >= 0.65d, () -> decision.toString());
        assertTrue(decision.budgetDebitMs() > 0L, () -> decision.toString());
        assertTrue(decision.forceFallback(), () -> decision.toString());
        assertEquals("time_budget_force_fallback", decision.reason());
    }

    @Test
    void healthyBranchKeepsBudgetAndRouteWeight() {
        TimeBudgetGuard.Decision decision = TimeBudgetGuard.evaluate(
                "BQ",
                350L,
                900L,
                2_500L,
                0.91d,
                TimeBudgetGuard.Signals.builder()
                        .remainingMs(1_500L)
                        .build());

        assertTrue(decision.overrunLogit() < 0.0d, () -> decision.toString());
        assertTrue(decision.latencyPenalty() < 0.15d, () -> decision.toString());
        assertEquals(0L, decision.budgetDebitMs());
        assertFalse(decision.forceFallback());
    }

    @Test
    void evaluatePublishesLowCardinalityTraceBreadcrumbs() {
        TimeBudgetGuard.Decision decision = TimeBudgetGuard.evaluate(
                "ER",
                1_100L,
                600L,
                1_500L,
                0.42d,
                TimeBudgetGuard.Signals.builder()
                        .timeoutCount(1)
                        .rateLimitCount(2)
                        .qtxSoftCooldownActive(true)
                        .remainingMs(35L)
                        .providerCoolingDown(true)
                        .breakerOpen(true)
                        .build());

        assertEquals("ER", TraceStore.get("zero100.timeBudget.guard.lane"));
        assertEquals(35L, TraceStore.get("zero100.timeBudget.guard.remainingMs"));
        assertEquals(1, TraceStore.get("zero100.timeBudget.guard.timeoutCount"));
        assertEquals(2, TraceStore.get("zero100.timeBudget.guard.rateLimitCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("zero100.timeBudget.guard.qtxSoftCooldownActive"));
        assertEquals(Boolean.TRUE, TraceStore.get("zero100.timeBudget.guard.providerCoolingDown"));
        assertEquals(Boolean.TRUE, TraceStore.get("zero100.timeBudget.guard.breakerOpen"));
        assertEquals(decision.latencyPenalty(), TraceStore.get("zero100.timeBudget.guard.latencyPenalty"));
        assertEquals(decision.budgetDebitMs(), TraceStore.get("zero100.timeBudget.guard.budgetDebitMs"));
        assertEquals(decision.forceFallback(), TraceStore.get("zero100.timeBudget.guard.forceFallback"));
        assertEquals(decision.reason(), TraceStore.get("zero100.timeBudget.guard.reason"));
    }

    @Test
    void evaluatePublishesCrossSubsystemTimeBudgetTraceKeys() {
        TimeBudgetGuard.Decision decision = TimeBudgetGuard.evaluate(
                "RC",
                1_300L,
                700L,
                1_600L,
                0.50d,
                TimeBudgetGuard.Signals.builder()
                        .timeoutCount(1)
                        .remainingMs(40L)
                        .breakerOpen(true)
                        .build());

        assertEquals("RC", TraceStore.get("timeBudget.lane"));
        assertEquals(decision.forceFallback(), TraceStore.get("timeBudget.forceFallback"));
        assertEquals(decision.latencyPenalty(), TraceStore.get("timeBudget.latencyPenalty"));
        assertEquals(decision.routeMultiplier(), TraceStore.get("timeBudget.routeMultiplier"));
        assertEquals(decision.budgetMs(), TraceStore.get("timeBudget.budgetMs"));
        assertEquals(decision.elapsedMs(), TraceStore.get("timeBudget.elapsedMs"));
        assertEquals(decision.reason(), TraceStore.get("timeBudget.reason"));
    }

    @Test
    void schedulerDebitsOverrunLaneAndLowersRoutingProbability() {
        Zero100SessionRegistry.Slice slice = slice(
                2,
                20,
                Map.of("RC", 0.82d),
                Map.of("RC", 900L),
                true);

        Zero100BranchScheduler.Schedule schedule = Zero100BranchScheduler.schedule(slice, null);

        assertTrue(schedule.forceFallback());
        assertEquals(0.82d, schedule.latencyPenalties().get("RC"));
        assertTrue(schedule.laneTimeboxesMs().get("RC") < 750L, () -> schedule.laneTimeboxesMs().toString());
        assertTrue(schedule.laneWeights().get("RC") < schedule.laneWeights().get("BQ"),
                () -> schedule.laneWeights().toString());
        assertTrue(schedule.routingProbabilities().get("RC") < schedule.routingProbabilities().get("BQ"),
                () -> schedule.routingProbabilities().toString());
    }

    private static Zero100SessionRegistry.Slice slice(
            long sliceIndex,
            int progressPct,
            Map<String, Double> penalties,
            Map<String, Long> debits,
            boolean forceFallback) {
        long start = 1_000L;
        long deadline = 101_000L;
        long now = start + Math.max(0, Math.min(100, progressPct)) * 1_000L;
        return new Zero100SessionRegistry.Slice(
                "test-session",
                now,
                start,
                deadline,
                400L,
                sliceIndex,
                0.2d,
                Zero100SessionRegistry.ClampMode.RECALL_CLAMP,
                2_500L,
                2_500L,
                "intent",
                penalties,
                debits,
                forceFallback);
    }
}
