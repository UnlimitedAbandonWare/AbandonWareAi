package com.example.lms.metrics;

import com.example.lms.harmony.HarmonyScoreEngine;
import com.example.lms.harmony.HarmonyScoreSnapshot;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FaithfulnessMetricControllerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void harmonyScoreFailureLeavesRedactedMetricBreadcrumb() {
        FaithfulnessMetricController controller = new FaithfulnessMetricController(new HarmonyScoreEngine(null, null) {
            @Override
            public HarmonyScoreSnapshot compute() {
                throw new IllegalStateException("private harmony failure should not leak");
            }
        });

        ResponseEntity<Map<String, Object>> response = controller.faithfulness();

        assertEquals("evidence_needed:IllegalStateException", response.getBody().get("harmony.score"));
        assertEquals("harmonyScore", TraceStore.get("faithfulness.metric.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("faithfulness.metric.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("faithfulness.metric.suppressed.harmonyScore"));
        assertEquals("IllegalStateException",
                TraceStore.get("faithfulness.metric.suppressed.harmonyScore.errorType"));
        assertFalse(String.valueOf(TraceStore.getByPrefix("faithfulness.metric."))
                .contains("private harmony failure should not leak"));
    }
}
