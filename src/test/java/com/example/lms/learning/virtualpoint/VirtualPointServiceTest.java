package com.example.lms.learning.virtualpoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualPointServiceTest {

    @Test
    void nearestReturnsBestCosineMatchAboveThreshold() {
        VirtualPointService service = new VirtualPointService();
        service.put("weak", new VirtualPoint(new float[]{0.2f, 0.8f}, 0.3d, 0.3d,
                "none", "observe_only", "weak", 1L));
        service.put("strong", new VirtualPoint(new float[]{1.0f, 0.0f}, 0.9d, 0.9d,
                "after_filter_starvation", "anchor_compression_topup", "strong", 2L));

        var match = service.nearest(new float[]{1.0f, 0.0f}, 0.95d);

        assertTrue(match.isPresent());
        assertEquals("strong", match.get().key());
        assertEquals("after_filter_starvation", match.get().point().dominantFailure);
        assertEquals(1.0d, match.get().similarity(), 0.0001d);
    }

    @Test
    void nearestSkipsDimensionMismatchAndEmptyVectors() {
        VirtualPointService service = new VirtualPointService();
        service.put("mismatch", new VirtualPoint(new float[]{1.0f, 0.0f, 0.0f}));
        service.put("empty", new VirtualPoint(new float[0]));

        assertTrue(service.nearest(new float[]{1.0f, 0.0f}, 0.1d).isEmpty());
    }

    @Test
    void putKeepsBoundedLruHistory() {
        VirtualPointService service = new VirtualPointService();

        for (int i = 0; i < 257; i++) {
            service.put("k" + i, new VirtualPoint(new float[]{1.0f, i}));
        }

        assertEquals(256, service.size());
        assertTrue(service.get("k0").isEmpty());
        assertTrue(service.get("k256").isPresent());
    }
}
