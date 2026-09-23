package com.example.lms.nova;


public final class NovaPlanApplier {
    private NovaPlanApplier() {}

    public static <T> T apply(T planObj, BravePlan plan) {
        if (planObj == null || plan == null || !plan.enabled) return planObj;
        if (planObj instanceof BravePlanTarget target) {
            target.withWebTopK(plan.webTopK);
            target.withVectorTopK(plan.vectorTopK);
            target.withKgTopK(plan.kgTopK);
            target.withMinCitations(plan.minCitations);
            target.withBurst(plan.burstMin, plan.burstMax);
        }
        return planObj;
    }

    public static <T> T overlayTopK(T options, int webTopK) {
        if (options instanceof WebTopKTarget target) {
            target.setWebTopK(webTopK);
        }
        return options;
    }

    public interface BravePlanTarget {
        void withWebTopK(int topK);
        void withVectorTopK(int topK);
        void withKgTopK(int topK);
        void withMinCitations(int minCitations);
        void withBurst(int min, int max);
    }

    public interface WebTopKTarget {
        void setWebTopK(int topK);
    }
}
