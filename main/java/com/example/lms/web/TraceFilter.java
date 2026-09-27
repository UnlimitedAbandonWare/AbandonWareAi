package com.example.lms.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter implements Filter {

    private static final String SID_HEADER = "X-Session-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String SID_COOKIE = "sid";

    // Request-scoped debug flag (propagated via MDC, and auto-propagated by
    // ContextPropagation)
    private static final String DBG_SEARCH_MDC = "dbgSearch";
    // Extra MDC keys to distinguish request-debug vs boost-debug
    private static final String DBG_SEARCH_SOURCE_MDC = "dbgSearchSrc"; // request|boost
    private static final String DBG_SEARCH_BOOST_ENGINES_MDC = "dbgSearchBoostEngines"; // CSV substrings
    private static final String DBG_SEARCH_HEADER = "X-Debug-Search";

    // Optional global boost window driven by NightmareBreaker OPEN
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.trace.SearchDebugBoost searchDebugBoost;

    // In-memory snapshot store for post-mortem debugging
    // (fail-soft; may be absent in minimal builds).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TraceSnapshotStore traceSnapshotStore;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.diag.RetrievalDiagnosticsCollector retrievalDiagnosticsCollector;

    // When boost-mode debug is active, keep detailed await-events only for selected
    // engines
    // (comma-separated substring match, case-insensitive; empty = all engines)
    @Value("${lms.debug.search.await.boost.detail-engines:}")
    private String awaitBoostDetailEnginesCsv;

    // When boost-mode is active and provider matches selected substrings, show
    // extra columns and higher row limits
    // in the "web trace steps" table.
    @Value("${lms.debug.search.trace.steps.boost.detail-provider-contains:}")
    private String stepsBoostDetailProviderContainsCsv;

    @Value("${lms.debug.search.trace.steps.maxRows:20}")
    private int stepsMaxRows;

    @Value("${lms.debug.search.trace.steps.maxRows.boost:40}")
    private int stepsMaxRowsBoost;

    @Value("${lms.debug.search.trace.steps.maxRows.boost.detail:80}")
    private int stepsMaxRowsBoostDetail;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // Best-effort: clear any stale ThreadLocal state (thread reuse safety)
        String prevSid = MDC.get("sid");
        String prevSessionId = MDC.get("sessionId");
        String prevTrace = MDC.get("trace");
        String prevTraceId = MDC.get("traceId");
        String prevXRequestId = MDC.get("x-request-id");
        String prevRequestSid = MDC.get("requestSid");
        String prevChatSessionId = MDC.get("chatSessionId");
        String prevDbgSearch = MDC.get(DBG_SEARCH_MDC);
        String prevDbgSearchSrc = MDC.get(DBG_SEARCH_SOURCE_MDC);
        String prevDbgSearchBoostEngines = MDC.get(DBG_SEARCH_BOOST_ENGINES_MDC);

        try { com.example.lms.search.TraceStore.clear(); } catch (Throwable ignore) { traceSuppressed("requestStart.traceStoreClear", ignore); }

        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }

        String sid = extractSid(req);
        String trace = extractRequestId(req);
        if (trace == null || trace.isBlank()) {
            trace = java.util.UUID.randomUUID().toString();
        }

        // [MLA breadcrumb] correlation ids into TraceStore + echo headers for client-side reports (best-effort)
        try {
            String traceHash = SafeRedactor.hashValue(trace);
            com.example.lms.search.TraceStore.putIfAbsent("trace.id", traceHash);
            com.example.lms.search.TraceStore.putIfAbsent("x-request-id", traceHash);
            com.example.lms.search.TraceStore.putIfAbsent("requestId", traceHash);
            com.example.lms.search.TraceStore.putIfAbsent("rid", traceHash);
            com.example.lms.search.TraceStore.putIfAbsent("trace", traceHash);
            com.example.lms.search.TraceStore.putIfAbsent("traceId", traceHash);
            if (sid != null && !sid.isBlank()) {
                com.example.lms.search.TraceStore.putIfAbsent("sid", SafeRedactor.hashValue(sid));
                com.example.lms.search.TraceStore.putIfAbsent("sessionId", SafeRedactor.hashValue(sid));
            }
        } catch (Throwable ignore) {
            traceSuppressed("requestIds.traceStore", ignore);
        }
        try {
            // Echo ids so clients can paste them into bug reports
            res.setHeader(REQUEST_ID_HEADER, trace);
            if (sid != null && !sid.isBlank()) res.setHeader(SID_HEADER, sid);
        } catch (Throwable ignore) {
            traceSuppressed("requestIds.responseHeader", ignore);
        }

        boolean boostActive = false;
        String boostReason = null;
        long boostRemainingMs = 0L;

        try {
            if (searchDebugBoost != null) {
                boostActive = searchDebugBoost.isActive();
                boostReason = searchDebugBoost.reason();
                boostRemainingMs = searchDebugBoost.remainingMs();
            }
        } catch (Throwable ignore) {
            traceSuppressed("debugBoost.state", ignore);
        }

        boolean requestDebug = isTruthy(req.getParameter("debug"))
                || isTruthy(req.getHeader(DBG_SEARCH_HEADER))
                || isTruthy(req.getHeader("X-Debug"));

        boolean dbgSearch = requestDebug || boostActive;

        // Enrich TraceStore with boost status + debug UX knobs (best-effort)
        try {
            if (boostActive) {
                com.example.lms.search.TraceStore.put("dbg.search.boost.active", true);
                if (boostRemainingMs > 0)
                    com.example.lms.search.TraceStore.put("dbg.search.boost.remainingMs", boostRemainingMs);
                if (boostReason != null)
                    com.example.lms.search.TraceStore.put("dbg.search.boost.reason", SafeRedactor.traceLabelOrFallback(boostReason, "unknown"));
            }

            if (dbgSearch) {
                if (awaitBoostDetailEnginesCsv != null && !awaitBoostDetailEnginesCsv.isBlank()) {
                    com.example.lms.search.TraceStore.put("dbg.search.await.boost.detailEngines",
                            awaitBoostDetailEnginesCsv);
                }
                if (stepsBoostDetailProviderContainsCsv != null && !stepsBoostDetailProviderContainsCsv.isBlank()) {
                    com.example.lms.search.TraceStore.put("dbg.search.trace.steps.boost.detailProviderContains",
                            stepsBoostDetailProviderContainsCsv);
                }
                com.example.lms.search.TraceStore.put("dbg.search.trace.steps.maxRows", stepsMaxRows);
                com.example.lms.search.TraceStore.put("dbg.search.trace.steps.maxRows.boost", stepsMaxRowsBoost);
                com.example.lms.search.TraceStore.put("dbg.search.trace.steps.maxRows.boost.detail",
                        stepsMaxRowsBoostDetail);
            }
        } catch (Throwable ignore) {
            traceSuppressed("debugKnobs.traceStore", ignore);
        }

        try (com.example.lms.trace.TraceContext __traceContext =
                     com.example.lms.trace.TraceContext.attach(sid, trace)) {
            appendMlaBreadcrumb("request_started", req.getMethod(), req.getRequestURI(), null);
            if (sid != null && !sid.isBlank()) {
                MDC.put("sid", sid);
                MDC.put("sessionId", sid);
            }
            MDC.put("trace", trace);
            MDC.put("traceId", trace);
            MDC.put("x-request-id", trace);

            // Bridge dbgSearch state into TraceStore so console/HTML can share one truth.
            try {
                if (dbgSearch)
                    com.example.lms.search.TraceStore.put("dbg.search.enabled", true);
                if (dbgSearch)
                    com.example.lms.search.TraceStore.put("uaw.ablation.bridge", true);
                if (boostActive)
                    com.example.lms.search.TraceStore.put("dbg.search.source", "boost");
                else if (dbgSearch)
                    com.example.lms.search.TraceStore.put("dbg.search.source", "request");
            } catch (Throwable ignore) {
                traceSuppressed("debugSource.traceStore", ignore);
            }

            if (dbgSearch) {
                MDC.put(DBG_SEARCH_MDC, "1");
                MDC.put(DBG_SEARCH_SOURCE_MDC, boostActive ? "boost" : "request");
                if (boostActive && awaitBoostDetailEnginesCsv != null && !awaitBoostDetailEnginesCsv.isBlank()) {
                    MDC.put(DBG_SEARCH_BOOST_ENGINES_MDC, awaitBoostDetailEnginesCsv);
                }

                try {
                    res.setHeader(DBG_SEARCH_HEADER, "1");
                    if (boostActive) {
                        res.setHeader("X-Debug-Search-Boost", "1");
                        if (searchDebugBoost != null) {
                            res.setHeader("X-Debug-Search-Boost-RemainingMs",
                                    String.valueOf(searchDebugBoost.remainingMs()));
                        }
                    }
                } catch (Exception ignore) {
                    traceSuppressed("debugHeaders", ignore);
                }
            }

            Throwable failure = null;
            try {
                chain.doFilter(request, response);
            } catch (Throwable t) {
                failure = t;
                throw t;
            } finally {
                appendMlaBreadcrumb("request_completed", req.getMethod(), req.getRequestURI(), safeStatus(res));
                // Snapshot capture MUST happen before TraceStore/MDC is cleared/restored.
                // This makes stage-handoff / merge-boundary breadcrumbs visible even after the request.
                try {
                    if (traceSnapshotStore != null) {
                        int status = 0;
                        try {
                            status = res.getStatus();
                        } catch (Throwable ignore) {
                            traceSuppressed("snapshot.status", ignore);
                        }

                        boolean hasMl = false;
                        boolean hasTraceMemory = false;
                        try {
                            java.util.Map<String, Object> ctx = com.example.lms.search.TraceStore.context();
                            if (ctx != null) {
                                for (String k : ctx.keySet()) {
                                    if (k != null && (k.startsWith("ml.") || k.startsWith("orch."))) {
                                        hasMl = true;
                                    }
                                    if (k != null && k.startsWith("traceMemory.")) {
                                        hasTraceMemory = true;
                                    }
                                    if (hasMl && hasTraceMemory) {
                                        break;
                                    }
                                }
                            }
                        } catch (Throwable ignore) {
                            traceSuppressed("snapshot.hasMl", ignore);
                        }

                        boolean capture = dbgSearch || hasMl || hasTraceMemory || status >= 400 || failure != null;
                        if (capture) {
                            String snapId = traceSnapshotStore.captureCurrent(
                                    "http_request",
                                    req.getMethod(),
                                    req.getRequestURI(),
                                    status,
                                    failure
                            );
                            if (snapId != null && !snapId.isBlank()) {
                                try {
                                    res.setHeader("X-Trace-Snapshot-Id", snapId);
                                } catch (Throwable ignore) {
                                    traceSuppressed("snapshot.header", ignore);
                                }
                                try {
                                    com.example.lms.search.TraceStore.put("trace.snapshot.id", snapId);
                                } catch (Throwable ignore) {
                                    traceSuppressed("snapshot.traceStoreId", ignore);
                                }
                            }
                        }
                    }
                } catch (Throwable ignore) {
                    traceSuppressed("snapshot.capture", ignore);
                }
            }
        } finally {
            restoreMdc("sid", prevSid);
            restoreMdc("sessionId", prevSessionId);
            restoreMdc("trace", prevTrace);
            restoreMdc("traceId", prevTraceId);
            restoreMdc("x-request-id", prevXRequestId);
            restoreMdc(DBG_SEARCH_MDC, prevDbgSearch);
            restoreMdc(DBG_SEARCH_SOURCE_MDC, prevDbgSearchSrc);
            restoreMdc(DBG_SEARCH_BOOST_ENGINES_MDC, prevDbgSearchBoostEngines);
            restoreMdc("requestSid", prevRequestSid);
            restoreMdc("chatSessionId", prevChatSessionId);

            // MERGE_HOOK:PROJ_AGENT::TRACE_FILTER_CLEAR_TRACESTORE_V1
            try {
                com.example.lms.search.TraceStore.clear();
            } catch (Throwable ignore) { traceSuppressed("cleanup.traceStoreClear", ignore); }
            try {
                com.example.lms.trace.TraceContext.cleanupCurrentThread();
            } catch (Throwable ignore) { traceSuppressed("cleanup.traceContext", ignore); }
            try {
                if (retrievalDiagnosticsCollector != null) {
                    retrievalDiagnosticsCollector.reset();
                }
            } catch (Throwable ignore) { traceSuppressed("cleanup.retrievalDiagnostics", ignore); }
        }
    }

    private static void appendMlaBreadcrumb(String decision, String method, String path, Integer status) {
        try {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("v", 1);
            row.put("seq", com.example.lms.search.TraceStore.nextSequence("ml.breadcrumbs.v1"));
            row.put("ts", java.time.Instant.now().toString());
            row.put("component", "TraceFilter");
            row.put("rules", "request_correlation");
            row.put("decision", decision);
            row.put("requestId", SafeRedactor.hashValue(firstNonBlank(MDC.get("x-request-id"),
                    com.example.lms.search.TraceStore.getString("requestId"))));
            row.put("sessionId", SafeRedactor.hashValue(firstNonBlank(MDC.get("sessionId"), MDC.get("sid"),
                    com.example.lms.search.TraceStore.getString("sessionId"))));
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("queryRedacted", true);
            if (method != null && !method.isBlank()) data.put("method", method);
            if (path != null && !path.isBlank()) {
                data.put("pathHash", SafeRedactor.hashValue(path));
                data.put("pathLength", path.length());
            }
            if (status != null) data.put("status", status);
            row.put("data", data);
            com.example.lms.search.TraceStore.append("ml.breadcrumbs.v1", row);
            com.example.lms.search.TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
            com.example.lms.trace.TraceContext.current().setFlag("ml.request.decision", decision);
        } catch (Throwable ignore) {
            traceSuppressed("mlaBreadcrumb.append", ignore);
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
        com.example.lms.search.TraceStore.put("trace.filter.suppressed.stage", safeStage);
        com.example.lms.search.TraceStore.put("trace.filter.suppressed.errorType", errorType);
        com.example.lms.search.TraceStore.put("trace.filter.suppressed." + safeStage, true);
        com.example.lms.search.TraceStore.put("trace.filter.suppressed." + safeStage + ".errorType", errorType);
    }

    private static Integer safeStatus(HttpServletResponse res) {
        try {
            return res == null ? null : res.getStatus();
        } catch (Throwable ignore) {
            traceSuppressed("safeStatus", ignore);
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }
        return null;
    }

    private static void restoreMdc(String key, String prev) {
        if (key == null || key.isBlank()) return;
        if (prev != null) {
            MDC.put(key, prev);
        } else {
            MDC.remove(key);
        }
    }

    private static boolean isTruthy(String v) {
        if (v == null)
            return false;
        String s = v.trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    private String extractRequestId(HttpServletRequest req) {
        String rid = req.getHeader(REQUEST_ID_HEADER);
        if (rid != null && !rid.isBlank())
            return rid.trim();
        return null;
    }

    private String extractSid(HttpServletRequest req) {
        String sid = req.getHeader(SID_HEADER);
        if (sid != null && !sid.isBlank())
            return sid.trim();

        Cookie[] cookies = req.getCookies();
        if (cookies == null)
            return null;
        return Optional.ofNullable(cookies)
                .stream()
                .flatMap(java.util.Arrays::stream)
                .filter(c -> SID_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }
}
