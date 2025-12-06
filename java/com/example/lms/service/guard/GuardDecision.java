package com.example.lms.service.guard;

import java.util.List;

public record GuardDecision(
        Level level,
        double evidenceScore,
        double coverageScore,
        java.util.List<String> flags,
        String summary
) {
    public enum Level {
        PASS,
        SOFT_WARN,
        HARD_BLOCK
    }

    public boolean isBlocked() {
        return level == Level.HARD_BLOCK;
    }

    public boolean needsWarning() {
        return level == Level.SOFT_WARN;
    }
}
