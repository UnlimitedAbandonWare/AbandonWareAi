package com.abandonware.ai.agent.nn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnsembleEvaluatorTest {

    @Test
    void statsIgnoreNonFiniteScores() {
        EnsembleEvaluator.Stats stats = EnsembleEvaluator.stats(
                List.of(1.0, Double.NaN, Double.POSITIVE_INFINITY, 3.0));

        assertThat(stats.min).isEqualTo(1.0);
        assertThat(stats.max).isEqualTo(3.0);
        assertThat(stats.mean).isEqualTo(2.0);
        assertThat(stats.std).isEqualTo(1.0);
        assertThat(stats.zscore).containsExactly(-1.0, 1.0);
    }

    @Test
    void statsReturnEmptyForOnlyNonFiniteScores() {
        EnsembleEvaluator.Stats stats = EnsembleEvaluator.stats(
                List.of(Double.NaN, Double.NEGATIVE_INFINITY));

        assertThat(stats.min).isZero();
        assertThat(stats.max).isZero();
        assertThat(stats.mean).isZero();
        assertThat(stats.std).isZero();
        assertThat(stats.zscore).isEmpty();
    }
}
