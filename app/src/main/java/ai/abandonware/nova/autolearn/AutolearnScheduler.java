package ai.abandonware.nova.autolearn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Legacy autolearn scheduler (unsafe defaults in older versions).
 *
 * <p>Disabled by default. Prefer the UAW orchestrator in the main runtime.
 */
@Component
@Deprecated
@ConditionalOnProperty(prefix = "autolearn.legacy", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AutolearnScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutolearnScheduler.class);

    private final SoakTestRunner soakTestRunner;
    private final IndexRefresher indexRefresher;
    private final IdleGuard idleGuard;

    public AutolearnScheduler(SoakTestRunner soakTestRunner,
                             IndexRefresher indexRefresher,
                             IdleGuard idleGuard) {
        this.soakTestRunner = soakTestRunner;
        this.indexRefresher = indexRefresher;
        this.idleGuard = idleGuard;
    }

    /** Invoke manually if you enable this legacy path. */
    public void tick() {
        if (!idleGuard.isIdleNow()) {
            log.debug("[legacy] Skip: not idle");
            return;
        }
        soakTestRunner.runBatch();
        indexRefresher.refreshIncremental();
    }
}
