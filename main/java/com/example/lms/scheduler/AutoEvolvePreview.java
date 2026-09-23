package com.example.lms.scheduler;

import com.example.lms.moe.RgbLogSignalParser;
import com.example.lms.moe.RgbResourceProbe;
import com.example.lms.moe.RgbStrategySelector;

import java.time.Instant;
import java.util.List;

/**
 * Lightweight "what would happen if we run now?" snapshot.
 *
 * <p>Used by internal debug endpoints to inspect the current signals,
 * resource health and strategy scoring without executing the heavier
 * soak pipeline.</p>
 */
public record AutoEvolvePreview(
        Instant at,
        boolean idleNow,
        RgbLogSignalParser.Features logFeatures,
        RgbResourceProbe.Snapshot resourceSnapshot,
        RgbStrategySelector.Decision decision,
        List<String> baseQueries,
        boolean willExpandWithGreen,
        boolean willAttemptBlue,
        String blueBlockedReason
) {
}
