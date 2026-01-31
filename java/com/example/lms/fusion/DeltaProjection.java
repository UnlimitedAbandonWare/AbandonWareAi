
package com.example.lms.fusion;

import java.time.Instant;
import java.util.Map;



public class DeltaProjection {
    /** Apply recency and novelty boosts: expects 'ageDays' and 'dupPenalty' if available. */
    public static double apply(double fusedScore, Map<String,Object> meta) {
        double ageDays = meta.getOrDefault("ageDays", 0.0) instanceof Number ? ((Number)meta.get("ageDays")).doubleValue() : 0.0;
        double recency = Math.exp(-0.03 * Math.max(0.0, ageDays)); // ~e^-0.03 per day
        double dupPenalty = meta.getOrDefault("dupPenalty", 0.0) instanceof Number ? ((Number)meta.get("dupPenalty")).doubleValue() : 0.0;
        double novelty = 1.0 - Math.min(0.5, dupPenalty);
        double delta = 0.5*recency + 0.5*novelty;
        return Math.max(0.0, Math.min(1.0, fusedScore * (0.7 + 0.3*delta)));
    }
}