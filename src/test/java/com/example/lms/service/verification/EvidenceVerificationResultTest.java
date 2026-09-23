package com.example.lms.service.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceVerificationResultTest {

    @Test
    void constructorClampsMetricBounds() {
        EvidenceVerificationResult result = new EvidenceVerificationResult(
                -2,
                -3,
                Double.POSITIVE_INFINITY,
                false,
                true,
                true);

        assertEquals(0, result.getCoveredEntityCount());
        assertEquals(0, result.getSupportingDocCount());
        assertEquals(1.0, result.getCoverageScore());
    }

    @Test
    void fluentSettersClampMetricBounds() {
        EvidenceVerificationResult result = new EvidenceVerificationResult()
                .withCoveredEntityCount(-1)
                .withSupportingDocCount(-1)
                .withCoverageScore(Double.NaN);

        assertEquals(0, result.getCoveredEntityCount());
        assertEquals(0, result.getSupportingDocCount());
        assertEquals(0.0, result.getCoverageScore());
    }
}
