package com.example.lms.api;

import com.example.lms.trace.TraceSnapshotStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceMemoryFingerprintProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostics endpoints for in-memory trace snapshots.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/diagnostics/trace/snapshots?limit=50</li>
 *   <li>GET /api/diagnostics/trace/snapshots/{id}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/diagnostics/trace")
public class TraceSnapshotsDiagnosticsController {

    private static final Object MISSING_TRACE_VALUE = new Object();
    private static final int MAX_CHECKPOINT_HISTORY_ITEMS = 16;

    private final ObjectProvider<TraceSnapshotStore> storeProvider;
    private final ObjectProvider<TraceMemoryFingerprintProbe> traceMemoryProbeProvider;

    public TraceSnapshotsDiagnosticsController(ObjectProvider<TraceSnapshotStore> storeProvider) {
        this(storeProvider, noTraceMemoryProbe());
    }

    @Autowired
    public TraceSnapshotsDiagnosticsController(ObjectProvider<TraceSnapshotStore> storeProvider,
                                               ObjectProvider<TraceMemoryFingerprintProbe> traceMemoryProbeProvider) {
        this.storeProvider = storeProvider;
        this.traceMemoryProbeProvider = traceMemoryProbeProvider;
    }

    @GetMapping("/snapshots")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", Instant.now().toString());
        out.put("available", store != null);
        out.put("snapshots", store == null ? java.util.List.of() : store.listSummaries(limit));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/snapshots/{id}")
    public ResponseEntity<?> get(@PathVariable("id") String id) {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "TraceSnapshotStore not available"));
        }
        return store.get(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "snapshot not found")));
    }

    @GetMapping(value = "/snapshots/latest-harmony/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> latestHarmonyHtml() {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(simpleHtml("TraceSnapshotStore not available", Map.of("reason", "trace_snapshot_store_missing")));
        }
        for (Map<String, Object> summary : store.listSummaries(50)) {
            String id = summary == null ? "" : String.valueOf(summary.getOrDefault("id", ""));
            if (!hasText(id)) {
                continue;
            }
            java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
            if (snapshot.isPresent() && hasHarmonyTrace(snapshot.get().trace())) {
                return renderHarmonySnapshotHtml(snapshot.get());
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .body(simpleHtml("latest harmony snapshot not found", Map.of("reason", "harmony_trace_missing")));
    }

    @GetMapping(value = "/snapshots/latest-trace-memory/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> latestTraceMemoryHtml() {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(simpleHtml("TraceSnapshotStore not available", Map.of("reason", "trace_snapshot_store_missing")));
        }
        for (Map<String, Object> summary : store.listSummaries(50)) {
            String id = summary == null ? "" : String.valueOf(summary.getOrDefault("id", ""));
            if (!hasText(id)) {
                continue;
            }
            java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
            if (snapshot.isPresent() && hasTraceMemoryTrace(snapshot.get().trace())) {
                return renderTraceMemorySnapshotHtml(snapshot.get());
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .body(simpleHtml("latest trace-memory snapshot not found", Map.of("reason", "trace_memory_trace_missing")));
    }

    @GetMapping(value = "/snapshots/latest-trace-memory/checkpoints", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> latestTraceMemoryCheckpoints() {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(checkpointJsonError("trace_snapshot_store_missing"));
        }
        for (Map<String, Object> summary : store.listSummaries(50)) {
            String id = summary == null ? "" : String.valueOf(summary.getOrDefault("id", ""));
            if (!hasText(id)) {
                continue;
            }
            java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
            if (snapshot.isPresent() && hasTraceMemoryTrace(snapshot.get().trace())) {
                return ResponseEntity.ok(checkpointHistoryPayload(snapshot.get()));
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(checkpointJsonError("trace_memory_trace_missing"));
    }

    @RequestMapping(value = "/memory/self-probe", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> traceMemorySelfProbe(
            @RequestParam(value = "scenario", required = false, defaultValue = "") String scenario
    ) {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        TraceMemoryFingerprintProbe probe = traceMemoryProbeProvider.getIfAvailable();
        if (store == null || probe == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", "WARN",
                    "reason", store == null ? "trace_snapshot_store_missing" : "trace_memory_probe_missing"));
        }
        String safeScenario = traceMemorySelfProbeScenario(scenario);
        Map<String, Object> previousSyntheticTrace = installSyntheticTraceValues(safeScenario);
        try {
        Map<String, Object> trace = TraceStore.getAll();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("source", "trace_memory_self_probe");
        raw.put("phase", "trace_memory_self_probe");
        raw.put("scenario", safeScenario);
        raw.put("traceStoreSize", trace.size());
        raw.put("traceStore", trace);
        if ("synthetic_context_contamination".equals(safeScenario)) {
            raw.put("syntheticSignal", "history_context_contamination");
        }
        TraceMemoryFingerprintProbe.Checkpoint rawCheckpoint =
                probe.checkpoint("raw_snapshot", "TraceSnapshotsDiagnosticsController.self_probe", raw);
        TraceMemoryFingerprintProbe.Checkpoint loadCheckpoint =
                probe.checkpoint("load", "TraceSnapshotsDiagnosticsController.self_probe", raw);
        String recoveryAction = loadCheckpoint.recoveryAction() == null || loadCheckpoint.recoveryAction().isBlank()
                ? "continue_trace_memory_checkpointing"
                : loadCheckpoint.recoveryAction();
        boolean quarantine = truthyTraceValue(TraceStore.get("traceMemory.recovery.quarantine"));
        String risk = safeTraceValue(TraceStore.get("traceMemory.errorBreak.risk"), "none");
        String recoveryRoute = safeTraceValue(TraceStore.get("traceMemory.recovery.route"),
                "continue_trace_memory_checkpointing");
        String recoveryRouteDecision = safeTraceValue(TraceStore.get("traceMemory.recovery.routeDecision"),
                "continue_trace_memory_checkpointing");
        long recoveryPolicyMaxRounds = toLongTraceValue("traceMemory.recovery.policy.maxRounds",
                TraceStore.get("traceMemory.recovery.policy.maxRounds"));
        long recoveryPolicyMinCitations = toLongTraceValue("traceMemory.recovery.policy.minCitations",
                TraceStore.get("traceMemory.recovery.policy.minCitations"));
        boolean cfvmOffered = truthyTraceValue(TraceStore.get("traceMemory.cfvm.offered"));
        long cfvmPatternId = toLongTraceValue("traceMemory.cfvm.patternId",
                TraceStore.get("traceMemory.cfvm.patternId"));
        boolean suspectPayloadIsolated = truthyTraceValue(TraceStore.get("traceMemory.suspectPayload.isolated"));
        String snapshotId = store.captureCurrent(
                "trace_memory_self_probe",
                "GET",
                "/api/diagnostics/trace/memory/self-probe" + scenarioPathSuffix(safeScenario),
                200,
                null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", snapshotId == null || snapshotId.isBlank() ? "WARN" : "OK");
        out.put("reason", snapshotId == null || snapshotId.isBlank()
                ? "trace_memory_snapshot_not_captured"
                : "trace_memory_self_probe_captured");
        out.put("checkpointStage", loadCheckpoint.stage());
        out.put("checkpointSource", loadCheckpoint.source());
        out.put("fingerprintHash", loadCheckpoint.fingerprint());
        out.put("previousFingerprintHash", loadCheckpoint.previousFingerprint());
        out.put("changedCount", loadCheckpoint.changedCount());
        out.put("droppedBreadcrumbCount", loadCheckpoint.droppedBreadcrumbCount());
        out.put("scenario", safeScenario);
        out.put("triggered", loadCheckpoint.triggered());
        out.put("triggerReason", loadCheckpoint.triggerReason());
        out.put("recoveryAction", recoveryAction);
        out.put("recoveryRoute", recoveryRoute);
        out.put("recoveryRouteDecision", recoveryRouteDecision);
        out.put("recoveryPolicyMaxRounds", recoveryPolicyMaxRounds);
        out.put("recoveryPolicyMinCitations", recoveryPolicyMinCitations);
        out.put("quarantine", quarantine);
        out.put("suspectPayloadIsolated", suspectPayloadIsolated);
        out.put("risk", risk);
        out.put("cfvmOffered", cfvmOffered);
        out.put("cfvmPatternId", cfvmPatternId);
        out.put("rawSnapshotFingerprintHash", rawCheckpoint.fingerprint());
        out.put("snapshotIdHash", SafeRedactor.hashValue(snapshotId));
        out.put("snapshotIdLength", snapshotId == null ? 0 : snapshotId.length());
        out.put("latestTraceRoute", "/api/diagnostics/trace/snapshots/latest-trace-memory/html");
        return ResponseEntity.ok(out);
        } finally {
            restoreSyntheticTraceValues(previousSyntheticTrace);
        }
    }

    /**
     * HTML view for a snapshot (best-effort).
     *
     * <p>Endpoint:</p>
     * <ul>
     *   <li>GET /api/diagnostics/trace/snapshots/{id}/html</li>
     * </ul>
     */
    @GetMapping(value = "/snapshots/{id}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getHtml(@PathVariable("id") String id) {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(simpleHtml("TraceSnapshotStore not available", snapshotIdMeta(id)));
        }
        return store.get(id)
                .map(TraceSnapshotsDiagnosticsController::renderSnapshotHtml)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_HTML)
                        .body(simpleHtml("snapshot not found", snapshotIdMeta(id))));
    }

    private static ResponseEntity<String> renderSnapshotHtml(TraceSnapshotStore.TraceSnapshot s) {
        String html = s.html();
        if (html == null || html.isBlank()) {
            java.util.LinkedHashMap<String, String> kv = snapshotFallbackMeta(s);
            html = simpleHtml("Trace snapshot (no stored HTML)", kv);
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private static ResponseEntity<String> renderTraceMemorySnapshotHtml(TraceSnapshotStore.TraceSnapshot s) {
        String html = simpleHtml("Trace memory snapshot", traceMemoryFallbackMeta(s));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private static ResponseEntity<String> renderHarmonySnapshotHtml(TraceSnapshotStore.TraceSnapshot s) {
        String html = simpleHtml("Chat harmony snapshot", harmonyFallbackMeta(s));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private static boolean hasHarmonyTrace(Map<String, Object> trace) {
        return trace != null
                && (trace.containsKey("chat.harmony.postprocess.decision")
                || trace.containsKey("chat.harmony.postprocess.agentVisible"));
    }

    private static boolean hasTraceMemoryTrace(Map<String, Object> trace) {
        return trace != null
                && (trace.containsKey("traceMemory.fingerprint.current")
                || trace.containsKey("traceMemory.triggered")
                || trace.containsKey("traceMemory.checkpoint.stage")
                || trace.containsKey("traceMemory.virtualCheckpoint.latestKey"));
    }

    private static LinkedHashMap<String, String> snapshotFallbackMeta(TraceSnapshotStore.TraceSnapshot s) {
        LinkedHashMap<String, String> kv = new LinkedHashMap<>();
        putHashMeta(kv, "id", s == null ? null : s.id());
        kv.put("ts", safeValue(s == null ? null : s.tsIso()));
        putHashMeta(kv, "sid", s == null ? null : s.sid());
        putHashMeta(kv, "traceId", s == null ? null : s.traceId());
        putHashMeta(kv, "requestId", s == null ? null : s.requestId());
        kv.put("reason", safeText(s == null ? null : s.reason(), 180));
        kv.put("method", safeText(s == null ? null : s.method(), 40));
        putHashMeta(kv, "path", s == null ? null : s.path());
        kv.put("status", safeValue(s == null ? null : s.status()));
        putHashMeta(kv, "error", s == null ? null : s.error());
        kv.put("traceEntryCount", safeValue(s == null ? null : s.traceEntryCount()));
        kv.put("hasMlBreadcrumbs", safeValue(s == null ? null : s.hasMlBreadcrumbs()));
        kv.put("hasHtml", safeValue(s != null && s.html() != null));
        return kv;
    }

    private static LinkedHashMap<String, String> traceMemoryFallbackMeta(TraceSnapshotStore.TraceSnapshot s) {
        LinkedHashMap<String, String> kv = snapshotFallbackMeta(s);
        Map<String, Object> trace = s == null || s.trace() == null ? Map.of() : s.trace();
        putTraceMeta(kv, trace, "traceMemory.checkpoint.stage", 80);
        putTraceMeta(kv, trace, "traceMemory.checkpoint.phase", 80);
        putTraceMeta(kv, trace, "traceMemory.checkpoint.historySize", 20);
        putTraceMeta(kv, trace, "traceMemory.virtualCheckpoint.latestKey", 120);
        putTraceMeta(kv, trace, "traceMemory.virtualCheckpoint.latestStage", 80);
        putTraceMeta(kv, trace, "traceMemory.virtualCheckpoint.latestPhase", 80);
        putTraceMetaDefault(kv, trace, "traceMemory.triggered", 20, "false");
        putTraceMetaDefault(kv, trace, "traceMemory.trigger.reason", 120, "none");
        putTraceMetaDefault(kv, trace, "traceMemory.recovery.failureClass", 120, "none");
        putTraceMetaDefault(kv, trace, "traceMemory.recovery.action", 120, "none");
        putTraceMetaDefault(kv, trace, "traceMemory.recovery.route", 120, "none");
        putTraceMetaDefault(kv, trace, "traceMemory.recovery.routeDecision", 160, "none");
        putTraceMetaDefault(kv, trace, "traceMemory.recovery.policy.maxRounds", 20, "0");
        putTraceMetaDefault(kv, trace, "traceMemory.recovery.policy.minCitations", 20, "0");
        putTraceMetaDefault(kv, trace, "traceMemory.recovery.quarantine", 20, "false");
        putTraceMetaDefault(kv, trace, "traceMemory.errorBreak.risk", 40, "none");
        putTraceMetaDefault(kv, trace, "traceMemory.suspectPayload.isolated", 20, "false");
        putTraceMetaDefault(kv, trace, "traceMemory.suspectPayload.route", 120, "none");
        putTraceMetaDefault(kv, trace, "traceMemory.cfvm.offered", 20, "false");
        putTraceMetaDefault(kv, trace, "traceMemory.cfvm.patternId", 40, "0");
        putTraceMeta(kv, trace, "traceMemory.rawSnapshot.supabaseShadowCount", 20);
        putTraceMeta(kv, trace, "traceMemory.delta.changed", 20);
        putTraceMeta(kv, trace, "traceMemory.delta.changedCount", 20);
        putTraceMeta(kv, trace, "traceMemory.delta.droppedBreadcrumbCount", 20);
        return kv;
    }

    private static LinkedHashMap<String, String> harmonyFallbackMeta(TraceSnapshotStore.TraceSnapshot s) {
        LinkedHashMap<String, String> kv = snapshotFallbackMeta(s);
        Map<String, Object> trace = s == null || s.trace() == null ? Map.of() : s.trace();
        putTraceMetaDefault(kv, trace, "chat.harmony.postprocess.decision", 120, "unknown");
        putTraceMetaDefault(kv, trace, "chat.harmony.postprocess.reason", 120, "unknown");
        putTraceMetaDefault(kv, trace, "chat.harmony.postprocess.degraded", 20, "false");
        putTraceMetaDefault(kv, trace, "chat.harmony.postprocess.agentVisible", 20, "false");
        putTraceMetaDefault(kv, trace, "chat.harmony.postprocess.weightedScore", 40, "0");
        putTraceMetaDefault(kv, trace, "chat.harmony.postprocess.evidenceCount", 20, "0");
        putTraceMetaDefault(kv, trace, "chat.harmony.postprocess.answerLength", 20, "0");
        putTraceMetaDefault(kv, trace, "chat.harmony.postprocess.sentenceCount", 20, "0");
        putTraceMetaDefault(kv, trace, "debug.ai.metrics.nextAction", 120, "continue_observing_chat_harmony");
        putTraceMetaDefault(kv, trace, "debug.ai.metrics.nextReason", 120, "chat_harmony_observed");
        putTraceMeta(kv, trace, "chat.harmony.postprocess.shapeApplied", 20);
        putTraceMeta(kv, trace, "chat.harmony.postprocess.shapeReason", 120);
        putTraceMeta(kv, trace, "chat.harmony.postprocess.shapeFinalSentenceCount", 20);
        return kv;
    }

    private static Map<String, Object> checkpointHistoryPayload(TraceSnapshotStore.TraceSnapshot s) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> trace = s == null || s.trace() == null ? Map.of() : s.trace();
        Object historyValue = trace.get("traceMemory.checkpoint.history");
        List<Map<String, Object>> checkpointHistory = safeCheckpointHistory(historyValue);
        int rawHistoryCount = checkpointHistoryRawCount(historyValue);

        out.put("status", "OK");
        out.put("source", "traceSnapshotStore");
        out.put("ts", safeText(s == null ? null : s.tsIso(), 80));
        out.put("reason", safeText(s == null ? null : s.reason(), 120));
        out.put("method", safeText(s == null ? null : s.method(), 40));
        putHashObject(out, "snapshotId", s == null ? null : s.id());
        putHashObject(out, "path", s == null ? null : s.path());
        putHashObject(out, "requestId", s == null ? null : s.requestId());
        putHashObject(out, "sessionId", s == null ? null : s.sessionId());
        out.put("statusCode", s == null || s.status() == null ? 0 : s.status());
        out.put("traceEntryCount", s == null ? 0 : s.traceEntryCount());
        out.put("checkpointStage", safeTraceValue(trace.get("traceMemory.checkpoint.stage"), "unknown"));
        out.put("checkpointPhase", safeTraceValue(trace.get("traceMemory.checkpoint.phase"), "unknown"));
        out.put("checkpointHistorySize", toLongTraceValue("traceMemory.checkpoint.historySize",
                trace.get("traceMemory.checkpoint.historySize")));
        out.put("checkpointHistoryCount", checkpointHistory.size());
        out.put("checkpointHistoryTruncated", rawHistoryCount > checkpointHistory.size());
        out.put("checkpointHistory", checkpointHistory);
        out.put("virtualCheckpoint", traceMemoryVirtualCheckpointPayload(trace));
        out.put("delta", traceMemoryDeltaPayload(trace));
        out.put("rawSnapshot", traceMemoryRawSnapshotPayload(trace));
        out.put("recovery", traceMemoryRecoveryPayload(trace));
        out.put("cfvm", traceMemoryCfvmPayload(trace));
        out.put("links", Map.of(
                "latestTraceHtml", "/api/diagnostics/trace/snapshots/latest-trace-memory/html"));
        return out;
    }

    private static Map<String, Object> checkpointJsonError(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "WARN");
        out.put("reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        out.put("links", Map.of(
                "latestTraceHtml", "/api/diagnostics/trace/snapshots/latest-trace-memory/html"));
        return out;
    }

    private static Map<String, Object> traceMemoryDeltaPayload(Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", truthyTraceValue(trace.get("traceMemory.delta.changed")));
        out.put("addedCount", toLongTraceValue("traceMemory.delta.addedCount",
                trace.get("traceMemory.delta.addedCount")));
        out.put("removedCount", toLongTraceValue("traceMemory.delta.removedCount",
                trace.get("traceMemory.delta.removedCount")));
        out.put("changedCount", toLongTraceValue("traceMemory.delta.changedCount",
                trace.get("traceMemory.delta.changedCount")));
        out.put("droppedBreadcrumbCount", toLongTraceValue("traceMemory.delta.droppedBreadcrumbCount",
                trace.get("traceMemory.delta.droppedBreadcrumbCount")));
        return out;
    }

    private static Map<String, Object> traceMemoryVirtualCheckpointPayload(Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("latestKey", safeTraceValue(trace.get("traceMemory.virtualCheckpoint.latestKey"), "unknown"));
        out.put("latestStage", safeTraceValue(trace.get("traceMemory.virtualCheckpoint.latestStage"), "unknown"));
        out.put("latestPhase", safeTraceValue(trace.get("traceMemory.virtualCheckpoint.latestPhase"), "unknown"));
        return out;
    }

    private static Map<String, Object> traceMemoryRawSnapshotPayload(Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memoryCount", toLongTraceValue("traceMemory.rawSnapshot.memoryCount",
                trace.get("traceMemory.rawSnapshot.memoryCount")));
        out.put("traceCount", toLongTraceValue("traceMemory.rawSnapshot.traceCount",
                trace.get("traceMemory.rawSnapshot.traceCount")));
        out.put("debugCount", toLongTraceValue("traceMemory.rawSnapshot.debugCount",
                trace.get("traceMemory.rawSnapshot.debugCount")));
        out.put("cfvmCount", toLongTraceValue("traceMemory.rawSnapshot.cfvmCount",
                trace.get("traceMemory.rawSnapshot.cfvmCount")));
        out.put("supabaseShadowCount", toLongTraceValue("traceMemory.rawSnapshot.supabaseShadowCount",
                trace.get("traceMemory.rawSnapshot.supabaseShadowCount")));
        return out;
    }

    private static Map<String, Object> traceMemoryRecoveryPayload(Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("triggered", truthyTraceValue(trace.get("traceMemory.triggered")));
        out.put("triggerReason", safeTraceValue(trace.get("traceMemory.trigger.reason"), "none"));
        out.put("failureClass", safeTraceValue(trace.get("traceMemory.recovery.failureClass"), "none"));
        out.put("action", safeTraceValue(trace.get("traceMemory.recovery.action"), "none"));
        out.put("route", safeTraceValue(trace.get("traceMemory.recovery.route"), "none"));
        out.put("routeDecision", safeTraceValue(trace.get("traceMemory.recovery.routeDecision"), "none"));
        out.put("policyMaxRounds", toLongTraceValue("traceMemory.recovery.policy.maxRounds",
                trace.get("traceMemory.recovery.policy.maxRounds")));
        out.put("policyMinCitations", toLongTraceValue("traceMemory.recovery.policy.minCitations",
                trace.get("traceMemory.recovery.policy.minCitations")));
        out.put("quarantine", truthyTraceValue(trace.get("traceMemory.recovery.quarantine")));
        out.put("failSoft", truthyTraceValue(trace.get("traceMemory.recovery.failSoft")));
        out.put("retry", truthyTraceValue(trace.get("traceMemory.recovery.retry")));
        out.put("risk", safeTraceValue(trace.get("traceMemory.errorBreak.risk"), "none"));
        out.put("suspectPayloadIsolated", truthyTraceValue(trace.get("traceMemory.suspectPayload.isolated")));
        out.put("suspectPayloadRoute", safeTraceValue(trace.get("traceMemory.suspectPayload.route"), "none"));
        return out;
    }

    private static Map<String, Object> traceMemoryCfvmPayload(Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offered", truthyTraceValue(trace.get("traceMemory.cfvm.offered")));
        out.put("patternId", toLongTraceValue("traceMemory.cfvm.patternId",
                trace.get("traceMemory.cfvm.patternId")));
        out.put("skippedReason", safeTraceValue(trace.get("traceMemory.cfvm.skippedReason"), "none"));
        return out;
    }

    private static List<Map<String, Object>> safeCheckpointHistory(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof Collection<?> entries) {
            for (Object entry : entries) {
                addCheckpointHistoryItem(out, entry);
            }
        } else if (value != null && value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                addCheckpointHistoryItem(out, java.lang.reflect.Array.get(value, i));
            }
        } else if (value != null) {
            addCheckpointHistoryItem(out, value);
        }
        return out;
    }

    private static void addCheckpointHistoryItem(List<Map<String, Object>> out, Object entry) {
        if (out.size() >= MAX_CHECKPOINT_HISTORY_ITEMS) {
            return;
        }
        out.add(safeCheckpointHistoryItem(entry));
    }

    private static int checkpointHistoryRawCount(Object value) {
        if (value instanceof Collection<?> entries) {
            return entries.size();
        }
        if (value != null && value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value);
        }
        return value == null ? 0 : 1;
    }

    private static Map<String, Object> safeCheckpointHistoryItem(Object entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(entry instanceof Map<?, ?> raw)) {
            String text = safeValue(entry);
            out.put("itemHash", SafeRedactor.hashValue(text));
            out.put("itemLength", text.length());
            return out;
        }
        putLongItem(out, raw, "index");
        putLabelItem(out, raw, "stage", "unknown");
        putLabelItem(out, raw, "source", "unknown");
        putLabelItem(out, raw, "phase", "unknown");
        putFingerprintItem(out, raw, "fingerprint", "fingerprintHash");
        putFingerprintItem(out, raw, "previousFingerprint", "previousFingerprintHash");
        putLongItem(out, raw, "deltaAddedCount");
        putLongItem(out, raw, "deltaRemovedCount");
        putLongItem(out, raw, "deltaChangedCount");
        putLongItem(out, raw, "droppedBreadcrumbCount");
        putLongItem(out, raw, "memoryCount");
        putLongItem(out, raw, "traceCount");
        putLongItem(out, raw, "debugCount");
        putLongItem(out, raw, "cfvmCount");
        putLongItem(out, raw, "supabaseShadowCount");
        out.put("triggered", truthyTraceValue(raw.get("triggered")));
        putLabelItem(out, raw, "triggerReason", "");
        return out;
    }

    private static void putHashObject(Map<String, Object> out, String prefix, String raw) {
        String value = raw == null ? "" : raw;
        String hash = value.startsWith("hash:") ? value : SafeRedactor.hashValue(value);
        out.put(prefix + "Hash", hash == null ? "" : hash);
        out.put(prefix + "Length", value.length());
    }

    private static void putLongItem(Map<String, Object> out, Map<?, ?> raw, String key) {
        out.put(key, toLongTraceValue(key, raw.get(key)));
    }

    private static void putLabelItem(Map<String, Object> out, Map<?, ?> raw, String key, String fallback) {
        out.put(key, SafeRedactor.traceLabelOrFallback(raw.get(key), fallback));
    }

    private static void putFingerprintItem(Map<String, Object> out, Map<?, ?> raw, String sourceKey, String outKey) {
        out.put(outKey, safeFingerprint(raw.get(sourceKey)));
    }

    private static String safeFingerprint(Object value) {
        String text = safeValue(value).trim();
        if (text.isBlank()) {
            return "";
        }
        if (text.matches("hash:[A-Fa-f0-9]{6,64}")) {
            return text;
        }
        String hash = SafeRedactor.hashValue(text);
        return hash == null ? "" : hash;
    }

    private static long toLongTraceValue(String field, Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception failure) {
            TraceStore.put("traceMemory.diagnostics.numericCoercion.failed", true);
            traceNumericCoercion(field, value, failure);
            return 0L;
        }
    }

    private static void traceNumericCoercion(String field, Object value, Exception failure) {
        String safeField = SafeRedactor.traceLabelOrFallback(field, "field");
        String raw = String.valueOf(value);
        TraceStore.put("traceMemory.diagnostics.numericCoercion.field", safeField);
        TraceStore.put("traceMemory.diagnostics.numericCoercion.errorType", "invalid_number");
        TraceStore.put("traceMemory.diagnostics.numericCoercion.exceptionType",
                SafeRedactor.traceLabelOrFallback(failure == null ? null : failure.getClass().getSimpleName(),
                        "unknown"));
        TraceStore.put("traceMemory.diagnostics.numericCoercion.valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put("traceMemory.diagnostics.numericCoercion.valueLength", raw.length());
    }

    private static void putTraceMeta(Map<String, String> out,
                                     Map<String, Object> trace,
                                     String key,
                                     int maxLength) {
        Object value = trace == null ? null : trace.get(key);
        out.put(key, safeText(safeValue(value), maxLength));
    }

    private static void putTraceMetaDefault(Map<String, String> out,
                                            Map<String, Object> trace,
                                            String key,
                                            int maxLength,
                                            String fallback) {
        Object value = trace == null ? null : trace.get(key);
        String text = safeValue(value);
        out.put(key, safeText(text.isBlank() ? fallback : text, maxLength));
    }

    private static void putHashMeta(Map<String, String> out, String prefix, String raw) {
        String value = raw == null ? "" : raw;
        String hash = value.startsWith("hash:") ? value : SafeRedactor.hashValue(value);
        out.put(prefix + "Hash", hash == null ? "" : hash);
        out.put(prefix + "Length", String.valueOf(value.length()));
    }

    private static Map<String, String> snapshotIdMeta(String id) {
        String idHash = SafeRedactor.hashValue(id);
        return Map.of(
                "idHash", idHash == null ? "" : idHash,
                "idLength", String.valueOf(id == null ? 0 : id.length()));
    }

    private static String safeValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safeText(String value, int maxLength) {
        String safe = SafeRedactor.safeMessage(value, maxLength);
        return safe == null ? "" : safe;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String traceMemorySelfProbeScenario(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            return "baseline";
        }
        String normalized = scenario.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "context_contamination", "synthetic_context_contamination" -> "synthetic_context_contamination";
            case "loader_starvation", "synthetic_loader_starvation" -> "synthetic_loader_starvation";
            case "after_filter_starvation", "synthetic_after_filter_starvation" -> "synthetic_after_filter_starvation";
            case "dropped_breadcrumb", "synthetic_dropped_breadcrumb" -> "synthetic_dropped_breadcrumb";
            case "silent_failure", "synthetic_silent_failure" -> "synthetic_silent_failure";
            default -> "baseline";
        };
    }

    private static String scenarioPathSuffix(String scenario) {
        return "baseline".equals(scenario) ? "" : "?scenario=" + scenario;
    }

    private static Map<String, Object> installSyntheticTraceValues(String scenario) {
        Map<String, Object> previous = new LinkedHashMap<>();
        switch (scenario) {
            case "synthetic_loader_starvation" ->
                    putSyntheticTraceValue(previous, "prompt.memory.compressor.reason", "all_lines_dropped");
            case "synthetic_after_filter_starvation" -> {
                putSyntheticTraceValue(previous, "context.candidates.raw", 3);
                putSyntheticTraceValue(previous, "context.candidates.afterFilter", 0);
                putSyntheticTraceValue(previous, "afterFilterCount", 0);
            }
            case "synthetic_dropped_breadcrumb" ->
                    putSyntheticTraceValue(previous, "trace.snapshot.capture.skipped", true);
            case "synthetic_silent_failure" ->
                    putSyntheticTraceValue(previous, "silent.failure", true);
            default -> {
                // Baseline and context contamination use raw checkpoint input only.
            }
        }
        return previous;
    }

    private static void putSyntheticTraceValue(Map<String, Object> previous, String key, Object value) {
        Object old = TraceStore.get(key);
        previous.put(key, old == null ? MISSING_TRACE_VALUE : old);
        TraceStore.put(key, value);
    }

    private static void restoreSyntheticTraceValues(Map<String, Object> previous) {
        if (previous == null || previous.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : previous.entrySet()) {
            TraceStore.put(entry.getKey(), entry.getValue() == MISSING_TRACE_VALUE ? null : entry.getValue());
        }
    }

    private static boolean truthyTraceValue(Object value) {
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String safeTraceValue(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value == null ? null : String.valueOf(value), fallback);
    }

    private static String simpleHtml(String title, Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
        sb.append("<title>").append(esc(title)).append("</title>");
        sb.append("<style>body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:16px} table{border-collapse:collapse} td,th{border:1px solid #ddd;padding:6px 8px} th{background:#f7f7f7;text-align:left}</style>");
        sb.append("</head><body>");
        sb.append("<h2>").append(esc(title)).append("</h2>");
        sb.append("<table>");
        if (kv != null) {
            for (Map.Entry<String, String> e : kv.entrySet()) {
                sb.append("<tr><th>").append(esc(String.valueOf(e.getKey()))).append("</th><td>")
                        .append(esc(String.valueOf(e.getValue()))).append("</td></tr>");
            }
        }
        sb.append("</table>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static ObjectProvider<TraceMemoryFingerprintProbe> noTraceMemoryProbe() {
        return new ObjectProvider<>() {
            @Override
            public TraceMemoryFingerprintProbe getObject(Object... args) {
                return null;
            }

            @Override
            public TraceMemoryFingerprintProbe getObject() {
                return null;
            }

            @Override
            public TraceMemoryFingerprintProbe getIfAvailable() {
                return null;
            }

            @Override
            public TraceMemoryFingerprintProbe getIfUnique() {
                return null;
            }
        };
    }
}
