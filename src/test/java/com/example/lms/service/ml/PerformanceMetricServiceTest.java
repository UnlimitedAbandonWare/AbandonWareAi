package com.example.lms.service.ml;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceMetricServiceTest {

    @Test
    void nonFiniteRewardDoesNotPoisonEwma() {
        PerformanceMetricService service = new PerformanceMetricService();
        double before = service.getRewardEwma();

        service.trackReward(Double.NaN);
        service.trackReward(Double.POSITIVE_INFINITY);

        assertThat(service.getRewardEwma()).isEqualTo(before);
    }

    @Test
    void rewardIsClampedToUnitRange() {
        PerformanceMetricService service = new PerformanceMetricService();

        service.trackReward(2.0);

        assertThat(service.getRewardEwma()).isEqualTo(0.703);
    }
}
