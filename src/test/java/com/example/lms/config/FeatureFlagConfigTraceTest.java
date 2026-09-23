package com.example.lms.config;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FeatureFlagConfigTraceTest {

    @AfterEach
    void clearState() {
        System.clearProperty("fusion.rrf.weight.web");
        System.clearProperty("bm25.topK");
        TraceStore.clear();
    }

    @Test
    void featureFlagInvalidDoublePropertyLeavesStableReasonCode() {
        System.setProperty("fusion.rrf.weight.web", "not-a-number-owner-token");

        double parsed = FeatureFlags.parseDoubleProperty("fusion.rrf.weight.web", 0.75d);

        assertEquals(0.75d, parsed, 1.0e-9d);
        assertEquals(Boolean.TRUE, TraceStore.get("config.suppressed.featureFlags.doubleProperty"));
        assertEquals("invalid_number",
                TraceStore.get("config.suppressed.featureFlags.doubleProperty.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("owner-token"));
    }

    @Test
    void bm25InvalidTopKPropertyLeavesStableReasonCode() {
        System.setProperty("bm25.topK", "not-a-number-owner-token");

        int parsed = Bm25Config.parsePositiveIntProperty("bm25.topK", 12);

        assertEquals(12, parsed);
        assertEquals(Boolean.TRUE, TraceStore.get("config.suppressed.bm25Config.topK"));
        assertEquals("invalid_number", TraceStore.get("config.suppressed.bm25Config.topK.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("owner-token"));
    }
}
