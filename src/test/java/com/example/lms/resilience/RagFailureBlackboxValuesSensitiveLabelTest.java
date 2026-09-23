package com.example.lms.resilience;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

class RagFailureBlackboxValuesSensitiveLabelTest {

    @Test
    void hashesSecretFieldShapedAsciiLabelsBeforePublishingThem() {
        String shortSyntheticSecretShape = "sk-" + "x".repeat(24);
        String overlengthSyntheticSecretShape = "sk-" + "x".repeat(90);
        assertAll(
                () -> assertHashed("apiKey"),
                () -> assertHashed("BearerToken"),
                () -> assertHashed("owner-token"),
                () -> assertHashed(shortSyntheticSecretShape),
                () -> assertHashed(overlengthSyntheticSecretShape));
    }

    @Test
    void appliesTheCentralRedactorBoundaryBeforePublishingAsciiLabels() {
        assertEquals("provider_disabled",
                RagFailureBlackboxValues.safePublicLabel("Provider_Disabled", "none"));
        String length80 = "a".repeat(80);
        assertEquals(length80, RagFailureBlackboxValues.safePublicLabel(length80, "none"));
        assertHashed("b".repeat(81));
        assertHashed("c".repeat(96));

        String overLimit = "d".repeat(97);
        String bounded = RagFailureBlackboxValues.safePublicLabel(overLimit, "none");
        assertTrue(bounded.length() <= 96, bounded);
        assertNotEquals(overLimit, bounded);
        assertFalse(overLimit.startsWith(bounded), bounded);
        assertEquals(SafeRedactor.traceLabel(overLimit), bounded);
    }

    @Test
    void continuesHashingFreeTextLabels() {
        String label = RagFailureBlackboxValues.safePublicLabel("synthetic private reason", "signal");

        assertTrue(label.startsWith("hash:"), label);
        assertFalse(label.contains("synthetic"), label);
    }

    @Test
    void mapsBlankInputAndBlankFallbackToNone() {
        assertEquals("none", RagFailureBlackboxValues.safePublicLabel("   ", "   "));
    }

    private static void assertHashed(String syntheticLabel) {
        String label = RagFailureBlackboxValues.safePublicLabel(syntheticLabel, "signal");

        assertTrue(label.startsWith("hash:"), label);
        assertFalse(label.contains(syntheticLabel.toLowerCase()), label);
    }
}
