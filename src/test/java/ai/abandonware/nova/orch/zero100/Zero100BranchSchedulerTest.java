package ai.abandonware.nova.orch.zero100;

import com.example.lms.service.guard.GuardContext;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Zero100BranchSchedulerTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void sliceIndexRotatesActiveLaneBqErRc() {
        assertEquals("BQ", Zero100BranchScheduler.schedule(slice(0, 0), null).activeLane());
        assertEquals("ER", Zero100BranchScheduler.schedule(slice(1, 0), null).activeLane());
        assertEquals("RC", Zero100BranchScheduler.schedule(slice(2, 0), null).activeLane());
        assertEquals("BQ", Zero100BranchScheduler.schedule(slice(3, 0), null).activeLane());
    }

    @Test
    void phaseBoundariesUseFifteenFiftyFiveEightyDefaults() {
        assertEquals(Zero100BranchScheduler.Phase.CALIBRATE,
                Zero100BranchScheduler.schedule(slice(0, 14), null).phase());
        assertEquals(Zero100BranchScheduler.Phase.DIVERGE,
                Zero100BranchScheduler.schedule(slice(0, 15), null).phase());
        assertEquals(Zero100BranchScheduler.Phase.DIVERGE,
                Zero100BranchScheduler.schedule(slice(0, 54), null).phase());
        assertEquals(Zero100BranchScheduler.Phase.CROSS_VERIFY,
                Zero100BranchScheduler.schedule(slice(0, 55), null).phase());
        assertEquals(Zero100BranchScheduler.Phase.CROSS_VERIFY,
                Zero100BranchScheduler.schedule(slice(0, 79), null).phase());
        Zero100BranchScheduler.Schedule consensus = Zero100BranchScheduler.schedule(slice(0, 80), null);
        assertEquals(Zero100BranchScheduler.Phase.CONSENSUS, consensus.phase());
        assertTrue(consensus.crossVerifyRequired());
        assertTrue(consensus.consensusEnabled());
    }

    @Test
    void planOverridesAreClampedBeforeScheduling() {
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("search.zero100.crossVerifyStartPct", 2);
        ctx.putPlanOverride("search.zero100.consensusStartPct", 500);
        ctx.putPlanOverride("search.zero100.queryBurstMax", 100);
        ctx.putPlanOverride("search.zero100.strictWeight", 9.0d);
        ctx.putPlanOverride("search.zero100.relaxedWeight", -3.0d);
        ctx.putPlanOverride("search.zero100.exploreWeight", 0.0d);
        ctx.putPlanOverride("search.zero100.riskConsensus.minLaneCoverage", 9);
        ctx.putPlanOverride("search.zero100.riskConsensus.riskPenaltyLambda", 2.0d);

        Zero100BranchScheduler.Schedule schedule = Zero100BranchScheduler.schedule(slice(1, 20), ctx);

        assertEquals(12, schedule.queryBurstMax());
        assertEquals(1.1875d, schedule.laneWeights().get("BQ"));
        assertEquals(0.0525d, schedule.laneWeights().get("ER"));
        assertEquals(0.05d, schedule.laneWeights().get("RC"));
        assertFalse(schedule.consensusEnabled());
        assertEquals(3, schedule.minLaneCoverage());
        assertEquals(1.0d, schedule.riskPenaltyLambda());
    }

    @Test
    void riskConsensusDefaultsExposeRatiosAndTimeboxes() {
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("search.zero100.strictCallBudgetRatio", 0.0d);
        ctx.putPlanOverride("search.zero100.relaxedCallBudgetRatio", 0.0d);
        ctx.putPlanOverride("search.zero100.exploreCallBudgetRatio", 0.0d);

        Zero100BranchScheduler.Schedule schedule = Zero100BranchScheduler.schedule(slice(0, 0), ctx);

        assertTrue(schedule.riskConsensusEnabled());
        assertEquals(2, schedule.minLaneCoverage());
        assertEquals(0.45d, schedule.riskPenaltyLambda());
        assertEquals(0.34d, schedule.callBudgetRatios().get("BQ"));
        assertEquals(0.33d, schedule.callBudgetRatios().get("ER"));
        assertEquals(0.33d, schedule.callBudgetRatios().get("RC"));
        assertEquals(950L, schedule.laneTimeboxesMs().get("BQ"));
        assertEquals(800L, schedule.laneTimeboxesMs().get("ER"));
        assertEquals(750L, schedule.laneTimeboxesMs().get("RC"));
    }

    @Test
    void degradedPlanOverrideReadersLeaveRedactedTraceBreadcrumb() {
        Zero100BranchScheduler.Schedule schedule = Zero100BranchScheduler.schedule(slice(0, 0), new ThrowingGuardContext());

        assertEquals(9, schedule.queryBurstMax());
        assertEquals(Boolean.TRUE, TraceStore.get("zero100.branchScheduler.suppressed"));
        assertEquals("planDouble", TraceStore.get("zero100.branchScheduler.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("zero100.branchScheduler.suppressed.errorClass"));
        assertEquals("IllegalStateException", TraceStore.get("zero100.branchScheduler.suppressed.errorType"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("secret query"));
        assertFalse(rendered.contains("fake-sensitive-token"));
    }

    private static Zero100SessionRegistry.Slice slice(long sliceIndex, int progressPct) {
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
                "intent");
    }

    private static final class ThrowingGuardContext extends GuardContext {
        @Override
        public int planInt(String key, int defaultValue) {
            throw new IllegalStateException("secret query fake-sensitive-token");
        }

        @Override
        public double planDouble(String key, double defaultValue) {
            throw new IllegalStateException("secret query fake-sensitive-token");
        }

        @Override
        public boolean planBool(String key, boolean defaultValue) {
            throw new IllegalStateException("secret query fake-sensitive-token");
        }
    }
}
