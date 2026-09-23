package com.example.lms.service;

import com.example.lms.util.TextSimilarityUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QualityMetricServiceTest {

    @Test
    void scoreIsFiniteAndClampedToUnitInterval() {
        assertEquals(0.0, serviceReturning(Double.NaN).calculateScore("a", "b"));
        assertEquals(0.0, serviceReturning(Double.NEGATIVE_INFINITY).calculateScore("a", "b"));
        assertEquals(0.0, serviceReturning(-0.25).calculateScore("a", "b"));
        assertEquals(1.0, serviceReturning(1.25).calculateScore("a", "b"));
    }

    @Test
    void nullInputReturnsZeroWithoutCallingSimilarity() {
        QualityMetricService service = serviceReturning(1.0);

        assertEquals(0.0, service.calculateScore(null, "b"));
        assertEquals(0.0, service.calculateScore("a", null));
    }

    private static QualityMetricService serviceReturning(double value) {
        return new QualityMetricService(new StubSimilarity(value));
    }

    private static final class StubSimilarity extends TextSimilarityUtil {
        private final double value;

        private StubSimilarity(double value) {
            this.value = value;
        }

        @Override
        public double calculateSimilarity(String a, String b) {
            return value;
        }
    }
}
