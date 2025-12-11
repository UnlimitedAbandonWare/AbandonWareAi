package ai.abandonware.nova.autolearn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/**
 * Simple idle detector backed by toggles.
 * In early integration we default to "always idle" if `autolearn.alwaysIdle=true`.
 */
@Component
@ConditionalOnProperty(name = "autolearn.enabled", havingValue = "true")
public class IdleGuard {

    @Value("${scheduling.idle-threshold-sec:60}")
    private int idleThresholdSec;

    @Value("${autolearn.alwaysIdle:true}")
    private boolean alwaysIdle;

    /**
     * TODO: Wire real metrics (active sessions, GPU/CPU util, queue length).
     */
    public boolean isIdleNow() {
        return alwaysIdle;
    }
}