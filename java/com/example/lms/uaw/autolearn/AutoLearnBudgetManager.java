package com.example.lms.uaw.autolearn;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Simple budget manager to avoid running autolearn too frequently.
 *
 * <p>State is persisted so restarts do not cause runaway background loops.
 */
@Component
public class AutoLearnBudgetManager {

    private final UawAutolearnProperties props;
    private final AutoLearnRunStateStore store;

    public AutoLearnBudgetManager(UawAutolearnProperties props, AutoLearnRunStateStore store) {
        this.props = props;
        this.store = store;
    }

    public BudgetLease tryAcquire() {
        long now = System.currentTimeMillis();
        String day = LocalDate.now().toString();
        Path p = Path.of(props.getBudget().getStatePath());

        synchronized (this) {
            AutoLearnRunState s = store.load(p, day);

            if (now < s.backoffUntilEpochMs) {
                return null;
            }
            if (s.runsToday >= props.getBudget().getMaxRunsPerDay()) {
                return null;
            }
            long minIntervalMs = Math.max(0, props.getBudget().getMinIntervalSeconds()) * 1000L;
            long sinceLastStart = now - s.lastStartEpochMs;
            if (s.lastStartEpochMs > 0 && sinceLastStart < minIntervalMs) {
                return null;
            }

            // Acquire: count as a run immediately to prevent restart-thrashing.
            s.day = day;
            s.runsToday++;
            s.lastStartEpochMs = now;
            store.save(p, s);
            return new BudgetLease(p, s, now);
        }
    }

    public void onFinish(BudgetLease lease, boolean success) {
        if (lease == null) return;
        long now = System.currentTimeMillis();

        synchronized (this) {
            AutoLearnRunState s = store.load(lease.statePath, lease.state.day);
            s.lastFinishEpochMs = now;

            if (success) {
                s.consecutiveFailures = 0;
                s.backoffUntilEpochMs = 0L;
            } else {
                s.consecutiveFailures = Math.max(0, s.consecutiveFailures) + 1;
                int base = Math.max(0, props.getBudget().getBaseBackoffSeconds());
                int cap = Math.max(base, props.getBudget().getMaxBackoffSeconds());
                long backoffSec = Math.min((long) cap, (long) base * (long) s.consecutiveFailures);
                s.backoffUntilEpochMs = now + backoffSec * 1000L;
            }

            store.save(lease.statePath, s);
        }
    }

    public static final class BudgetLease {
        private final Path statePath;
        private final AutoLearnRunState state;
        private final long startEpochMs;

        private BudgetLease(Path statePath, AutoLearnRunState state, long startEpochMs) {
            this.statePath = statePath;
            this.state = state;
            this.startEpochMs = startEpochMs;
        }

        public long startEpochMs() {
            return startEpochMs;
        }
    }
}
