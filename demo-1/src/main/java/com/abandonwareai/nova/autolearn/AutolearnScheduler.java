package com.abandonwareai.nova.autolearn;

import com.abandonwareai.nova.config.IdleTrainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single scheduler entry-point (demo-1):
 * user absent + spare capacity -> autolearn -> optional retrain ingest.
 */
@Component
public class AutolearnScheduler {
    private static final Logger log = LoggerFactory.getLogger(AutolearnScheduler.class);

    private final IdleTrainProperties props;
    private final IdleDetector idleDetector;
    private final AutoLearnOrchestrator autoLearnOrchestrator;
    private final AutolearnRagRetrainOrchestrator retrainOrchestrator;
    private final AutoLearnBudgetManager budgetManager;

    public AutolearnScheduler(IdleTrainProperties props,
                              IdleDetector idleDetector,
                              AutoLearnOrchestrator autoLearnOrchestrator,
                              AutolearnRagRetrainOrchestrator retrainOrchestrator,
                              AutoLearnBudgetManager budgetManager) {
        this.props = props;
        this.idleDetector = idleDetector;
        this.autoLearnOrchestrator = autoLearnOrchestrator;
        this.retrainOrchestrator = retrainOrchestrator;
        this.budgetManager = budgetManager;
    }

    @Scheduled(fixedDelayString = "${idle.pollMillis:60000}")
    public void tick() {
        if (props == null || !props.isEnabled()) return;

        if (!idleDetector.isIdle()) {
            log.debug("Skip AutoLearn: not idle. {}", idleDetector.snapshot());
            return;
        }

        AutoLearnBudgetManager.Lease lease = budgetManager.tryAcquire();
        if (lease == null) {
            log.debug("Skip AutoLearn: budget/cooldown.");
            return;
        }

        try {
            String sessionId = "idle-" + System.currentTimeMillis();
            PreemptionToken token = () -> !idleDetector.isIdle();

            AutoLearnCycleResult r = autoLearnOrchestrator.runIdleCycle(sessionId, token);
            if (r.abortedByUser()) {
                log.info("User returned; abort cycle.");
                return;
            }

            if (props.isAutoTrainEnabled()) {
                retrainOrchestrator.maybeRetrainByFileSize(r.acceptedCount(), token);
            }
        } catch (Exception e) {
            log.warn("AutoLearn tick error: {}", e.toString());
        } finally {
            budgetManager.onFinish(lease);
        }
    }
}
