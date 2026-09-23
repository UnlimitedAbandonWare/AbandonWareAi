package com.example.lms.service.rag.quality;

import java.util.List;

/**
 * GuardDecision record for chipset/quality-specific guards.
 * <p>
 * This mirrors the structure of com.example.lms.service.guard.GuardDecision
 * but lives in the rag.quality package to avoid circular dependencies.
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

    public enum Level {
        PASS,
        ALLOW_WITH_WARNING,
        STORE_MEMORY_ONLY,
        SOFT_WARN,
        HARD_BLOCK
    }

    public enum GuardAction {
        ALLOW,
        ALLOW_WITH_RUMOR,
        REGENERATE_WITH_EVIDENCE,
        EVIDENCE_LIST,
        FALLBACK,
        BLOCK
    }

    public enum DraftQuality {
        GOOD,
        WEAK,
        CONTRADICTORY,
        NO_INFO_CLAIM
    }

    public enum EvidenceStrength {
        NONE,
        WEAK,
        MEDIUM,
        STRONG
    }

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
