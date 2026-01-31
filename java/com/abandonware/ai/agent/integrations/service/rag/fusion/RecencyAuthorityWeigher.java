package com.abandonware.ai.agent.integrations.service.rag.fusion;


import java.time.Instant;
/**
 * Applies authority & recency weights to a base score.
 * s' = (1-α) s + α * recency(ts)
 */
public class RecencyAuthorityWeigher {
    private final double alpha;
    public RecencyAuthorityWeigher(double alpha){
        this.alpha = Math.max(0.0, Math.min(1.0, alpha));
    }
    public double weigh(double baseScore, String domainTier, long tsMillis){
        double auth = tierWeight(domainTier);
        double rec = recencyScore(tsMillis);
        double s = baseScore * auth;
        return (1.0 - alpha) * s + alpha * rec;
    }
    private double tierWeight(String tier){
        if (tier == null) return 1.0;
        switch (tier.toUpperCase()){
            case "T1": return 1.2;
            case "T2": return 1.1;
            case "T3": return 1.0;
            case "T4": return 0.9;
            default: return 1.0;
        }
    }
    private double recencyScore(long ts){
        long now = Instant.now().toEpochMilli();
        long ageDays = Math.max(0, (now - ts) / (1000L*60*60*24));
        double x = Math.max(0.0, 180.0 - ageDays) / 180.0;
        return x; // 0..1
    }
}