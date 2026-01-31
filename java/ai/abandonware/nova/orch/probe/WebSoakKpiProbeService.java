package ai.abandonware.nova.orch.probe;

import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
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
 * - web.brave.skipped.reason=disabled -> should converge to 0
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
                log.warn("[WebSoakKPI] baseline file not found: {}", f.getAbsolutePath());
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
        report.queries = queries;
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

        TraceContext.attach(sessionId, rid);

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
            err = t;
            out = Collections.emptyList();
        } finally {
            GuardContextHolder.clear();
        }

        long elapsedMs = System.currentTimeMillis() - begin;

        // Snapshot minimal KPI from TraceStore
        Sample s = new Sample();
        s.seq = seq;
        s.query = query;
        s.elapsedMs = elapsedMs;
        s.outCount = safeLong(TraceStore.get("web.failsoft.outCount"));
        s.rawInputCount = safeLong(TraceStore.get("web.failsoft.rawInputCount"));
        s.braveSkippedReason = safeStr(TraceStore.get("web.brave.skipped.reason"));
        s.naverHardTimeoutCount = safeLong(firstNonNull(
                TraceStore.get("web.await.events.summary.engine.Naver.cause.timeout_hard.count"),
                TraceStore.get("web.await.events.summary.engine.Naver.cause.await_timeout.count"),
                TraceStore.get("web.await.events.summary.engine.Naver.cause.timeout.count"),
                TraceStore.get("web.await.events.summary.timeout.hard.count"),
                TraceStore.get("web.await.events.timeout.hard.count")
        ));
        s.naverBackoffReason = safeStr(TraceStore.get("web.failsoft.rateLimitBackoff.naver.reason"));
        s.braveBackoffReason = safeStr(TraceStore.get("web.failsoft.rateLimitBackoff.brave.reason"));
        s.naverBackoffRemainingMs = safeLong(TraceStore.get("web.failsoft.rateLimitBackoff.naver.remainingMs"));
        s.braveBackoffRemainingMs = safeLong(TraceStore.get("web.failsoft.rateLimitBackoff.brave.remainingMs"));

        Object trig = TraceStore.get("web.failsoft.hybridEmptyFallback.triggeredBy");
        s.hybridEmptyTriggered = trig != null;

        s.error = (err == null) ? "" : err.getClass().getSimpleName() + ": " + safeTrim(err.getMessage(), 140);

        // cleanup
        TraceStore.clear();
        MDC.clear();

        return s;
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
        private Long elapsedMs;

        private Long outCount;
        private Long rawInputCount;

        private String braveSkippedReason;

        private Long naverHardTimeoutCount;
        private String naverBackoffReason;
        private Long naverBackoffRemainingMs;

        private String braveBackoffReason;
        private Long braveBackoffRemainingMs;

        private Boolean hybridEmptyTriggered;

        private String error;
    }

    @Data
    public static class Aggregate {
        private Long calls;

        private Long braveDisabledCount;
        private Long naverHardTimeoutTotal;

        private Long outZeroCount;
        private Integer outZeroMaxStreak;

        private Long mergedZeroCount;
        private Integer mergedZeroMaxStreak;

        private Long naverAwaitTimeoutBackoffCount;

        public static Aggregate fromSamples(List<Sample> samples) {
            Aggregate a = new Aggregate();
            if (samples == null) samples = Collections.emptyList();

            long calls = samples.size();
            long braveDisabled = 0;
            long naverHardTimeoutTotal = 0;
            long outZero = 0;
            int outZeroMax = 0;
            int outZeroCur = 0;
            long mergedZero = 0;
            int mergedZeroMax = 0;
            int mergedZeroCur = 0;
            long naverAwaitTimeoutBackoff = 0;

            for (Sample s : samples) {
                long outCount = s != null && s.getOutCount() != null ? s.getOutCount() : 0L;
                long rawInput = s != null && s.getRawInputCount() != null ? s.getRawInputCount() : 0L;

                if ("disabled".equalsIgnoreCase(safeTrim(s != null ? s.getBraveSkippedReason() : null, 32))) {
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
                }
            }

            a.calls = calls;
            a.braveDisabledCount = braveDisabled;
            a.naverHardTimeoutTotal = naverHardTimeoutTotal;
            a.outZeroCount = outZero;
            a.outZeroMaxStreak = outZeroMax;
            a.mergedZeroCount = mergedZero;
            a.mergedZeroMaxStreak = mergedZeroMax;
            a.naverAwaitTimeoutBackoffCount = naverAwaitTimeoutBackoff;
            return a;
        }
    }

    @Data
    public static class Comparison {
        private Long braveDisabledDelta;
        private Long naverHardTimeoutDelta;
        private Long outZeroDelta;
        private Integer outZeroMaxStreakDelta;
        private Long mergedZeroDelta;
        private Integer mergedZeroMaxStreakDelta;
        private Long naverAwaitTimeoutBackoffDelta;

        static Comparison compare(Aggregate base, Aggregate cur) {
            Comparison c = new Comparison();
            c.braveDisabledDelta = deltaLong(base != null ? base.braveDisabledCount : null, cur != null ? cur.braveDisabledCount : null);
            c.naverHardTimeoutDelta = deltaLong(base != null ? base.naverHardTimeoutTotal : null, cur != null ? cur.naverHardTimeoutTotal : null);
            c.outZeroDelta = deltaLong(base != null ? base.outZeroCount : null, cur != null ? cur.outZeroCount : null);
            c.outZeroMaxStreakDelta = deltaInt(base != null ? base.outZeroMaxStreak : null, cur != null ? cur.outZeroMaxStreak : null);
            c.mergedZeroDelta = deltaLong(base != null ? base.mergedZeroCount : null, cur != null ? cur.mergedZeroCount : null);
            c.mergedZeroMaxStreakDelta = deltaInt(base != null ? base.mergedZeroMaxStreak : null, cur != null ? cur.mergedZeroMaxStreak : null);
            c.naverAwaitTimeoutBackoffDelta = deltaLong(base != null ? base.naverAwaitTimeoutBackoffCount : null, cur != null ? cur.naverAwaitTimeoutBackoffCount : null);
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
            long braveDisabledFromCounter = -1L;
            long naverHardTimeoutFromCounter = -1L;
            long naverAwaitTimeoutBackoffFromCounter = -1L;

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
                            // ignore
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
                        braveDisabledFromCounter = Math.max(braveDisabledFromCounter, parseLongSafe(v));
                    } else if ("web.await.events.timeout.hard.count".equals(k)
                            || "web.await.events.summary.timeout.hard.count".equals(k)
                            || "web.await.events.summary.engine.Naver.cause.timeout_hard.count".equals(k)) {
                        naverHardTimeoutFromCounter = Math.max(naverHardTimeoutFromCounter, parseLongSafe(v));
                    } else if ("web.failsoft.rateLimitBackoff.naver.reason".equals(k)) {
                        // no-op (reason is string)
                    } else if ("web.failsoft.rateLimitBackoff.naver.reason.await_timeout.count".equals(k)) {
                        naverAwaitTimeoutBackoffFromCounter = Math.max(naverAwaitTimeoutBackoffFromCounter, parseLongSafe(v));
                    }
                }
            } catch (Exception e) {
                log.warn("[WebSoakKPI] baseline parse failed: {}", e.getMessage());
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
                    s.naverHardTimeoutCount = 0L;
                    s.naverBackoffReason = "";
                    samples.add(s);
                }
                a = Aggregate.fromSamples(samples);
            }

            // Counter-based overrides (log counters are often monotonic)
            if (braveDisabledFromCounter >= 0) {
                a.braveDisabledCount = braveDisabledFromCounter;
            }
            if (naverHardTimeoutFromCounter >= 0) {
                a.naverHardTimeoutTotal = naverHardTimeoutFromCounter;
            }
            if (naverAwaitTimeoutBackoffFromCounter >= 0) {
                a.naverAwaitTimeoutBackoffCount = naverAwaitTimeoutBackoffFromCounter;
            }

            b.aggregate = a;
            return b;
        }

        private static long parseLongSafe(String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (Exception ignore) {
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

            sb.append(row("web.brave.skipped.reason=disabled",
                    base != null ? base.braveDisabledCount : null,
                    cur != null ? cur.braveDisabledCount : null,
                    d != null ? d.braveDisabledDelta : null));

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

            sb.append("\nTargets:\n");
            sb.append(" - web.brave.skipped.reason=disabled -> 0\n");
            sb.append(" - Naver hard timeout total -> down\n");
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

            long awaitTimeoutBackoff = cur.naverAwaitTimeoutBackoffCount != null ? cur.naverAwaitTimeoutBackoffCount : 0L;
            long hardTimeout = cur.naverHardTimeoutTotal != null ? cur.naverHardTimeoutTotal : 0L;

            long calls = cur.calls != null ? cur.calls : 0L;
            double awaitTimeoutRatio = calls > 0 ? (awaitTimeoutBackoff / (double) calls) : 0.0;

            String naverTimeoutMs = env.getProperty("naver.search.timeout-ms", "");
            String hybridTimeoutSec = env.getProperty("gpt-search.hybrid.timeout-sec", "");
            String naverRetryMax = env.getProperty("naver.search.retry.max-attempts", "");

            if (awaitTimeoutBackoff > 0 || hardTimeout > 0) {
                out.add("Detected Naver timeouts/backoff. Consider tuning:");
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
        return String.valueOf(v);
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (max > 0 && t.length() > max) {
            return t.substring(0, max);
        }
        return t;
    }

    private static long safeLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception ignore) {
            return 0L;
        }
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
