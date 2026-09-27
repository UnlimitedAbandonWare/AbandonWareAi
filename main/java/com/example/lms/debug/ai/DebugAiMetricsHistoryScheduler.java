package com.example.lms.debug.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "lms.debug.ai.history", name = "scheduled", havingValue = "true", matchIfMissing = false)
public class DebugAiMetricsHistoryScheduler {

    private final DebugAiMetricsService service;

    public DebugAiMetricsHistoryScheduler(DebugAiMetricsService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${lms.debug.ai.history.interval-ms:30000}",
            initialDelayString = "${lms.debug.ai.history.initial-delay-ms:30000}")
    public void recordHistorySnapshot() {
        service.recordSnapshot();
    }
}
