package com.example.lms.orchestration.control;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Process-local Shadow/Enforce rollout state. A process restart always starts in Shadow. */
@Component
public final class RagControlRolloutState {

    private final RagControlProperties properties;
    private final Deque<Observation> window = new ArrayDeque<>();
    private Mode mode = Mode.SHADOW;
    private long generation;

    public RagControlRolloutState() {
        this(new RagControlProperties());
    }

    public RagControlRolloutState(RagControlProperties properties) {
        this.properties = properties;
    }

    public synchronized Mode record(Observation observation) {
        if (observation == null) {
            return mode;
        }
        if (observation.hardGuardInversion() || observation.disclosureLeak()) {
            mode = Mode.SHADOW;
            window.clear();
            generation++;
            return mode;
        }
        if (!observation.eligible()) {
            return mode;
        }

        window.addLast(observation);
        while (window.size() > properties.observationWindow()) {
            window.removeFirst();
        }

        Snapshot snapshot = snapshotInternal();
        if (snapshot.eligibleCount() == properties.observationWindow()) {
            boolean healthy = snapshot.gapOrUnclassifiedRate() <= properties.maxGapOrUnclassifiedRate()
                    && snapshot.p95OverheadMillis() <= properties.maxP95OverheadMillis();
            Mode next = healthy ? Mode.ENFORCE : Mode.SHADOW;
            if (next != mode) {
                mode = next;
                generation++;
            }
        }
        return mode;
    }

    public synchronized Mode mode() {
        return mode;
    }

    public synchronized Snapshot snapshot() {
        return snapshotInternal();
    }

    public synchronized RolloutLease lease() {
        return new RolloutLease(mode, generation);
    }

    synchronized <T> T withCurrentLease(Function<RolloutLease, T> action) {
        return Objects.requireNonNull(action, "action").apply(new RolloutLease(mode, generation));
    }

    private Snapshot snapshotInternal() {
        int gapCount = 0;
        ArrayList<Long> overhead = new ArrayList<>(window.size());
        for (Observation observation : window) {
            if (observation.gapOrUnclassified()) {
                gapCount++;
            }
            overhead.add(observation.composerOverheadMillis());
        }
        overhead.sort(Comparator.naturalOrder());
        long p95 = percentile95(overhead);
        double gapRate = window.isEmpty() ? 0.0d : (double) gapCount / (double) window.size();
        return new Snapshot(mode, generation, window.size(), gapCount, gapRate, p95);
    }

    private long percentile95(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int rank = (int) Math.ceil(0.95d * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    public enum Mode {
        SHADOW,
        ENFORCE
    }

    public record RolloutLease(Mode mode, long generation) {
        public RolloutLease {
            mode = mode == null ? Mode.SHADOW : mode;
            generation = Math.max(0L, generation);
        }
    }

    public record Observation(
            boolean eligible,
            boolean hardGuardInversion,
            boolean disclosureLeak,
            boolean gapOrUnclassified,
            long composerOverheadMillis) {

        public Observation {
            if (composerOverheadMillis < 0L) {
                throw new IllegalArgumentException("composerOverheadMillis must be non-negative");
            }
        }
    }

    public record Snapshot(
            Mode mode,
            long generation,
            int eligibleCount,
            int gapOrUnclassifiedCount,
            double gapOrUnclassifiedRate,
            long p95OverheadMillis) {
    }
}
