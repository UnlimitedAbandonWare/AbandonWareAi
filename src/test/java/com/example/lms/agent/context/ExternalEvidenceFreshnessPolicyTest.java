package com.example.lms.agent.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalEvidenceFreshnessPolicyTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T01:01:00Z"), ZoneOffset.UTC);

    @Test
    void explicitAgeIsFlooredAndComparedWithConfiguredThreshold() {
        ExternalEvidenceFreshnessPolicy policy = new ExternalEvidenceFreshnessPolicy(FIXED_CLOCK);
        ObjectNode evidence = JsonNodeFactory.instance.objectNode()
                .put("ageMinutes", 11.75d)
                .put("staleAfterMinutes", 60);

        ExternalEvidenceFreshnessPolicy.Freshness freshness = policy.evaluate(evidence);

        assertEquals(11, freshness.ageMinutes());
        assertEquals(60, freshness.staleAfterMinutes());
        assertFalse(freshness.stale());
    }

    @Test
    void generatedAtUsesDefaultThresholdAgainstInjectedClock() {
        ExternalEvidenceFreshnessPolicy policy = new ExternalEvidenceFreshnessPolicy(FIXED_CLOCK);
        ObjectNode evidence = JsonNodeFactory.instance.objectNode()
                .put("generatedAt", "2026-08-27T00:00:00Z");

        ExternalEvidenceFreshnessPolicy.Freshness freshness = policy.evaluate(evidence);

        assertEquals(61, freshness.ageMinutes());
        assertEquals(60, freshness.staleAfterMinutes());
        assertTrue(freshness.stale());
    }

    @Test
    void explicitStaleFlagWinsWithoutInventingAgeForUndatedEvidence() {
        ExternalEvidenceFreshnessPolicy policy = new ExternalEvidenceFreshnessPolicy(FIXED_CLOCK);
        ObjectNode evidence = JsonNodeFactory.instance.objectNode().put("stale", true);

        ExternalEvidenceFreshnessPolicy.Freshness freshness = policy.evaluate(evidence);

        assertEquals(0, freshness.ageMinutes());
        assertEquals(0, freshness.staleAfterMinutes());
        assertTrue(freshness.stale());
    }
}
