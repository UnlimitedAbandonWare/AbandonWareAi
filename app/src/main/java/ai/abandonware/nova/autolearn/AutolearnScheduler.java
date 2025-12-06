package ai.abandonware.nova.autolearn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triggers the idle auto-learn loop when the system is idle.
 * This bean is fully optional and activates only if `autolearn.enabled=true`.
 */
@Component
@ConditionalOnProperty(name = "autolearn.enabled", havingValue = "true")
public class AutolearnScheduler {
    private static final Logger log = LoggerFactory.getLogger(AutolearnScheduler.class);

    private final AutolearnService autolearnService;
    private final IdleGuard idleGuard;

    @Autowired
    public AutolearnScheduler(AutolearnService autolearnService, IdleGuard idleGuard) {
        this.autolearnService = autolearnService;
        this.idleGuard = idleGuard;
    }

    /**
     * Cron style by default; overridable via `scheduling.autolearn.cron`.
     * Example default: every 5 minutes.
     */
    @Scheduled(cron = "${scheduling.autolearn.cron:0 0/5 * * * *}")
    public void triggerAutoLearn() {
        try {
            if (!idleGuard.isIdleNow()) {
                log.debug("[AutolearnScheduler] Skip (system busy).");
                return;
            }
            log.info("[AutolearnScheduler] Idle detected; launching auto-learn loop...");
            AutolearnSummary summary = autolearnService.runAutoLearnLoop();
            log.info("[AutolearnScheduler] Loop finished: {}", summary);
        } catch (Throwable t) {
            log.warn("[AutolearnScheduler] Loop error: {}", t.toString(), t);
        }
    }
}