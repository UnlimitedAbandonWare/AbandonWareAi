package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanApplierTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void lowRecallTraceAppliesExtremeZOverrides() {
        TraceStore.put("outCount", 0);
        TraceStore.put("stageCountsSelectedFromOut", "{}");
        GuardContext ctx = new GuardContext();

        ExecutionPlan plan = new ExecutionPlanApplier(new StrategyConflictResolver())
                .apply(ctx, normalSignals(false));

        assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
        assertEquals(Boolean.TRUE, ctx.getPlanOverride("extremeZ.enabled"));
        assertEquals(Boolean.FALSE, ctx.getPlanOverride("extremeZ.skipWhenStrikeMode"));
        assertEquals(8, ctx.getPlanOverride("extremeZ.maxSubQueries"));
        assertEquals(Boolean.TRUE, ctx.getPlanOverride("starvationLadder.deterministic"));
        assertEquals("GACHA_BURST", ctx.getPlanOverride("dualGear.mode"));
        assertTrue(ctx.getPlanOverride("dualGear.gachaVariants") instanceof List<?>);
        assertEquals("EXTREMEZ", TraceStore.get("routing.executionPlan.applied.primaryMode"));
        assertEquals(Boolean.TRUE, TraceStore.get("routing.executionPlan.applied"));
        assertEquals("GACHA_BURST", TraceStore.get("dualGear.mode"));
    }

    @Test
    void lowAuthorityAndContradictionApplyOverdriveAndPromptBoundaryKnobs() {
        TraceStore.put("overdrive.authority.avg", 0.22d);
        TraceStore.put("overdrive.contradiction.mean", 0.80d);
        GuardContext ctx = new GuardContext();

        ExecutionPlan plan = new ExecutionPlanApplier(new StrategyConflictResolver())
                .apply(ctx, normalSignals(false));

        assertEquals(ExecutionPlan.PrimaryMode.OVERDRIVE, plan.primaryMode());
        assertEquals(Boolean.TRUE, ctx.getPlanOverride("overdrive.enabled"));
        assertEquals(Boolean.TRUE, ctx.getPlanOverride("overdrive.aggressive"));
        assertEquals(Boolean.TRUE, ctx.getPlanOverride("promptBuilder.required"));
        assertEquals(Boolean.TRUE, ctx.getPlanOverride("gateChain.sharp"));
        assertEquals("OVERDRIVE", TraceStore.get("routing.executionPlan.applied.primaryMode"));
    }

    @Test
    void conflictingSignalsApplyOnlyPrimaryModeAndClearStaleFlags() {
        TraceStore.put("outCount", 0);
        TraceStore.put("overdrive.authority.avg", 0.10d);
        TraceStore.put("overdrive.contradiction.mean", 0.90d);
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        ctx.putPlanOverride("hypernova.enabled", true);

        ExecutionPlan plan = new ExecutionPlanApplier(new StrategyConflictResolver())
                .apply(ctx, normalSignals(true));

        assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
        assertEquals(Boolean.TRUE, ctx.getPlanOverride("extremeZ.enabled"));
        assertEquals(Boolean.FALSE, ctx.getPlanOverride("overdrive.enabled"));
        assertEquals(Boolean.FALSE, ctx.getPlanOverride("hypernova.enabled"));
        assertEquals(List.of("lowRecall", "lowAuthority", "contradiction", "highRiskTail"), plan.triggers());
        assertEquals(Boolean.TRUE, ctx.getPlanOverride("cancelShield.breadcrumb"));
        assertEquals("EXTREMEZ", TraceStore.get("routing.executionPlan.applied.primaryMode"));
        assertEquals(Boolean.TRUE, TraceStore.get("routing.executionPlan.applied.extremeZ"));
        assertEquals(Boolean.FALSE, TraceStore.get("routing.executionPlan.applied.overdrive"));
        assertEquals(Boolean.FALSE, TraceStore.get("routing.executionPlan.applied.hypernova"));
    }

    @Test
    void normalPlanClearsStaleCancelShieldBreadcrumbOverride() {
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("cancelShield.breadcrumb", true);

        ExecutionPlan plan = new ExecutionPlanApplier(new StrategyConflictResolver())
                .apply(ctx, normalSignals(false));

        assertEquals(ExecutionPlan.PrimaryMode.NORMAL, plan.primaryMode());
        assertEquals(Boolean.FALSE, ctx.getPlanOverride("cancelShield.breadcrumb"));
    }

    @Test
    void diagnosticFallbackCatchesLeaveScannerVisibleBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/orchestration/ExecutionPlanApplier.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("ExecutionPlanApplier trace skipped errorType="));
        assertTrue(source.contains("TraceStore.put(\"routing.executionPlan.suppressed.traceDouble\", true)"));
        assertTrue(source.contains("TraceStore.put(\"routing.executionPlan.suppressed.traceDouble.errorType\", errorType(ignored))"));
        assertTrue(source.contains("return \"invalid_number\";"));
    }

    @Test
    void malformedTraceDoubleUsesStableReasonCodeWithoutRawValue() {
        String raw = "private authority ownerToken=fake-token";
        TraceStore.put("overdrive.authority.avg", raw);
        GuardContext ctx = new GuardContext();

        new ExecutionPlanApplier(new StrategyConflictResolver()).apply(ctx, normalSignals(false));

        assertEquals("invalid_number", TraceStore.get("routing.executionPlan.suppressed.traceDouble.errorType"));
        assertFalse(String.valueOf(TraceStore.get("routing.executionPlan.suppressed.traceDouble.errorType")).contains(raw));
    }

    @Test
    void nonFiniteTraceDoubleDoesNotTriggerExecutionPlan() {
        TraceStore.put("overdrive.contradiction.mean", Double.POSITIVE_INFINITY);
        GuardContext ctx = new GuardContext();

        ExecutionPlan plan = new ExecutionPlanApplier(new StrategyConflictResolver())
                .apply(ctx, normalSignals(false));

        assertEquals(ExecutionPlan.PrimaryMode.NORMAL, plan.primaryMode());
        assertEquals("invalid_number", TraceStore.get("routing.executionPlan.suppressed.traceDouble.errorType"));
    }

    private static OrchestrationSignals normalSignals(boolean highRisk) {
        return new OrchestrationSignals(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                "",
                highRisk,
                0.0d,
                List.of());
    }
}
