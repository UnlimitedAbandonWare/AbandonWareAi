package com.example.lms.service.rag.fusion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FusionCalibratorTest {

    @Test
    void minMaxDropsNonFiniteValuesInsteadOfLeakingNaN() {
        double[] out = FusionCalibrator.minMax(new double[] {
                Double.NaN,
                2.0d,
                Double.POSITIVE_INFINITY,
                4.0d
        });

        assertArrayEquals(new double[] {0.0d, 0.0d, 0.0d, 1.0d}, out, 1.0e-9);
        for (double score : out) {
            assertFalse(Double.isNaN(score));
            assertFalse(Double.isInfinite(score));
        }
    }

    @Test
    void minMaxReturnsZerosWhenNoFiniteScoreExists() {
        assertArrayEquals(new double[] {0.0d, 0.0d},
                FusionCalibrator.minMax(new double[] {Double.NaN, Double.NEGATIVE_INFINITY}),
                1.0e-9);
    }
}
