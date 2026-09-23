package com.abandonware.ai.agent.orchestrator.recovery;

import java.time.Instant;
import java.util.Map;

public record RecoveryRound(
        int roundNo,
        String stepName,
        Verdict verdict,
        RecoveryAction action,
        long durationMs,
        Instant startedAt,
        Map<String, Object> snapshotKeys) {
}
