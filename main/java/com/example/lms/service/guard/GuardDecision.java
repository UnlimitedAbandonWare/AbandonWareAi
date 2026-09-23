package com.example.lms.service.guard;

import java.util.List;

/**
 * Unified GuardDecision record for high-level guardrail reporting.
 * <p>
 * NOTE: EvidenceAwareGuard has its own nested GuardDecision used internally;
 * this type is for cross-cutting logging / analytics / external consumers.
 */
public record GuardDecision(
        Level level,
        GuardAction action,
        EvidenceStrength strength,
        DraftQuality quality,
        double evidenceScore,
        double coverageScore,
        List<String> flags,
        String summary
) {

    /**
     * High-level decision level for downstream services.
     */
    public enum Level {
        PASS,
        ALLOW_WITH_WARNING,    // 답변 허용 + 경고 문구
        STORE_MEMORY_ONLY,     // 메모리 저장만
        SOFT_WARN,
        HARD_BLOCK
    }

    /**
     * Action that downstream orchestrators should take.
     */
    public enum GuardAction {
        ALLOW,                          // 그대로 통과
        ALLOW_WITH_RUMOR,               // 루머 디스클레이머 추가 후 통과
        REGENERATE_WITH_EVIDENCE,       // 웹 증거 기반으로 재생성
        EVIDENCE_LIST,                  // 증거 목록만 나열
        FALLBACK,                       // 폴백 응답
        BLOCK                           // 차단 (안전 도메인만)
    }

    /**
     * Draft answer quality assessment.
     */
    public enum DraftQuality {
        GOOD,
        WEAK,
        CONTRADICTORY,
        NO_INFO_CLAIM
    }

    /**
     * Aggregate strength of the evidence used.
     */
    public enum EvidenceStrength {
        NONE,        // 0개
        WEAK,        // 1-2개
        MEDIUM,      // 3-4개
        STRONG       // 5개 이상
    }

    /**
     * Canonical PASS instance for trivial allow decisions.
     */
    public static final GuardDecision PASS = new GuardDecision(
            Level.PASS,
            GuardAction.ALLOW,
            EvidenceStrength.NONE,
            DraftQuality.GOOD,
            1.0,
            1.0,
            List.of(),
            "통과"
    );

    public boolean isBlocked() {
        return level == Level.HARD_BLOCK || action == GuardAction.BLOCK;
    }

    public boolean needsWarning() {
        return level == Level.SOFT_WARN
                || level == Level.ALLOW_WITH_WARNING
                || action == GuardAction.ALLOW_WITH_RUMOR;
    }

    public boolean allowsMemoryOnly() {
        return level == Level.STORE_MEMORY_ONLY;
    }
}
