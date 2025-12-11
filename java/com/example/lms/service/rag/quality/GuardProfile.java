package com.example.lms.service.rag.quality;

/**
 * @deprecated Use {@link com.example.lms.guard.GuardProfile} instead.
 * <p>
 * RAG 품질 튜닝을 위한 과거 threshold 정의입니다.
 * 신규 코드는 {@link com.example.lms.guard.GuardProfile}의
 * minEvidenceScore(), minCitationCount() 등을 사용해야 합니다.
 * </p>
 */
@Deprecated
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
