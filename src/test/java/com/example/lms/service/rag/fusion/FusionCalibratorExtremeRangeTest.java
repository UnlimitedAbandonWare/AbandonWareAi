package com.example.lms.service.rag.fusion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FusionCalibratorExtremeRangeTest {
    @Test
    void oppositeFiniteExtremesRetainEndpointsAndMidpoint() {
        assertArrayEquals(new double[] {0.0, 0.5, 1.0},
                FusionCalibrator.minMax(new double[] {-Double.MAX_VALUE, 0.0, Double.MAX_VALUE}), 1e-15);
    }

    @Test
    void overflowingAsymmetricRangeKeepsOrderAndDropsNonFiniteEntries() {
        assertArrayEquals(new double[] {1.0, 0.0, 2.0 / 3.0, 0.0, 0.0},
                FusionCalibrator.minMax(new double[] {Double.MAX_VALUE / 2.0, Double.NaN,
                        0.0, -Double.MAX_VALUE, Double.POSITIVE_INFINITY}), 1e-15);
    }

    @Test
    void adjacentLargeValuesRemainDistinct() {
        double high = Double.MAX_VALUE;
        double middle = Math.nextDown(high);
        double low = Math.nextDown(middle);
        assertArrayEquals(new double[] {0.0, 0.5, 1.0},
                FusionCalibrator.minMax(new double[] {low, middle, high}), 1e-15);
    }

    @Test
    void ordinaryAndSubnormalRangesPreserveTheirScale() {
        assertArrayEquals(new double[] {0.0, 0.25, 1.0},
                FusionCalibrator.minMax(new double[] {-4.0, -2.0, 4.0}), 1e-15);
        assertArrayEquals(new double[] {0.0, 0.5, 1.0},
                FusionCalibrator.minMax(new double[] {-Double.MIN_VALUE, 0.0, Double.MIN_VALUE}), 1e-15);
    }

    @Test
    void constantAndEmptyInputsKeepTheirExistingContract() {
        assertArrayEquals(new double[] {0.0, 0.0},
                FusionCalibrator.minMax(new double[] {Double.MAX_VALUE, Double.MAX_VALUE}));
        assertNull(FusionCalibrator.minMax(null));
        assertArrayEquals(new double[0], FusionCalibrator.minMax(new double[0]));
    }

    @Test
    void doesNotMutateInput() {
        double[] scores = {-Double.MAX_VALUE, 0.0, Double.MAX_VALUE};
        double[] original = scores.clone();
        assertNotSame(scores, FusionCalibrator.minMax(scores));
        assertArrayEquals(original, scores);
    }
}
