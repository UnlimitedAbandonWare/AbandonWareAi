package com.example.lms.service.rag.calib;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RecencyBoostCalibratorTest {

    @Test
    void nonFiniteBaseScoreFallsBackToZero() {
        RecencyBoostCalibrator calibrator = new RecencyBoostCalibrator();

        assertThat(calibrator.calibrate(Double.NaN, LocalDate.now(), "recent doc")).isZero();
        assertThat(calibrator.calibrate(Double.POSITIVE_INFINITY, LocalDate.now(), "recent doc")).isZero();
    }
}
