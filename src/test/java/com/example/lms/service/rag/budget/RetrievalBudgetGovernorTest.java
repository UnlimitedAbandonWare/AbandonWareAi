package com.example.lms.service.rag.budget;

import ai.abandonware.nova.orch.anchor.AnchorNarrowingResult;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalBudgetGovernorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void highAnchorAndTextureHitClampQueryBurstToThree() {
        RetrievalBudgetGovernor governor = new RetrievalBudgetGovernor(new RetrievalBudgetProperties());
        AnchorNarrowingResult anchor = new AnchorNarrowingResult(List.of("kg"), 0.90d, 0.10d, 4, 0, "ok");

        RetrievalBudgetDecision decision = governor.decide(anchor, 0.80d, false, 8, 8, 4, 9);

        assertEquals(3, decision.queryBurstCount());
        assertEquals("offline_texture_hit", decision.reason());
        assertFalse(decision.enableMassiveExpansion());
        assertEquals(3, TraceStore.get("rag.budget.applied.queryBurstCount"));
        assertEquals(8, TraceStore.get("rag.budget.suggested.webTopK"));
        assertEquals(8, TraceStore.get("rag.budget.suggested.vectorTopK"));
        assertEquals(6, TraceStore.get("rag.budget.suggested.kgTopK"));
    }

    @Test
    void highDriftDisablesHypernovaAndClampsBurstToTwo() {
        RetrievalBudgetGovernor governor = new RetrievalBudgetGovernor(new RetrievalBudgetProperties());
        AnchorNarrowingResult anchor = new AnchorNarrowingResult(List.of("kg"), 0.80d, 0.80d, 1, 3, "drift");

        RetrievalBudgetDecision decision = governor.decide(anchor, 0.0d, false, 8, 8, 4, 9);

        assertEquals(2, decision.queryBurstCount());
        assertFalse(decision.enableHypernova());
        assertEquals("anchor_drift_high", decision.reason());
        assertTrue((Double) TraceStore.get("rag.metrics.burstReductionRate") > 0.0d);
    }

    @Test
    void budgetReasonDiagnosticsUseTraceLabel() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/budget/RetrievalBudgetGovernor.java"));

        assertFalse(source.contains("TraceStore.put(\"rag.budget.reason\", decision.reason());"));
        assertFalse(source.contains("\"reason\", decision.reason(),"));
        assertFalse(source.contains("String safeReason = SafeRedactor.safeMessage(decision.reason(), 120);"));
        assertTrue(source.contains("String safeReason = SafeRedactor.traceLabelOrFallback(decision.reason(), \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"rag.budget.reason\", safeReason);"));
        assertTrue(source.contains("\"reason\", safeReason,"));
        assertTrue(source.contains("TraceStore.put(\"rag.budget.suppressed.traceDecision\", true);"));
        assertTrue(source.contains("TraceStore.put(\"rag.budget.suppressed.traceDecision.errorType\", errorType);"));
        assertTrue(source.contains("log.debug(\"[RetrievalBudgetGovernor] fail-soft stage={} errorType={}\", \"traceDecision\", errorType)"));
    }
}
