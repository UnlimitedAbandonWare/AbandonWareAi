package com.example.lms.debug.ai;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DebugAiMetricsService {

    public static final String TRACE_MEMORY_CHECKPOINTS_ROUTE =
            "/api/diagnostics/trace/snapshots/latest-trace-memory/checkpoints";
    public static final String TRACE_MEMORY_HTML_ROUTE =
            "/api/diagnostics/trace/snapshots/latest-trace-memory/html";

    private static final int MAX_LIMIT = 500;
    private static final int MAX_HISTORY = 48;
    private static final long DEFAULT_WINDOW_MS = 60_000L;
    private static final long MAX_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final long ANOMALY_ERROR_DELTA_THRESHOLD = 2L;
    private static final long ANOMALY_WARN_DELTA_THRESHOLD = 4L;
    private static final double ANOMALY_SCORE_THRESHOLD = 0.70d;
    private static final double ANOMALY_POWER = 3.0d;
    private static final int VIRTUAL_MATRIX_COUNT = 300;
    private static final int VIRTUAL_MATRIX_CHUNK_SIZE = 10;

    private final DebugEventStore store;
    private final ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider;
    private final Deque<DebugAiMetricSnapshot> history = new ConcurrentLinkedDeque<>();

    @Autowired(required = false)
    private ChatUsageLedger chatUsageLedger;

    public DebugAiMetricsService(DebugEventStore store) {
        this(store, null);
    }

    @Autowired
    public DebugAiMetricsService(DebugEventStore store,
                                 ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider) {
        this.store = store;
        this.traceSnapshotStoreProvider = traceSnapshotStoreProvider;
    }

    public DebugAiMetricSnapshot snapshot(int limit, long windowMs) {
        DebugAiMetricSnapshot snapshot = buildSnapshot(limit, windowMs);
        recordSnapshot(snapshot);
        return snapshot;
    }

    public void recordSnapshot() {
        recordSnapshot(buildSnapshot(MAX_LIMIT, DEFAULT_WINDOW_MS));
    }

    public List<DebugAiMetricSnapshot> snapshotHistory(int maxEntries) {
        int limit = clamp(maxEntries, 1, MAX_HISTORY);
        List<DebugAiMetricSnapshot> out = new ArrayList<>(limit);
        int i = 0;
        for (DebugAiMetricSnapshot snap : history) {
            if (i++ >= limit) {
                break;
            }
            out.add(snap);
        }
        return List.copyOf(out);
    }

    public Map<String, Object> compactSnapshot(int limit) {
        return compactSnapshot(limit, DEFAULT_WINDOW_MS);
    }

    public Map<String, Object> compactSnapshot(int limit, long windowMs) {
        DebugAiMetricSnapshot snapshot = snapshot(limit, windowMs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", snapshot.schemaVersion());
        out.put("generatedAt", snapshot.generatedAt());
        out.put("windowMs", snapshot.windowMs());
        out.put("totalEvents", snapshot.totalEvents());
        out.put("warnEvents", snapshot.warnEvents());
        out.put("errorEvents", snapshot.errorEvents());
        out.put("usedDebugTools", snapshot.usedDebugTools());
        out.put("planUsage", snapshot.planUsage());
        out.put("tiles", snapshot.tiles());
        Map<String, Object> scorecard = snapshot.scorecard();
        out.put("scorecard", scorecard);
        putVirtualMatrixCompactSummary(out, scorecard);
        out.put("recommendations", snapshot.recommendations());
        out.put("traceMemoryDiagnostics", traceMemoryDiagnostics());
        return out;
    }

    private Map<String, Object> traceMemoryDiagnostics() {
        Map<String, Object> trace = traceMemoryTraceMap();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkpointJsonRoute", TRACE_MEMORY_CHECKPOINTS_ROUTE);
        out.put("latestTraceHtml", TRACE_MEMORY_HTML_ROUTE);
        out.put("routeDecision",
                firstNonBlank(label(trace.get("traceMemory.recovery.routeDecision")), "unavailable"));
        out.put("cfvmOffered",
                firstNonBlank(label(trace.get("traceMemory.cfvm.offered")), "unavailable"));
        out.put("cfvmPatternId",
                firstNonBlank(label(trace.get("traceMemory.cfvm.patternId")), "unavailable"));
        putTraceMemoryDiagnostic(out, "recoveryRoute", trace.get("traceMemory.recovery.route"));
        putTraceMemoryDiagnostic(out, "failureClass", trace.get("traceMemory.recovery.failureClass"));
        putTraceMemoryDiagnostic(out, "quarantine", trace.get("traceMemory.recovery.quarantine"));
        putTraceMemoryDiagnostic(out, "errorBreakRisk", trace.get("traceMemory.errorBreak.risk"));
        putTraceMemoryDiagnostic(out, "checkpointPhase", trace.get("traceMemory.checkpoint.phase"));
        putTraceMemoryDiagnostic(out, "virtualCheckpointKey", trace.get("traceMemory.virtualCheckpoint.latestKey"));
        putTraceMemoryDiagnostic(out, "virtualCheckpointStage", trace.get("traceMemory.virtualCheckpoint.latestStage"));
        putTraceMemoryDiagnostic(out, "virtualCheckpointPhase", trace.get("traceMemory.virtualCheckpoint.latestPhase"));
        return out;
    }

    private static void putTraceMemoryDiagnostic(Map<String, Object> out, String key, Object value) {
        if (out == null || key == null || key.isBlank()) {
            return;
        }
        String safe = label(value);
        if (safe != null && !safe.isBlank()) {
            out.put(key, safe);
        }
    }

    private Map<String, Object> traceMemoryTraceMap() {
        Map<String, Object> current = TraceStore.getAll();
        if (hasTraceMemorySignal(current)) {
            return current;
        }
        TraceSnapshotStore store = traceSnapshotStore();
        if (store == null) {
            return current == null ? Map.of() : current;
        }
        try {
            List<Map<String, Object>> summaries = store.listSummaries(20);
            if (summaries == null || summaries.isEmpty()) {
                return current == null ? Map.of() : current;
            }
            for (Map<String, Object> summary : summaries) {
                String id = string(summary == null ? null : summary.get("id"));
                if (id == null || id.isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
                if (snapshot.isEmpty()) {
                    continue;
                }
                Map<String, Object> trace = snapshot.get().trace();
                if (hasTraceMemorySignal(trace)) {
                    return trace;
                }
            }
        } catch (RuntimeException ex) {
            traceSuppressed("debugAiMetrics.traceMemorySnapshot", ex);
        }
        return current == null ? Map.of() : current;
    }

    private static boolean hasTraceMemorySignal(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        for (String key : trace.keySet()) {
            if (key != null && key.startsWith("traceMemory.")) {
                return true;
            }
        }
        return false;
    }

    private static void putVirtualMatrixCompactSummary(Map<String, Object> out, Map<String, Object> scorecard) {
        if (out == null || scorecard == null || scorecard.isEmpty()) {
            return;
        }
        out.put("virtualMatrixCount", scorecard.get("virtualMatrixCount"));
        out.put("virtualMatrixChunkCount", scorecard.get("virtualMatrixChunkCount"));
        out.put("virtualMatrixWeightedScore", scorecard.get("virtualMatrixWeightedScore"));
        out.put("virtualMatrixScoreRole", scorecard.get("virtualMatrixScoreRole"));
        out.put("virtualMatrixScoreTrusted", scorecard.get("virtualMatrixScoreTrusted"));
        out.put("virtualMatrixDecision", scorecard.get("virtualMatrixDecision"));
        out.put("virtualMatrixActionAllowed", scorecard.get("virtualMatrixActionAllowed"));

        List<Object> hotChunks = new ArrayList<>(3);
        Object chunks = scorecard.get("virtualMatrixChunks");
        if (chunks instanceof Iterable<?> iterable) {
            for (Object chunk : iterable) {
                if (chunk instanceof Map<?, ?>) {
                    hotChunks.add(chunk);
                    if (hotChunks.size() >= 3) {
                        break;
                    }
                }
            }
        }
        out.put("virtualMatrixHotChunks", List.copyOf(hotChunks));
    }

    private DebugAiMetricSnapshot buildSnapshot(int limit, long windowMs) {
        int safeLimit = clamp(limit, 1, MAX_LIMIT);
        long safeWindowMs = clampWindow(windowMs);
        long nowMs = System.currentTimeMillis();
        long cutoffMs = nowMs - safeWindowMs;

        List<DebugAiRawSlot> slots = new ArrayList<>();
        if (store != null) {
            try {
                for (DebugEvent event : store.list(safeLimit)) {
                    if (event == null || event.tsMs() < cutoffMs) {
                        continue;
                    }
                    slots.add(slotFromEvent(event));
                }
            } catch (RuntimeException ex) {
                traceSuppressed("debugAiMetrics.storeList", ex);
            }
        }
        slotFromTraceStore(nowMs).ifPresent(slots::add);
        riskRewriteSlotFromTraceStore(nowMs).ifPresent(slots::add);
        harmonySlotFromTraceStore(nowMs).ifPresent(slots::add);
        traceMemorySlotFromTraceStore(nowMs).ifPresent(slots::add);
        harmonySlotFromTraceSnapshots(nowMs).ifPresent(slots::add);

        Map<String, Long> probeCounts = counts(slots, DebugAiRawSlot::probe);
        Map<String, Long> layerCounts = counts(slots, DebugAiRawSlot::layer);
        Map<String, Long> failureClassCounts = counts(slots, DebugAiRawSlot::failureClass);
        List<DebugAiRawTile> tiles = tiles(slots);
        long warnEvents = slots.stream().filter(s -> "WARN".equalsIgnoreCase(s.severity())).count();
        long errorEvents = slots.stream().filter(s -> "ERROR".equalsIgnoreCase(s.severity())).count();

        return new DebugAiMetricSnapshot(
                1,
                Instant.ofEpochMilli(nowMs),
                safeWindowMs,
                slots.size(),
                warnEvents,
                errorEvents,
                probeCounts,
                layerCounts,
                failureClassCounts,
                fingerprintHotspots(slots),
                usage(slots, DebugAiRawSlot::toolId, "toolId"),
                usage(slots, DebugAiRawSlot::planId, "planId"),
                tiles,
                scorecard(slots, tiles, warnEvents, errorEvents, safeLimit, safeWindowMs, nowMs),
                recommendations(tiles, failureClassCounts, slots));
    }

    private void recordSnapshot(DebugAiMetricSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        history.addFirst(snapshot);
        while (history.size() > MAX_HISTORY) {
            history.pollLast();
        }
    }

    private DebugAiRawSlot slotFromEvent(DebugEvent event) {
        Map<String, Object> data = event.data() == null ? Map.of() : event.data();
        String probe = event.probe() == null ? "GENERIC" : event.probe().name();
        String layer = firstNonBlank(
                label(data.get("layer")),
                layerFromProbe(event.probe()),
                "spring.context");
        String failureClass = firstNonBlank(
                label(data.get("failureClass")),
                label(data.get("failure_class")),
                label(data.get("disabledReason")),
                label(data.get("reason")),
                event.error() == null ? null : label(event.error().type()),
                queryTransformerFailureClass(data, event.probe()),
                event.level() == DebugEventLevel.ERROR ? "error" : null,
                event.level() == DebugEventLevel.WARN ? "warning" : "observed");
        return new DebugAiRawSlot(
                safeMessage(event.id(), 96),
                event.tsMs(),
                probe,
                safeMessage(layer, 96),
                safeMessage(failureClass, 96),
                hash(event.fingerprint()),
                hashAlready(event.sid()),
                hashAlready(event.traceId()),
                hashAlready(event.requestId()),
                safeMessage(event.where(), 160),
                firstLong(data, "latencyMs", "tookMs", "durationMs", "timeoutMs"),
                safeId(data, "toolId", "debug.toolId", "tool"),
                safeId(data, "planId", "debug.planId", "plan"),
                hash(firstNonBlank(string(data.get("verificationCommand")), string(data.get("command")))),
                safeMessage(firstNonBlank(label(data.get("result")), label(data.get("status")),
                        event.level() == null ? null : event.level().name()), 80),
                event.level() == null ? "INFO" : event.level().name(),
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? firstLong(data, "subModelCount") : 0L,
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? firstLong(data, "branchTitleCount") : 0L,
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? branchTitleHashCount(data) : 0L,
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? firstLong(data, "branchAxisCount") : 0L,
                event.probe() == DebugProbeType.QUERY_TRANSFORMER ? firstLong(data, "paddedCount") : 0L);
    }

    private java.util.Optional<DebugAiRawSlot> slotFromTraceStore(long nowMs) {
        Map<String, Object> trace = TraceStore.getAll();
        String toolId = safeId(trace, "debug.toolId", "toolId", "tool.id");
        String planId = safeId(trace, "debug.planId", "planId", "plan.id");
        String failureClass = firstNonBlank(
                label(trace.get("debug.failureClass")),
                label(trace.get("failureClass")),
                label(trace.get("disabledReason")),
                label(trace.get("reason")));
        String verification = firstNonBlank(string(trace.get("debug.verificationCommand")),
                string(trace.get("verificationCommand")),
                string(trace.get("command")));
        if (toolId == null && planId == null && failureClass == null && verification == null) {
            return java.util.Optional.empty();
        }
        String layer = firstNonBlank(label(trace.get("debug.layer")), label(trace.get("layer")), "agent.tool");
        return java.util.Optional.of(new DebugAiRawSlot(
                "trace-current",
                nowMs,
                "TRACE_STORE",
                safeMessage(layer, 96),
                safeMessage(firstNonBlank(failureClass, "observed"), 96),
                hash(firstNonBlank(failureClass, toolId, planId, layer)),
                hashAlready(string(trace.get("sid"))),
                hashAlready(firstNonBlank(string(trace.get("traceId")), string(trace.get("trace.id")))),
                hashAlready(firstNonBlank(string(trace.get("requestId")), string(trace.get("rid")))),
                "TraceStore",
                firstLong(trace, "latencyMs", "tookMs", "durationMs", "timeoutMs"),
                toolId,
                planId,
                hash(verification),
                safeMessage(firstNonBlank(label(trace.get("debug.result")), label(trace.get("result")), "observed"), 80),
                "INFO",
                0L,
                0L,
                0L,
                0L,
                0L));
    }

    private java.util.Optional<DebugAiRawSlot> riskRewriteSlotFromTraceStore(long nowMs) {
        Map<String, Object> trace = TraceStore.getAll();
        String band = label(trace.get("ml.risk.rewrite.band"));
        String primary = label(trace.get("ml.risk.rewrite.primaryFactor"));
        String score = label(trace.get("ml.risk.rewrite.score"));
        String currentScore = label(trace.get("ml.risk.rewrite.currentScore"));
        String temperature = label(trace.get("ml.risk.rewrite.temperature"));
        String policy = label(trace.get("ml.risk.rewrite.policy"));
        String requeryRequired = label(trace.get("selfask.3way.requery.required"));
        String requeryConfirmed = label(trace.get("selfask.3way.requery.confirmed"));
        long laneWeightCount = boundedSignalSize(trace.get("selfask.3way.weights"));
        long laneProfileCount = Math.max(
                boundedSignalSize(trace.get("extremeZ.burstExpand.laneProfiles")),
                boundedSignalSize(trace.get("extremeZ.burstExpand.laneVariantProfiles")));
        Object components = trace.get("ml.risk.rewrite.components");
        if (band == null && primary == null && score == null && currentScore == null && temperature == null
                && policy == null && requeryRequired == null && requeryConfirmed == null
                && components == null && laneWeightCount <= 0L && laneProfileCount <= 0L) {
            return java.util.Optional.empty();
        }

        String normalized = SafeRedactor.traceLabelOrFallback(firstNonBlank(primary, band, "observed"),
                "observed");
        String failureClass = "risk_rewrite." + normalized;
        String planId = "selfask.risk_rewrite."
                + SafeRedactor.traceLabelOrFallback(firstNonBlank(policy, "observed"), "observed");
        String fingerprintSeed = String.join("|",
                failureClass,
                firstNonBlank(band, "none"),
                firstNonBlank(score, currentScore, "0"),
                firstNonBlank(temperature, "0"),
                String.valueOf(laneWeightCount),
                String.valueOf(laneProfileCount),
                firstNonBlank(requeryRequired, "false"),
                firstNonBlank(requeryConfirmed, "false"));
        return java.util.Optional.of(new DebugAiRawSlot(
                "trace-current-risk-rewrite",
                nowMs,
                "TRACE_RISK_REWRITE",
                "query_transformer.risk_rewrite",
                safeMessage(failureClass, 96),
                hash(fingerprintSeed),
                hashAlready(string(trace.get("sid"))),
                hashAlready(firstNonBlank(string(trace.get("traceId")), string(trace.get("trace.id")))),
                hashAlready(firstNonBlank(string(trace.get("requestId")), string(trace.get("rid")))),
                "TraceStore.riskRewrite",
                firstLong(trace, "latencyMs", "tookMs", "durationMs", "timeoutMs"),
                null,
                planId,
                null,
                safeMessage(firstNonBlank(band, primary, "observed"), 80),
                riskRewriteSeverity(band, requeryRequired, requeryConfirmed),
                laneWeightCount,
                0L,
                0L,
                laneProfileCount,
                0L));
    }

    private java.util.Optional<DebugAiRawSlot> traceMemorySlotFromTraceStore(long nowMs) {
        Map<String, Object> trace = traceMemoryTraceMap();
        String triggered = label(trace.get("traceMemory.triggered"));
        String reason = label(trace.get("traceMemory.trigger.reason"));
        String recoveryAction = label(trace.get("traceMemory.recovery.action"));
        String recoveryRoute = label(trace.get("traceMemory.recovery.route"));
        String recoveryRouteDecision = label(trace.get("traceMemory.recovery.routeDecision"));
        String recoveryFailureClass = label(trace.get("traceMemory.recovery.failureClass"));
        String quarantine = label(trace.get("traceMemory.recovery.quarantine"));
        String suspectPayloadIsolated = label(trace.get("traceMemory.suspectPayload.isolated"));
        String risk = label(trace.get("traceMemory.errorBreak.risk"));
        String cfvmOffered = label(trace.get("traceMemory.cfvm.offered"));
        String cfvmPatternId = label(trace.get("traceMemory.cfvm.patternId"));
        String fingerprint = string(trace.get("traceMemory.fingerprint.current"));
        String checkpointStage = label(trace.get("traceMemory.checkpoint.stage"));
        String checkpointPhase = label(trace.get("traceMemory.checkpoint.phase"));
        String virtualCheckpointKey = label(trace.get("traceMemory.virtualCheckpoint.latestKey"));
        String virtualCheckpointStage = label(trace.get("traceMemory.virtualCheckpoint.latestStage"));
        String virtualCheckpointPhase = label(trace.get("traceMemory.virtualCheckpoint.latestPhase"));
        long checkpointIndex = firstLong(trace, "traceMemory.checkpoint.index");
        long changedCount = firstLong(trace, "traceMemory.delta.changedCount");
        long droppedBreadcrumbCount = firstLong(trace, "traceMemory.delta.droppedBreadcrumbCount");
        if (triggered == null && reason == null && recoveryAction == null && recoveryRoute == null
                && recoveryRouteDecision == null
                && recoveryFailureClass == null && quarantine == null && suspectPayloadIsolated == null
                && risk == null && cfvmOffered == null && cfvmPatternId == null
                && fingerprint == null && checkpointStage == null && checkpointPhase == null
                && virtualCheckpointKey == null && virtualCheckpointStage == null && virtualCheckpointPhase == null
                && checkpointIndex <= 0L && changedCount <= 0L && droppedBreadcrumbCount <= 0L) {
            return java.util.Optional.empty();
        }

        String normalized = SafeRedactor.traceLabelOrFallback(
                firstNonBlank(reason, recoveryRouteDecision, recoveryRoute, recoveryAction, recoveryFailureClass,
                        risk, cfvmPatternId, virtualCheckpointPhase, virtualCheckpointStage,
                        checkpointPhase, checkpointStage, "checkpoint"),
                "checkpoint");
        String failureClass = "trace_memory." + normalized;
        String fingerprintSeed = firstNonBlank(fingerprint, failureClass, String.valueOf(checkpointIndex),
                recoveryRouteDecision, recoveryRoute, cfvmPatternId, cfvmOffered,
                virtualCheckpointKey, virtualCheckpointPhase, virtualCheckpointStage, checkpointPhase, checkpointStage,
                String.valueOf(changedCount), String.valueOf(droppedBreadcrumbCount));
        String virtualPlanId = traceMemoryVirtualPlanId(virtualCheckpointStage, virtualCheckpointKey);
        return java.util.Optional.of(new DebugAiRawSlot(
                "trace-current-trace-memory",
                nowMs,
                "TRACE_MEMORY",
                "memory.postprocess",
                safeMessage(failureClass, 96),
                fingerprint != null && fingerprint.startsWith("hash:") ? fingerprint : hash(fingerprintSeed),
                hashAlready(string(trace.get("sid"))),
                hashAlready(firstNonBlank(string(trace.get("traceId")), string(trace.get("trace.id")))),
                hashAlready(firstNonBlank(string(trace.get("requestId")), string(trace.get("rid")))),
                "TraceStore.traceMemory",
                0L,
                null,
                firstNonBlank(virtualPlanId, recoveryRouteDecision, recoveryRoute, checkpointPhase,
                        "trace.memory.fingerprint"),
                null,
                safeMessage(firstNonBlank(recoveryRouteDecision, recoveryRoute, recoveryAction, reason, risk,
                        cfvmPatternId, virtualCheckpointPhase, virtualCheckpointStage, checkpointPhase,
                        checkpointStage, "checkpoint"), 80),
                traceMemorySeverity(trace),
                0L,
                0L,
                0L,
                0L,
                0L));
    }

    private static String traceMemoryVirtualPlanId(String virtualCheckpointStage, String virtualCheckpointKey) {
        String label = firstNonBlank(virtualCheckpointStage, virtualCheckpointKey);
        if (label == null) {
            return null;
        }
        return "trace.memory.virtual." + SafeRedactor.traceLabelOrFallback(label, "checkpoint");
    }

    private java.util.Optional<DebugAiRawSlot> harmonySlotFromTraceStore(long nowMs) {
        Map<String, Object> trace = TraceStore.getAll();
        return harmonySlotFromTraceMap(
                trace,
                nowMs,
                "trace-current-chat-harmony",
                "TraceStore.chatHarmony",
                hashAlready(string(trace.get("sid"))),
                hashAlready(firstNonBlank(string(trace.get("traceId")), string(trace.get("trace.id")))),
                hashAlready(firstNonBlank(string(trace.get("requestId")), string(trace.get("rid")))));
    }

    /**
     * Returns only the bounded, agent-visible chat-harmony scalars from the
     * current trace or, after a turn reset, the newest retained trace snapshot.
     */
    public Map<String, Object> latestChatHarmonyEvidence() {
        Map<String, Object> currentTrace = TraceStore.getAll();
        Map<String, Object> current = chatHarmonyEvidenceFromTraceMap(currentTrace);
        if (!current.isEmpty()) {
            return current;
        }
        String currentSid = string(currentTrace.get("sid"));
        if (currentSid == null || currentSid.isBlank()) {
            return Map.of();
        }

        TraceSnapshotStore store = traceSnapshotStore();
        if (store == null) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> summaries = store.listSummaries(20);
            if (summaries == null) {
                return Map.of();
            }
            for (Map<String, Object> summary : summaries) {
                String id = string(summary == null ? null : summary.get("id"));
                if (id == null || id.isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
                if (snapshot.isEmpty()) {
                    continue;
                }
                TraceSnapshotStore.TraceSnapshot snap = snapshot.get();
                if (!sameChatHarmonySession(currentSid, snap)) {
                    continue;
                }
                Map<String, Object> evidence = chatHarmonyEvidenceFromTraceMap(snap.trace());
                if (!evidence.isEmpty()) {
                    return evidence;
                }
            }
        } catch (RuntimeException ex) {
            traceSuppressed("debugAiMetrics.chatHarmonyEvidenceSnapshot", ex);
        }
        return Map.of();
    }

    /**
     * Returns only the same-session MLA breadcrumb count and redaction flag.
     * Raw breadcrumb payloads are intentionally never promoted.
     */
    public Map<String, Object> latestChatMlaEvidence() {
        Map<String, Object> currentTrace = TraceStore.getAll();
        String currentSid = string(currentTrace.get("sid"));
        if (currentSid == null || currentSid.isBlank()) {
            return Map.of();
        }
        Map<String, Object> current = chatMlaEvidenceFromTraceMap(currentTrace);
        if (!current.isEmpty()) {
            return current;
        }

        TraceSnapshotStore store = traceSnapshotStore();
        if (store == null) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> summaries = store.listSummaries(20);
            if (summaries == null) {
                return Map.of();
            }
            for (Map<String, Object> summary : summaries) {
                String id = string(summary == null ? null : summary.get("id"));
                if (id == null || id.isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
                if (snapshot.isEmpty() || !sameChatHarmonySession(currentSid, snapshot.get())) {
                    continue;
                }
                Map<String, Object> evidence = chatMlaEvidenceFromTraceMap(snapshot.get().trace());
                if (!evidence.isEmpty()) {
                    return evidence;
                }
            }
        } catch (RuntimeException ex) {
            traceSuppressed("debugAiMetrics.chatMlaEvidenceSnapshot", ex);
        }
        return Map.of();
    }

    private static boolean sameChatHarmonySession(String currentSid,
                                                   TraceSnapshotStore.TraceSnapshot snapshot) {
        if (currentSid == null || currentSid.isBlank() || snapshot == null) {
            return false;
        }
        String snapshotSid = snapshot.sid();
        if (currentSid.equals(snapshotSid)) {
            return true;
        }
        String hashedCurrentSid = SafeRedactor.hashValue(currentSid);
        if (hashedCurrentSid != null && hashedCurrentSid.equals(snapshotSid)) {
            return true;
        }
        String snapshotTraceSid = string(snapshot.trace() == null ? null : snapshot.trace().get("sid"));
        return currentSid.equals(snapshotTraceSid)
                || (hashedCurrentSid != null && hashedCurrentSid.equals(snapshotTraceSid));
    }

    private static Map<String, Object> chatHarmonyEvidenceFromTraceMap(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        putHarmonyEvidenceScalar(out, "agentVisible", trace.get("chat.harmony.postprocess.agentVisible"));
        putHarmonyEvidenceScalar(out, "applied", trace.get("chat.harmony.postprocess.applied"));
        putHarmonyEvidenceScalar(out, "degraded", trace.get("chat.harmony.postprocess.degraded"));
        putHarmonyEvidenceScalar(out, "decision", trace.get("chat.harmony.postprocess.decision"));
        putHarmonyEvidenceScalar(out, "reason", trace.get("chat.harmony.postprocess.reason"));
        putHarmonyEvidenceScalar(out, "weightedScore", trace.get("chat.harmony.postprocess.weightedScore"));
        putHarmonyEvidenceScalar(out, "evidenceCount", trace.get("chat.harmony.postprocess.evidenceCount"));
        putHarmonyEvidenceScalar(out, "nextAction", trace.get("debug.ai.metrics.nextAction"));
        putHarmonyEvidenceScalar(out, "nextReason", trace.get("debug.ai.metrics.nextReason"));
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static Map<String, Object> chatMlaEvidenceFromTraceMap(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Object countValue = trace.get("cihRag.mlaBreadcrumbCount");
        if (countValue instanceof Number number) {
            double finite = number.doubleValue();
            if (Double.isFinite(finite) && finite >= 0.0d && finite == Math.rint(finite)) {
                out.put("breadcrumbCount", number.longValue());
            }
        }
        Object redactedValue = trace.get("cihRag.breadcrumb.queryRedacted");
        if (redactedValue instanceof Boolean) {
            out.put("queryRedacted", redactedValue);
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static void putHarmonyEvidenceScalar(Map<String, Object> out, String key, Object value) {
        if (value instanceof Boolean) {
            out.put(key, value);
            return;
        }
        if (value instanceof Number number) {
            if (Double.isFinite(number.doubleValue())) {
                out.put(key, value);
            }
            return;
        }
        String safe = SafeRedactor.traceLabelOrFallback(value, "");
        if (safe != null && !safe.isBlank()) {
            out.put(key, safe);
        }
    }

    private java.util.Optional<DebugAiRawSlot> harmonySlotFromTraceSnapshots(long nowMs) {
        TraceSnapshotStore store = traceSnapshotStore();
        if (store == null) {
            return java.util.Optional.empty();
        }
        try {
            List<Map<String, Object>> summaries = store.listSummaries(20);
            if (summaries == null || summaries.isEmpty()) {
                return java.util.Optional.empty();
            }
            for (Map<String, Object> summary : summaries) {
                String id = string(summary == null ? null : summary.get("id"));
                if (id == null || id.isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
                if (snapshot.isEmpty()) {
                    continue;
                }
                TraceSnapshotStore.TraceSnapshot snap = snapshot.get();
                java.util.Optional<DebugAiRawSlot> slot = harmonySlotFromTraceMap(
                        snap.trace(),
                        nowMs,
                        "trace-snapshot-chat-harmony",
                        "TraceSnapshotStore.chatHarmony",
                        hashAlready(snap.sid()),
                        hashAlready(snap.traceId()),
                        hashAlready(snap.requestId()));
                if (slot.isPresent()) {
                    return slot;
                }
            }
        } catch (RuntimeException ex) {
            traceSuppressed("debugAiMetrics.harmonySnapshot", ex);
        }
        return java.util.Optional.empty();
    }

    private TraceSnapshotStore traceSnapshotStore() {
        try {
            return traceSnapshotStoreProvider == null ? null : traceSnapshotStoreProvider.getIfAvailable();
        } catch (RuntimeException ex) {
            traceSuppressed("debugAiMetrics.traceSnapshotStore", ex);
            return null;
        }
    }

    private java.util.Optional<DebugAiRawSlot> harmonySlotFromTraceMap(Map<String, Object> trace,
                                                                      long nowMs,
                                                                      String slotId,
                                                                      String where,
                                                                      String sidHash,
                                                                      String traceIdHash,
                                                                      String requestIdHash) {
        if (trace == null || trace.isEmpty()) {
            return java.util.Optional.empty();
        }
        String agentVisible = label(trace.get("chat.harmony.postprocess.agentVisible"));
        String decision = label(trace.get("chat.harmony.postprocess.decision"));
        String reason = label(trace.get("chat.harmony.postprocess.reason"));
        String weightedScore = label(trace.get("chat.harmony.postprocess.weightedScore"));
        long evidenceCount = firstLong(trace, "chat.harmony.postprocess.evidenceCount");
        if (agentVisible == null && decision == null && reason == null
                && weightedScore == null && evidenceCount <= 0L) {
            return java.util.Optional.empty();
        }

        String normalizedDecision = SafeRedactor.traceLabelOrFallback(
                firstNonBlank(decision, reason, "observed"),
                "observed");
        String failureClass = "chat_harmony." + normalizedDecision;
        String fingerprintSeed = firstNonBlank(failureClass, reason, weightedScore, String.valueOf(evidenceCount));
        return java.util.Optional.of(new DebugAiRawSlot(
                slotId,
                nowMs,
                "TRACE_STORE",
                "evidence.output",
                safeMessage(failureClass, 96),
                hash(fingerprintSeed),
                sidHash,
                traceIdHash,
                requestIdHash,
                where,
                0L,
                null,
                "chat.harmony.postprocess",
                null,
                safeMessage(firstNonBlank(decision, reason, "observed"), 80),
                harmonySeverity(trace),
                0L,
                0L,
                0L,
                0L,
                0L));
    }

    private static String harmonySeverity(Map<String, Object> trace) {
        String decision = label(trace.get("chat.harmony.postprocess.decision"));
        String reason = label(trace.get("chat.harmony.postprocess.reason"));
        if (containsHarmonyRisk(decision) || containsHarmonyRisk(reason)) {
            return "WARN";
        }
        Object scoreValue = trace.get("chat.harmony.postprocess.weightedScore");
        if (scoreValue instanceof Number && doubleNumber(scoreValue) < 0.45d) {
            return "WARN";
        }
        return "INFO";
    }

    private static String traceMemorySeverity(Map<String, Object> trace) {
        String risk = label(trace.get("traceMemory.errorBreak.risk"));
        String quarantine = label(trace.get("traceMemory.recovery.quarantine"));
        String triggered = label(trace.get("traceMemory.triggered"));
        if ("BREAK".equalsIgnoreCase(risk) || "true".equalsIgnoreCase(quarantine)) {
            return "ERROR";
        }
        if ("WARN".equalsIgnoreCase(risk) || "true".equalsIgnoreCase(triggered)) {
            return "WARN";
        }
        return "INFO";
    }

    private static boolean containsHarmonyRisk(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("blank")
                || normalized.contains("fallback")
                || normalized.contains("limited")
                || normalized.contains("without_context")
                || normalized.contains("guarded");
    }

    private List<DebugAiRawTile> tiles(List<DebugAiRawSlot> slots) {
        List<DebugAiRawTile> out = new ArrayList<>();
        for (DebugAiTileType type : DebugAiTileType.values()) {
            List<DebugAiRawSlot> matching = slots.stream()
                    .filter(slot -> tileFor(slot) == type)
                    .toList();
            long warn = matching.stream().filter(s -> "WARN".equalsIgnoreCase(s.severity())).count();
            long error = matching.stream().filter(s -> "ERROR".equalsIgnoreCase(s.severity())).count();
            long lastTs = matching.stream().mapToLong(DebugAiRawSlot::tsMs).max().orElse(0L);
            out.add(new DebugAiRawTile(
                    type.ordinal(),
                    type.name(),
                    matching.size(),
                    warn,
                    error,
                    topValue(matching, DebugAiRawSlot::failureClass),
                    topValue(matching, DebugAiRawSlot::fingerprintHash),
                    lastTs,
                    error > 0 ? "error" : warn > 0 ? "warn" : matching.isEmpty() ? "idle" : "observed"));
        }
        return out;
    }

    private DebugAiTileType tileFor(DebugAiRawSlot slot) {
        String joined = (slot.probe() + " " + slot.layer() + " " + slot.failureClass() + " " + slot.toolId())
                .toLowerCase(Locale.ROOT);
        if (slot.verificationCommandHash() != null || joined.contains("gradle") || joined.contains("compile")
                || joined.contains("build") || joined.contains("verification")) {
            return DebugAiTileType.VERIFICATION_BUILD;
        }
        if (joined.contains("external_evidence") || joined.contains("external.evidence")
                || joined.contains("external-evidence")) {
            return DebugAiTileType.EXTERNAL_EVIDENCE;
        }
        if (slot.toolId() != null || joined.contains("agent.tool") || joined.contains("agent.report")
                || joined.contains("tool")) {
            return DebugAiTileType.AGENT_TOOL_USAGE;
        }
        if (joined.contains("query_transformer") || joined.contains("querytransformer")) {
            return DebugAiTileType.QUERY_TRANSFORMER;
        }
        if (joined.contains("web_search") || joined.contains("naver") || joined.contains("brave")
                || joined.contains("serpapi") || joined.contains("tavily") || joined.contains("web.search")) {
            return DebugAiTileType.WEB_SEARCH;
        }
        if (joined.contains("vector") || joined.contains("embedding")) {
            return DebugAiTileType.VECTOR_RETRIEVAL;
        }
        if (joined.contains("cancel") || joined.contains("shield") || joined.contains("interrupt")
                || joined.contains("nightmare") || joined.contains("breaker")) {
            return DebugAiTileType.SPRING_CONTEXT;
        }
        if (joined.contains("kg") || joined.contains("graph")) {
            return DebugAiTileType.KG_GRAPH;
        }
        if (joined.contains("model_guard") || joined.contains("modelguard") || joined.contains("llm")) {
            return DebugAiTileType.LLM_MODEL_GUARD;
        }
        if (joined.contains("evidence") || joined.contains("prompt")) {
            return DebugAiTileType.EVIDENCE_OUTPUT;
        }
        if (joined.contains("image_job") || joined.contains("image.job") || joined.contains("imagejob")) {
            return DebugAiTileType.IMAGE_JOB;
        }
        return DebugAiTileType.SPRING_CONTEXT;
    }

    private List<Map<String, Object>> usage(List<DebugAiRawSlot> slots,
                                            Function<DebugAiRawSlot, String> extractor,
                                            String keyName) {
        Map<String, List<DebugAiRawSlot>> grouped = slots.stream()
                .filter(slot -> extractor.apply(slot) != null)
                .collect(Collectors.groupingBy(extractor, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<DebugAiRawSlot>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(keyName, entry.getKey());
            row.put("count", entry.getValue().size());
            row.put("lastTsMs", entry.getValue().stream().mapToLong(DebugAiRawSlot::tsMs).max().orElse(0L));
            row.put("resultCounts", counts(entry.getValue(), DebugAiRawSlot::result));
            out.add(row);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> number(m.get("count"))).reversed());
        return out;
    }

    private List<Map<String, Object>> fingerprintHotspots(List<DebugAiRawSlot> slots) {
        Map<String, List<DebugAiRawSlot>> grouped = slots.stream()
                .filter(slot -> slot.fingerprintHash() != null)
                .collect(Collectors.groupingBy(DebugAiRawSlot::fingerprintHash, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<DebugAiRawSlot>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fingerprintHash", entry.getKey());
            row.put("count", entry.getValue().size());
            row.put("topFailureClass", topValue(entry.getValue(), DebugAiRawSlot::failureClass));
            row.put("lastTsMs", entry.getValue().stream().mapToLong(DebugAiRawSlot::tsMs).max().orElse(0L));
            out.add(row);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> number(m.get("count"))).reversed());
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    private Map<String, Object> scorecard(List<DebugAiRawSlot> slots,
                                          List<DebugAiRawTile> tiles,
                                          long warnEvents,
                                          long errorEvents,
                                          int currentSampleLimit,
                                          long currentWindowMs,
                                          long currentGeneratedAtMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = Math.max(1, slots.size());
        DebugAiMetricSnapshot previousCandidate = history.peekFirst();
        Map<String, Object> previousScorecard = previousCandidate == null || previousCandidate.scorecard() == null
                ? Map.of()
                : previousCandidate.scorecard();
        int previousSampleLimit = (int) number(previousScorecard.get("currentSampleLimit"));
        long previousWindowMs = previousCandidate == null ? 0L : previousCandidate.windowMs();
        long previousGeneratedAtMs = previousCandidate == null || previousCandidate.generatedAt() == null
                ? 0L
                : previousCandidate.generatedAt().toEpochMilli();
        boolean historyComparisonComparable = previousCandidate != null
                && previousWindowMs == currentWindowMs
                && previousSampleLimit == currentSampleLimit;
        String historyComparisonReason = previousCandidate == null
                ? "baseline_missing"
                : historyComparisonComparable ? "comparable" : "sampling_policy_mismatch";
        DebugAiMetricSnapshot previous = historyComparisonComparable ? previousCandidate : null;
        long warnDelta = previous == null ? 0L : warnEvents - previous.warnEvents();
        long errorDelta = previous == null ? 0L : errorEvents - previous.errorEvents();
        DebugAiRawTile hotTile = tiles.stream()
                .max(Comparator.comparingLong(DebugAiRawTile::eventCount))
                .orElse(null);
        String hotTileName = hotTile == null ? "SPRING_CONTEXT" : hotTile.tileName();
        String anomalyFailureClass = hotTile == null ? null : hotTile.topFailureClass();
        double anomalyScore = anomalyScore(total, warnEvents, errorEvents, warnDelta, errorDelta, hotTile);
        String anomalyReason = anomalyReason(previous, warnDelta, errorDelta, hotTile, anomalyScore);
        boolean anomalyTriggered = previous != null && !"observe".equals(anomalyReason);
        out.put("warnRatio", warnEvents / (double) total);
        out.put("errorRatio", errorEvents / (double) total);
        out.put("warnTrend", trendLabel(previous, warnDelta));
        out.put("errorTrend", trendLabel(previous, errorDelta));
        out.put("warnDelta", warnDelta);
        out.put("errorDelta", errorDelta);
        out.put("historyComparisonComparable", historyComparisonComparable);
        out.put("historyComparisonReason", historyComparisonReason);
        out.put("currentWindowMs", currentWindowMs);
        out.put("previousWindowMs", previousWindowMs);
        out.put("currentSampleLimit", currentSampleLimit);
        out.put("previousSampleLimit", previousSampleLimit);
        out.put("currentGeneratedAtMs", Math.max(0L, currentGeneratedAtMs));
        out.put("previousGeneratedAtMs", Math.max(0L, previousGeneratedAtMs));
        out.put("toolUsageCount", slots.stream().filter(s -> s.toolId() != null).count());
        out.put("verificationUsageCount", slots.stream().filter(s -> s.verificationCommandHash() != null).count());
        out.put("queryRewriteSubModelCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewriteSubModelCount)
                .sum());
        out.put("queryRewriteBranchTitleCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewriteBranchTitleCount)
                .sum());
        out.put("queryRewriteBranchTitleHashCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewriteBranchTitleHashCount)
                .sum());
        out.put("queryRewriteBranchAxisCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewriteBranchAxisCount)
                .sum());
        out.put("queryRewritePaddedCount", slots.stream()
                .mapToLong(DebugAiRawSlot::queryRewritePaddedCount)
                .sum());
        out.put("hotTile", hotTileName);
        out.put("historySize", history.size());
        out.put("anomalyTriggered", anomalyTriggered);
        out.put("anomalyReason", anomalyReason);
        out.put("anomalyScore", roundScore(anomalyScore));
        out.put("anomalyTile", hotTileName);
        out.put("anomalyFailureClass", anomalyFailureClass);
        out.putAll(virtualMatrixScorecard(slots, tiles, warnDelta, errorDelta));
        out.put("llmTimeoutCancellation", llmTimeoutCancellationCounts());
        out.put("traceSnapshotRetention", traceSnapshotRetention());
        if (chatUsageLedger != null) {
            out.put("chatUsage", chatUsageLedger.snapshot());
        }
        traceAnomaly(anomalyTriggered, anomalyReason, hotTileName, anomalyFailureClass,
                roundScore(anomalyScore), history.size());
        traceHistoryComparison(historyComparisonComparable, historyComparisonReason,
                currentWindowMs, previousWindowMs, currentSampleLimit, previousSampleLimit,
                currentGeneratedAtMs, previousGeneratedAtMs);
        return out;
    }

    private Map<String, Object> traceSnapshotRetention() {
        TraceSnapshotStore snapshotStore = traceSnapshotStore();
        if (snapshotStore != null) {
            try {
                return snapshotStore.retentionStats();
            } catch (RuntimeException ex) {
                traceSuppressed("debugAiMetrics.traceSnapshotRetention", ex);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storageMode", "unavailable");
        out.put("captureEnabled", false);
        out.put("restartDurable", false);
        out.put("counterScope", "process_lifetime");
        out.put("capacity", 0L);
        out.put("retainedSnapshotCount", 0L);
        out.put("capturedSnapshotCount", 0L);
        out.put("evictedSnapshotCount", 0L);
        return out;
    }

    private Map<String, Object> llmTimeoutCancellationCounts() {
        Map<String, Object> current = TraceStore.getAll();
        if (hasLlmTimeoutCancellationSignal(current)) {
            return llmTimeoutCancellationCounts(List.of(current), "trace_current", 0L, 0L);
        }

        TraceSnapshotStore snapshotStore = traceSnapshotStore();
        if (snapshotStore == null) {
            return llmTimeoutCancellationCounts(List.of(), "none", 0L, 0L);
        }

        List<Map<String, Object>> samples = new ArrayList<>();
        Map<String, Boolean> seenSampleKeys = new LinkedHashMap<>();
        long scanned = 0L;
        long duplicates = 0L;
        try {
            List<Map<String, Object>> summaries = snapshotStore.listSummaries(20);
            if (summaries != null) {
                for (Map<String, Object> summary : summaries) {
                    scanned++;
                    String id = string(summary == null ? null : summary.get("id"));
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot = snapshotStore.get(id);
                    if (snapshot.isEmpty()) {
                        continue;
                    }
                    TraceSnapshotStore.TraceSnapshot captured = snapshot.get();
                    Map<String, Object> trace = captured.trace();
                    if (hasLlmTimeoutCancellationSignal(trace)) {
                        String sampleKey = firstNonBlank(
                                string(trace.get("trace.id")),
                                string(trace.get("traceId")),
                                string(trace.get("requestId")),
                                captured.requestId(),
                                captured.traceId(),
                                id);
                        if (seenSampleKeys.putIfAbsent(sampleKey, Boolean.TRUE) != null) {
                            duplicates++;
                            continue;
                        }
                        samples.add(trace);
                    }
                }
            }
        } catch (RuntimeException ex) {
            traceSuppressed("debugAiMetrics.llmTimeoutCancellationSnapshot", ex);
        }
        return llmTimeoutCancellationCounts(
                samples,
                samples.isEmpty() ? "none" : "trace_snapshots",
                scanned,
                duplicates);
    }

    private static boolean hasLlmTimeoutCancellationSignal(Map<String, Object> trace) {
        return trace != null
                && Boolean.TRUE.equals(trace.get("llm.call.timeout"))
                && trace.containsKey("llm.call.timeout.cancelMayInterruptIfRunning")
                && trace.containsKey("llm.call.timeout.cancelAccepted")
                && trace.containsKey("llm.call.timeout.futureCancelledState");
    }

    private static Map<String, Object> llmTimeoutCancellationCounts(
            List<Map<String, Object>> samples,
            String source,
            long snapshotRowsScanned,
            long duplicateRowsSkipped) {
        long mayInterruptTrue = 0L;
        long acceptedAndFutureCancelled = 0L;
        long acceptedButFutureNotCancelled = 0L;
        long notAcceptedButFutureCancelled = 0L;
        long notAcceptedAndFutureNotCancelled = 0L;
        long workerTerminationNotObserved = 0L;
        long validSamples = 0L;
        long invalidSamples = 0L;
        List<Map<String, Object>> safeSamples = samples == null ? List.of() : samples;

        for (Map<String, Object> sample : safeSamples) {
            if (!(sample.get("llm.call.timeout.cancelMayInterruptIfRunning") instanceof Boolean)
                    || !(sample.get("llm.call.timeout.cancelAccepted") instanceof Boolean)
                    || !(sample.get("llm.call.timeout.futureCancelledState") instanceof Boolean)) {
                invalidSamples++;
                continue;
            }
            validSamples++;
            boolean mayInterrupt = Boolean.TRUE.equals(
                    sample.get("llm.call.timeout.cancelMayInterruptIfRunning"));
            boolean cancelAccepted = Boolean.TRUE.equals(sample.get("llm.call.timeout.cancelAccepted"));
            boolean futureCancelled = Boolean.TRUE.equals(
                    sample.get("llm.call.timeout.futureCancelledState"));
            if (mayInterrupt) {
                mayInterruptTrue++;
            }
            Object workerTerminationEvidence = sample.get("llm.call.timeout.workerTerminationEvidence");
            if (workerTerminationEvidence instanceof String value && "not_observed".equals(value)) {
                workerTerminationNotObserved++;
            }
            if (cancelAccepted && futureCancelled) {
                acceptedAndFutureCancelled++;
            } else if (cancelAccepted) {
                acceptedButFutureNotCancelled++;
            } else if (futureCancelled) {
                notAcceptedButFutureCancelled++;
            } else {
                notAcceptedAndFutureNotCancelled++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", firstNonBlank(source, "none"));
        out.put("snapshotRowsScanned", Math.max(0L, snapshotRowsScanned));
        out.put("duplicateRowsSkipped", Math.max(0L, duplicateRowsSkipped));
        out.put("sampleCount", validSamples);
        out.put("invalidSampleCount", invalidSamples);
        out.put("mayInterruptTrueCount", mayInterruptTrue);
        out.put("workerTerminationNotObservedCount", workerTerminationNotObserved);
        out.put("acceptedAndFutureCancelledCount", acceptedAndFutureCancelled);
        out.put("acceptedButFutureNotCancelledCount", acceptedButFutureNotCancelled);
        out.put("notAcceptedButFutureCancelledCount", notAcceptedButFutureCancelled);
        out.put("notAcceptedAndFutureNotCancelledCount", notAcceptedAndFutureNotCancelled);
        return out;
    }

    private Map<String, Object> virtualMatrixScorecard(List<DebugAiRawSlot> slots,
                                                       List<DebugAiRawTile> tiles,
                                                       long warnDelta,
                                                       long errorDelta) {
        List<DebugAiRawTile> safeTiles = tiles == null || tiles.isEmpty()
                ? List.of(new DebugAiRawTile(0, DebugAiTileType.SPRING_CONTEXT.name(), 0L, 0L, 0L,
                "observed", null, 0L, "idle"))
                : tiles;
        List<Map<String, Object>> chunks = new ArrayList<>();
        long totalEvents = Math.max(1L, slots == null ? 0L : slots.size());
        boolean hasVerification = slots != null && slots.stream()
                .anyMatch(slot -> slot.verificationCommandHash() != null);
        double weightedNumerator = 0.0d;
        double weightDenominator = 0.0d;

        int chunkCount = VIRTUAL_MATRIX_COUNT / VIRTUAL_MATRIX_CHUNK_SIZE;
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            double chunkNumerator = 0.0d;
            double chunkWeightDenominator = 0.0d;
            DebugAiRawTile dominant = safeTiles.get(chunkIndex % safeTiles.size());
            for (int offset = 0; offset < VIRTUAL_MATRIX_CHUNK_SIZE; offset++) {
                int matrixIndex = (chunkIndex * VIRTUAL_MATRIX_CHUNK_SIZE) + offset;
                DebugAiRawTile tile = safeTiles.get(matrixIndex % safeTiles.size());
                double matrixWeight = virtualMatrixWeight(matrixIndex, tile);
                double matrixRisk = virtualMatrixRisk(tile, totalEvents, warnDelta, errorDelta, hasVerification);
                chunkNumerator += matrixRisk * matrixWeight;
                chunkWeightDenominator += matrixWeight;
                if (tile.eventCount() > dominant.eventCount()) {
                    dominant = tile;
                }
            }
            double chunkWeight = chunkWeightDenominator / VIRTUAL_MATRIX_CHUNK_SIZE;
            double chunkRisk = chunkWeightDenominator <= 0.0d ? 0.0d : chunkNumerator / chunkWeightDenominator;
            weightedNumerator += chunkRisk * chunkWeight;
            weightDenominator += chunkWeight;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkIndex", chunkIndex);
            row.put("matrixStart", chunkIndex * VIRTUAL_MATRIX_CHUNK_SIZE);
            row.put("matrixEnd", ((chunkIndex + 1) * VIRTUAL_MATRIX_CHUNK_SIZE) - 1);
            row.put("weight", roundScore(chunkWeight));
            row.put("riskScore", roundScore(chunkRisk));
            row.put("dominantTile", dominant.tileName());
            row.put("dominantFailureClass", dominant.topFailureClass());
            row.put("decision", virtualMatrixDecision(chunkRisk));
            chunks.add(row);
        }

        chunks.sort(Comparator
                .comparingDouble((Map<String, Object> row) -> doubleNumber(row.get("riskScore"))).reversed()
                .thenComparingInt(row -> (int) number(row.get("chunkIndex"))));

        double weightedScore = weightDenominator <= 0.0d ? 0.0d : weightedNumerator / weightDenominator;
        boolean hasObservedFailure = safeTiles.stream()
                .anyMatch(tile -> tile.errorCount() > 0L || tile.warnCount() > 0L);
        String matrixDecision = hasObservedFailure ? "probe_required" : virtualMatrixDecision(weightedScore);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("virtualMatrixCount", VIRTUAL_MATRIX_COUNT);
        out.put("virtualMatrixChunkSize", VIRTUAL_MATRIX_CHUNK_SIZE);
        out.put("virtualMatrixChunkCount", chunkCount);
        out.put("virtualMatrixWeightedScore", roundScore(weightedScore));
        out.put("virtualMatrixScoreRole", "evidence");
        out.put("virtualMatrixScoreTrusted", false);
        out.put("virtualMatrixDecision", matrixDecision);
        out.put("virtualMatrixActionAllowed", false);
        out.put("virtualMatrixChunks", List.copyOf(chunks));
        traceVirtualMatrix(roundScore(weightedScore), matrixDecision, chunkCount);
        return out;
    }

    private static double virtualMatrixWeight(int matrixIndex, DebugAiRawTile tile) {
        double axisWeight = 0.65d + ((matrixIndex % VIRTUAL_MATRIX_CHUNK_SIZE) * 0.035d);
        double severityWeight = 1.0d;
        if (tile != null) {
            if (tile.errorCount() > 0L) {
                severityWeight += 0.45d;
            }
            if (tile.warnCount() > 0L) {
                severityWeight += 0.20d;
            }
            if ("error".equalsIgnoreCase(tile.status())) {
                severityWeight += 0.20d;
            }
        }
        return axisWeight * severityWeight;
    }

    private static double virtualMatrixRisk(DebugAiRawTile tile,
                                            long totalEvents,
                                            long warnDelta,
                                            long errorDelta,
                                            boolean hasVerification) {
        if (tile == null) {
            return 0.0d;
        }
        long tileEvents = Math.max(0L, tile.eventCount());
        double tileSeverity = tileEvents <= 0L ? 0.0d
                : clip01((tile.errorCount() + tile.warnCount() * 0.5d) / (double) tileEvents);
        double volumePressure = clip01(tileEvents / (double) Math.max(1L, totalEvents));
        double trendPressure = clip01((Math.max(0L, errorDelta) + Math.max(0L, warnDelta) * 0.5d) / 4.0d);
        double verificationGap = hasVerification ? 0.0d : 0.25d;
        return weightedPowerMean(
                new double[]{tileSeverity, volumePressure, trendPressure, verificationGap},
                new double[]{0.45d, 0.25d, 0.20d, 0.10d});
    }

    private static String virtualMatrixDecision(double riskScore) {
        if (riskScore >= 0.42d) {
            return "probe_required";
        }
        return "observe";
    }

    private static void traceVirtualMatrix(double weightedScore, String decision, int chunkCount) {
        try {
            TraceStore.put("debug.ai.metrics.virtualMatrix.count", VIRTUAL_MATRIX_COUNT);
            TraceStore.put("debug.ai.metrics.virtualMatrix.chunkSize", VIRTUAL_MATRIX_CHUNK_SIZE);
            TraceStore.put("debug.ai.metrics.virtualMatrix.chunkCount", chunkCount);
            TraceStore.put("debug.ai.metrics.virtualMatrix.weightedScore", weightedScore);
            TraceStore.put("debug.ai.metrics.virtualMatrix.scoreRole", "evidence");
            TraceStore.put("debug.ai.metrics.virtualMatrix.scoreTrusted", false);
            TraceStore.put("debug.ai.metrics.virtualMatrix.decision",
                    SafeRedactor.traceLabelOrFallback(decision, "observe"));
            TraceStore.put("debug.ai.metrics.virtualMatrix.actionAllowed", false);
        } catch (Throwable ignore) {
            traceSuppressed("debugAiMetrics.virtualMatrixTrace", ignore);
        }
    }

    private static double anomalyScore(long total,
                                       long warnEvents,
                                       long errorEvents,
                                       long warnDelta,
                                       long errorDelta,
                                       DebugAiRawTile hotTile) {
        double severityPressure = clip01((errorEvents + warnEvents * 0.5d) / Math.max(1d, total));
        double deltaPressure = clip01((Math.max(0L, errorDelta) + Math.max(0L, warnDelta) * 0.5d) / 3.0d);
        double hotTilePressure = 0.0d;
        if (hotTile != null && hotTile.eventCount() > 0L) {
            hotTilePressure = clip01((hotTile.errorCount() + hotTile.warnCount() * 0.5d)
                    / (double) hotTile.eventCount());
        }
        return weightedPowerMean(
                new double[]{severityPressure, deltaPressure, hotTilePressure},
                new double[]{0.45d, 0.35d, 0.20d});
    }

    private static String anomalyReason(DebugAiMetricSnapshot previous,
                                        long warnDelta,
                                        long errorDelta,
                                        DebugAiRawTile hotTile,
                                        double anomalyScore) {
        if (previous == null) {
            return "observe";
        }
        if (errorDelta >= ANOMALY_ERROR_DELTA_THRESHOLD) {
            return "error_delta_threshold";
        }
        if (warnDelta >= ANOMALY_WARN_DELTA_THRESHOLD) {
            return "warn_delta_threshold";
        }
        if (hotTile != null && hotTile.errorCount() >= 3L) {
            return "hot_tile_spike";
        }
        if (anomalyScore >= ANOMALY_SCORE_THRESHOLD) {
            return "weighted_signal_pressure";
        }
        return "observe";
    }

    private static double weightedPowerMean(double[] values, double[] weights) {
        double numerator = 0.0d;
        double denominator = 0.0d;
        int limit = Math.min(values.length, weights.length);
        for (int i = 0; i < limit; i++) {
            double weight = Math.max(0.0d, weights[i]);
            numerator += weight * Math.pow(clip01(values[i]), ANOMALY_POWER);
            denominator += weight;
        }
        if (denominator <= 0.0d) {
            return 0.0d;
        }
        return clip01(Math.pow(numerator / denominator, 1.0d / ANOMALY_POWER));
    }

    private static double clip01(double value) {
        if (Double.isNaN(value) || value <= 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, value);
    }

    private static double roundScore(double value) {
        return Math.round(clip01(value) * 1000.0d) / 1000.0d;
    }

    private static void traceAnomaly(boolean triggered,
                                     String reason,
                                     String tile,
                                     String failureClass,
                                     double score,
                                     int historySize) {
        try {
            TraceStore.put("debug.ai.metrics.anomaly.triggered", triggered);
            TraceStore.put("debug.ai.metrics.anomaly.reason",
                    SafeRedactor.traceLabelOrFallback(reason, "observe"));
            TraceStore.put("debug.ai.metrics.anomaly.tile",
                    SafeRedactor.traceLabelOrFallback(tile, "unknown"));
            TraceStore.put("debug.ai.metrics.anomaly.score", score);
            TraceStore.put("debug.ai.metrics.anomaly.history.size", Math.max(0, historySize));
            if (failureClass != null) {
                TraceStore.put("debug.ai.metrics.anomaly.failureClass",
                        SafeRedactor.traceLabelOrFallback(failureClass, "unknown"));
            }
        } catch (Throwable ignore) {
            traceSuppressed("debugAiMetrics.anomalyTrace", ignore);
        }
    }

    private static void traceHistoryComparison(boolean comparable,
                                               String reason,
                                               long currentWindowMs,
                                               long previousWindowMs,
                                               int currentSampleLimit,
                                               int previousSampleLimit,
                                               long currentGeneratedAtMs,
                                               long previousGeneratedAtMs) {
        try {
            TraceStore.put("debug.ai.metrics.anomaly.historyComparisonComparable", comparable);
            TraceStore.put("debug.ai.metrics.anomaly.historyComparisonReason",
                    SafeRedactor.traceLabelOrFallback(reason, "baseline_missing"));
            TraceStore.put("debug.ai.metrics.anomaly.currentWindowMs", Math.max(0L, currentWindowMs));
            TraceStore.put("debug.ai.metrics.anomaly.previousWindowMs", Math.max(0L, previousWindowMs));
            TraceStore.put("debug.ai.metrics.anomaly.currentSampleLimit", Math.max(0, currentSampleLimit));
            TraceStore.put("debug.ai.metrics.anomaly.previousSampleLimit", Math.max(0, previousSampleLimit));
            TraceStore.put("debug.ai.metrics.anomaly.currentGeneratedAtMs", Math.max(0L, currentGeneratedAtMs));
            TraceStore.put("debug.ai.metrics.anomaly.previousGeneratedAtMs", Math.max(0L, previousGeneratedAtMs));
        } catch (Throwable ignore) {
            traceSuppressed("debugAiMetrics.historyComparisonTrace", ignore);
        }
    }

    private static String trendLabel(DebugAiMetricSnapshot previous, long delta) {
        if (previous == null) {
            return "none";
        }
        if (delta > 0L) {
            return "up";
        }
        if (delta < 0L) {
            return "down";
        }
        return "flat";
    }

    private List<String> recommendations(List<DebugAiRawTile> tiles,
                                         Map<String, Long> failureClassCounts,
                                         List<DebugAiRawSlot> slots) {
        List<String> out = new ArrayList<>();
        if (slots.isEmpty()) {
            out.add("observe_debug_events");
            return out;
        }
        tiles.stream()
                .filter(t -> t.errorCount() > 0)
                .findFirst()
                .ifPresent(t -> out.add("review_error_tile:" + t.tileName()));
        if (failureClassCounts.containsKey("timeout") || failureClassCounts.containsKey("rate-limit")) {
            out.add("inspect_provider_failsoft_ladder");
        }
        if (slots.stream().noneMatch(s -> s.toolId() != null)) {
            out.add("record_debug_tool_usage");
        }
        if (out.isEmpty()) {
            out.add("continue_observing");
        }
        return List.copyOf(out);
    }

    private static Map<String, Long> counts(List<DebugAiRawSlot> slots, Function<DebugAiRawSlot, String> extractor) {
        Map<String, Long> out = slots.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return sortedCounts(out);
    }

    private static Map<String, Long> sortedCounts(Map<String, Long> in) {
        return in.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private static String topValue(List<DebugAiRawSlot> slots, Function<DebugAiRawSlot, String> extractor) {
        return counts(slots, extractor).entrySet().stream()
                .findFirst()
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static String layerFromProbe(DebugProbeType probe) {
        if (probe == null) {
            return null;
        }
        return switch (probe) {
            case HTTP -> "http";
            case CONTEXT_PROPAGATION -> "context.propagation";
            case GUARD_CONTEXT -> "guard.context";
            case RULE_BREAK -> "guard.ruleBreak";
            case FAULT_MASK -> "failsoft.faultMask";
            case QUERY_TRANSFORMER -> "query.transformer";
            case NIGHTMARE_BREAKER -> "breaker";
            case EXECUTOR -> "executor";
            case REACTOR -> "reactor";
            case AUTOLEARN -> "learning.autolearn";
            case WEB_SEARCH, NAVER_SEARCH -> "web.search";
            case EMBEDDING -> "vector.retrieval";
            case MODEL_GUARD -> "llm.modelGuard";
            case PROMPT -> "evidence.output";
            case ORCHESTRATION -> "agent.tool";
            case EXTERNAL_EVIDENCE -> "external.evidence";
            case TRACE_MEMORY -> "memory.postprocess";
            case GENERIC -> "spring.context";
            default -> probe.name().toLowerCase(Locale.ROOT).replace('_', '.');
        };
    }

    private static String queryTransformerFailureClass(Map<String, Object> data, DebugProbeType probe) {
        if (probe != DebugProbeType.QUERY_TRANSFORMER || data == null) {
            return null;
        }
        String stage = label(data.get("stage"));
        if (stage == null || stage.isBlank()) {
            return null;
        }
        if ("query_rewrite".equals(stage)) {
            long superCount = firstLong(data, "superCount", "branchCount");
            return superCount > 0L ? "query_rewrite.super_tokens" : "query_rewrite";
        }
        return stage;
    }

    private static String safeId(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            String label = label(data.get(key));
            if (label != null && label.matches("[A-Za-z0-9_.:-]{1,120}")) {
                return label;
            }
        }
        return null;
    }

    private static String label(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            Object hash = firstPresent(map.get("hash12"), map.get("hash"), map.get("fingerprintHash"));
            if (hash != null) {
                String h = String.valueOf(hash).trim();
                return h.startsWith("hash:") ? h : "hash:" + h;
            }
            Object host = map.get("host");
            if (host != null) {
                return SafeRedactor.traceLabelOrFallback(host, "host");
            }
            return map.containsKey("present") ? "present" : "map";
        }
        return SafeRedactor.traceLabelOrFallback(String.valueOf(value), "present");
    }

    private static long firstLong(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            Long parsed = longValue(value);
            if (parsed != null) {
                return Math.max(0L, parsed);
            }
        }
        return 0L;
    }

    private static long branchTitleHashCount(Map<String, Object> data) {
        long explicit = firstLong(data, "branchTitleHashCount");
        if (explicit > 0L) {
            return explicit;
        }
        Object value = data.get("branchTitleHashes");
        if (value instanceof Iterable<?> iterable) {
            long count = 0L;
            for (Object item : iterable) {
                if (isHash12(item)) {
                    count++;
                }
            }
            return count;
        }
        return isHash12(value) ? 1L : 0L;
    }

    private static boolean isHash12(Object value) {
        if (value == null) {
            return false;
        }
        return String.valueOf(value).trim().matches("[a-f0-9]{12}");
    }

    private static Long longValue(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignore) {
                traceSuppressed("debugAiMetrics.longValue", ignore);
                return null;
            }
        }
        return null;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("debug.ai.metrics.suppressed.stage", safeStage);
        TraceStore.put("debug.ai.metrics.suppressed.errorType", safeErrorType);
        TraceStore.put("debug.ai.metrics.suppressed." + safeStage, true);
        TraceStore.put("debug.ai.metrics.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : failure.getClass().getSimpleName();
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private static long boundedSignalSize(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Math.max(0L, Math.min(12L, map.size()));
        }
        if (value instanceof Iterable<?> iterable) {
            long count = 0L;
            for (Object ignored : iterable) {
                if (++count >= 12L) {
                    return 12L;
                }
            }
            return count;
        }
        return value == null ? 0L : 1L;
    }

    private static String riskRewriteSeverity(String band, String requeryRequired, String requeryConfirmed) {
        if ("HIGH".equalsIgnoreCase(band)) {
            return "WARN";
        }
        if ("true".equalsIgnoreCase(requeryRequired) && !"true".equalsIgnoreCase(requeryConfirmed)) {
            return "WARN";
        }
        return "INFO";
    }

    private static double doubleNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0d;
    }

    private static String hash(String value) {
        String h = SafeRedactor.hashValue(value);
        return h == null || h.isBlank() ? null : h;
    }

    private static String hashAlready(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith("hash:") ? value : hash(value);
    }

    private static String safeMessage(String value, int max) {
        return value == null ? null : SafeRedactor.safeMessage(value, max);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Object firstPresent(Object first, Object second, Object third) {
        return first != null ? first : second != null ? second : third;
    }

    private static Object firstPresent(Object first, Object second) {
        return first != null ? first : second;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampWindow(long value) {
        if (value <= 0L) {
            return DEFAULT_WINDOW_MS;
        }
        return Math.max(1_000L, Math.min(MAX_WINDOW_MS, value));
    }
}
