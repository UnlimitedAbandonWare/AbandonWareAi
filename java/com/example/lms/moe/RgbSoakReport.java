package com.example.lms.moe;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RGB soak report written under {@code rgb.moe.soakReportDir}.
 */
public record RgbSoakReport(
        String sessionId,
        Instant startedAt,
        Instant endedAt,
        String primaryStrategy,
        List<String> fallbackStrategies,
        List<RgbStrategySelector.Reason> reasons,
        List<String> queries,
        Map<String, RgbSoakMetrics> metricsByStrategy,
        Map<String, Object> debug
) {}
