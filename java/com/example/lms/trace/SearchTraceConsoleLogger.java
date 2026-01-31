// src/main/java/com/example/lms/trace/SearchTraceConsoleLogger.java
package com.example.lms.trace;

import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Console-friendly search trace dumper.
 *
 * Why:
 * - The system already captures diagnostics in TraceStore + SearchTrace,
 *   but most of it is only visible in UI panels or NDJSON files.
 * - In production consoles, debug logs are often suppressed by log level.
 *
 * This logger writes a compact, redacted summary to a dedicated logger name
 * (SEARCH_TRACE). That logger can be kept at INFO without flooding regular logs.
 */
@Component
public class SearchTraceConsoleLogger {

    private static final Logger TRACE = LoggerFactory.getLogger("SEARCH_TRACE");

    private final boolean alwaysEnabled;

    // Optional: when NightmareBreaker trips OPEN, auto-enable dbgSearch for a short window.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SearchDebugBoost debugBoost;

    public SearchTraceConsoleLogger(@Value("${lms.debug.search.console:false}") boolean alwaysEnabled) {
        this.alwaysEnabled = alwaysEnabled;
    }

    private boolean isBoostActive() {
        try {
            return debugBoost != null && debugBoost.isActive();
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Whether we should emit any console trace at all.
     *
     * Note: boost-mode is intentionally included so we can capture diagnostics
     * right after a breaker OPEN without per-request toggles.
     */
    public boolean isEnabled() {
        return alwaysEnabled || isRequestEnabled() || isBoostActive();
    }

    /**
     * Whether this request is explicitly dbgSearch-enabled (header/param) or via boost.
     */
    public static boolean isRequestEnabled() {
        String v = MDC.get("dbgSearch");
        if (v == null) return false;
        String t = v.trim().toLowerCase(Locale.ROOT);
        return t.equals("1") || t.equals("true") || t.equals("on") || t.equals("yes") || t.equals("y");
    }

    /**
     * Verbose mode: explicit per-request dbgSearch or alwaysEnabled.
     *
     * (Boost-mode still logs, but stays compact to avoid flooding.)
     */
    private boolean isVerbose() {
        return alwaysEnabled || isRequestEnabled();
    }

    public void maybeLog(
            String stage,
            NaverSearchService.SearchTrace trace,
            List<String> rawSnippets,
            List<Content> webTopK,
            List<Content> vectorTopK,
            Map<String, Object> extraMeta
    ) {
        if (!isEnabled()) return;

        boolean boost = isBoostActive();
        boolean verbose = isVerbose();

        String sid = safe(MDC.get("sid"), "-");
        String rid = safe(MDC.get("trace"), "-");

        String query = null;
        String provider = null;
        long elapsedMs = -1L;
        int stepCount = 0;

        if (trace != null) {
            query = trace.query();
            provider = trace.provider();
            elapsedMs = trace.elapsedMs();
            stepCount = (trace.steps != null) ? trace.steps.size() : 0;
        }

        // Fallback: use effective query captured in TraceStore/extraMeta
        if ((query == null || query.isBlank()) && extraMeta != null) {
            query = firstString(extraMeta.get("web.effectiveQuery"));
            if (query == null || query.isBlank()) query = firstString(extraMeta.get("queryPlanner.finalUsed"));
            if (query == null || query.isBlank()) query = firstString(extraMeta.get("finalUsed"));
        }
        if (query == null) query = "";

        int rawCount = (rawSnippets != null) ? rawSnippets.size() : 0;
        String webSz = (webTopK != null) ? Integer.toString(webTopK.size()) : "disabled";
        String vecSz = (vectorTopK != null) ? Integer.toString(vectorTopK.size()) : "disabled";

        TRACE.info("[{}] sid={} trace={} provider={} ms={} steps={} raw={} webTopK={} vecTopK={} q=\"{}\"",
                safe(stage, "?"),
                sid,
                rid,
                safe(provider, "-"),
                elapsedMs,
                stepCount,
                rawCount,
                webSz,
                vecSz,
                SafeRedactor.redact(trunc(oneLine(query), 220))
        );

        if (boost) {
            TRACE.info("  dbgBoost active=1 remainingMs={} reason={}",
                    (debugBoost != null ? debugBoost.remainingMs() : -1L),
                    SafeRedactor.redact(trunc(oneLine(debugBoost != null ? debugBoost.reason() : ""), 220))
            );
        }

        dumpPlannerOneLine(extraMeta);
        dumpAwaitOneLine(extraMeta, verbose || boost);

        dumpKeyMeta(extraMeta, verbose, boost);
        dumpSteps(trace, verbose ? 25 : (boost ? 12 : 8));
    }

    private void dumpPlannerOneLine(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty()) return;

        Object llm = extraMeta.get("queryPlanner.llmProposed");
        Object hygiene = extraMeta.get("queryPlanner.hygieneKept");
        Object fin = extraMeta.get("queryPlanner.finalUsed");
        if (llm == null && hygiene == null && fin == null) return;

        TRACE.info("  planner llmProposed={} hygieneKept={} finalUsed={}",
                SafeRedactor.redact(trunc(oneLine(compact(llm, 2)), 260)),
                SafeRedactor.redact(trunc(oneLine(compact(hygiene, 2)), 260)),
                SafeRedactor.redact(trunc(oneLine(compact(fin, 2)), 260))
        );
    }

    @SuppressWarnings("unchecked")
    private void dumpAwaitOneLine(Map<String, Object> extraMeta, boolean details) {
        if (extraMeta == null || extraMeta.isEmpty()) return;

        Object ev = extraMeta.get("web.await.events");
        if (!(ev instanceof List<?> list) || list.isEmpty()) return;

        int total = 0;
        int soft = 0;
        int hard = 0;
        int timeout = 0;
        long maxWaited = -1L;

        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            total++;
            Object stage = m.get("stage");
            if (stage != null) {
                String s = String.valueOf(stage).toLowerCase(Locale.ROOT);
                if (s.contains("soft")) soft++;
                if (s.contains("hard")) hard++;
            }
            Object cause = m.get("cause");
            if (cause != null) {
                String c = String.valueOf(cause).toLowerCase(Locale.ROOT);
                if (c.contains("timeout") || c.contains("budget_exhausted")) {
                    timeout++;
                }
            }
            Object waited = m.get("waitedMs");
            if (waited instanceof Number n) {
                maxWaited = Math.max(maxWaited, n.longValue());
            }
        }

        Object last = extraMeta.get("web.await.last");
        String lastStr = last != null ? SafeRedactor.redact(trunc(oneLine(String.valueOf(last)), 180)) : "";

        TRACE.info("  await events total={} soft={} hard={} timeout={} maxWaitedMs={} last={}",
                total, soft, hard, timeout, maxWaited, lastStr);

        if (!details) return;

        // show a few most expensive waits (waitedMs desc)
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                try {
                    candidates.add((Map<String, Object>) m);
                } catch (Throwable ignore) {
                    // ignore
                }
            }
        }
        candidates.sort(Comparator.comparingLong(m -> {
            Object w = m.get("waitedMs");
            return (w instanceof Number n) ? -n.longValue() : 0L;
        }));

        int limit = Math.min(6, candidates.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> m = candidates.get(i);
            TRACE.info("  await[{}] stage={} engine={} step={} cause={} timeoutMs={} waitedMs={} err={}",
                    i,
                    safeObj(m.get("stage"), "-"),
                    safeObj(m.get("engine"), "-"),
                    safeObj(m.get("step"), "-"),
                    safeObj(m.get("cause"), "-"),
                    safeObj(m.get("timeoutMs"), "-"),
                    safeObj(m.get("waitedMs"), "-"),
                    SafeRedactor.redact(trunc(oneLine(String.valueOf(m.get("err"))), 120))
            );
        }
    }

    private void dumpKeyMeta(Map<String, Object> extraMeta, boolean verbose, boolean boost) {
        if (extraMeta == null || extraMeta.isEmpty()) return;

        String[] verboseKeys = new String[] {
                "plan.id",
                "plan.retrievalOrder",
                "retrieval.order.override",

                "queryPlanner.llmProposed",
                "queryPlanner.hygieneKept",
                "queryPlanner.finalUsed",

                "web.effectiveQuery",
                "web.selectedTerms.summary",
                "web.selectedTerms.applied",
                "web.await.last",
                "web.await.events",
                "web.await.events.summary.engine.Naver.cause.await_timeout.count",
                "web.await.events.summary.engine.Brave.cause.await_timeout.count",

                // SOAK_WEB_KPI one-glance fields
                "web.failsoft.outCount",
                "stageCountsSelectedFromOut",
                "cacheOnly.merged.count",
                "tracePool.size",
                "rescueMerge.used",
                "starvationFallback.trigger",
                "poolSafeEmpty",
                "nofilterSafeRatio",
                "web.brave.cooldown.effectiveDelayMs",
                "web.failsoft.rateLimitBackoff.naver.awaitTimeoutApplied",
                "web.failsoft.rateLimitBackoff.brave.awaitTimeoutApplied",
                "web.brave.cooldown.consecutive429",
                "llm.modelGuard.mode",
                "llm.modelGuard.requestedModel",
                "llm.modelGuard.substituteChatModel",
                "web.failsoft.soakKpiJson.last",

                "aux.llm.down",
                "aux.llm.degraded",
                "aux.llm.hardDown",
                "aux.down.first",
				"aux.down.last",
				"aux.down.events",

				"aux.blocked",
				"aux.blocked.first",
				"aux.blocked.last",
				"aux.blocked.events",

                "aux.keywordSelection.blocked",
                "aux.keywordSelection.blocked.reason",
                "aux.keywordSelection.degraded",
                "aux.keywordSelection.degraded.reason",
                "aux.keywordSelection.degraded.count",

                "aux.queryTransformer",
                "aux.queryTransformer.blocked",
                "aux.queryTransformer.blocked.reason",
                "aux.queryTransformer.degraded",
                "aux.queryTransformer.degraded.reason",
                "aux.queryTransformer.degraded.trigger",
                "aux.queryTransformer.degraded.count",

                "aux.disambiguation",
                "aux.disambiguation.blocked",
                "aux.disambiguation.blocked.reason",

                "nightmare.breaker.openAtMs",
                "nightmare.breaker.openUntilMs",
                "nightmare.breaker.openUntilMs.last",

                "nightmare.mode",

                "orch.mode",
                "orch.strike",
                "orch.compression",
                "orch.bypass",
                "orch.webRateLimited",
                "orch.auxLlmDown",
                "orch.reason",
                "uaw.thumb.recall.hits",

                "faultmask.stage",
                "faultmask.count",
                "faultmask.last",
                "faultmask.note",

                "guard.final.action",
                "guard.minCitations.required",
                "guard.minCitations.actual",
                "guard.degrade.reason",
                "guard.forceEscalateOverDegrade",
                "guard.forceEscalateOverDegrade.by",
                "guard.forceEscalateOverDegrade.trigger",
                "guard.forceEscalateOverDegrade.blocked",
                "guard.detour.forceEscalate",
                "guard.detour.forceEscalate.by",
                "guard.detour.cheapRetry.forceEscalate",
                "guard.detour.cheapRetry.forceEscalate.by",
                "guard.detour.cheapRetry.regen",
                "guard.detour.cheapRetry.web.calls",
                "guard.detour.cheapRetry.regen.calls",
                "needle.web.calls",
                "keywordSelection.fallback.seedSource",
                "keywordSelection.fallback.seed.baseScore",
                "keywordSelection.fallback.seed.uqScore",
                "keywordSelection.fallback.exact",
                "keywordSelection.fallback.entityPhrase",
                "qtx.userPrompt.recovered",
                "qtx.normalized.blankRecovered",
                "qtx.cheapFallback.recovered",
                "qtx.softCooldown.active",
                "qtx.softCooldown.remainingMs"
        };

        String[] boostKeys = new String[] {
                "plan.id",
                "plan.retrievalOrder",
                "retrieval.order.override",

                "queryPlanner.finalUsed",
                "web.effectiveQuery",
                "web.selectedTerms.summary",
                "web.selectedTerms.applied",
                "web.await.last",
                "web.await.events.summary.engine.Naver.cause.await_timeout.count",
                "web.await.events.summary.engine.Brave.cause.await_timeout.count",

                // SOAK_WEB_KPI / DC shortcuts
                "web.failsoft.outCount",
                "stageCountsSelectedFromOut",
                "web.brave.cooldown.effectiveDelayMs",
                "web.failsoft.rateLimitBackoff.naver.awaitTimeoutApplied",
                "web.failsoft.rateLimitBackoff.brave.awaitTimeoutApplied",
                "llm.modelGuard.mode",
                "web.failsoft.soakKpiJson.last",
                "qtx.softCooldown.remainingMs",

                "nightmare.mode",
                "orch.mode",
                "orch.strike",
                "orch.bypass",
                "orch.reason",
                "uaw.thumb.recall.hits",
                "faultmask.stage",
                "faultmask.count",
                "faultmask.last",
                "guard.final.action"
        };

        String[] keys = verbose ? verboseKeys : (boost ? boostKeys : new String[] {
                "web.effectiveQuery",
                "web.selectedTerms.summary",
                "web.await.last",
                "nightmare.mode",
                "orch.mode",
                "orch.strike",
                "orch.bypass",
                "faultmask.stage",
                "faultmask.count"
        });

        for (String k : keys) {
            if (!extraMeta.containsKey(k)) continue;
            Object v = extraMeta.get(k);
            TRACE.info("  meta {}={}", k, SafeRedactor.redact(trunc(oneLine(String.valueOf(v)), 420)));
        }
    }

    private void dumpSteps(NaverSearchService.SearchTrace trace, int maxSteps) {
        if (trace == null || trace.steps == null || trace.steps.isEmpty()) return;

        int limit = Math.min(Math.max(1, maxSteps), trace.steps.size());
        for (int i = 0; i < limit; i++) {
            NaverSearchService.SearchStep s = trace.steps.get(i);
            if (s == null) continue;
            TRACE.info("  step[{}] {} returned={} afterFilter={} tookMs={}",
                    i,
                    SafeRedactor.redact(trunc(oneLine(s.query), 240)),
                    s.returned,
                    s.afterFilter,
                    s.tookMs);
        }
        if (trace.steps.size() > limit) {
            TRACE.info("  ... {} more steps", (trace.steps.size() - limit));
        }
    }

    private static String compact(Object v, int sampleLimit) {
        if (v == null) return "";

        if (v instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            int i = 0;
            for (Object o : c) {
                if (i >= sampleLimit) break;
                if (i > 0) sb.append(" | ");
                sb.append(trunc(oneLine(String.valueOf(o)), 80));
                i++;
            }
            if (c.size() > sampleLimit) {
                sb.append(" | … +").append(c.size() - sampleLimit).append("]");
            } else {
                sb.append("]");
            }
            return sb.toString();
        }

        return String.valueOf(v);
    }

    private static String firstString(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o == null) continue;
                String s = String.valueOf(o);
                if (!s.isBlank()) return s;
            }
            return null;
        }
        return String.valueOf(v);
    }

    private static String safeObj(Object o, String d) {
        if (o == null) return d;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? d : s;
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String safe(String s, String d) {
        if (s == null) return d;
        String t = s.trim();
        return t.isEmpty() ? d : t;
    }
}
