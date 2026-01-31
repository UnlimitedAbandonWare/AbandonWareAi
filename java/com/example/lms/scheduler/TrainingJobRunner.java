package com.example.lms.scheduler;

import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.moe.RgbLogSignalParser;
import com.example.lms.moe.RgbMoeProperties;
import com.example.lms.moe.RgbResourceProbe;
import com.example.lms.moe.RgbSoakReport;
import com.example.lms.moe.RgbSoakReportService;
import com.example.lms.moe.RgbStrategySelector;
import com.example.lms.trace.TraceContext;
import com.example.lms.trace.TraceLogger;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline/idle training runner.
 *
 * <p>Policy: BLUE(Gemini) is used only here.</p>
 */
@Component
public class TrainingJobRunner {

    private static final Logger log = LoggerFactory.getLogger(TrainingJobRunner.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final RgbMoeProperties props;
    private final UserIdleDetector idleDetector;
    private final RgbLogSignalParser logParser;
    private final RgbResourceProbe resourceProbe;
    private final RgbStrategySelector selector;
    private final RgbSoakReportService reportService;
    private final AutoEvolveDebugStore debugStore;

    private final ChatModel greenModel;
    private final ObjectProvider<GeminiClient> geminiProvider;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> runningSessionId = new AtomicReference<>();

    public TrainingJobRunner(RgbMoeProperties props,
                             UserIdleDetector idleDetector,
                             RgbLogSignalParser logParser,
                             RgbResourceProbe resourceProbe,
                             RgbStrategySelector selector,
                             RgbSoakReportService reportService,
                             AutoEvolveDebugStore debugStore,
                             @Qualifier("greenChatModel") ChatModel greenModel,
                             ObjectProvider<GeminiClient> geminiProvider) {
        this.props = props;
        this.idleDetector = idleDetector;
        this.logParser = logParser;
        this.resourceProbe = resourceProbe;
        this.selector = selector;
        this.reportService = reportService;
        this.debugStore = debugStore;
        this.greenModel = greenModel;
        this.geminiProvider = geminiProvider;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String currentSessionId() {
        return runningSessionId.get();
    }

    /**
     * Preview current signals/snapshot + strategy scoring without executing the full soak.
     */
    public AutoEvolvePreview preview(boolean requireIdle) {
        if (!props.isEnabled()) {
            throw new IllegalStateException("rgb.moe.enabled=false");
        }

        boolean idleNow = idleDetector.isIdleNow();
        Path logPath = Path.of(props.getLogPath());
        RgbLogSignalParser.Features f = logParser.parse(logPath, props.getLogTailLines(), Duration.ofHours(24));
        RgbResourceProbe.Snapshot r = resourceProbe.snapshot();
        RgbStrategySelector.Decision decision = selector.select(f, r);

        List<String> baseQueries = buildBaseQueries(f);

        boolean willGreen = shouldExpandGreen(decision);
        boolean willBlue = false;
        String blueBlockedReason = null;

        // Provide more actionable reasons for BLUE eligibility (debug UX)
        List<String> blocks = new ArrayList<>();
        if (!props.isBlueEnabled()) {
            blocks.add("blue_disabled_by_config");
        }
        if (props.getBlueMaxCallsPerRun() <= 0) {
            blocks.add("blue_max_calls=0");
        }
        boolean needsBlueByStrategy = decision != null && (
                decision.primaryStrategy() == RgbStrategySelector.Strategy.GB_FALLBACK
                        || decision.primaryStrategy() == RgbStrategySelector.Strategy.RB_ENSEMBLE
                        || decision.primaryStrategy() == RgbStrategySelector.Strategy.RGB_ENSEMBLE
        );
        if (!needsBlueByStrategy) {
            blocks.add("blue_not_required_by_strategy");
        }
        if (r == null) {
            blocks.add("no_resource_snapshot");
        } else if (!r.blueHealthy()) {
            if (r.blueCooldownRemainingMs() > 0) {
                blocks.add("blue_cooldown_ms=" + r.blueCooldownRemainingMs());
            }
            if (r.breakerOpenKeys() != null && !r.breakerOpenKeys().isEmpty()) {
                blocks.add("breaker_open=" + r.breakerOpenKeys());
            }
            blocks.add("blue_unhealthy");
        }
        if (blocks.isEmpty()) {
            GeminiClient gemini = geminiProvider.getIfAvailable();
            if (gemini == null) {
                blocks.add("geminiClientBeanMissing");
            } else {
                willBlue = true;
            }
        }
        if (!willBlue && !blocks.isEmpty()) {
            blueBlockedReason = String.join(";", blocks);
        }

        boolean idleOk = !requireIdle || idleNow;
        if (!idleOk) {
            // It's still a valid preview, but callers likely care about the gating result.
            blueBlockedReason = (blueBlockedReason == null ? "" : blueBlockedReason + ";") + "not_idle";
        }

        return new AutoEvolvePreview(Instant.now(), idleNow, f, r, decision, baseQueries, willGreen, willBlue, blueBlockedReason);
    }

    /**
     * Runs once. If requireIdle=true, the run is skipped unless (idle + window + low pressure) holds.
     */
    public RgbSoakReport runOnce(boolean requireIdle, String trigger) {
        if (!props.isEnabled()) {
            throw new IllegalStateException("rgb.moe.enabled=false");
        }

        Instant startedAt = Instant.now();
        Instant endedAt = null;
        String sessionId = "autoevolve-" + TS.format(LocalDateTime.now());

        Boolean idleSatisfied = null;
        RgbLogSignalParser.Features f = null;
        RgbResourceProbe.Snapshot r = null;
        RgbStrategySelector.Decision decision = null;
        List<String> baseQueries = List.of();
        List<String> finalQueries = List.of();
        AutoEvolveRunDebug.ExpansionDebug greenDebug = AutoEvolveRunDebug.ExpansionDebug.skipped(0);
        AutoEvolveRunDebug.BlueCallDebug blueDebug = AutoEvolveRunDebug.BlueCallDebug.skipped();
        int blueCalls = 0;
        String reportFile = null;
        RgbSoakReport report = null;
        AutoEvolveRunDebug.Outcome outcome = AutoEvolveRunDebug.Outcome.FAILED;
        Throwable failure = null;

        // idle gating (recorded for debugging regardless of requireIdle)
        idleSatisfied = idleDetector.isIdleNow();
        if (requireIdle && !Boolean.TRUE.equals(idleSatisfied)) {
            log.info("[AutoEvolve] skipped: not idle/window");
            outcome = AutoEvolveRunDebug.Outcome.SKIPPED_NOT_IDLE;
            endedAt = Instant.now();
            debugStore.record(new AutoEvolveRunDebug(
                    sessionId,
                    trigger,
                    true,
                    idleSatisfied,
                    outcome,
                    startedAt,
                    endedAt,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    greenDebug,
                    blueDebug,
                    null,
                    null,
                    null,
                    null
            ));
            return null;
        }

        if (!running.compareAndSet(false, true)) {
            log.info("[AutoEvolve] skipped: already running");
            outcome = AutoEvolveRunDebug.Outcome.SKIPPED_ALREADY_RUNNING;
            endedAt = Instant.now();
            debugStore.record(new AutoEvolveRunDebug(
                    sessionId,
                    trigger,
                    requireIdle,
                    idleSatisfied,
                    outcome,
                    startedAt,
                    endedAt,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    greenDebug,
                    blueDebug,
                    null,
                    null,
                    null,
                    null
            ));
            return null;
        }

        runningSessionId.set(sessionId);

        MDC.put("sessionId", sessionId);
        MDC.put("x-request-id", sessionId);

        try (TraceContext ignored = TraceContext.attach(sessionId, sessionId)) {
            log.info("[AutoEvolve] start trigger={} sessionId={}", trigger, sessionId);
            TraceLogger.emit("autoevolve_start", "autoevolve", Map.of("sessionId", sessionId, "trigger", String.valueOf(trigger)));

            // (1) log signals + query samples
            Path logPath = Path.of(props.getLogPath());
            f = logParser.parse(logPath, props.getLogTailLines(), Duration.ofHours(24));
            baseQueries = buildBaseQueries(f);

            // (2) resource snapshot
            r = resourceProbe.snapshot();

            // (3) strategy decision (includes scorecard)
            decision = selector.select(f, r);

            // (4) query set (mutated via optional expansions)
            List<String> queries = new ArrayList<>(baseQueries);

            // (5) optional query expansion (GREEN, then BLUE if allowed)
            if (shouldExpandGreen(decision)) {
                ExpansionResult er = expandWithGreenDetailed(queries, 16);
                greenDebug = er.debug();
                if (er.expanded() != null && !er.expanded().isEmpty()) {
                    queries = com.example.lms.moe.RgbMixPolicy.mergeQueries(queries, er.expanded());
                }
            } else {
                greenDebug = AutoEvolveRunDebug.ExpansionDebug.skipped(queries.size());
            }

            if (shouldAttemptBlue(decision, r)) {
                int cap = Math.min(8, props.getBlueMaxCallsPerRun());
                GeminiClient gemini = geminiProvider.getIfAvailable();
                BlueAttempt br = expandWithBlueDetailed(gemini, queries, cap);
                blueDebug = br.debug();
                if (blueDebug.success() && br.expanded() != null && !br.expanded().isEmpty()) {
                    blueCalls = 1;
                    queries = com.example.lms.moe.RgbMixPolicy.mergeQueries(queries, br.expanded());
                }
            } else {
                blueDebug = AutoEvolveRunDebug.BlueCallDebug.skipped();
            }

            finalQueries = queries;

            Map<String, Object> extraDebug = new HashMap<>();
            extraDebug.put("trigger", trigger);
            extraDebug.put("idleSatisfied", idleSatisfied);
            extraDebug.put("resourceSnapshot", r);
            extraDebug.put("logFeatures", f);
            extraDebug.put("greenExpansion", greenDebug);
            extraDebug.put("blueCall", blueDebug);
            extraDebug.put("sessionId", sessionId);

            // (6) quick soak report (writes JSON)
            report = reportService.run(sessionId, finalQueries, decision, blueCalls, true, extraDebug);
            if (report != null && report.debug() != null) {
                Object rf = report.debug().get("reportFile");
                reportFile = rf == null ? null : String.valueOf(rf);
            }

            outcome = AutoEvolveRunDebug.Outcome.SUCCESS;
            TraceLogger.emit("autoevolve_success", "autoevolve", Map.of(
                    "sessionId", sessionId,
                    "primary", decision == null ? null : String.valueOf(decision.primaryStrategy()),
                    "blueCalls", blueCalls,
                    "queries", finalQueries == null ? 0 : finalQueries.size()
            ));
            return report;

        } catch (Exception e) {
            failure = e;
            log.warn("[AutoEvolve] failed sessionId={} err={}", sessionId, e.toString());
            TraceLogger.emit("autoevolve_failed", "autoevolve", Map.of(
                    "sessionId", sessionId,
                    "error", e.getClass().getSimpleName() + ":" + safeMsg(e)
            ));
            throw e;

        } finally {
            endedAt = endedAt != null ? endedAt : Instant.now();
            running.set(false);
            runningSessionId.set(null);

            debugStore.record(new AutoEvolveRunDebug(
                    sessionId,
                    trigger,
                    requireIdle,
                    idleSatisfied,
                    outcome,
                    startedAt,
                    endedAt,
                    f,
                    r,
                    decision,
                    baseQueries,
                    finalQueries,
                    greenDebug,
                    blueDebug,
                    reportFile,
                    report,
                    failure == null ? null : failure.getClass().getName(),
                    failure == null ? null : safeMsg(failure)
            ));

            MDC.clear();
            log.info("[AutoEvolve] end sessionId={} outcome={}", sessionId, outcome);
        }
    }

    private record ExpansionResult(List<String> expanded, AutoEvolveRunDebug.ExpansionDebug debug) {}

    private ExpansionResult expandWithGreenDetailed(List<String> base, int cap) {
        int inCount = base == null ? 0 : base.size();
        if (base == null || base.isEmpty() || greenModel == null) {
            return new ExpansionResult(List.of(), AutoEvolveRunDebug.ExpansionDebug.skipped(inCount));
        }

        String joined = String.join("\n", base.stream().limit(4).toList());
        String sys = "You are a query expansion helper. Return only expanded search queries, one per line.";
        String user = "Base queries:\n" + joined + "\n\nGenerate 6 alternative Korean search queries (no numbering).";

        long t0 = System.nanoTime();
        try {
            String out = greenModel.chat(List.of(SystemMessage.from(sys), UserMessage.from(user))).aiMessage().text();
            List<String> expanded = splitLines(out, cap);
            long dtMs = (System.nanoTime() - t0) / 1_000_000L;
            AutoEvolveRunDebug.ExpansionDebug dbg = new AutoEvolveRunDebug.ExpansionDebug(true, dtMs, inCount, expanded.size(), null, null);
            return new ExpansionResult(expanded, dbg);
        } catch (Exception e) {
            long dtMs = (System.nanoTime() - t0) / 1_000_000L;
            AutoEvolveRunDebug.ExpansionDebug dbg = new AutoEvolveRunDebug.ExpansionDebug(true, dtMs, inCount, 0, e.getClass().getName(), safeMsg(e));
            return new ExpansionResult(List.of(), dbg);
        }
    }

    private record BlueAttempt(List<String> expanded, AutoEvolveRunDebug.BlueCallDebug debug) {}

    /**
     * BLUE is best-effort and strictly bounded.
     *
     * <p>Note: makes at most one Gemini call and returns both (expanded queries, debug info).</p>
     */
    private BlueAttempt expandWithBlueDetailed(GeminiClient gemini, List<String> base, int cap) {
        if (gemini == null || base == null || base.isEmpty()) {
            AutoEvolveRunDebug.BlueCallDebug dbg = new AutoEvolveRunDebug.BlueCallDebug(
                    true,
                    false,
                    0L,
                    cap,
                    0,
                    null,
                    null,
                    Map.of(),
                    gemini == null ? "NoGeminiClient" : null,
                    gemini == null ? "GeminiClient bean missing" : "empty_base",
                    null,
                    false
            );
            return new BlueAttempt(List.of(), dbg);
        }

        long t0 = System.nanoTime();
        boolean cooldownApplied = false;
        try {
            // Conservative: apply cooldown on attempt (success or failure) to avoid repeated hammering.
            resourceProbe.markBlueCalled();
            cooldownApplied = true;

            String anchor = base.get(0);
            GeminiClient.KeywordVariantsResult res = gemini.keywordVariantsWithMeta(anchor, anchor, cap, Duration.ofSeconds(8));
            List<String> out = (res == null || res.variants() == null) ? List.of() : res.variants();

            Integer status = res == null ? null : res.httpStatus();
            HttpHeaders rawHeaders = res == null ? null : res.headers();
            Map<String, String> whitelisted = BlueHeaderWhitelist.extract(rawHeaders);
            String retryAfter = whitelisted.get("retry-after");

            long dtMs = (System.nanoTime() - t0) / 1_000_000L;
            AutoEvolveRunDebug.BlueCallDebug dbg = new AutoEvolveRunDebug.BlueCallDebug(
                    true,
                    true,
                    dtMs,
                    cap,
                    out.size(),
                    status == null ? 200 : status,
                    retryAfter,
                    whitelisted,
                    null,
                    null,
                    null,
                    cooldownApplied
            );
            return new BlueAttempt(out, dbg);

        } catch (WebClientResponseException ex) {
            long dtMs = (System.nanoTime() - t0) / 1_000_000L;
            Integer status = ex.getRawStatusCode();

            String retryAfter = null;
            try {
                retryAfter = ex.getHeaders() == null ? null : ex.getHeaders().getFirst("Retry-After");
                if (retryAfter == null && ex.getHeaders() != null) {
                    retryAfter = ex.getHeaders().getFirst("retry-after");
                }
            } catch (Exception ignore) {
                // ignore
            }

            Map<String, String> headers = BlueHeaderWhitelist.extract(ex.getHeaders());
            if (retryAfter == null) {
                retryAfter = headers.get("retry-after");
            }

            String bodyPreview = null;
            try {
                bodyPreview = limit(ex.getResponseBodyAsString(), props.getDebug().getMaxErrorBodyChars());
            } catch (Exception ignore) {
                // ignore
            }

            AutoEvolveRunDebug.BlueCallDebug dbg = new AutoEvolveRunDebug.BlueCallDebug(
                    true,
                    false,
                    dtMs,
                    cap,
                    0,
                    status,
                    retryAfter,
                    headers,
                    ex.getClass().getName(),
                    safeMsg(ex),
                    bodyPreview,
                    cooldownApplied
            );

            TraceLogger.emit("autoevolve_blue_failed", "autoevolve", Map.of(
                    "status", status,
                    "retryAfter", retryAfter,
                    "latencyMs", dtMs,
                    "xGoogRequestId", headers.get("x-goog-request-id")
            ));

            return new BlueAttempt(List.of(), dbg);

        } catch (Exception e) {
            long dtMs = (System.nanoTime() - t0) / 1_000_000L;
            AutoEvolveRunDebug.BlueCallDebug dbg = new AutoEvolveRunDebug.BlueCallDebug(
                    true,
                    false,
                    dtMs,
                    cap,
                    0,
                    null,
                    null,
                    Map.of(),
                    e.getClass().getName(),
                    safeMsg(e),
                    null,
                    cooldownApplied
            );

            TraceLogger.emit("autoevolve_blue_failed", "autoevolve", Map.of(
                    "error", e.getClass().getSimpleName(),
                    "latencyMs", dtMs
            ));

            return new BlueAttempt(List.of(), dbg);
        }
    }

    private static String limit(String s, int max) {
        if (s == null) return null;
        if (max <= 0) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "…";
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return null;
        String m = t.getMessage();
        if (m == null) return null;
        m = m.replaceAll("\\s+", " ").trim();
        if (m.length() > 240) m = m.substring(0, 240) + "…";
        return m;
    }

    private static boolean shouldExpandGreen(RgbStrategySelector.Decision decision) {
        if (decision == null || decision.primaryStrategy() == null) return false;
        return decision.primaryStrategy() == RgbStrategySelector.Strategy.RG_ENSEMBLE
                || decision.primaryStrategy() == RgbStrategySelector.Strategy.G_ONLY
                || decision.primaryStrategy() == RgbStrategySelector.Strategy.GB_FALLBACK
                || decision.primaryStrategy() == RgbStrategySelector.Strategy.RGB_ENSEMBLE;
    }

    private boolean shouldAttemptBlue(RgbStrategySelector.Decision decision, RgbResourceProbe.Snapshot r) {
        if (decision == null || r == null) return false;
        if (!props.isBlueEnabled()) return false;
        if (props.getBlueMaxCallsPerRun() <= 0) return false;
        if (!r.blueHealthy()) return false;
        return decision.primaryStrategy() == RgbStrategySelector.Strategy.GB_FALLBACK
                || decision.primaryStrategy() == RgbStrategySelector.Strategy.RB_ENSEMBLE
                || decision.primaryStrategy() == RgbStrategySelector.Strategy.RGB_ENSEMBLE;
    }

    private static List<String> buildBaseQueries(RgbLogSignalParser.Features f) {
        if (f != null && f.querySamples() != null && !f.querySamples().isEmpty()) {
            return new ArrayList<>(f.querySamples());
        }
        // last-resort defaults
        return List.of(
                "오늘 서울 날씨",
                "자바 스프링 부트 JPA 예제",
                "갤럭시 S24 배터리 절약 설정"
        );
    }

    private static List<String> splitLines(String raw, int cap) {
        if (raw == null) return List.of();
        String[] arr = raw.split("\\r?\\n");
        List<String> out = new ArrayList<>();
        for (String s : arr) {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) continue;
            t = t.replaceAll("^[0-9]+[).:-]\\s*", "");
            if (t.length() > 200) t = t.substring(0, 200);
            if (!t.isBlank()) out.add(t);
            if (out.size() >= cap) break;
        }
        return out;
    }
}
