package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HarmonyTraceReader {

    static final int RECENT_SNAPSHOT_LIMIT = 20;

    private static final Set<String> STRONG_RUNTIME_FRAME_KEYS = Set.of(
            "retrievalOrder.lastSetBy",
            "routing.executionPlan.primaryMode",
            "boosterMode.active",
            "hypernova.dppApplied",
            "hypernova.twpmP",
            "hypernova.cvarPhi",
            "hypernova.sourceScoreScaleMismatchCount",
            "extremeZ.cancelShieldWrapped",
            "cfvm.boltzmannTemp",
            "cfvm.tempSource",
            "cfvm.rawTile.enabled",
            "cfvm.tempAnnealApplied",
            "moe.evolverPlateRegistered",
            "extremeZ.timeBudgetConsumedMs",
            "hypernova.whitening.provider",
            "outCount",
            "starvationFallback.trigger",
            "queryTransformer.bypassed",
            "boosterMode.exclusionReason",
            "boosterMode.conflictResolved");

    private static final List<String> ADVISORY_PATH_PREFIXES = List.of(
            "/api/harmony",
            "/harmony",
            "/api/metrics/faithfulness",
            "/api/diagnostics/trace");

    private final ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider;

    public HarmonyTraceReader() {
        this.traceSnapshotStoreProvider = null;
    }

    @Autowired
    public HarmonyTraceReader(ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider) {
        this.traceSnapshotStoreProvider = traceSnapshotStoreProvider;
    }

    public TraceRead read(String key) {
        return readFrame().read(key);
    }

    public TraceFrame readFrame() {
        Map<String, Object> current = readCurrentTrace();
        if (isEligibleRuntimeTrace(current)) {
            recordFrameSelection("present", 0, 0);
            return new TraceFrame(current, "present");
        }

        TraceFrame recent = readRecentSnapshotFrame();
        if (recent != null) {
            return recent;
        }
        recordFrameSelection("missing", 0, 0);
        return TraceFrame.missing();
    }

    private Map<String, Object> readCurrentTrace() {
        try {
            return immutableCopy(TraceStore.getAll());
        } catch (RuntimeException error) {
            TraceStore.put("harmony.trace.currentRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.trace.currentRead.key", "coherentFrame");
            TraceStore.put("harmony.trace.currentRead.errorType", error.getClass().getSimpleName());
            return Map.of();
        }
    }

    private TraceFrame readRecentSnapshotFrame() {
        if (traceSnapshotStoreProvider == null) {
            return null;
        }
        int scanned = 0;
        int skipped = 0;
        try {
            TraceSnapshotStore store = traceSnapshotStoreProvider.getIfAvailable();
            if (store == null) {
                return null;
            }
            List<Map<String, Object>> summaries = store.listSummaries(RECENT_SNAPSHOT_LIMIT);
            if (summaries == null || summaries.isEmpty()) {
                return null;
            }
            for (Map<String, Object> summary : summaries) {
                Object rawId = summary == null ? null : summary.get("id");
                if (rawId == null || String.valueOf(rawId).isBlank()) {
                    continue;
                }
                scanned++;
                TraceSnapshotStore.TraceSnapshot snapshot = store.get(String.valueOf(rawId)).orElse(null);
                Map<String, Object> trace = snapshot == null ? null : snapshot.trace();
                if (isAdvisorySnapshot(snapshot) || !isEligibleRuntimeTrace(trace)) {
                    skipped++;
                    continue;
                }
                recordFrameSelection("recentSnapshot", scanned, skipped);
                return new TraceFrame(trace, "recentSnapshot");
            }
        } catch (RuntimeException error) {
            TraceStore.put("harmony.trace.snapshotRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.trace.snapshotRead.key", "coherentFrame");
            TraceStore.put("harmony.trace.snapshotRead.errorType", error.getClass().getSimpleName());
        }
        return null;
    }

    private static boolean isEligibleRuntimeTrace(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        return STRONG_RUNTIME_FRAME_KEYS.stream()
                .anyMatch(key -> trace.containsKey(key) && trace.get(key) != null);
    }

    private static boolean isAdvisorySnapshot(TraceSnapshotStore.TraceSnapshot snapshot) {
        if (snapshot == null) {
            return true;
        }
        String path = snapshot.path() == null ? "" : snapshot.path().trim();
        boolean advisoryPath = ADVISORY_PATH_PREFIXES.stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
        if (advisoryPath) {
            return true;
        }
        String reason = snapshot.reason() == null ? "" : snapshot.reason().trim().toLowerCase(java.util.Locale.ROOT);
        return reason.contains("harmony") || reason.contains("diagnostic");
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(trace));
    }

    private static void recordFrameSelection(String source, int scanned, int skipped) {
        TraceStore.put("harmony.trace.frame.source", source);
        TraceStore.put("harmony.trace.frame.snapshotCandidatesScanned", Math.max(0, scanned));
        TraceStore.put("harmony.trace.frame.skippedIneligibleCount", Math.max(0, skipped));
    }

    public record TraceFrame(Map<String, Object> values, String evidenceSource) {

        public TraceFrame {
            values = immutableCopy(values);
            evidenceSource = evidenceSource == null || evidenceSource.isBlank()
                    ? "missing"
                    : evidenceSource;
        }

        public static TraceFrame missing() {
            return new TraceFrame(Map.of(), "missing");
        }

        public TraceRead read(String key) {
            if (key == null || key.isBlank()) {
                return new TraceRead(null, "missing");
            }
            Object value = values.get(key);
            return value == null
                    ? new TraceRead(null, "missing")
                    : new TraceRead(value, evidenceSource);
        }
    }

    public record TraceRead(Object value, String evidenceSource) {
    }
}
