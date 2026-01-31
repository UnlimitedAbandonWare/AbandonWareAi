package com.example.lms.scheduler;

// MERGE_HOOK:PROJ_AGENT::VECTORSTORE_FLUSH_SCHED_V1

import com.example.lms.service.VectorStoreService;
import com.example.lms.trace.TraceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vectorstore.flush.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreFlushScheduler {

    private final VectorStoreService vectorStoreService;

    public VectorStoreFlushScheduler(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    @Scheduled(fixedDelayString = "${vectorstore.flush.scheduler.period-ms:2000}")
    public void tick() {
        // Background scheduler ticks still need a trace context so downstream logs
        // (e.g., embedding fallback, DLQ, quarantines) can be correlated.
        try (TraceContext ignored = TraceContext.attach("vector-flush", "vector-flush-" + System.currentTimeMillis())) {
            vectorStoreService.triggerFlushIfDue();
        }
    }
}
