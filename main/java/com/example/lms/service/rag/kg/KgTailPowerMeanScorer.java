package com.example.lms.service.rag.kg;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KgTailPowerMeanScorer {

    private final boolean enabled;
    private final double p;
    private final double maxBoost;

    public KgTailPowerMeanScorer(
            @Value("${retrieval.fusion.kg-tail.enabled:true}") boolean enabled,
            @Value("${retrieval.fusion.kg-tail.p:2.0}") double p,
            @Value("${retrieval.fusion.kg-tail.max-boost:0.25}") double maxBoost) {
        this.enabled = enabled;
        this.p = Math.abs(p) < 1.0e-6 ? 2.0 : p;
        this.maxBoost = clamp(maxBoost, 0.0, 0.25);
    }

    public double adjust(double baseScore, double path, double confidence, double recency, double degree) {
        if (!enabled) {
            return baseScore;
        }
        double bounded = boundedSignal(path, confidence, recency, degree);
        return clamp(baseScore * (1.0 + bounded * maxBoost), 0.0, Math.max(1.0, baseScore * 1.25));
    }

    public double boundedSignal(double path, double confidence, double recency, double degree) {
        double[] values = {
                clamp(path, 0.0, 1.0),
                clamp(confidence, 0.0, 1.0),
                clamp(recency, 0.0, 1.0),
                clamp(Math.log1p(Math.max(0.0, degree)) / Math.log1p(16.0), 0.0, 1.0)
        };
        double mean = powerMean(values, p);
        return clamp(Math.tanh(mean), 0.0, 1.0);
    }

    static double powerMean(double[] values, double p) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double exponent = Math.abs(p) < 1.0e-6 ? 2.0 : p;
        double sum = 0.0;
        for (double value : values) {
            sum += Math.pow(clamp(value, 0.0, 1.0), exponent);
        }
        return Math.pow(sum / values.length, 1.0 / exponent);
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
