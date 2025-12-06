package com.example.lms.service.rag.quality;

public record GuardProfile(
        double minEvidence,
        double minCoverage,
        boolean allowSoftWeak
) {
    public static GuardProfile strict() {
        return new GuardProfile(0.8, 0.7, false);
    }

    public static GuardProfile balanced() {
        return new GuardProfile(0.6, 0.6, true);
    }

    public static GuardProfile wild() {
        return new GuardProfile(0.4, 0.4, true);
    }
}
