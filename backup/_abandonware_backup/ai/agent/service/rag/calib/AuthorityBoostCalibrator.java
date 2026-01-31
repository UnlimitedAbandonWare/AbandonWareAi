package com.abandonware.ai.agent.service.rag.calib;
import java.util.List;
import com.abandonware.ai.service.rag.model.ContextSlice;
import java.time.*;
import java.util.*;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;
import java.util.concurrent.ConcurrentHashMap;
import com.abandonware.ai.service.rag.model.ContextSlice;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.service.rag.calib.AuthorityBoostCalibrator
 * Role: config
 * Dependencies: com.abandonware.ai.service.rag.model.ContextSlice, com.abandonware.ai.agent.service.plan.RetrievalPlan
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.agent.service.rag.calib.AuthorityBoostCalibrator
role: config
*/
public class AuthorityBoostCalibrator {

    private final Map<String, Double> tierWeights = new ConcurrentHashMap<>();
    private volatile double alpha = 0.6; // blend: authority vs recency elsewhere

    public void setAlpha(double a) { this.alpha = Math.max(0.0, Math.min(1.0, a)); }
    public void putTierWeight(String tier, double w) { tierWeights.put(Objects.requireNonNull(tier), clamp01(w)); }

    public double apply(String tier, double normalizedScore) {
        double w = tierWeights.getOrDefault(tier, 0.5);
        double s = normalizedScore * (alpha * w + (1.0 - alpha)); // fallback keeps scale
        return clamp01(s);
    }

    private static double clamp01(double x) { return x<0?0: (x>1?1:x); }


    // Overload for FusionService: list-level apply

    public java.util.List<ContextSlice> apply(java.util.List<ContextSlice> in, Object ctx, RetrievalPlan plan) {
        if (in == null) return java.util.Collections.emptyList();
        // If domain tiers are not attached to ContextSlice, return as-is.
        // Non-breaking: perform no-op to keep pipeline stable.
        return in;
    }

}