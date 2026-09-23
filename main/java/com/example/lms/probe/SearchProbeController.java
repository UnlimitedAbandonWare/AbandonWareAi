package com.example.lms.probe;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.search.TraceStore;
import com.example.lms.probe.dto.SearchProbeResponse;
import com.example.lms.probe.dto.SearchProbeRequest;
import com.example.lms.probe.dto.StageSnapshot;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/probe")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="probe.search.enabled", havingValue="true")
public class SearchProbeController {
    private final SearchProbeService service;
    private final boolean enabled;
    private final String adminToken;

    @Autowired(required = false)
    private TraceSnapshotStore traceSnapshotStore;

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    public SearchProbeController(
            SearchProbeService service,
            @Value("${probe.search.enabled:false}") boolean enabled,
            @Value("${probe.admin-token:}") String adminToken) {
        this.service = service;
        this.adminToken = adminToken;
        if (enabled && ConfigValueGuards.isMissing(adminToken)) {
            // Fail-soft: don't crash boot, but DO disable the probe when token is missing.
            this.enabled = false;
            org.slf4j.LoggerFactory.getLogger(SearchProbeController.class)
                    .warn("[ProviderGuard] PROBE_TOKEN missing -> probe.search disabled{}", LogCorrelation.suffix());
        } else {
            this.enabled = enabled;
        }
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchProbeRequest req,
                                    @RequestHeader(value = "X-Probe-Token", required = false) String token,
                                    HttpServletRequest request) {
        if (!enabled) {
            return ResponseEntity.status(404).body(err("PROBE_DISABLED"));
        }
        if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
            return ResponseEntity.status(401).body(err("UNAUTHORIZED"));
        }
        Object out;
        try {
            out = service.run(req);
        } catch (RuntimeException error) {
            TraceStore.put("traceMemory.probe.search.serviceRun.catch", Boolean.TRUE);
            recordServiceFailure(error);
            String snapshotId = captureProbeSnapshot(request, 500);
            ResponseEntity.BodyBuilder failed = ResponseEntity.status(500);
            if (snapshotId != null && !snapshotId.isBlank()) {
                failed.header("X-Trace-Snapshot-Id", snapshotId);
            }
            return failed.body(err("PROBE_FAILED"));
        }
        restoreSelectedTrace(out);
        String snapshotId = captureProbeSnapshot(request);
        appendProbeSnapshotDiagnostics(out, snapshotId);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (snapshotId != null && !snapshotId.isBlank()) {
            ok.header("X-Trace-Snapshot-Id", snapshotId);
        }
        return ok.body(out);
    }

    private static java.util.Map<String, String> err(String code) {
        return java.util.Map.of("error", code, "code", code);
    }

    private String captureProbeSnapshot(HttpServletRequest request) {
        return captureProbeSnapshot(request, 200);
    }

    private String captureProbeSnapshot(HttpServletRequest request, int status) {
        TraceSnapshotStore store = traceSnapshotStore;
        if (store == null) {
            TraceStore.put("traceSnapshot.probe.search.storeAvailable", Boolean.FALSE);
            TraceStore.put("traceSnapshot.probe.search.captured", Boolean.FALSE);
            TraceStore.put("traceSnapshot.probe.search.missingReason", "store_unavailable");
            return null;
        }
        TraceStore.put("traceSnapshot.probe.search.storeAvailable", Boolean.TRUE);
        try {
            String method = request == null ? "POST" : request.getMethod();
            String path = request == null ? "/api/probe/search" : request.getRequestURI();
            String snapshotId = store.captureCurrent("probe_search", method, path, status, null);
            boolean captured = snapshotId != null && !snapshotId.isBlank();
            TraceStore.put("traceSnapshot.probe.search.captured", captured);
            if (captured) {
                TraceStore.put("traceSnapshot.probe.search.snapshotIdHash", SafeRedactor.hashValue(snapshotId));
                TraceStore.put("traceSnapshot.probe.search.snapshotIdLength", snapshotId.length());
            } else {
                Object skipReason = TraceStore.get("trace.snapshot.capture.skipReason");
                TraceStore.put("traceSnapshot.probe.search.missingReason",
                        skipReason == null ? "capture_returned_null" : skipReason);
            }
            return snapshotId;
        } catch (Throwable error) {
            TraceStore.put("traceSnapshot.probe.search.failed", Boolean.TRUE);
            TraceStore.put("traceSnapshot.probe.search.errorType",
                    error == null ? "unknown" : error.getClass().getSimpleName());
            return null;
        }
    }

    private void recordServiceFailure(RuntimeException error) {
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        String errorHash = SafeRedactor.hashValue(error == null ? "" : String.valueOf(error.getMessage()));
        TraceStore.put("traceSnapshot.probe.search.serviceFailed", Boolean.TRUE);
        TraceStore.put("traceSnapshot.probe.search.failedStage", "serviceRun");
        TraceStore.put("traceSnapshot.probe.search.errorType", errorType);
        TraceStore.put("traceSnapshot.probe.search.errorHash", errorHash);
        TraceStore.put("traceMemory.probe.search.serviceFailed", Boolean.TRUE);
        TraceStore.put("traceMemory.probe.search.failedStage", "serviceRun");
        TraceStore.put("traceMemory.probe.search.errorType", errorType);
        TraceStore.put("traceMemory.probe.search.errorHash", errorHash);
        TraceStore.put("traceMemory.virtualCheckpoint.probeSearch", "serviceRun.failed");
        TraceStore.put("traceMemory.probe.search.sequence", TraceStore.nextSequence("traceMemory.probe.search.failure"));
        emitServiceFailureDebugEvent(errorType, errorHash);
    }

    private void emitServiceFailureDebugEvent(String errorType, String errorHash) {
        DebugEventStore store = debugEventStore;
        if (store == null) {
            TraceStore.put("traceMemory.probe.search.debugEventStoreAvailable", Boolean.FALSE);
            return;
        }
        try {
            store.emit(
                    DebugProbeType.TRACE_MEMORY,
                    DebugEventLevel.ERROR,
                    "trace-memory:probe-search:service-run",
                    "Probe search service failed before trace snapshot capture",
                    "SearchProbeController.search",
                    Map.of(
                            "stage", "serviceRun",
                            "errorType", errorType,
                            "errorHash", errorHash,
                            "snapshotAttempted", Boolean.TRUE),
                    null);
            TraceStore.put("traceMemory.probe.search.debugEventEmitted", Boolean.TRUE);
        } catch (RuntimeException debugError) {
            TraceStore.put("traceMemory.probe.search.debugEventSuppressed", Boolean.TRUE);
            TraceStore.put("traceMemory.probe.search.debugEventErrorType",
                    debugError.getClass().getSimpleName());
            TraceStore.put("traceMemory.probe.search.debugEventErrorHash",
                    SafeRedactor.hashValue(String.valueOf(debugError.getMessage())));
        }
    }

    private static void restoreSelectedTrace(Object out) {
        if (!(out instanceof SearchProbeResponse response) || response.stages == null) {
            return;
        }
        for (StageSnapshot stage : response.stages) {
            if (stage == null || !"trace:selected".equals(stage.name) || stage.params == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : stage.params.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank() || "trace.size".equals(key)) {
                    continue;
                }
                TraceStore.put(key, entry.getValue());
            }
            TraceStore.put("traceSnapshot.probe.search.restoredSelectedTrace", Boolean.TRUE);
            return;
        }
    }

    private static void appendProbeSnapshotDiagnostics(Object out, String snapshotId) {
        if (!(out instanceof SearchProbeResponse response) || response.stages == null) {
            return;
        }
        StageSnapshot selected = null;
        for (StageSnapshot stage : response.stages) {
            if (stage != null && "trace:selected".equals(stage.name)) {
                selected = stage;
                break;
            }
        }
        if (selected == null) {
            return;
        }
        if (selected.params == null) {
            selected.params = new java.util.HashMap<>();
        }
        boolean headerPresent = snapshotId != null && !snapshotId.isBlank();
        selected.params.put("traceSnapshot.probe.search.headerPresent", headerPresent);
        if (headerPresent) {
            selected.params.put("traceSnapshot.probe.search.snapshotIdHash", SafeRedactor.hashValue(snapshotId));
            selected.params.put("traceSnapshot.probe.search.snapshotIdLength", snapshotId.length());
        }
        copyTraceDiagnostic(selected.params, "traceSnapshot.probe.search.storeAvailable");
        copyTraceDiagnostic(selected.params, "traceSnapshot.probe.search.captured");
        copyTraceDiagnostic(selected.params, "traceSnapshot.probe.search.missingReason");
        copyTraceDiagnostic(selected.params, "traceSnapshot.probe.search.failed");
        copyTraceDiagnostic(selected.params, "traceSnapshot.probe.search.errorType");
        copyTraceDiagnostic(selected.params, "trace.snapshot.capture.skipped");
        copyTraceDiagnostic(selected.params, "trace.snapshot.capture.skipReason");
        copyTraceDiagnostic(selected.params, "trace.snapshot.capture.reason");
        copyTraceDiagnostic(selected.params, "trace.snapshot.capture.failed");
        copyTraceDiagnostic(selected.params, "trace.snapshot.capture.errorType");
        copyTraceDiagnostic(selected.params, "trace.snapshot.capture.errorHash");
    }

    private static void copyTraceDiagnostic(Map<String, Object> target, String key) {
        Object value = TraceStore.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }
}
