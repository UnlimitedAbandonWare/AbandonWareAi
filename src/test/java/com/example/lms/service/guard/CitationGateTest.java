package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationGateTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void insufficientCitationsDegradeByDefault() {
        CitationGate gate = new CitationGate(false);

        assertEquals(CitationGate.GateDecision.DEGRADE, gate.decide(List.of("one"), 2, 1.0d));
        assertFalse(gate.ok(List.of("one"), 2, 1.0d));
    }

    @Test
    void logOnlyKeepsBackwardBooleanPass() {
        CitationGate gate = new CitationGate(true);

        assertEquals(CitationGate.GateDecision.WARN, gate.decide(List.of(), 1, 1.0d));
        assertTrue(gate.ok(List.of(), 1, 1.0d));
    }

    @Test
    void enoughCitationsPass() {
        CitationGate gate = new CitationGate(false);

        assertEquals(CitationGate.GateDecision.PASS, gate.decide(List.of("a", "b", "c"), 3, 1.0d));
        assertTrue(gate.ok(List.of("a", "b", "c"), 3, 1.0d));
    }

    @Test
    void decideStoresCountOnlyTraceBreadcrumbs() {
        CitationGate gate = new CitationGate(false);

        assertEquals(CitationGate.GateDecision.DEGRADE, gate.decide(List.of("private source"), 2, 0.5d));

        assertEquals("DEGRADE", TraceStore.get("guard.citation.decision"));
        assertEquals(1, TraceStore.get("guard.citation.sourceCount"));
        assertEquals(2, TraceStore.get("guard.citation.requiredCount"));
        assertEquals(0.5d, TraceStore.get("guard.citation.allowlistRatio"));
        assertEquals(1, TraceStore.get("gate.citation.count"));
        assertEquals(Boolean.FALSE, TraceStore.get("gate.citation.passed"));
        assertEquals(Boolean.FALSE, TraceStore.get("gate.hypernova.override"));
        assertEquals("DEGRADE", TraceStore.get("web.citation.gateStatus"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private source"));
    }

    @Test
    void zeroSourcesWarnWithMissingEvidenceHintEvenWithoutLogOnly() {
        CitationGate gate = new CitationGate(false);
        assertEquals(CitationGate.GateDecision.WARN, gate.decide(List.of(), 1, 1.0d));
        assertTrue(gate.ok(List.of(), 1, 1.0d));
        assertEquals(0, TraceStore.get("guard.citation.sourceCount"));
        var hint = java.util.Map.of("status", "근거 없음");
        assertEquals(hint, TraceStore.get("guard.citation.hint"));
        assertEquals(hint, com.example.lms.trace.SafeRedactor.diagnosticValue(
                "guard.citation.hint", TraceStore.get("guard.citation.hint")));
        String html = new com.example.lms.service.trace.TraceHtmlBuilder(null).buildSnapshotHtml(
                "test-snapshot", null, null, null, null, "codex-test", "POST", "/api/chat/sync", 200, null,
                java.util.Map.of("guard.citation.hint", hint), java.util.Map.of());
        assertTrue(html.contains("근거 없음"));
    }
}
