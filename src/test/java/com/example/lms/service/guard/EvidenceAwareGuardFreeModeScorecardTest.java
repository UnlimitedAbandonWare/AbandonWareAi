package com.example.lms.service.guard;

import com.example.lms.domain.enums.VisionMode;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvidenceAwareGuardFreeModeScorecardTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void constitutionalBlockStillWinsInFreeVisionMode() {
        TraceStore.put("blackbox.risk.routingDecision", "BLOCK");
        TraceStore.put("blackbox.risk.blockRecommended", Boolean.TRUE);
        TraceStore.put("blackbox.risk.reasonCode", "block_policyrisk_observe_only");

        EvidenceAwareGuard.GuardDecision decision = new EvidenceAwareGuard().guardWithEvidence(
                "A speculative draft that must not bypass an existing constitutional block.",
                evidence(),
                0,
                VisionMode.FREE);

        assertEquals(EvidenceAwareGuard.GuardAction.BLOCK, decision.action());
        assertEquals("CONSTITUTIONAL_SCORECARD_BLOCK", TraceStore.get("guard.final.action"));
        assertEquals(Boolean.TRUE, TraceStore.get("guard.scorecard.blockApplied"));
    }

    @Test
    void freeVisionModeRemainsNeutralWithoutAConstitutionalBlock() {
        EvidenceAwareGuard.GuardDecision decision = new EvidenceAwareGuard().guardWithEvidence(
                "A speculative draft allowed without persistence.",
                evidence(),
                0,
                VisionMode.FREE);

        assertEquals(EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY, decision.action());
        assertNull(TraceStore.get("guard.scorecard.blockApplied"));
    }

    @Test
    void strictVisionForcesLowCoverageEvidenceThroughTheStrictGuardPath() {
        List<EvidenceAwareGuard.EvidenceDoc> unrelatedEvidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/quasar", "Quasar spectroscopy", "xenon quasar ultraviolet spectrum"),
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/volcano", "Volcanic geology", "basalt magma caldera stratigraphy"),
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/ocean", "Ocean chemistry", "salinity plankton thermocline current"));

        EvidenceAwareGuard.GuardDecision decision = new EvidenceAwareGuard().guardWithEvidence(
                "Bananas and bicycles move quickly across a crowded city avenue.",
                unrelatedEvidence,
                0,
                VisionMode.STRICT);

        assertEquals(EvidenceAwareGuard.GuardAction.REWRITE, decision.action());
        assertEquals(true, decision.degradedToEvidence());
    }

    @Test
    void strictVisionBlocksWhenRetrievalReturnsNoEvidence() {
        EvidenceAwareGuard.GuardDecision decision = new EvidenceAwareGuard().guardWithEvidence(
                "A confident answer that has no supporting evidence.",
                List.of(),
                0,
                VisionMode.STRICT);

        assertEquals(EvidenceAwareGuard.GuardAction.BLOCK, decision.action());
        assertEquals(false, decision.shouldPersist());
    }

    private static List<EvidenceAwareGuard.EvidenceDoc> evidence() {
        return List.of(new EvidenceAwareGuard.EvidenceDoc(
                "https://example.test/source",
                "Source title",
                "Source snippet with enough grounded context for a normal guard pass."));
    }
}
