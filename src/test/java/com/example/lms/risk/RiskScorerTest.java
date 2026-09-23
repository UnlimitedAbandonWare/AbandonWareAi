package com.example.lms.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScorerTest {

    @Test
    void shrinkTopKHandlesNullRiskAndNegativeBounds() {
        RiskScorer scorer = new RiskScorer();

        assertThat(scorer.shrinkTopK(10, null, -5)).isEqualTo(10);
        assertThat(scorer.shrinkTopK(-10, new RiskDecisionIndex(0.5), -5)).isZero();
    }
}
