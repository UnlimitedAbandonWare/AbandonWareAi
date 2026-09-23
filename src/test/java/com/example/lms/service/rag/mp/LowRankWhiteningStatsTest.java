package com.example.lms.service.rag.mp;

import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LowRankWhiteningStatsTest {

    @Test
    void dimensionChangeResetsObservationWindowAndProjection() {
        LowRankWhiteningStats stats = new LowRankWhiteningStats(8, 16, 64, 1.0e-6);
        for (int i = 0; i < 64; i++) {
            stats.observe(Embedding.from(new float[] {i, i + 1.0f}));
        }
        stats.refit();

        stats.observe(Embedding.from(new float[] {1.0f, 2.0f, 3.0f}));
        float[] freshDimensionVector = {2.0f, 3.0f, 4.0f};

        assertEquals(1L, stats.seen());
        assertEquals(3, stats.dimension());
        assertSame(freshDimensionVector, stats.transform(freshDimensionVector));
    }
}
