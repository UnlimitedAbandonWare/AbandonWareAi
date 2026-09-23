package ai.abandonware.nova.orch.probe;

import ai.abandonware.nova.orch.web.ProviderStateNormalizer;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a small "web soak" (20~50 calls) and compares KPI against a baseline log (e.g. X_Brave.txt).
 *
 * KPI focus (user request):
 * - web.brave.skipped (any reason) -> should converge to 0
 * - [Naver] Hard Timeout frequency down
 * - outCount=0 and merged(rawInput)=0 streaks down
 */
@Slf4j
public class WebSoakKpiProbeService {

    private final HybridWebSearchProvider hybrid;
    private final Environment env;
    private final ObjectMapper om;

    public WebSoakKpiProbeService(HybridWebSearchProvider hybrid, Environment env, ObjectMapper om) {
        this.hybrid = hybrid;
        this.env = env;
        this.om = om;
    }

    public Report run(WebSoakKpiProbeController.Request req) {
        // Defaults
        int iterations = clampInt(firstInt(req != null ? req.getIterations() : null,
                env.getProperty("probe.websoak-kpi.iterations", Integer.class, 30)), 1, 50);

        int topK = clampInt(firstInt(req != null ? req.getTopK() : null,
                env.getProperty("probe.websoak-kpi.topK", Integer.class, 8)), 1, 30);

        long sleepMsBetween = clampLong(firstLong(req != null ? req.getSleepMsBetween() : null,
                env.getProperty("probe.websoak-kpi.sleep-ms-between", Long.class, 0L)), 0, 10_000);

        boolean dbgSearch = firstBool(req != null ? req.getDbgSearch() : null,
                env.getProperty("probe.websoak-kpi.dbg-search", Boolean.class, Boolean.TRUE));

        String baselineFile = firstNonBlank(
                req != null ? req.getBaselineFile() : null,
                env.getProperty("probe.websoak-kpi.baseline-file", String.class, ""),
                "./X_Brave.txt"
        );

        boolean useBaselineQueries = firstBool(req != null ? req.getUseBaselineQueries() : null, true);
        String webPrimary = firstNonBlank(req != null ? req.getWebPrimary() : null,
                env.getProperty("probe.websoak-kpi.webPrimary", String.class, ""));

        List<String> queries = new ArrayList<>();
        if (req != null && req.getQueries() != null && !req.getQueries().isEmpty()) {
            for (String q : req.getQueries()) {
                if (q != null && !q.trim().isEmpty()) {
                    queries.add(q.trim());
                }
            }
        }

        Baseline baseline = null;
        if (baselineFile != null && !baselineFile.isBlank()) {
            File f = new File(baselineFile);
            if (f.exists() && f.isFile()) {
                baseline = BaselineParser.parse(f);
                if (useBaselineQueries && queries.isEmpty()) {
                    queries.addAll(baseline.queries);
                }
            } else {
                String path = f.getAbsolutePath();
                log.warn("[WebSoakKPI] baseline file not found pathHash={} pathLength={}",
                        SafeRedactor.hashValue(path), path == null ? 0 : path.length());
            }
        }

        if (queries.isEmpty()) {
            String single = firstNonBlank(req != null ? req.getQuery() : null,
                    env.getProperty("probe.websoak-kpi.query", String.class, ""),
                    "아카네 리제가 뭐야?");
            queries.add(single);
        }

        // Run soak
        List<Sample> samples = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger(0);
        long started = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            String query = queries.get(i % queries.size());
            Sample s = runOnce(seq.incrementAndGet(), query, topK, webPrimary, dbgSearch);
            samples.add(s);

            if (sleepMsBetween > 0) {
                try {
                    Thread.sleep(sleepMsBetween);
                } catch (InterruptedException ie) {
                    log.debug("[WebSoakKPI] sleep interrupted stage={}", "service.run.sleepBetween");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        long elapsedMs = System.currentTimeMillis() - started;

        Aggregate currentAgg = Aggregate.fromSamples(samples);

        Aggregate baselineAgg = baseline != null ? baseline.aggregate : null;

        Comparison cmp = Comparison.compare(baselineAgg, currentAgg);

        String table = TableRenderer.render(baselineAgg, currentAgg, cmp, iterations, elapsedMs);

        // console output requested
        log.info("\n{}", table);

        List<String> tuningHints = TuningHints.suggest(env, currentAgg);

        Report report = new Report();
        report.generatedAt = Instant.now().toString();
        report.iterations = iterations;
        report.topK = topK;
        report.sleepMsBetween = sleepMsBetween;
        report.dbgSearch = dbgSearch;
        report.webPrimary = webPrimary == null ? "" : webPrimary;
        report.queries = diagnosticQueries(queries);
        report.baselineFile = baselineFile;
        report.baseline = baselineAgg;
        report.current = currentAgg;
        report.delta = cmp;
        report.table = table;
        report.tuningHints = tuningHints;
        report.samples = samples; // useful for quick inspection; can be large but 50 max.
        return report;
    }

    private Sample runOnce(int seq, String query, int topK, String webPrimary, boolean dbgSearch) {
        long begin = System.currentTimeMillis();

        // Reset per-call context
        TraceStore.clear();
        MDC.clear();

        String sessionId = "websoak-" + System.currentTimeMillis();
        String rid = sessionId + "-" + seq;

        try (TraceContext ignored = TraceContext.attach(sessionId, rid)) {

        if (dbgSearch) {
            MDC.put("dbgSearch", "1");
        }

        GuardContext ctx = GuardContext.defaultContext();
        ctx.setPlanId("soak.webkpi");
        ctx.setMode("soak");
        ctx.setEngine("probe");
        if (webPrimary != null && !webPrimary.isBlank()) {
            ctx.setWebPrimary(webPrimary.trim().toUpperCase(Locale.ROOT));
        }
        GuardContextHolder.set(ctx);

        List<String> out = Collections.emptyList();
        Throwable err = null;
        try {
            out = hybrid.search(query, topK);
            if (out == null) out = Collections.emptyList();
        } catch (Throwable t) {
            recordRunOnceFailure(t);
            err = t;
            out = Collections.emptyList();
        } finally {
            GuardContextHolder.clear();
        }

        long elapsedMs = System.currentTimeMillis() - begin;

        // Snapshot minimal KPI from TraceStore
        Sample s = new Sample();
        s.seq = seq;
        s.query = "";
        s.queryPresent = query != null && !query.isBlank();
        s.queryLength = query == null ? 0 : query.length();
        s.queryHash12 = SafeRedactor.hash12(query);
        s.sessionId = SafeRedactor.hashValue(sessionId);
        s.rid = SafeRedactor.hashValue(rid);
        s.elapsedMs = elapsedMs;
        s.outCount = safeLong(TraceStore.get("web.failsoft.outCount"));
        s.rawInputCount = safeLong(TraceStore.get("web.failsoft.rawInputCount"));
        s.naverSkippedReason = safeStr(TraceStore.get("web.naver.skipped.reason"));
        s.braveSkippedReason = safeStr(TraceStore.get("web.brave.skipped.reason"));
        s.braveSkipped = safeBoolean(TraceStore.get("web.brave.skipped")) || hasText(s.braveSkippedReason);
        s.serpapiSkippedReason = safeStr(TraceStore.get("web.serpapi.skipped.reason"));
        s.tavilySkippedReason = safeStr(TraceStore.get("web.tavily.skipped.reason"));
        s.naverHardTimeoutCount = safeLong(firstNonNull(
                TraceStore.get("web.await.events.summary.engine.Naver.cause.timeout_hard.count"),
                TraceStore.get("web.await.events.summary.engine.Naver.cause.await_timeout.count"),
                TraceStore.get("web.await.events.summary.engine.Naver.cause.timeout.count"),
                TraceStore.get("web.await.events.summary.timeout.hard.count"),
                TraceStore.get("web.await.events.timeout.hard.count")
        ));
        s.naverBackoffReason = safeStr(TraceStore.get("web.failsoft.rateLimitBackoff.naver.reason"));
        s.braveBackoffReason = safeStr(TraceStore.get("web.failsoft.rateLimitBackoff.brave.reason"));
        s.serpapiBackoffReason = safeStr(TraceStore.get("web.failsoft.rateLimitBackoff.serpapi.reason"));
        s.tavilyBackoffReason = safeStr(TraceStore.get("web.failsoft.rateLimitBackoff.tavily.reason"));
        s.naverBackoffRemainingMs = safeLong(TraceStore.get("web.failsoft.rateLimitBackoff.naver.remainingMs"));
        s.braveBackoffRemainingMs = safeLong(TraceStore.get("web.failsoft.rateLimitBackoff.brave.remainingMs"));
        s.serpapiBackoffRemainingMs = safeLong(TraceStore.get("web.failsoft.rateLimitBackoff.serpapi.remainingMs"));
        s.tavilyBackoffRemainingMs = safeLong(TraceStore.get("web.failsoft.rateLimitBackoff.tavily.remainingMs"));

        Object trig = TraceStore.get("web.failsoft.hybridEmptyFallback.triggeredBy");
        s.hybridEmptyTriggered = trig != null;
        s.kpi = fixedKpiSnapshot(rid, sessionId, s.outCount, s.rawInputCount);

        s.error = (err == null) ? "" : sampleFailureClass(err) + "#" + SafeRedactor.hash12(err.toString());

        return s;
        } finally {
            TraceStore.clear();
            MDC.clear();
        }
    }

    private static List<String> diagnosticQueries(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(queries.size());
        for (String query : queries) {
            out.add(queryDiagnostic(query));
        }
        return out;
    }

    private static String queryDiagnostic(String query) {
        boolean present = query != null && !query.isBlank();
        int length = query == null ? 0 : query.length();
        String hash12 = SafeRedactor.hash12(query);
        return "present=" + present + " length=" + length + " hash12=" + hash12;
    }

    private static String sampleFailureClass(Throwable err) {
        if (err == null) {
            return "";
        }
        NightmareBreaker.FailureKind kind = NightmareBreaker.classify(err);
        if (kind == NightmareBreaker.FailureKind.INTERRUPTED) {
            return "cancelled";
        }
        if (kind == NightmareBreaker.FailureKind.TIMEOUT) {
            return "timeout";
        }
        if (kind == NightmareBreaker.FailureKind.RATE_LIMIT) {
            return "rate-limit";
        }
        String errText = failureText(err);
        if (errText.contains("cancel") || errText.contains("interrupt")) {
            return "cancelled";
        }
        if (errText.contains("timeout") || errText.contains("timed out") || errText.contains("deadline")) {
            return "timeout";
        }
        if (errText.contains("429") || errText.contains("rate limit") || errText.contains("too many requests")) {
            return "rate-limit";
        }
        return "provider_error";
    }

    private static String failureText(Throwable err) {
        StringBuilder sb = new StringBuilder(160);
        Throwable cur = err;
        int guard = 0;
        while (cur != null && guard++ < 12) {
            sb.append(cur.getClass().getName()).append(' ');
            String message = cur.getMessage();
            if (message != null) {
                sb.append(message);
            }
            sb.append(' ');
            Throwable cause = cur.getCause();
            if (cause == cur) {
                break;
            }
            cur = cause;
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> fixedKpiSnapshot(String rid, String sessionId, long outCount, long rawInputCount) {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("rid", SafeRedactor.hashValue(rid));
        kpi.put("sessionId", SafeRedactor.hashValue(sessionId));
        boolean cacheOnlyUsed = safeBoolean(firstNonNull(
                TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.used"),
                TraceStore.get("cacheOnly.used")));
        boolean naverSkipped = safeBoolean(TraceStore.get("web.naver.skipped"));
        boolean braveSkipped = safeBoolean(TraceStore.get("web.brave.skipped"));
        boolean serpapiSkipped = safeBoolean(TraceStore.get("web.serpapi.skipped"));
        boolean tavilySkipped = safeBoolean(TraceStore.get("web.tavily.skipped"));
        String naverState = providerState(
                TraceStore.get("provider.naver"),
                naverSkipped,
                safeBoolean(TraceStore.get("web.naver.cacheOnlyFuture.used"))
                        || safeBoolean(TraceStore.get("web.naver.cacheOnly.timeoutRescue.used"))
                        || (cacheOnlyUsed && safeLong(TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.naver.count")) > 0L));
        String braveState = providerState(
                TraceStore.get("provider.brave"),
                braveSkipped,
                cacheOnlyUsed && safeLong(TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.brave.count")) > 0L);
        Object providerSerpapi = TraceStore.get("provider.serpapi");
        Object providerTavily = TraceStore.get("provider.tavily");
        String serpapiState = providerState(providerSerpapi, serpapiSkipped, false);
        String tavilyState = providerState(providerTavily, tavilySkipped, false);
        Object serpapiSummaryState = providerSerpapi != null || serpapiSkipped ? serpapiState : null;
        Object tavilySummaryState = providerTavily != null || tavilySkipped ? tavilyState : null;
        kpi.put("provider.naver", naverState);
        kpi.put("provider.brave", braveState);
        kpi.put("provider.serpapi", serpapiState);
        kpi.put("provider.tavily", tavilyState);
        kpi.put("web.naver.skipped.reason", safeStr(TraceStore.get("web.naver.skipped.reason")));
        kpi.put("web.brave.skipped.reason", safeStr(TraceStore.get("web.brave.skipped.reason")));
        kpi.put("web.serpapi.skipped.reason", safeStr(TraceStore.get("web.serpapi.skipped.reason")));
        kpi.put("web.tavily.skipped.reason", safeStr(TraceStore.get("web.tavily.skipped.reason")));
        Map<String, Object> providerTrace = TraceStore.getAll();
        putProviderTaxonomy(kpi, "web.naver", providerTrace);
        putProviderTaxonomy(kpi, "web.brave", providerTrace);
        putProviderTaxonomy(kpi, "web.serpapi", providerTrace);
        putProviderTaxonomy(kpi, "web.tavily", providerTrace);
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("brave", braveState);
        provider.put("naver", naverState);
        provider.put("serpapi", serpapiState);
        provider.put("tavily", tavilyState);
        kpi.put("provider", provider);
        kpi.put("providerStatus", ProviderStateNormalizer.summary(
                naverState,
                braveState,
                serpapiSummaryState,
                tavilySummaryState));
        kpi.put("outCount", outCount);
        kpi.put("rawInputCount", rawInputCount);
        Object stageCounts = firstNonNull(
                TraceStore.get("stageCountsSelectedFromOut"),
                TraceStore.get("stageCountsSelectedFromOut.last"),
                TraceStore.get("web.failsoft.stageCountsSelectedFromOut"),
                TraceStore.get("web.failsoft.stageCountsSelectedFromOut.last"));
        kpi.put("stageCountsSelectedFromOut", stageCounts instanceof Map<?, ?> ? stageCounts : Collections.emptyMap());
        kpi.put("cacheOnly.merged.count", safeLong(firstNonNull(
                TraceStore.get("cacheOnly.merged.count"),
                TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count"))));
        int tracePoolItemCount = TraceStore.getPoolItems().size();
        kpi.put("tracePool.size", Math.max(tracePoolItemCount, safeLong(firstNonNull(
                TraceStore.get("tracePool.size"),
                TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size")))));
        kpi.put("rescueMerge.used", tracePoolItemCount > 0 || safeBoolean(firstNonNull(
                TraceStore.get("rescueMerge.used"),
                TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used"))));
        kpi.put("vectorFallback.used", safeBoolean(firstNonNull(
                TraceStore.get("vectorFallback.used"),
                TraceStore.get("retrieval.vectorFallback.used"))));
        kpi.put("vectorFallback.reason", safeStr(firstNonNull(
                TraceStore.get("vectorFallback.reason"),
                TraceStore.get("retrieval.vectorFallback.reason"))));
        kpi.put("vectorFallback.effectiveTopK", safeLong(firstNonNull(
                TraceStore.get("vectorFallback.effectiveTopK"),
                TraceStore.get("retrieval.vectorFallback.effectiveTopK"))));
        kpi.put("starvationFallback.trigger", ecosystemFallbackTrigger(firstNonNull(
                TraceStore.get("starvationFallback.trigger"),
                TraceStore.get("web.failsoft.starvationFallback.trigger"),
                TraceStore.get("web.failsoft.starvationFallback"))));
        kpi.put("starvationFallback.used", safeBoolean(firstNonNull(
                TraceStore.get("starvationFallback.used"),
                TraceStore.get("web.failsoft.starvationFallback.used"))));
        kpi.put("starvationFallback.poolUsed", safeStr(firstNonNull(
                TraceStore.get("starvationFallback.poolUsed"),
                TraceStore.get("web.failsoft.starvationFallback.poolUsed"))));
        kpi.put("starvationFallback.pool.safe.size", safeLong(firstNonNull(
                TraceStore.get("starvationFallback.pool.safe.size"),
                TraceStore.get("web.failsoft.starvationFallback.pool.safe.size"))));
        kpi.put("starvationFallback.pool.dev.size", safeLong(firstNonNull(
                TraceStore.get("starvationFallback.pool.dev.size"),
                TraceStore.get("web.failsoft.starvationFallback.pool.dev.size"))));
        kpi.put("starvationFallback.count", safeLong(firstNonNull(
                TraceStore.get("starvationFallback.count"),
                TraceStore.get("web.failsoft.starvationFallback.count"))));
        kpi.put("starvationFallback.added", safeLong(firstNonNull(
                TraceStore.get("starvationFallback.added"),
                TraceStore.get("web.failsoft.starvationFallback.added"))));
        kpi.put("poolSafeEmpty", ecosystemPoolSafeEmpty(firstNonNull(
                TraceStore.get("poolSafeEmpty"),
                TraceStore.get("starvationFallback.poolSafeEmpty"),
                TraceStore.get("web.failsoft.starvationFallback.poolSafeEmpty"))));
        putEcosystemKpi(kpi);
        boolean allSkipped = ProviderStateNormalizer.allSkipped(
                naverState,
                braveState,
                serpapiSummaryState,
                tavilySummaryState);
        kpi.put("kpi.next.allSkipped", allSkipped);
        kpi.put("kpi.next.allSkipped.cacheOnlyRescueMissing", allSkipped && !cacheOnlyUsed);
        kpi.put("probe.websoakKpi.runOnce.failed",
                safeBoolean(TraceStore.get("probe.websoakKpi.runOnce.failed")));
        kpi.put("probe.websoakKpi.runOnce.failureType",
                safeStr(TraceStore.get("probe.websoakKpi.runOnce.failureType")));
        kpi.put("probe.websoakKpi.runOnce.messageHash",
                safeStr(TraceStore.get("probe.websoakKpi.runOnce.messageHash")));
        kpi.put("probe.websoakKpi.runOnce.messageLength",
                safeLong(TraceStore.get("probe.websoakKpi.runOnce.messageLength")));
        return kpi;
    }

    private static void recordRunOnceFailure(Throwable err) {
        String message = err == null ? null : err.getMessage();
        TraceStore.put("probe.websoakKpi.runOnce.failed", true);
        TraceStore.put("probe.websoakKpi.runOnce.failureType", sampleFailureClass(err));
        TraceStore.put("probe.websoakKpi.runOnce.messageHash", SafeRedactor.hashValue(message));
        TraceStore.put("probe.websoakKpi.runOnce.messageLength", message == null ? 0 : message.length());
    }

    private static void putEcosystemKpi(Map<String, Object> kpi) {
        kpi.put("ecosystem.recirculate.used", safeBoolean(TraceStore.get("ecosystem.recirculate.used")));
        kpi.put("ecosystem.recirculate.count", safeLong(TraceStore.get("ecosystem.recirculate.count")));
        kpi.put("ecosystem.recirculate.safe", safeLong(TraceStore.get("ecosystem.recirculate.safe")));
        kpi.put("ecosystem.recirculate.allUnverified",
                safeBoolean(TraceStore.get("ecosystem.recirculate.allUnverified")));
        kpi.put("ecosystem.pool.size", safeLong(TraceStore.get("ecosystem.pool.size")));
        kpi.put("ecosystem.recycled.total", safeLong(TraceStore.get("ecosystem.recycled.total")));
        kpi.put("ecosystem.ammonia.score", safeStr(TraceStore.get("ecosystem.ammonia.score")));
        kpi.put("ecosystem.ammonia.quarantined", safeLong(TraceStore.get("ecosystem.ammonia.quarantined")));
        kpi.put("ecosystem.ammonia.safe", safeLong(TraceStore.get("ecosystem.ammonia.safe")));
        kpi.put("ecosystem.ammonia.threshold", safeStr(TraceStore.get("ecosystem.ammonia.threshold")));
        kpi.put("ecosystem.ammonia.surgeBlocked", safeBoolean(TraceStore.get("ecosystem.ammonia.surgeBlocked")));
    }

    private static String providerState(Object explicit, boolean skipped, boolean cacheOnly) {
        return ProviderStateNormalizer.state(explicit, skipped, cacheOnly);
    }

    private static void putProviderTaxonomy(
            Map<String, Object> kpi,
            String prefix,
            Map<String, Object> trace) {
        kpi.put(prefix + ".traceObserved", hasProviderTrace(trace, prefix));
        kpi.put(prefix + ".providerDisabled", providerBoolean(trace, prefix + ".providerDisabled"));
        kpi.put(prefix + ".disabledReason", providerString(trace, prefix + ".disabledReason"));
        kpi.put(prefix + ".failureReason", providerString(trace, prefix + ".failureReason"));
        kpi.put(prefix + ".requestedCount", providerLong(trace, prefix + ".requestedCount"));
        kpi.put(prefix + ".returnedCount", providerLong(trace, prefix + ".returnedCount"));
        kpi.put(prefix + ".afterFilterCount", providerLong(trace, prefix + ".afterFilterCount"));
        kpi.put(prefix + ".providerEmpty", providerBoolean(trace, prefix + ".providerEmpty"));
        kpi.put(prefix + ".afterFilterStarved", providerBoolean(trace, prefix + ".afterFilterStarved"));
        kpi.put(prefix + ".timeout", providerBoolean(trace, prefix + ".timeout"));
        kpi.put(prefix + ".timeoutMs", providerLong(trace, prefix + ".timeoutMs"));
        kpi.put(prefix + ".rateLimited", providerBoolean(trace, prefix + ".rateLimited"));
        kpi.put(prefix + ".retryAfterMs", providerLong(trace, prefix + ".retryAfterMs"));
        kpi.put(prefix + ".cancelled", providerBoolean(trace, prefix + ".cancelled"));
        kpi.put(prefix + ".exceptionType", providerString(trace, prefix + ".exceptionType"));
    }

    private static boolean hasProviderTrace(Map<String, Object> trace, String prefix) {
        if (trace == null || prefix == null || prefix.isBlank()) {
            return false;
        }
        String providerName = prefix.startsWith("web.") ? prefix.substring("web.".length()) : prefix;
        if (trace.containsKey("provider." + providerName)) {
            return true;
        }
        String namespace = prefix + ".";
        for (String key : trace.keySet()) {
            if (key != null && key.startsWith(namespace)) {
                return true;
            }
        }
        return false;
    }

    private static String providerString(Map<String, Object> trace, String key) {
        Object value = trace == null ? null : trace.get(key);
        if (value == null) {
            return null;
        }
        String safeValue = safeStr(value);
        return hasText(safeValue) ? safeValue : null;
    }

    private static Long providerLong(Map<String, Object> trace, String key) {
        Object value = trace == null ? null : trace.get(key);
        if (!(value instanceof Number)) {
            return null;
        }
        try {
            long parsed = new java.math.BigDecimal(value.toString()).longValueExact();
            return parsed >= 0L ? parsed : null;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean providerBoolean(Map<String, Object> trace, String key) {
        Object value = trace == null ? null : trace.get(key);
        return value instanceof Boolean bool ? bool : null;
    }

    // =======================
    // Data models
    // =======================

    @Data
    public static class Report {
        private String generatedAt;
        private Integer iterations;
        private Integer topK;
        private Long sleepMsBetween;
        private Boolean dbgSearch;
        private String webPrimary;
        private List<String> queries;
        private String baselineFile;

        private Aggregate baseline;
        private Aggregate current;
        private Comparison delta;

        private String table;
        private List<String> tuningHints;

        private List<Sample> samples;
    }

    @Data
    public static class Sample {
        private Integer seq;
        private String query;
        private Boolean queryPresent;
        private Integer queryLength;
        private String queryHash12;
        private String sessionId;
        private String rid;
        private Long elapsedMs;

        private Long outCount;
        private Long rawInputCount;

        private String naverSkippedReason;
        private String braveSkippedReason;
        private Boolean braveSkipped;
        private String serpapiSkippedReason;
        private String tavilySkippedReason;

        private Long naverHardTimeoutCount;
        private String naverBackoffReason;
        private Long naverBackoffRemainingMs;

        private String braveBackoffReason;
        private Long braveBackoffRemainingMs;

        private String serpapiBackoffReason;
        private Long serpapiBackoffRemainingMs;

        private String tavilyBackoffReason;
        private Long tavilyBackoffRemainingMs;

        private Boolean hybridEmptyTriggered;
        private Map<String, Object> kpi;

        private String error;
    }

    @Data
    public static class Aggregate {
        private Long calls;

        private Long braveSkippedCount;
        private Long braveDisabledCount;
        private Long naverHardTimeoutTotal;

        private Long outZeroCount;
        private Integer outZeroMaxStreak;

        private Long mergedZeroCount;
        private Integer mergedZeroMaxStreak;

        private Long naverAwaitTimeoutBackoffCount;
        private Long providerAwaitTimeoutBackoffCount;

        public static Aggregate fromSamples(List<Sample> samples) {
            Aggregate a = new Aggregate();
            if (samples == null) samples = Collections.emptyList();

            long calls = samples.size();
            long braveSkipped = 0;
            long braveDisabled = 0;
            long naverHardTimeoutTotal = 0;
            long outZero = 0;
            int outZeroMax = 0;
            int outZeroCur = 0;
            long mergedZero = 0;
            int mergedZeroMax = 0;
            int mergedZeroCur = 0;
            long naverAwaitTimeoutBackoff = 0;
            long providerAwaitTimeoutBackoff = 0;

            for (Sample s : samples) {
                long outCount = s != null && s.getOutCount() != null ? s.getOutCount() : 0L;
                long rawInput = s != null && s.getRawInputCount() != null ? s.getRawInputCount() : 0L;

                String braveReason = safeTrim(s != null ? s.getBraveSkippedReason() : null, 80);
                if ((s != null && Boolean.TRUE.equals(s.getBraveSkipped())) || hasText(braveReason)) {
                    braveSkipped++;
                }
                if ("disabled".equalsIgnoreCase(safeTrim(braveReason, 32))) {
                    braveDisabled++;
                }

                long nht = (s != null && s.getNaverHardTimeoutCount() != null) ? s.getNaverHardTimeoutCount() : 0L;
                naverHardTimeoutTotal += Math.max(0L, nht);

                if (outCount == 0L) {
                    outZero++;
                    outZeroCur++;
                    outZeroMax = Math.max(outZeroMax, outZeroCur);
                } else {
                    outZeroCur = 0;
                }

                if (rawInput == 0L) {
                    mergedZero++;
                    mergedZeroCur++;
                    mergedZeroMax = Math.max(mergedZeroMax, mergedZeroCur);
                } else {
                    mergedZeroCur = 0;
                }

                if ("await_timeout".equalsIgnoreCase(safeTrim(s != null ? s.getNaverBackoffReason() : null, 40))) {
                    naverAwaitTimeoutBackoff++;
                    providerAwaitTimeoutBackoff++;
                }
                if ("await_timeout".equalsIgnoreCase(safeTrim(s != null ? s.getBraveBackoffReason() : null, 40))) {
                    providerAwaitTimeoutBackoff++;
                }
                if ("await_timeout".equalsIgnoreCase(safeTrim(s != null ? s.getSerpapiBackoffReason() : null, 40))) {
                    providerAwaitTimeoutBackoff++;
                }
                if ("await_timeout".equalsIgnoreCase(safeTrim(s != null ? s.getTavilyBackoffReason() : null, 40))) {
                    providerAwaitTimeoutBackoff++;
                }
            }

            a.calls = calls;
            a.braveSkippedCount = braveSkipped;
            a.braveDisabledCount = braveDisabled;
            a.naverHardTimeoutTotal = naverHardTimeoutTotal;
            a.outZeroCount = outZero;
            a.outZeroMaxStreak = outZeroMax;
            a.mergedZeroCount = mergedZero;
            a.mergedZeroMaxStreak = mergedZeroMax;
            a.naverAwaitTimeoutBackoffCount = naverAwaitTimeoutBackoff;
            a.providerAwaitTimeoutBackoffCount = providerAwaitTimeoutBackoff;
            return a;
        }
    }

    @Data
    public static class Comparison {
        private Long braveSkippedDelta;
        private Long braveDisabledDelta;
        private Long naverHardTimeoutDelta;
        private Long outZeroDelta;
        private Integer outZeroMaxStreakDelta;
        private Long mergedZeroDelta;
        private Integer mergedZeroMaxStreakDelta;
        private Long naverAwaitTimeoutBackoffDelta;
        private Long providerAwaitTimeoutBackoffDelta;

        static Comparison compare(Aggregate base, Aggregate cur) {
            Comparison c = new Comparison();
            c.braveSkippedDelta = deltaLong(base != null ? base.braveSkippedCount : null, cur != null ? cur.braveSkippedCount : null);
            c.braveDisabledDelta = deltaLong(base != null ? base.braveDisabledCount : null, cur != null ? cur.braveDisabledCount : null);
            c.naverHardTimeoutDelta = deltaLong(base != null ? base.naverHardTimeoutTotal : null, cur != null ? cur.naverHardTimeoutTotal : null);
            c.outZeroDelta = deltaLong(base != null ? base.outZeroCount : null, cur != null ? cur.outZeroCount : null);
            c.outZeroMaxStreakDelta = deltaInt(base != null ? base.outZeroMaxStreak : null, cur != null ? cur.outZeroMaxStreak : null);
            c.mergedZeroDelta = deltaLong(base != null ? base.mergedZeroCount : null, cur != null ? cur.mergedZeroCount : null);
            c.mergedZeroMaxStreakDelta = deltaInt(base != null ? base.mergedZeroMaxStreak : null, cur != null ? cur.mergedZeroMaxStreak : null);
            c.naverAwaitTimeoutBackoffDelta = deltaLong(base != null ? base.naverAwaitTimeoutBackoffCount : null, cur != null ? cur.naverAwaitTimeoutBackoffCount : null);
            c.providerAwaitTimeoutBackoffDelta = deltaLong(base != null ? base.providerAwaitTimeoutBackoffCount : null, cur != null ? cur.providerAwaitTimeoutBackoffCount : null);
            return c;
        }
    }

    private static class Baseline {
        private List<String> queries = new ArrayList<>();
        private Aggregate aggregate;
    }

    private static class BaselineParser {
        private static final Pattern Q = Pattern.compile("Search Trace\\s*-\\s*query:\\s*(.+)$");
        private static final Pattern JSON_LIKE = Pattern.compile("^\\s*\\{.*\\}\\s*$");

        static Baseline parse(File f) {
            Baseline b = new Baseline();

            List<Long> outCounts = new ArrayList<>();
            List<Long> rawInputCounts = new ArrayList<>();
            long braveSkippedFromCounter = -1L;
            long naverHardTimeoutFromCounter = -1L;
            long naverAwaitTimeoutBackoffFromCounter = -1L;
            long providerAwaitTimeoutBackoffFromCounter = -1L;

            try (BufferedReader br = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line == null) continue;
                    String ln = line.trim();
                    if (ln.isEmpty()) continue;

                    Matcher m = Q.matcher(ln);
                    if (m.find()) {
                        String q = safeTrim(m.group(1), 400);
                        if (q != null && !q.isBlank() && b.queries.size() < 50) {
                            b.queries.add(q);
                        }
                        continue;
                    }

                    // JSON line: best effort parse
                    if (JSON_LIKE.matcher(ln).matches() && ln.contains("\"outCount\"")) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> j = new ObjectMapper().readValue(ln, Map.class);
                            Object oc = j.get("outCount");
                            Object ric = j.get("rawInputCount");
                            if (oc != null) outCounts.add(safeLong(oc));
                            if (ric != null) rawInputCounts.add(safeLong(ric));
                        } catch (Exception ignore) {
                            log.debug("[WebSoakKPI] baseline json line skipped lineHash={} lineLength={}",
                                    SafeRedactor.hashValue(ln), ln == null ? 0 : ln.length());
                        }
                        continue;
                    }

                    // key-value counters (tab or '=')
                    int tab = ln.indexOf('\t');
                    int eq = ln.indexOf('=');
                    String k = null;
                    String v = null;
                    if (tab > 0) {
                        k = ln.substring(0, tab).trim();
                        v = ln.substring(tab + 1).trim();
                    } else if (eq > 0) {
                        k = ln.substring(0, eq).trim();
                        v = ln.substring(eq + 1).trim();
                    }
                    if (k == null || v == null) continue;

                    if ("web.failsoft.outCount".equals(k)) {
                        outCounts.add(parseLongSafe(v));
                    } else if ("web.failsoft.rawInputCount".equals(k)) {
                        rawInputCounts.add(parseLongSafe(v));
                    } else if ("web.brave.skipped.count".equals(k)) {
                        braveSkippedFromCounter = Math.max(braveSkippedFromCounter, parseLongSafe(v));
                    } else if ("web.await.events.timeout.hard.count".equals(k)
                            || "web.await.events.summary.timeout.hard.count".equals(k)
                            || "web.await.events.summary.engine.Naver.cause.timeout_hard.count".equals(k)) {
                        naverHardTimeoutFromCounter = Math.max(naverHardTimeoutFromCounter, parseLongSafe(v));
                    } else if ("web.failsoft.rateLimitBackoff.naver.reason".equals(k)) {
                        // no-op (reason is string)
                    } else if ("web.failsoft.rateLimitBackoff.naver.reason.await_timeout.count".equals(k)) {
                        naverAwaitTimeoutBackoffFromCounter = Math.max(naverAwaitTimeoutBackoffFromCounter, parseLongSafe(v));
                    } else if ("web.failsoft.rateLimitBackoff.awaitTimeoutReconciledApplyTimes".equals(k)
                            || "web.failsoft.rateLimitBackoff.provider.reason.await_timeout.count".equals(k)) {
                        providerAwaitTimeoutBackoffFromCounter = Math.max(providerAwaitTimeoutBackoffFromCounter,
                                parseLongSafe(v));
                    }
                }
            } catch (Exception e) {
                log.warn("[WebSoakKPI] baseline parse failed. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            }

            Aggregate a = new Aggregate();
            // For baseline, if we have per-run series -> compute streaks from series.
            if (!outCounts.isEmpty() || !rawInputCounts.isEmpty()) {
                List<Sample> samples = new ArrayList<>();
                int n = Math.max(outCounts.size(), rawInputCounts.size());
                for (int i = 0; i < n; i++) {
                    Sample s = new Sample();
                    s.outCount = (i < outCounts.size()) ? outCounts.get(i) : 0L;
                    s.rawInputCount = (i < rawInputCounts.size()) ? rawInputCounts.get(i) : 0L;
                    s.braveSkippedReason = ""; // unknown; may be overridden by counters
                    s.braveSkipped = false;
                    s.naverHardTimeoutCount = 0L;
                    s.naverBackoffReason = "";
                    samples.add(s);
                }
                a = Aggregate.fromSamples(samples);
            }

            // Counter-based overrides (log counters are often monotonic)
            if (braveSkippedFromCounter >= 0) {
                a.braveSkippedCount = braveSkippedFromCounter;
            }
            if (naverHardTimeoutFromCounter >= 0) {
                a.naverHardTimeoutTotal = naverHardTimeoutFromCounter;
            }
            if (naverAwaitTimeoutBackoffFromCounter >= 0) {
                a.naverAwaitTimeoutBackoffCount = naverAwaitTimeoutBackoffFromCounter;
            }
            if (providerAwaitTimeoutBackoffFromCounter >= 0) {
                a.providerAwaitTimeoutBackoffCount = providerAwaitTimeoutBackoffFromCounter;
            }

            b.aggregate = a;
            return b;
        }

        private static long parseLongSafe(String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                log.debug("[WebSoakKPI] numeric parse fallback stage={} errorType={}",
                        "service.baseline.parseLongSafe", "invalid_number");
                return 0L;
            }
        }
    }

    private static class TableRenderer {
        static String render(Aggregate base, Aggregate cur, Comparison d, int iterations, long elapsedMs) {
            StringBuilder sb = new StringBuilder();
            sb.append("[WebSoakKPI] baseline vs current\n");
            sb.append("  calls=").append(iterations).append(", elapsedMs=").append(elapsedMs).append("\n\n");

            sb.append(String.format(Locale.ROOT, "%-38s %12s %12s %12s\n",
                    "metric", "baseline", "current", "delta"));
            sb.append(String.format(Locale.ROOT, "%-38s %12s %12s %12s\n",
                    "--------------------------------------", "----------", "----------", "----------"));

            sb.append(row("web.brave.skipped (any reason)",
                    base != null ? base.braveSkippedCount : null,
                    cur != null ? cur.braveSkippedCount : null,
                    d != null ? d.braveSkippedDelta : null));

            sb.append(row("[Naver] Hard Timeout (total)",
                    base != null ? base.naverHardTimeoutTotal : null,
                    cur != null ? cur.naverHardTimeoutTotal : null,
                    d != null ? d.naverHardTimeoutDelta : null));

            sb.append(row("outCount==0 (calls)",
                    base != null ? base.outZeroCount : null,
                    cur != null ? cur.outZeroCount : null,
                    d != null ? d.outZeroDelta : null));

            sb.append(rowInt("outCount==0 (max streak)",
                    base != null ? base.outZeroMaxStreak : null,
                    cur != null ? cur.outZeroMaxStreak : null,
                    d != null ? d.outZeroMaxStreakDelta : null));

            sb.append(row("merged(rawInput)==0 (calls)",
                    base != null ? base.mergedZeroCount : null,
                    cur != null ? cur.mergedZeroCount : null,
                    d != null ? d.mergedZeroDelta : null));

            sb.append(rowInt("merged(rawInput)==0 (max streak)",
                    base != null ? base.mergedZeroMaxStreak : null,
                    cur != null ? cur.mergedZeroMaxStreak : null,
                    d != null ? d.mergedZeroMaxStreakDelta : null));

            sb.append(row("ProviderRateLimitBackoff (naver await_timeout)",
                    base != null ? base.naverAwaitTimeoutBackoffCount : null,
                    cur != null ? cur.naverAwaitTimeoutBackoffCount : null,
                    d != null ? d.naverAwaitTimeoutBackoffDelta : null));
            sb.append(row("ProviderBackoff (any await_timeout)",
                    base != null ? base.providerAwaitTimeoutBackoffCount : null,
                    cur != null ? cur.providerAwaitTimeoutBackoffCount : null,
                    d != null ? d.providerAwaitTimeoutBackoffDelta : null));

            sb.append("\nTargets:\n");
            sb.append(" - web.brave.skipped (any reason) -> 0\n");
            sb.append(" - Naver hard timeout total -> down\n");
            sb.append(" - Provider await_timeout backoff -> 0\n");
            sb.append(" - outCount/rawInput=0 streak -> down\n");
            return sb.toString();
        }

        private static String row(String metric, Long base, Long cur, Long delta) {
            return String.format(Locale.ROOT, "%-38s %12s %12s %12s\n",
                    safeMetric(metric),
                    fmtLong(base),
                    fmtLong(cur),
                    fmtLong(delta));
        }

        private static String rowInt(String metric, Integer base, Integer cur, Integer delta) {
            return String.format(Locale.ROOT, "%-38s %12s %12s %12s\n",
                    safeMetric(metric),
                    fmtInt(base),
                    fmtInt(cur),
                    fmtInt(delta));
        }

        private static String safeMetric(String s) {
            if (s == null) return "";
            if (s.length() > 38) return s.substring(0, 38);
            return s;
        }

        private static String fmtLong(Long v) {
            return v == null ? "n/a" : String.valueOf(v);
        }

        private static String fmtInt(Integer v) {
            return v == null ? "n/a" : String.valueOf(v);
        }
    }

    private static class TuningHints {
        static List<String> suggest(Environment env, Aggregate cur) {
            if (cur == null) return Collections.emptyList();

            List<String> out = new ArrayList<>();

            long naverAwaitTimeoutBackoff = cur.naverAwaitTimeoutBackoffCount != null ? cur.naverAwaitTimeoutBackoffCount : 0L;
            long awaitTimeoutBackoff = cur.providerAwaitTimeoutBackoffCount != null
                    ? cur.providerAwaitTimeoutBackoffCount
                    : naverAwaitTimeoutBackoff;
            long hardTimeout = cur.naverHardTimeoutTotal != null ? cur.naverHardTimeoutTotal : 0L;

            long calls = cur.calls != null ? cur.calls : 0L;
            double awaitTimeoutRatio = calls > 0 ? (awaitTimeoutBackoff / (double) calls) : 0.0;

            String naverTimeoutMs = env.getProperty("naver.search.timeout-ms", "");
            String hybridTimeoutSec = env.getProperty("gpt-search.hybrid.timeout-sec", "");
            String naverRetryMax = env.getProperty("naver.search.retry.max-attempts", "");

            if (awaitTimeoutBackoff > 0 || hardTimeout > 0) {
                out.add(naverAwaitTimeoutBackoff > 0 || hardTimeout > 0
                        ? "Detected Naver timeouts/backoff. Consider tuning:"
                        : "Provider await_timeout backoff detected. Inspect provider cooldown settings:");
                out.add(" - naver.search.timeout-ms: 6000~6500 (current=" + (naverTimeoutMs.isBlank() ? "default" : naverTimeoutMs) + ")");
                out.add(" - gpt-search.hybrid.timeout-sec: 8 (current=" + (hybridTimeoutSec.isBlank() ? "default" : hybridTimeoutSec) + ")");
                out.add(" - OR set naver.search.retry.max-attempts=0 (single-shot) and increase timeout (current=" + (naverRetryMax.isBlank() ? "default" : naverRetryMax) + ")");
            }

            if (awaitTimeoutRatio >= 0.30) {
                out.add("Naver await_timeout ratio is high (~" + String.format(Locale.ROOT, "%.0f%%", awaitTimeoutRatio * 100.0) + ").");
                out.add(" - If acceptable, prefer provider fallback (BRAVE→NAVER) + longer timeout over multiple retries.");
            }

            return out;
        }
    }

    // =======================
    // Utils
    // =======================

    private static String safeStr(Object v) {
        if (v == null) return "";
        return SafeRedactor.traceLabel(v);
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static String safeTrigger(Object v) {
        String raw = v == null ? "" : String.valueOf(v).trim();
        if (raw.matches("[A-Za-z0-9_.:-]{1,80}(->[A-Za-z0-9_.:-]{1,80})?")) {
            return raw;
        }
        return SafeRedactor.traceLabel(v);
    }

    private static String ecosystemFallbackTrigger(Object trigger) {
        if (safeBoolean(TraceStore.get("ecosystem.recirculate.used"))
                && safeLong(TraceStore.get("ecosystem.recirculate.safe")) > 0L) {
            return "ecosystem->NOFILTER_SAFE";
        }
        String safe = safeTrigger(trigger);
        if (safe != null && !safe.isBlank()) {
            return safe;
        }
        return safe == null ? "" : safe;
    }

    private static boolean ecosystemPoolSafeEmpty(Object poolSafeEmpty) {
        if (safeBoolean(TraceStore.get("ecosystem.recirculate.used"))
                && safeLong(TraceStore.get("ecosystem.recirculate.safe")) > 0L) {
            return false;
        }
        return safeBoolean(poolSafeEmpty);
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (max > 0 && t.length() > max) {
            return t.substring(0, max);
        }
        return t;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static long safeLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? n.longValue() : 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ignore) {
            log.debug("[WebSoakKPI] numeric parse fallback stage={} errorType={}",
                    "service.safeLong", "invalid_number");
            return 0L;
        }
    }

    private static boolean safeBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) && n.longValue() != 0L;
        }
        String s = v == null ? "" : String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static Object firstNonNull(Object... vs) {
        if (vs == null) return null;
        for (Object v : vs) {
            if (v != null) return v;
        }
        return null;
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clampLong(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int firstInt(Integer a, Integer b) {
        return a != null ? a : (b != null ? b : 0);
    }

    private static long firstLong(Long a, Long b) {
        return a != null ? a : (b != null ? b : 0L);
    }

    private static boolean firstBool(Boolean a, boolean b) {
        return a != null ? a : b;
    }

    private static boolean firstBool(Boolean a, Boolean b) {
        return a != null ? a : (b != null ? b : false);
    }

    private static String firstNonBlank(String... vs) {
        if (vs == null) return null;
        for (String v : vs) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private static Long deltaLong(Long base, Long cur) {
        if (base == null || cur == null) return null;
        return cur - base;
    }

    private static Integer deltaInt(Integer base, Integer cur) {
        if (base == null || cur == null) return null;
        return cur - base;
    }
}
