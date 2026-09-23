package com.example.lms.probe;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.orchestration.OrchestrationSignals;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin-only orchestration diagnostics.
 *
 * Enabled only when {@code probe.orch.enabled=true}.
 * Protected by {@code X-Probe-Token} matching {@code probe.admin-token}.
 */
@RestController
@RequestMapping("/api/probe")
@ConditionalOnProperty(name = "probe.orch.enabled", havingValue = "true", matchIfMissing = false)
public class OrchProbeController {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrchProbeController.class);

    private static final List<String> KG_AXIS_FIELDS = List.of(
            "schemaVersion",
            "status",
            "kgPoolCount",
            "kgFinalCount",
            "kgScoreMean",
            "kgScoreP95",
            "kgFinalRetention",
            "graphScore",
            "graphOpportunity",
            "neo4jStatus",
            "neo4jDisabledReason",
            "neo4jFailureClass",
            "signals");
    private static final List<String> SCORECARD_FIELDS = List.of(
            "schemaVersion",
            "status",
            "reasonCode",
            "thresholdLabels");
    private static final List<String> THRESHOLD_BREAK_FIELDS = List.of(
            "label",
            "reasonCode",
            "scope",
            "metric",
            "status",
            "category",
            "stage",
            "neo4jStatus",
            "neo4jDisabledReason",
            "neo4jFailureClass");
    private static final List<String> NESTED_TRACE_FIELDS = List.of(
            "schemaVersion",
            "status",
            "reasonCode",
            "thresholdLabels",
            "label",
            "scope",
            "metric",
            "category",
            "stage",
            "neo4jStatus",
            "neo4jDisabledReason",
            "neo4jFailureClass",
            "name",
            "value",
            "count",
            "score",
            "enabled",
            "degraded",
            "failureClass",
            "trigger",
            "fallbackTriggered");
    private static final Set<String> SAFE_SCALAR_TRACE_KEYS = Set.of(
            "langgraph.node.quality_gate.reason",
            "langgraph.node.quality_gate.degraded",
            "langgraph.invoke.failureClass",
            "langgraph.invoke.trigger",
            "langgraph.invoke.fallbackTriggered",
            "retrieval.kg.neo4j.status",
            "retrieval.kg.neo4j.disabledReason",
            "retrieval.kg.neo4j.failureClass");
    private static final Set<String> WEB_FAILSOFT_TRACE_KEYS = Set.of(
            "outCount",
            "cacheOnly.merged.count",
            "tracePool.size",
            "rescueMerge.used",
            "starvationFallback.trigger",
            "starvationFallback.used",
            "starvationFallback.poolUsed",
            "starvationFallback.pool.safe.size",
            "starvationFallback.pool.dev.size",
            "starvationFallback.poolSafeEmpty",
            "starvationFallback.count",
            "starvationFallback.added",
            "poolSafeEmpty",
            "vectorFallback.used",
            "vectorFallback.reason",
            "vectorFallback.effectiveTopK");
    private static final Set<String> PROVIDER_TRACE_SUFFIXES = Set.of(
            "skipped",
            "skipped.reason",
            "providerDisabled",
            "disabledReason",
            "failureReason",
            "providerEmpty",
            "afterFilterStarved",
            "rateLimited",
            "retryAfterMs");

    private final NightmareBreaker nightmareBreaker;
    private final boolean enabled;
    private final String adminToken;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RetrievalOrderService retrievalOrderService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TraceSnapshotStore traceSnapshotStore;

    public OrchProbeController(
            NightmareBreaker nightmareBreaker,
            @Value("${probe.orch.enabled:false}") boolean enabled,
            @Value("${probe.admin-token:}") String adminToken) {
        this.nightmareBreaker = nightmareBreaker;
        this.adminToken = adminToken;
        if (enabled && ConfigValueGuards.isMissing(adminToken)) {
            this.enabled = false;
            org.slf4j.LoggerFactory.getLogger(OrchProbeController.class)
                    .warn("[ProviderGuard] PROBE_TOKEN missing -> probe.orch disabled{}", LogCorrelation.suffix());
        } else {
            this.enabled = enabled;
        }
    }

    @GetMapping("/orch")
    public ResponseEntity<?> orch(
            @RequestParam(name = "q", required = false) String q,
            @RequestHeader(value = "X-Probe-Token", required = false) String token,
            HttpServletRequest request) {

        if (!enabled) {
            return ResponseEntity.status(404).body(err("PROBE_DISABLED"));
        }
        if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
            return ResponseEntity.status(401).body(err("UNAUTHORIZED"));
        }

        GuardContext ctx = GuardContextHolder.getOrDefault();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", Instant.now().toString());
        out.put("queryPresent", q != null && !q.isBlank());
        out.put("queryLength", q == null ? 0 : q.length());
        out.put("queryHash12", SafeRedactor.hash12(q));

        try {
            seedRetrievalOrderTrace(q);
        } catch (Throwable t) {
            traceSkipped("retrievalOrder.probe", t);
            TraceStore.put("retrievalOrder.probe.failed", true);
            TraceStore.put("retrievalOrder.probe.errorType",
                    SafeRedactor.traceLabelOrFallback(t.getClass().getSimpleName(), "unknown"));
        }

        try {
            out.put("breakers.open", nightmareBreaker != null ? nightmareBreaker.snapshot() : Map.of());
        } catch (Throwable t) {
            traceSkipped("breakers.open", t);
            out.put("breakers.open", safeError(t));
        }

        try {
            Map<String, Object> guard = new LinkedHashMap<>();
            if (ctx != null) {
                guard.put("auxDown", ctx.isAuxDown());
                guard.put("auxDegraded", ctx.isAuxDegraded());
                guard.put("auxHardDown", ctx.isAuxHardDown());
                guard.put("strikeMode", ctx.isStrikeMode());
                guard.put("compressionMode", ctx.isCompressionMode());
                guard.put("bypassMode", ctx.isBypassMode());
                guard.put("bypassReason", ctx.getBypassReason());
                guard.put("planId", ctx.getPlanId());
                guard.put("mode", ctx.getMode());
                guard.put("memoryProfile", ctx.getMemoryProfile());
            }
            out.put("guard", ctx == null ? null : guard);
        } catch (Throwable t) {
            traceSkipped("guard", t);
            out.put("guard", safeError(t));
        }

        try {
            out.put("signals", OrchestrationSignals.compute(q, nightmareBreaker, ctx));
        } catch (Throwable t) {
            traceSkipped("signals", t);
            out.put("signals", safeError(t));
        }

        try {
            out.put("trace", selectedTraceSnapshot());
        } catch (Throwable t) {
            traceSkipped("trace", t);
            out.put("trace", safeError(t));
        }

        String snapshotId = captureProbeSnapshot(request);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (snapshotId != null && !snapshotId.isBlank()) {
            ok.header("X-Trace-Snapshot-Id", snapshotId);
        }
        return ok.body(out);
    }

    @GetMapping(value = "/orch/ui", produces = MediaType.TEXT_HTML_VALUE)
    public String ui() {
        // The UI itself is a static helper; data fetching still requires X-Probe-Token.
        return """
                <!doctype html>
                <html lang=\"en\">
                <head>
                  <meta charset=\"utf-8\" />
                  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
                  <title>Orch Probe</title>
                  <style>
                    body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 16px; }
                    input { width: 520px; max-width: 95vw; padding: 6px; }
                    button { padding: 6px 10px; }
                    pre { background: #111; color: #eee; padding: 12px; overflow: auto; }
                    .row { margin: 8px 0; }
                  </style>
                </head>
                <body>
                  <h2>Orchestration Probe</h2>
                  <div class=\"row\">
                    <label>Probe Token (X-Probe-Token):</label><br/>
                    <input id=\"tok\" placeholder=\"paste token\" />
                  </div>
                  <div class=\"row\">
                    <label>Query (optional):</label><br/>
                    <input id=\"q\" placeholder=\"q=...\" />
                  </div>
                  <div class=\"row\">
                    <button onclick=\"refreshOnce()\">Refresh</button>
                    <button onclick=\"toggleAuto()\" id=\"autoBtn\">Auto: OFF</button>
                  </div>
                  <pre id=\"out\">(no data)</pre>

                  <script>
                    let timer = null;
                    async function refreshOnce() {
                      const tok = document.getElementById('tok').value;
                      const q = document.getElementById('q').value;
                      const url = '/api/probe/orch' + (q ? ('?q=' + encodeURIComponent(q)) : '');
                      const res = await fetch(url, { headers: { 'X-Probe-Token': tok } });
                      const txt = await res.text();
                      document.getElementById('out').textContent = txt;
                    }
                    function toggleAuto() {
                      const btn = document.getElementById('autoBtn');
                      if (timer) {
                        clearInterval(timer);
                        timer = null;
                        btn.textContent = 'Auto: OFF';
                        return;
                      }
                      timer = setInterval(refreshOnce, 2000);
                      btn.textContent = 'Auto: ON';
                      refreshOnce();
                    }
                  </script>
                </body>
                </html>
                """;
    }

    private static Map<String, String> err(String code) {
        return Map.of("error", code, "code", code);
    }

    private static Map<String, String> safeError(Throwable t) {
        String type = t == null ? "unknown" : t.getClass().getSimpleName();
        String hash12 = t == null ? null : SafeRedactor.hash12(t.toString());
        return Map.of("error", type, "errorHash12", hash12 == null ? "" : hash12);
    }

    private static void traceSkipped(String stage, Throwable t) {
        log.debug("[AWX][probe][orch] fallback stage={} errorType={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                SafeRedactor.traceLabelOrFallback(t == null ? null : t.getClass().getSimpleName(), "unknown"));
    }

    private void seedRetrievalOrderTrace(String q) {
        if (q == null || q.isBlank()) {
            return;
        }
        RetrievalOrderService orderService = retrievalOrderService;
        if (orderService == null) {
            TraceStore.put("retrievalOrder.probe.skipped", true);
            TraceStore.put("retrievalOrder.probe.reason", "missing_service");
            return;
        }
        List<RetrievalOrderService.Source> order = orderService.decideOrder(q);
        TraceStore.put("retrievalOrder.probe.used", true);
        TraceStore.put("retrievalOrder.probe.orderSize", order == null ? 0 : order.size());
    }

    private String captureProbeSnapshot(HttpServletRequest request) {
        TraceSnapshotStore store = traceSnapshotStore;
        if (store == null) {
            return null;
        }
        try {
            String method = request == null ? "GET" : request.getMethod();
            String path = request == null ? "/api/probe/orch" : request.getRequestURI();
            return store.captureCurrent("probe_orch", method, path, 200, null);
        } catch (Throwable t) {
            traceSkipped("traceSnapshot.probe", t);
            TraceStore.put("traceSnapshot.probe.failed", true);
            TraceStore.put("traceSnapshot.probe.errorType",
                    SafeRedactor.traceLabelOrFallback(t.getClass().getSimpleName(), "unknown"));
            return null;
        }
    }

    private static Map<String, Object> selectedTraceSnapshot() {
        Map<String, Object> all = TraceStore.getAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trace.size", all == null ? 0 : all.size());
        if (all == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : all.entrySet()) {
            String key = entry.getKey();
            Object value = selectedTraceValue(key, entry.getValue());
            if (value != null) {
                out.put(key, value);
            }
        }
        if (!out.containsKey("stageCountsSelectedFromOut")) {
            Object fallback = firstTraceValue(
                    all,
                    "stageCountsSelectedFromOut.last",
                    "web.failsoft.stageCountsSelectedFromOut",
                    "web.failsoft.stageCountsSelectedFromOut.last");
            Object value = selectedTraceValue("stageCountsSelectedFromOut", fallback);
            if (value != null) {
                out.put("stageCountsSelectedFromOut", value);
            }
        }
        putCanonicalTraceFallback(out, all, "cacheOnly.merged.count",
                "web.failsoft.hybridEmptyFallback.cacheOnly.merged.count");
        putCanonicalTraceFallback(out, all, "tracePool.size",
                "web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size");
        putCanonicalTraceFallback(out, all, "rescueMerge.used",
                "web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used");
        putCanonicalTraceFallback(out, all, "starvationFallback.trigger",
                "web.failsoft.starvationFallback.trigger",
                "web.failsoft.starvationFallback");
        putCanonicalTraceFallback(out, all, "starvationFallback.used",
                "web.failsoft.starvationFallback.used");
        putCanonicalTraceFallback(out, all, "starvationFallback.poolUsed",
                "web.failsoft.starvationFallback.poolUsed");
        putCanonicalTraceFallback(out, all, "starvationFallback.pool.safe.size",
                "web.failsoft.starvationFallback.pool.safe.size");
        putCanonicalTraceFallback(out, all, "starvationFallback.pool.dev.size",
                "web.failsoft.starvationFallback.pool.dev.size");
        putCanonicalTraceFallback(out, all, "starvationFallback.count",
                "web.failsoft.starvationFallback.count");
        putCanonicalTraceFallback(out, all, "starvationFallback.added",
                "web.failsoft.starvationFallback.added");
        putCanonicalTraceFallback(out, all, "starvationFallback.poolSafeEmpty",
                "web.failsoft.starvationFallback.poolSafeEmpty");
        putCanonicalTraceFallback(out, all, "poolSafeEmpty",
                "starvationFallback.poolSafeEmpty",
                "web.failsoft.starvationFallback.poolSafeEmpty");
        return out;
    }

    private static void putCanonicalTraceFallback(Map<String, Object> out,
                                                  Map<String, Object> all,
                                                  String canonicalKey,
                                                  String... fallbackKeys) {
        if (out == null || out.containsKey(canonicalKey)) {
            return;
        }
        Object fallback = firstTraceValue(all, fallbackKeys);
        Object value = selectedTraceValue(canonicalKey, fallback);
        if (value != null) {
            out.put(canonicalKey, value);
        }
    }

    private static Object firstTraceValue(Map<String, Object> all, String... keys) {
        if (all == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key != null && all.containsKey(key) && all.get(key) != null) {
                return all.get(key);
            }
        }
        return null;
    }

    private static Object selectedTraceValue(String key, Object value) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "rag.eval.kgAxis" -> projectAllowedMapValue(key, value, KG_AXIS_FIELDS);
            case "rag.eval.scorecard" -> projectAllowedMapValue(key, value, SCORECARD_FIELDS);
            case "rag.eval.thresholdBreaks" -> projectThresholdBreaks(key, value);
            default -> {
                if (SAFE_SCALAR_TRACE_KEYS.contains(key) || isSelectedTraceKey(key)) {
                    yield selectedScalarTraceValue(key, value);
                }
                yield null;
            }
        };
    }

    private static Object selectedScalarTraceValue(String key, Object value) {
        if (key != null && key.startsWith("retrievalOrder.")) {
            return selectedRetrievalOrderTraceValue(value);
        }
        if ("starvationFallback.poolUsed".equals(key)) {
            return SafeRedactor.traceLabelOrFallback(value, "");
        }
        if (key != null && (key.endsWith(".trigger") || key.endsWith(".reason") || key.endsWith("Reason"))) {
            return SafeRedactor.traceLabelOrFallback(value, "");
        }
        return SafeRedactor.diagnosticValue(key, value);
    }

    private static Object selectedRetrievalOrderTraceValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Collection<?> rows) {
            List<Object> out = new ArrayList<>();
            for (Object row : rows) {
                out.add(SafeRedactor.traceLabelOrFallback(row, ""));
            }
            return out;
        }
        return SafeRedactor.traceLabelOrFallback(value, "");
    }

    private static Object projectThresholdBreaks(String key, Object value) {
        if (value instanceof Collection<?> rows) {
            List<Object> out = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> rowMap) {
                    Map<String, Object> projected = projectAllowedMap(key, rowMap, THRESHOLD_BREAK_FIELDS);
                    if (!projected.isEmpty()) {
                        out.add(projected);
                    }
                } else {
                    out.add(SafeRedactor.diagnosticValue(key, row));
                }
            }
            return out;
        }
        return projectAllowedMapValue(key, value, THRESHOLD_BREAK_FIELDS);
    }

    private static Object projectAllowedMapValue(String key, Object value, List<String> allowedFields) {
        if (!(value instanceof Map<?, ?> source)) {
            return SafeRedactor.diagnosticValue(key, value);
        }
        return projectAllowedMap(key, source, allowedFields);
    }

    private static Map<String, Object> projectAllowedMap(String key, Map<?, ?> source, List<String> allowedFields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String field : allowedFields) {
            if (!source.containsKey(field) || isUnsafeTraceField(field)) {
                continue;
            }
            out.put(field, projectAllowedValue(key + "." + field, source.get(field)));
        }
        return out;
    }

    private static Object projectAllowedValue(String key, Object value) {
        if (value instanceof Map<?, ?> nested) {
            return projectAllowedMap(key, nested, NESTED_TRACE_FIELDS);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            for (Object item : collection) {
                if (item instanceof Map<?, ?> nested) {
                    Map<String, Object> projected = projectAllowedMap(key, nested, NESTED_TRACE_FIELDS);
                    if (!projected.isEmpty()) {
                        out.add(projected);
                    }
                } else {
                    out.add(SafeRedactor.diagnosticValue(key, item));
                }
            }
            return out;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(value, i);
                out.add(projectAllowedValue(key, item));
            }
            return out;
        }
        return SafeRedactor.diagnosticValue(key, value);
    }

    private static boolean isUnsafeTraceField(String field) {
        return SafeRedactor.isRestrictedKey(field);
    }

    private static boolean isSelectedTraceKey(String key) {
        return key != null && (key.startsWith("web.await.")
                || key.startsWith("web.failsoft.")
                || key.startsWith("web.brave.cooldown.")
                || key.startsWith("aux.queryTransformer.")
                || key.startsWith("qtx.")
                || key.startsWith("keywordSelection.fallback.")
                || key.startsWith("embed.")
                || key.equals("guard.forceEscalateOverDegrade")
                || key.startsWith("guard.forceEscalateOverDegrade.")
                || key.startsWith("guard.detour.")
                || key.startsWith("needle.")
                || key.startsWith("retrievalOrder.")
                || WEB_FAILSOFT_TRACE_KEYS.contains(key)
                || isProviderTraceKey(key)
                || key.equals("stageCountsSelectedFromOut")
                || key.equals("starvationFallback.trigger"));
    }

    private static boolean isProviderTraceKey(String key) {
        if (key == null) return false;
        String suffix = null;
        if (key.startsWith("web.naver.")) suffix = key.substring("web.naver.".length());
        if (key.startsWith("web.brave.")) suffix = key.substring("web.brave.".length());
        if (key.startsWith("web.serpapi.")) suffix = key.substring("web.serpapi.".length());
        if (key.startsWith("web.tavily.")) suffix = key.substring("web.tavily.".length());
        return suffix != null && PROVIDER_TRACE_SUFFIXES.contains(suffix);
    }
}
