package com.example.lms.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }

        String sid = extractSid(req);
        String trace = extractRequestId(req);
        if (trace == null || trace.isBlank()) {
            trace = java.util.UUID.randomUUID().toString();
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
            // never break user requests due to debug plumbing
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
                    com.example.lms.search.TraceStore.put("dbg.search.boost.reason", boostReason);
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
            // ignore
        }

        try {
            if (sid != null)
                MDC.put("sid", sid);
            MDC.put("trace", trace);
            MDC.put("x-request-id", trace);

            // Bridge dbgSearch state into TraceStore so console/HTML can share one truth.
            try {
                if (dbgSearch)
                    com.example.lms.search.TraceStore.put("dbg.search.enabled", true);
                if (boostActive)
                    com.example.lms.search.TraceStore.put("dbg.search.source", "boost");
                else if (dbgSearch)
                    com.example.lms.search.TraceStore.put("dbg.search.source", "request");
            } catch (Throwable ignore) {
                // ignore
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
                    // ignore
                }
            }

            chain.doFilter(request, response);
        } finally {
            MDC.remove("sid");
            MDC.remove("trace");
            MDC.remove("x-request-id");
            MDC.remove(DBG_SEARCH_MDC);
            MDC.remove(DBG_SEARCH_SOURCE_MDC);
            MDC.remove(DBG_SEARCH_BOOST_ENGINES_MDC);
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
