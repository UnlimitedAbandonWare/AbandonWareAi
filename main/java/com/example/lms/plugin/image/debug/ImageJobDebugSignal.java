package com.example.lms.plugin.image.debug;

import java.time.Instant;
import java.util.Map;

public record ImageJobDebugSignal(
        Instant at,
        String jobIdHash,
        ImageJobDebugAgent agent,
        String stage,
        double severity,
        double expected,
        double observed,
        double deltaRatio,
        double negativeScore,
        boolean triggered,
        String reason,
        Map<String, Object> data
) {
}
