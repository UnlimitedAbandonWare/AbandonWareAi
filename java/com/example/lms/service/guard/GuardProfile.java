package com.example.lms.service.guard;

/**
 * @deprecated Use {@link com.example.lms.guard.GuardProfile} instead.
 * <p>
 * EvidenceAwareGuard v2에서는 {@link com.example.lms.guard.GuardProfile}을
 * Canonical 프로필로 사용합니다. 이 record는 과거 threshold 튜닝 코드를 위한
 * 헬퍼로만 유지되며, 신규 코드는 사용을 지양해야 합니다.
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
