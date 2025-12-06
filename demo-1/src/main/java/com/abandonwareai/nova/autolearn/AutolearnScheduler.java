package com.abandonwareai.nova.autolearn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "autolearn", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AutolearnScheduler {
    private static final Logger log = LoggerFactory.getLogger(AutolearnScheduler.class);

    private final IdleDetector idleDetector;
    private final AutoLearnOrchestrator orchestrator;

    public AutolearnScheduler(IdleDetector idleDetector, AutoLearnOrchestrator orchestrator) {
        this.idleDetector = idleDetector;
        this.orchestrator = orchestrator;
    }

    // Check frequently; internal logic enforces CPU/idle thresholds.
    @Scheduled(fixedDelayString = "${idle.pollMillis:60000}")
    public void maybeRunIdleCycle() {
        if (!idleDetector.isIdle()) {
            log.debug("Skip AutoLearn: not idle enough ({})", idleDetector.snapshot());
            return;
        }
        try {
            String sessionId = "idle-" + System.currentTimeMillis();
            log.info("Starting AutoLearn idle cycle: {}", sessionId);
            orchestrator.runIdleCycle(sessionId);
            log.info("Completed AutoLearn idle cycle: {}", sessionId);
        } catch (Exception ex) {
            log.warn("AutoLearn idle cycle failed: {}", ex.toString(), ex);
        }
    }
}
