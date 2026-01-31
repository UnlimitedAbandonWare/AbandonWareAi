package com.example.lms._archive;

/**
 * Archive-only holder for legacy guard threshold records.
 *
 * <p>
 * 이 파일은 "정리(삭제)" 과정에서 과거 패키지에 흩어져 있던 중복 record 들을
 * 기능 손실 없이 보관하기 위한 목적의 아카이브입니다.
 * </p>
 *
 * <p>
 * NOTE: 이 클래스는 Spring Bean / Configuration / Entity 로 스캔되면 안 되므로
 * 어떠한 Spring 어노테이션도 추가하지 마세요.
 * </p>
 */
public final class ArchivedGuardProfiles {

    private ArchivedGuardProfiles() {
    }

    /**
     * Legacy threshold profile record.
     *
     * <p>
     * Originally duplicated as:
     * <ul>
     *   <li>com.example.lms.service.guard.GuardProfile (deprecated)</li>
     *   <li>com.example.lms.service.rag.quality.GuardProfile (deprecated)</li>
     * </ul>
     * Both definitions were identical and had no project usages at the time of cleanup,
     * so they were consolidated here.
     * </p>
     */
    @Deprecated
    public record GuardProfileThreshold(
            double minEvidence,
            double minCoverage,
            boolean allowSoftWeak
    ) {
        public static GuardProfileThreshold strict() {
            return new GuardProfileThreshold(0.8, 0.7, false);
        }

        public static GuardProfileThreshold balanced() {
            return new GuardProfileThreshold(0.6, 0.6, true);
        }

        public static GuardProfileThreshold wild() {
            return new GuardProfileThreshold(0.4, 0.4, true);
        }
    }
}
