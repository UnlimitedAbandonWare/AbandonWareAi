package com.abandonware.ai.normalization.service.rag.retrieval.kpolicy;

public class KAllocationPolicy {
    public static class Decision {
        public int webK; public int vectorK; public int kgK;
    }
    public enum Mode { STATIC, ADAPTIVE }
    private final Mode mode;
    private final int webMax, vectorMax, kgMax;
    public KAllocationPolicy(Mode mode, int webMax, int vectorMax, int kgMax) {
        this.mode = mode; this.webMax=webMax; this.vectorMax=vectorMax; this.kgMax=kgMax;
    }
    public Decision decide(String query, boolean recencyImportant, boolean domainSpecific) {
        Decision d = new Decision();
        if (mode == Mode.STATIC) {
            d.webK = Math.min(10, webMax);
            d.vectorK = Math.min(10, vectorMax);
            d.kgK = Math.min(5, kgMax);
            return d;
        }
        // ADAPTIVE heuristic
        if (recencyImportant) {
            d.webK = Math.min(20, webMax);
            d.vectorK = Math.min(6, vectorMax);
        } else if (domainSpecific) {
            d.webK = Math.min(6, webMax);
            d.vectorK = Math.min(12, vectorMax);
        } else {
            d.webK = Math.min(12, webMax);
            d.vectorK = Math.min(10, vectorMax);
        }
        d.kgK = Math.min(4, kgMax);
        return d;
    }
}