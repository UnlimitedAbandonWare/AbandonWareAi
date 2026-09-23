package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvidenceAwareGuardExecutionPlanTest {

    @AfterEach
    void clearContext() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void gateChainSharpRaisesCitationThresholdAndLeavesTrace() {
        GuardContext ctx = new GuardContext();
        ctx.setMode("ZERO_BREAK");
        ctx.putPlanOverride("gateChain.sharp", true);
        CitationGate gate = new CitationGate();

        boolean enough = gate.hasEnoughCitations(List.of("https://example.test/source"), ctx);

        assertFalse(enough);
        assertEquals(Boolean.TRUE, TraceStore.get("guard.gateChain.sharp"));
        assertEquals(1, TraceStore.get("guard.gateChain.baseThreshold"));
        assertEquals(2, TraceStore.get("guard.gateChain.effectiveThreshold"));
    }
}
