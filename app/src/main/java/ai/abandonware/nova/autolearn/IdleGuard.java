package ai.abandonware.nova.autolearn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Legacy idle guard.
 *
 * <p>Default is NOT idle. Set autolearn.alwaysIdle=true only for debugging.
 */
@Component
@Deprecated
public class IdleGuard {

    private final boolean alwaysIdle;

    public IdleGuard(@Value("${autolearn.alwaysIdle:false}") boolean alwaysIdle) {
        this.alwaysIdle = alwaysIdle;
    }

    public boolean isIdleNow() {
        // TODO: replace with UserAbsenceGate in the main runtime.
        return alwaysIdle;
    }
}
