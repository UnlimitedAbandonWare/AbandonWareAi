package com.abandonware.ai.agent.nn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SigmoidOrchestratorTest {

    @Test
    void improvementFactorIgnoresNonFiniteExternalScores() {
        double improvement = SigmoidOrchestrator.improvementFactor(
                0.5, 0.1, List.of(Double.NaN, Double.POSITIVE_INFINITY, 0.2));

        assertThat(improvement).isEqualTo(2.0);
    }

    @Test
    void improvementFactorReturnsZeroWhenNoFiniteExternalScoresRemain() {
        double improvement = SigmoidOrchestrator.improvementFactor(
                0.5, 0.1, List.of(Double.NaN, Double.NEGATIVE_INFINITY));

        assertThat(improvement).isZero();
    }

    @Test
    void combineReturnsFiniteScoreWhenFeaturesOrWeightsAreNonFinite() {
        double combined = SigmoidOrchestrator.combine(
                new double[]{Double.NaN, 0.5, Double.POSITIVE_INFINITY},
                new double[]{1.0, Double.NaN, 3.0},
                true,
                Double.NaN,
                1.0,
                0.0);

        assertThat(combined).isBetween(0.0, 1.0);
    }

    @Test
    void searchReturnsFiniteResultWhenInitialParamsAreNonFinite() {
        SigmoidOrchestrator.Result result = new SigmoidOrchestrator().search(
                new double[]{0.2, Double.NaN, 0.8},
                new double[]{1.0, Double.POSITIVE_INFINITY, 1.0},
                true,
                Double.NaN,
                0.1,
                List.of(0.2, Double.NaN),
                Double.NaN,
                Double.POSITIVE_INFINITY,
                3,
                42L);

        assertThat(result.sCombined).isBetween(0.0, 1.0);
        assertThat(result.improvementFactor).isFinite();
        assertThat(result.kBest).isFinite();
        assertThat(result.x0Best).isFinite();
    }
}
