package com.example.lms.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler that triggers offline auto-evolve checks during the configured window.
 */
@Component
@ConditionalOnProperty(prefix = "rgb.moe.autoevolve", name = "enabled", havingValue = "true")
public class AutoEvolveScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoEvolveScheduler.class);

    private final TrainingJobRunner runner;

    public AutoEvolveScheduler(TrainingJobRunner runner) {
        this.runner = runner;
    }

    /**
     * Check every 15 minutes. Actual execution is re-gated by UserIdleDetector.
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void tick() {
        try {
            runner.runOnce(true, "scheduler");
        } catch (Exception e) {
            log.warn("[AutoEvolve] scheduler error: {}", e.getMessage());
        }
    }
}
