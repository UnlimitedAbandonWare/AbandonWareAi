package com.example.lms.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskDecisionIndexTest {

    @Test
    void nonFiniteRiskFallsBackToZero() {
        assertThat(new RiskDecisionIndex(Double.NaN).rdi).isZero();
        assertThat(new RiskDecisionIndex(Double.POSITIVE_INFINITY).rdi).isZero();
    }

    @Test
    void finiteRiskIsClampedToUnitRange() {
        assertThat(new RiskDecisionIndex(-0.5).rdi).isZero();
        assertThat(new RiskDecisionIndex(1.5).rdi).isEqualTo(1.0);
        assertThat(new RiskDecisionIndex(0.4).rdi).isEqualTo(0.4);
    }
}
