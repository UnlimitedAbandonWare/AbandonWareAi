package com.nova.protocol.score;

import java.util.List;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.score.ScoreCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.score.ScoreCalibrator
role: config
*/
public class ScoreCalibrator {
    /** Simple isotonic-like fallback: mean of neighbors, bounded [0,1]. */
    public double fallback(double s) {
        double v = Math.max(0.0, Math.min(1.0, s));
        // gently compress extremes
        return 0.1 + 0.8 * v;
    }

    public double calibrate(double raw, List<Double> refDistribution) {
        if (refDistribution == null || refDistribution.isEmpty()) return fallback(raw);
        // naive histogram rank calibration
        int count = 0;
        for (double d : refDistribution) if (d <= raw) count++;
        double p = ((double)count) / refDistribution.size();
        return fallback(p);
    }
}