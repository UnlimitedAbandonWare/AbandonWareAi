package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.mp.LowRankWhiteningStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LowRankWhiteningTransformTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void nullInputReturnsEmptyVector() {
        LowRankWhiteningStats stats = new LowRankWhiteningStats(8, 16, 64, 1.0e-6);
        LowRankWhiteningTransform transform = new LowRankWhiteningTransform(stats);

        assertArrayEquals(new float[0], transform.apply(null));
    }

    @Test
    void missingStatsFallsBackToOriginalVector() {
        LowRankWhiteningTransform transform = new LowRankWhiteningTransform(null);
        float[] vector = {0.25f, -0.5f, 1.0f};

        assertSame(vector, transform.apply(vector));
        assertEquals(3, TraceStore.get("hypernova.whitening.inputDim"));
        assertEquals("stats_unavailable", TraceStore.get("hypernova.whitening.skipReason"));
        assertEquals("stats_unavailable", TraceStore.get("hypernova.whitening.skippedReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("hypernova.whitening.applied"));
    }

    @Test
    void providerMismatchSkipsWhiteningToAvoidFallbackDistributionContamination() {
        LowRankWhiteningStats stats = new LowRankWhiteningStats(8, 16, 64, 1.0e-6);
        LowRankWhiteningTransform transform = new LowRankWhiteningTransform(stats, "ollama");
        float[] vector = {0.25f, -0.5f, 1.0f};
        TraceStore.put("embed.provider", "openai");

        assertSame(vector, transform.apply(vector));
        assertArrayEquals(vector, transform.apply(vector));
        assertEquals("ollama", TraceStore.get("hypernova.whitening.provider"));
        assertEquals("openai", TraceStore.get("hypernova.whitening.runtimeProvider"));
        assertEquals("LowRankZCA", TraceStore.get("hypernova.whitening.method"));
        assertEquals(3, TraceStore.get("hypernova.whitening.inputDim"));
        assertEquals("provider_mismatch", TraceStore.get("hypernova.whitening.skipReason"));
        assertEquals("provider_mismatch", TraceStore.get("hypernova.whitening.skippedReason"));
        assertEquals("provider_mismatch", TraceStore.get("hypernova.whitening.disabledReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("hypernova.whitening.applied"));
    }
}
