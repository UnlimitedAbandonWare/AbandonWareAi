package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

/**
 * Fail-soft exploration gate. It never blocks the normal path; it only decides
 * whether to add a bounded stochastic escape when risk is elevated.
 */
public class StochasticCircuitBreaker {
    private static final System.Logger LOG = System.getLogger(StochasticCircuitBreaker.class.getName());

    private final double threshold;
    private final long salt;

    public StochasticCircuitBreaker(double threshold, long salt) {
        this.threshold = clamp01(threshold);
        this.salt = salt;
    }

    public boolean shouldExplore(double riskScore, String key) {
        double risk = clamp01(riskScore);
        double roll = deterministicRoll(key);
        double probability = risk >= threshold ? Math.min(0.85d, 0.35d + risk * 0.50d) : risk * 0.20d;
        boolean explore = roll < probability;
        try {
            TraceStore.put("dualGear.stochastic.risk", risk);
            TraceStore.put("dualGear.stochastic.threshold", threshold);
            TraceStore.put("dualGear.stochastic.roll", roll);
            TraceStore.put("dualGear.stochastic.probability", probability);
            TraceStore.put("dualGear.stochastic.explore", explore);
        } catch (Throwable ignore) {
            LOG.log(System.Logger.Level.DEBUG,
                    "StochasticCircuitBreaker trace skipped errorType="
                            + SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
        }
        return explore;
    }

    private double deterministicRoll(String key) {
        long h = 1125899906842597L ^ salt;
        String s = key == null ? "" : key;
        for (int i = 0; i < s.length(); i++) {
            h = 31L * h + s.charAt(i);
        }
        long positive = h == Long.MIN_VALUE ? 0L : Math.abs(h);
        return (positive % 10_000L) / 10_000.0d;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
