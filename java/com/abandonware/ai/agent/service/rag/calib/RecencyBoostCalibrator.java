package com.abandonware.ai.agent.service.rag.calib;
import java.util.List;
import com.abandonware.ai.service.rag.model.ContextSlice;
import java.time.*;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;
import com.abandonware.ai.service.rag.model.ContextSlice;

/**
 * RecencyBoostCalibrator
 * Exponential time decay weight: w = exp(-gamma * deltaDays)
 */
public class RecencyBoostCalibrator {

    private volatile double gamma = 0.02; // decay per day

    public void setGamma(double g) { this.gamma = Math.max(0.0, g); }

    public double apply(Instant publishedAt, Instant now, double normalizedScore) {
        if (publishedAt == null) return normalizedScore;
        long days = Duration.between(publishedAt, now).toDays();
        double w = Math.exp(-gamma * Math.max(0L, days));
        double s = normalizedScore * (0.5 + 0.5*w); // keep some base mass
        if (s<0) s=0; if (s>1) s=1;
        return s;
    }


    // Overload for FusionService: list-level apply

    public java.util.List<ContextSlice> apply(java.util.List<ContextSlice> in, Object ctx, RetrievalPlan plan) {
        if (in == null) return java.util.Collections.emptyList();
        // If publication time is not available on ContextSlice, return as-is.
        return in;
    }

}