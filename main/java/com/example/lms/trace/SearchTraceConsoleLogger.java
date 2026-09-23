// src/main/java/com/example/lms/trace/SearchTraceConsoleLogger.java
package com.example.lms.trace;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
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
import java.util.LinkedHashMap;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebugEventStore debugEventStore;

    public SearchTraceConsoleLogger(@Value("${lms.debug.search.console:false}") boolean alwaysEnabled) {
        this.alwaysEnabled = alwaysEnabled;
    }

    private boolean isBoostActive() {
        try {
            return debugBoost != null && debugBoost.isActive();
        } catch (Throwable ignore) {
            TraceStore.put("trace.console.suppressed.boostActive", true);
            TraceStore.put("trace.console.suppressed.boostActive.errorType",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
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

        String sid = safe(SafeRedactor.hashValue(MDC.get("sid")), "-");
        String rid = safe(SafeRedactor.hashValue(MDC.get("trace")), "-");

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

        String queryHash = queryHash(trace, query);
        int queryLength = queryLength(trace, query);
        String queryTokenBucket = queryTokenBucket(trace, query);
        Map<String, Map<String, Object>> providerMetrics = providerMetricsSnapshot();

        TRACE.info("[{}] sidHash={} traceHash={} provider={} ms={} steps={} raw={} webTopK={} vecTopK={} queryHash={} queryLength={} queryTokenBucket={}",
                safe(stage, "?"),
                sid,
                rid,
                safe(provider, "-"),
                elapsedMs,
                stepCount,
                rawCount,
                webSz,
                vecSz,
                safe(queryHash, "-"),
                queryLength,
                safe(queryTokenBucket, "-")
        );

        if (boost) {
            TRACE.info("  dbgBoost active=1 remainingMs={} reason={}",
                    (debugBoost != null ? debugBoost.remainingMs() : -1L),
                    SafeRedactor.redact(trunc(oneLine(debugBoost != null ? debugBoost.reason() : ""), 220))
            );
        }

        dumpPlannerOneLine(extraMeta);
        dumpAwaitOneLine(extraMeta, verbose || boost);
        dumpProviderMetrics(providerMetrics);

        dumpKeyMeta(extraMeta, verbose, boost);
        dumpSteps(trace, verbose ? 25 : (boost ? 12 : 8));
        emitDebugSummary(stage, provider, elapsedMs, stepCount, rawCount, webSz, vecSz,
                queryHash, queryLength, queryTokenBucket, providerMetrics, boost, verbose);
    }

    private void dumpPlannerOneLine(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty()) return;

        Object llm = extraMeta.get("queryPlanner.llmProposed");
        Object hygiene = extraMeta.get("queryPlanner.hygieneKept");
        Object fin = extraMeta.get("queryPlanner.finalUsed");
        if (llm == null && hygiene == null && fin == null) return;

        TRACE.info("  planner llmProposed={} hygieneKept={} finalUsed={}",
                diagnosticText("queryPlanner.llmProposed", compact(llm, 2), 260),
                diagnosticText("queryPlanner.hygieneKept", compact(hygiene, 2), 260),
                diagnosticText("queryPlanner.finalUsed", compact(fin, 2), 260)
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
        String lastStr = last != null ? diagnosticText("web.await.last", last, 180) : "";

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
                    TraceStore.put("trace.console.suppressed.awaitCandidateCast", true);
                    TraceStore.put("trace.console.suppressed.awaitCandidateCast.errorType",
                            SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
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
                    diagnosticText("web.await.err", m.get("err"), 120)
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
                "web.await.events.summary.engine.SerpApi.cause.await_timeout.count",
                "web.await.events.summary.engine.Tavily.cause.await_timeout.count",

                // SOAK_WEB_KPI one-glance fields
                "web.failsoft.outCount",
                "stageCountsSelectedFromOut",
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
                "vectorFallback.effectiveTopK",
                "nofilterSafeRatio",
                "web.brave.cooldown.effectiveDelayMs",
                "web.failsoft.rateLimitBackoff.naver.awaitTimeoutApplied",
                "web.failsoft.rateLimitBackoff.brave.awaitTimeoutApplied",
                "web.brave.cooldown.consecutive429",
                "llm.modelGuard.mode",
                "llm.modelGuard.triggered",
                "llm.modelGuard.endpoint",
                "llm.modelGuard.failReason",
                "llm.modelGuard.requestedModelHash",
                "llm.modelGuard.requestedModelLength",
                "llm.modelGuard.substituteChatModelHash",
                "llm.modelGuard.substituteChatModelLength",
                "boosterMode.active",
                "boosterMode.excludedModes",
                "boosterMode.priority",
                "boosterMode.exclusionReason",
                "routing.executionPlan.primaryMode",
                "routing.executionPlan.triggers",
                "routing.executionPlan.extremeZ",
                "routing.executionPlan.overdrive",
                "routing.executionPlan.hypernova",
                "routing.executionPlan.applied",
                "routing.executionPlan.applied.primaryMode",
                "routing.executionPlan.applied.triggers",
                "routing.executionPlan.applied.stages",
                "specialMode.conflict.suppressed",
                "specialMode.priority",
                "hypernova.twpmP",
                "hypernova.cvarPhi",
                "hypernova.clampApplied",
                "hypernova.dppApplied",
                "hypernova.sourceScoreScaleMismatchCount",
                "hypernova.sourceScoreScaleMismatchPolicy",
                "nova.hypernova.riskK.alloc.sum",
                "hypernova.riskKAlloc",
                "retrievalOrder.lastSetBy",
                "retrievalOrder.lastOrder",
                "retrievalOrder.lastOrderSize",
                "retrievalOrder.authority.owner",
                "retrievalOrder.authority.suppressedOwner",
                "retrievalOrder.authority.reason",
                "retrievalOrder.authority.suppressedReason",
                "retrieval.order.strategy.applied",
                "embed.matryoshka.slice.actual",
                "embed.matryoshka.slice.target",
                "embed.matryoshka.slice.reductionRatio",
                "embed.matryoshka.slice.expectedDistanceOpsRatio",
                "embed.matryoshka.slice.expectedDistanceOpsSpeedup",
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
                "web.await.events.summary.engine.SerpApi.cause.await_timeout.count",
                "web.await.events.summary.engine.Tavily.cause.await_timeout.count",

                // SOAK_WEB_KPI / DC shortcuts
                "web.failsoft.outCount",
                "stageCountsSelectedFromOut",
                "embed.matryoshka.slice.actual",
                "embed.matryoshka.slice.target",
                "embed.matryoshka.slice.reductionRatio",
                "embed.matryoshka.slice.expectedDistanceOpsRatio",
                "embed.matryoshka.slice.expectedDistanceOpsSpeedup",
                "web.brave.cooldown.effectiveDelayMs",
                "web.failsoft.rateLimitBackoff.naver.awaitTimeoutApplied",
                "web.failsoft.rateLimitBackoff.brave.awaitTimeoutApplied",
                "llm.modelGuard.mode",
                "llm.modelGuard.triggered",
                "llm.modelGuard.endpoint",
                "llm.modelGuard.failReason",
                "llm.modelGuard.requestedModelHash",
                "llm.modelGuard.requestedModelLength",
                "llm.modelGuard.substituteChatModelHash",
                "llm.modelGuard.substituteChatModelLength",
                "boosterMode.active",
                "boosterMode.excludedModes",
                "boosterMode.priority",
                "boosterMode.exclusionReason",
                "routing.executionPlan.primaryMode",
                "routing.executionPlan.triggers",
                "routing.executionPlan.extremeZ",
                "routing.executionPlan.overdrive",
                "routing.executionPlan.hypernova",
                "routing.executionPlan.applied",
                "routing.executionPlan.applied.primaryMode",
                "routing.executionPlan.applied.triggers",
                "routing.executionPlan.applied.stages",
                "specialMode.conflict.suppressed",
                "hypernova.twpmP",
                "hypernova.cvarPhi",
                "hypernova.clampApplied",
                "hypernova.dppApplied",
                "hypernova.sourceScoreScaleMismatchCount",
                "hypernova.sourceScoreScaleMismatchPolicy",
                "nova.hypernova.riskK.alloc.sum",
                "hypernova.riskKAlloc",
                "retrievalOrder.lastSetBy",
                "retrievalOrder.lastOrder",
                "retrievalOrder.authority.owner",
                "retrievalOrder.authority.suppressedOwner",
                "retrievalOrder.authority.reason",
                "retrievalOrder.authority.suppressedReason",
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
            boolean present = extraMeta.containsKey(k);
            Object v = present ? extraMeta.get(k) : null;
            if (!present && "stageCountsSelectedFromOut".equals(k)) {
                v = firstNonNull(
                        extraMeta.get("stageCountsSelectedFromOut.last"),
                        extraMeta.get("web.failsoft.stageCountsSelectedFromOut"),
                        extraMeta.get("web.failsoft.stageCountsSelectedFromOut.last"));
                present = v != null;
            }
            if (!present) continue;
            TRACE.info("  meta {}={}", k, diagnosticText(k, v, 420));
        }
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void dumpSteps(NaverSearchService.SearchTrace trace, int maxSteps) {
        if (trace == null || trace.steps == null || trace.steps.isEmpty()) return;

        int limit = Math.min(Math.max(1, maxSteps), trace.steps.size());
        for (int i = 0; i < limit; i++) {
            NaverSearchService.SearchStep s = trace.steps.get(i);
            if (s == null) continue;
            TRACE.info("  step[{}] queryHash={} queryLength={} queryTokenBucket={} returned={} afterFilter={} tookMs={}",
                    i,
                    safe(SafeRedactor.hashValue(s.query), "-"),
                    s.query == null ? 0 : s.query.length(),
                    tokenBucket(s.query),
                    s.returned,
                    s.afterFilter,
                    s.tookMs);
        }
        if (trace.steps.size() > limit) {
            TRACE.info("  ... {} more steps", (trace.steps.size() - limit));
        }
    }

    private void dumpProviderMetrics(Map<String, Map<String, Object>> providerMetrics) {
        if (providerMetrics == null || providerMetrics.isEmpty()) return;
        for (Map.Entry<String, Map<String, Object>> entry : providerMetrics.entrySet()) {
            Map<String, Object> m = entry.getValue();
            if (m == null || m.isEmpty()) continue;
            TRACE.info("  provider {} requested={} returned={} afterFilter={} disabled={} skipped={} skipReason={} empty={} zero={} starved={} reason={} queryHash={} queryLength={} tookMs={} rateLimited={} retryAfterMs={} timeout={}",
                    safe(entry.getKey(), "-"),
                    safeObj(m.get("requestedCount"), "-"),
                    safeObj(m.get("returnedCount"), "-"),
                    safeObj(m.get("afterFilterCount"), "-"),
                    safeObj(m.get("providerDisabled"), "-"),
                    safeObj(m.get("skipped"), "-"),
                    diagnosticText("web." + entry.getKey() + ".skipped.reason", m.get("skippedReason"), 120),
                    safeObj(m.get("providerEmpty"), "-"),
                    safeObj(m.get("zeroResults"), "-"),
                    safeObj(m.get("afterFilterStarved"), "-"),
                    diagnosticText("web." + entry.getKey() + ".failureReason", m.get("failureReason"), 120),
                    diagnosticText("web." + entry.getKey() + ".queryHash", m.get("queryHash"), 120),
                    safeObj(m.get("queryLength"), "-"),
                    safeObj(m.get("tookMs"), "-"),
                    safeObj(m.get("rateLimited"), "-"),
                    safeObj(m.get("retryAfterMs"), "-"),
                    safeObj(m.get("timeout"), "-"));
        }
    }

    private void emitDebugSummary(String stage,
                                  String provider,
                                  long elapsedMs,
                                  int stepCount,
                                  int rawCount,
                                  String webTopKCount,
                                  String vectorTopKCount,
                                  String queryHash,
                                  int queryLength,
                                  String queryTokenBucket,
                                  Map<String, Map<String, Object>> providerMetrics,
                                  boolean boost,
                                  boolean verbose) {
        if (debugEventStore == null) return;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stage", safe(stage, "?"));
            data.put("provider", safe(provider, "-"));
            data.put("elapsedMs", elapsedMs);
            data.put("stepCount", stepCount);
            data.put("rawSnippetCount", rawCount);
            data.put("webTopKCount", webTopKCount);
            data.put("vectorTopKCount", vectorTopKCount);
            data.put("queryHash", queryHash);
            data.put("queryLength", queryLength);
            data.put("queryTokenBucket", queryTokenBucket);
            data.put("boost", boost);
            data.put("verbose", verbose);
            data.put("providers", providerMetrics == null ? Map.of() : providerMetrics);
            debugEventStore.emit(
                    DebugProbeType.WEB_SEARCH,
                    DebugEventLevel.INFO,
                    "search.trace.console.summary",
                    "search trace console summary",
                    "SearchTraceConsoleLogger.maybeLog",
                    data,
                    null);
        } catch (Throwable ignore) {
            TraceStore.put("trace.console.suppressed.debugEventSummary", true);
            TraceStore.put("trace.console.suppressed.debugEventSummary.errorType",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
            // fail-soft diagnostics only
        }
    }

    private static Map<String, Map<String, Object>> providerMetricsSnapshot() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String provider : List.of("naver", "brave", "serpapi", "tavily")) {
            Map<String, Object> m = providerMetrics(provider);
            if (!m.isEmpty()) {
                out.put(provider, m);
            }
        }
        return out;
    }

    private static Map<String, Object> providerMetrics(String provider) {
        Map<String, Object> out = new LinkedHashMap<>();
        String p = "web." + provider + ".";
        putIfPresent(out, "requestedCount", firstTraceValue(p,
                "requestedCount", "display", "count", "topK"));
        putIfPresent(out, "returnedCount", firstTraceValue(p,
                "returnedCount", "rawCount", "filter.rawCount", "outCount"));
        putIfPresent(out, "afterFilterCount", firstTraceValue(p,
                "afterFilterCount", "afterFilter", "filter.afterFilterCount"));
        putIfPresent(out, "providerDisabled", TraceStore.get(p + "providerDisabled"));
        putIfPresent(out, "skipped", TraceStore.get(p + "skipped"));
        putIfPresent(out, "skippedReason", TraceStore.get(p + "skipped.reason"));
        putIfPresent(out, "providerEmpty", TraceStore.get(p + "providerEmpty"));
        putIfPresent(out, "zeroResults", TraceStore.get(p + "zeroResults"));
        putIfPresent(out, "afterFilterStarved", firstTraceValue(p,
                "afterFilterStarved", "afterFilterStarvation"));
        putIfPresent(out, "failureReason", firstTraceValue(p,
                "failureReason", "disabledReason", "disabledReasonCanonical"));
        putIfPresent(out, "queryHash", TraceStore.get(p + "queryHash"));
        putIfPresent(out, "queryLength", TraceStore.get(p + "queryLength"));
        putIfPresent(out, "queryTokenBucket", TraceStore.get(p + "queryTokenBucket"));
        putIfPresent(out, "tookMs", firstTraceValue(p, "tookMs", "elapsedMs"));
        putIfPresent(out, "rateLimited", firstTraceValue(p, "rateLimited", "429"));
        putIfPresent(out, "retryAfterMs", firstTraceValue(p, "retryAfterMs", "cooldown.hintMs"));
        putIfPresent(out, "timeout", TraceStore.get(p + "timeout"));
        return out;
    }

    private static Object firstTraceValue(String prefix, String... suffixes) {
        if (suffixes == null) return null;
        for (String suffix : suffixes) {
            if (suffix == null || suffix.isBlank()) continue;
            Object v = TraceStore.get(prefix + suffix);
            if (v != null) return v;
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (out == null || key == null || key.isBlank() || value == null) return;
        out.put(key, value);
    }

    private static String queryHash(NaverSearchService.SearchTrace trace, String query) {
        if (trace != null && trace.queryHash != null && !trace.queryHash.isBlank()) {
            return trace.queryHash;
        }
        return SafeRedactor.hashValue(query);
    }

    private static int queryLength(NaverSearchService.SearchTrace trace, String query) {
        if (trace != null && trace.queryLength > 0) {
            return trace.queryLength;
        }
        return query == null ? 0 : query.length();
    }

    private static String queryTokenBucket(NaverSearchService.SearchTrace trace, String query) {
        if (trace != null && trace.queryTokenBucket != null && !trace.queryTokenBucket.isBlank()) {
            return trace.queryTokenBucket;
        }
        return tokenBucket(query);
    }

    private static String tokenBucket(String query) {
        if (query == null || query.isBlank()) {
            return "0";
        }
        int tokens = query.trim().split("\\s+").length;
        if (tokens <= 4) return "1-4";
        if (tokens <= 12) return "5-12";
        if (tokens <= 32) return "13-32";
        return "33+";
    }

    private static String diagnosticText(String key, Object value, int max) {
        if (isModeListKey(key) && value instanceof Collection<?> collection) {
            return trunc(collection.stream()
                    .map(item -> SafeRedactor.traceLabelOrFallback(item, "unknown"))
                    .reduce((left, right) -> left + "," + right)
                    .orElse(""), max);
        }
        if ("llm.modelGuard.endpoint".equals(key)) {
            return trunc(safeEndpointPath(value), max);
        }
        if (isModeLabelKey(key)) {
            String label = value == null ? "" : String.valueOf(value)
                    .replace('>', '_')
                    .replace(',', '_');
            return trunc(SafeRedactor.traceLabelOrFallback(label, "unknown"), max);
        }
        Object safe = SafeRedactor.diagnosticValue(key, value, max);
        if (safe == null) return "";
        return trunc(oneLine(String.valueOf(safe)), max);
    }

    private static boolean isModeListKey(String key) {
        return "boosterMode.excludedModes".equals(key)
                || "routing.executionPlan.triggers".equals(key)
                || "routing.executionPlan.applied.triggers".equals(key)
                || "routing.executionPlan.applied.stages".equals(key)
                || "retrievalOrder.lastOrder".equals(key);
    }

    private static boolean isModeLabelKey(String key) {
        return "boosterMode.active".equals(key)
                || "boosterMode.priority".equals(key)
                || "boosterMode.exclusionReason".equals(key)
                || "routing.executionPlan.primaryMode".equals(key)
                || "routing.executionPlan.applied.primaryMode".equals(key)
                || "specialMode.conflict.suppressed".equals(key)
                || "specialMode.priority".equals(key)
                || "llm.modelGuard.mode".equals(key)
                || "llm.modelGuard.failReason".equals(key)
                || "hypernova.sourceScoreScaleMismatchPolicy".equals(key)
                || "retrievalOrder.lastSetBy".equals(key)
                || "retrievalOrder.authority.owner".equals(key)
                || "retrievalOrder.authority.suppressedOwner".equals(key)
                || "retrievalOrder.authority.reason".equals(key)
                || "retrievalOrder.authority.suppressedReason".equals(key);
    }

    private static String safeEndpointPath(Object value) {
        if (value == null) {
            return "unknown";
        }
        String text = String.valueOf(value).trim();
        if (text.matches("/v1/[A-Za-z0-9_./:-]{1,120}")) {
            return text;
        }
        return SafeRedactor.traceLabelOrFallback(value, "unknown");
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
        return s.substring(0, Math.max(0, max - 1)) + "...";
    }

    private static String safe(String s, String d) {
        if (s == null) return d;
        String t = s.trim();
        return t.isEmpty() ? d : t;
    }
}
