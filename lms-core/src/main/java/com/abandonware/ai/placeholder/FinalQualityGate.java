package com.abandonware.ai.placeholder;

/** Sigmoid-based final quality gate.
 *  Aggregates normalized signals and computes a logistic score S(x), passing only if >= threshold.
 *  This is a lightweight helper with no framework dependencies.
 */
public final class FinalQualityGate {
    private final double threshold;
    private final double k;
    public FinalQualityGate(double threshold, double k) {
        this.threshold = threshold;
        this.k = k;
    }
    /** @param rrf 0..1
      *  @param rerank 0..1
      *  @param trust 0..1
      *  @param cites 0..1
      */
    public boolean allow(double rrf, double rerank, double trust, double cites) {
        double x = clamp(rrf)*0.4 + clamp(rerank)*0.3 + clamp(trust)*0.2 + clamp(cites)*0.1;
        double s = 1.0 / (1.0 + Math.exp(-k * (x - 0.5)));
        return s >= threshold;
    }
    private static double clamp(double v){ return Math.max(0.0, Math.min(1.0, v)); }
}