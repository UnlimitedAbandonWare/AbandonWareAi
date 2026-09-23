package com.example.lms.artplate.sse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "artplate.sse")
public record StochasticEvolverConfig(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("4") int stagnationThreshold,
        @DefaultValue("0.25") double noiseScale,
        @DefaultValue("0.05") double highRewardThreshold,
        @DefaultValue("-0.04") double lowPenaltyThreshold,
        @DefaultValue("0.08") double learningRate,
        @DefaultValue("1.5") double lrBoostFactor,
        @DefaultValue("0.5") double lrDecayFactor,
        @DefaultValue("2") int maxResetCount) {

    public StochasticEvolverConfig {
        stagnationThreshold = positiveOr(stagnationThreshold, 4);
        noiseScale = positiveOr(noiseScale, 0.25d);
        highRewardThreshold = positiveOr(highRewardThreshold, 0.05d);
        lowPenaltyThreshold = negativeOr(lowPenaltyThreshold, -0.04d);
        learningRate = positiveOr(learningRate, 0.08d);
        lrBoostFactor = atLeastOneOr(lrBoostFactor, 1.5d);
        lrDecayFactor = decayFactorOr(lrDecayFactor, 0.5d);
        maxResetCount = positiveOr(maxResetCount, 2);
    }

    public static StochasticEvolverConfig defaultConfig() {
        return new StochasticEvolverConfig(false, 4, 0.25d, 0.05d, -0.04d, 0.08d, 1.5d, 0.5d, 2);
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static double positiveOr(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0d ? value : fallback;
    }

    private static double negativeOr(double value, double fallback) {
        return Double.isFinite(value) && value < 0.0d ? value : fallback;
    }

    private static double atLeastOneOr(double value, double fallback) {
        return Double.isFinite(value) && value > 1.0d ? value : fallback;
    }

    private static double decayFactorOr(double value, double fallback) {
        if (!Double.isFinite(value) || value <= 0.0d || value >= 1.0d) {
            return fallback;
        }
        return value;
    }
}
