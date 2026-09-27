package com.example.lms.cfvm;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProviderAttemptCoverageTest {
    @Test void clientExchangeWithNoProviderLineageRemainsUnobservedRatherThanZeroPhysicalCalls() {
        var summary = DecisionEvidenceReconstructionValidator.audit(List.of(), List.of(), List.of(),
                List.of(Map.of("clientHttpExchangeObserved", true, "providerAttemptObserved", false)), List.of());
        assertEquals(0, summary.providerAttemptCount());
        assertEquals("not_observed", summary.toTraceMap().get("providerAttemptCoverage"));
        assertEquals("observed_provider_lineage_rows", summary.toTraceMap().get("providerAttemptCountMeaning"));
    }

    @Test void providerLineageIsCountedWithoutClaimingExhaustiveCoverage() {
        var summary = DecisionEvidenceReconstructionValidator.audit(List.of(), List.of(), List.of(),
                List.of(Map.of("providerAttemptObserved", true)), List.of());
        assertEquals(1, summary.providerAttemptCount());
        assertEquals("observed_partial", summary.toTraceMap().get("providerAttemptCoverage"));
    }
}
