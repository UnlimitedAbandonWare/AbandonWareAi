package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyConflictResolverTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void simultaneousTriggersCollapseIntoSingleExtremeZPlan() {
        StrategyConflictResolver resolver = new StrategyConflictResolver();

        ExecutionPlan plan = resolver.resolve(new StrategyConflictResolver.Signals(
                true,
                true,
                true,
                true));

        assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
        assertTrue(plan.extremeZEnabled());
        assertFalse(plan.overdriveEnabled());
        assertFalse(plan.hypernovaEnabled());
        assertEquals(List.of("lowRecall", "lowAuthority", "contradiction", "highRiskTail"), plan.triggers());
        assertEquals(List.of(
                "SelfAsk",
                "QueryBurst",
                "ExtremeZBurst",
                "OverdriveNarrow",
                "Grandas/RRF",
                "BiEncoder",
                "DPP",
                "ONNX CrossEncoder",
                "GateChain"), plan.stages());

        assertEquals("EXTREMEZ", TraceStore.get("routing.executionPlan.primaryMode"));
        assertEquals("OVERDRIVE,HYPERNOVA", TraceStore.get("specialMode.conflict.suppressed"));
        assertEquals("EXTREMEZ", TraceStore.get("boosterMode.active"));
        assertEquals(List.of("OVERDRIVE", "HYPERNOVA"), TraceStore.get("boosterMode.excludedModes"));
        assertEquals(List.of("OVERDRIVE", "HYPERNOVA"), TraceStore.get("boosterMode.suppressed"));
        assertEquals(Boolean.TRUE, TraceStore.get("boosterMode.conflictResolved"));
        assertEquals("EXTREMEZ>HYPERNOVA>OVERDRIVE", TraceStore.get("boosterMode.priority"));
        assertEquals("single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE",
                TraceStore.get("boosterMode.exclusionReason"));
        assertEquals(plan.triggers(), TraceStore.get("routing.executionPlan.triggers"));
        assertEquals(Boolean.TRUE, TraceStore.get("routing.trigger.highRiskTail"));
        assertEquals(plan.triggers(), TraceStore.get("strategy.conflict.triggers"));
        assertEquals("EXTREMEZ", TraceStore.get("strategy.conflict.primaryMode"));
        assertEquals(List.of("OVERDRIVE", "HYPERNOVA"), TraceStore.get("strategy.conflict.suppressed"));
        assertEquals(Boolean.TRUE, TraceStore.get("strategy.conflict.overdriveDeferred"));
        assertEquals(Boolean.TRUE, TraceStore.get("strategy.conflict.hypernovaDeferred"));
        assertEquals("extremeZ_priority", TraceStore.get("strategy.conflict.reason"));
    }

    @Test
    void lowRecallOnlyRoutesToExtremeZWithoutOverdrive() {
        StrategyConflictResolver resolver = new StrategyConflictResolver();

        ExecutionPlan plan = resolver.resolve(new StrategyConflictResolver.Signals(
                true,
                false,
                false,
                false));

        assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
        assertTrue(plan.extremeZEnabled());
        assertFalse(plan.overdriveEnabled());
        assertFalse(plan.hypernovaEnabled());
        assertEquals(List.of("lowRecall"), plan.triggers());
    }

    @Test
    void extremeZWinsOverOverdriveWhenHypernovaIsAbsent() {
        StrategyConflictResolver resolver = new StrategyConflictResolver();

        ExecutionPlan plan = resolver.resolve(new StrategyConflictResolver.Signals(
                true,
                true,
                true,
                false));

        assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, plan.primaryMode());
        assertTrue(plan.extremeZEnabled());
        assertFalse(plan.overdriveEnabled());
        assertFalse(plan.hypernovaEnabled());
        assertEquals("OVERDRIVE", TraceStore.get("specialMode.conflict.suppressed"));
        assertEquals(Boolean.TRUE, plan.knobs().get("extremeZ.aggressive"));
        assertEquals(Boolean.FALSE, plan.knobs().get("overdrive.aggressive"));
        assertEquals("OVERDRIVE", plan.knobs().get("specialMode.conflict.suppressed"));
    }

    @Test
    void nullSignalsReturnNormalPlanWithNoSuppressedModes() {
        StrategyConflictResolver resolver = new StrategyConflictResolver();

        ExecutionPlan plan = resolver.resolve(null);

        assertEquals(ExecutionPlan.PrimaryMode.NORMAL, plan.primaryMode());
        assertFalse(plan.extremeZEnabled());
        assertFalse(plan.overdriveEnabled());
        assertFalse(plan.hypernovaEnabled());
        assertEquals(List.of(), TraceStore.get("boosterMode.excludedModes"));
        assertEquals(List.of(), TraceStore.get("boosterMode.suppressed"));
        assertEquals(Boolean.FALSE, TraceStore.get("boosterMode.conflictResolved"));
        assertEquals("NONE", TraceStore.get("boosterMode.active"));
        assertEquals(Boolean.FALSE, TraceStore.get("strategy.conflict.overdriveDeferred"));
        assertEquals(Boolean.FALSE, TraceStore.get("strategy.conflict.hypernovaDeferred"));
        assertEquals("none", TraceStore.get("strategy.conflict.reason"));
    }

    @Test
    void highRiskTailOnlyRoutesToHypernova() {
        StrategyConflictResolver resolver = new StrategyConflictResolver();

        ExecutionPlan plan = resolver.resolve(new StrategyConflictResolver.Signals(
                false,
                false,
                false,
                true));

        assertEquals(ExecutionPlan.PrimaryMode.HYPERNOVA, plan.primaryMode());
        assertFalse(plan.extremeZEnabled());
        assertFalse(plan.overdriveEnabled());
        assertTrue(plan.hypernovaEnabled());
        assertEquals(List.of("highRiskTail"), plan.triggers());
    }

    @Test
    void lowAuthorityAndContradictionRouteToOverdrive() {
        StrategyConflictResolver resolver = new StrategyConflictResolver();

        ExecutionPlan plan = resolver.resolve(new StrategyConflictResolver.Signals(
                false,
                true,
                true,
                false));

        assertEquals(ExecutionPlan.PrimaryMode.OVERDRIVE, plan.primaryMode());
        assertFalse(plan.extremeZEnabled());
        assertTrue(plan.overdriveEnabled());
        assertFalse(plan.hypernovaEnabled());
        assertEquals(List.of("lowAuthority", "contradiction"), plan.triggers());
    }

    @Test
    void traceFallbackCatchUsesDirectSafeLog() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/orchestration/StrategyConflictResolver.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("StrategyConflictResolver trace skipped errorType="));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), \"unknown\")"));
    }
}
