package ai.abandonware.nova.orch.probe;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;

/**
 * Internal WebSoak KPI probe (Nova Overlay).
 *
 * - POST /internal/probe/websoak-kpi/run
 * - GET  /internal/probe/websoak-kpi/last?format=line|json|pretty
 * - GET  /internal/probe/websoak-kpi/recent?limit=60&format=json|line
 * - GET  /internal/probe/websoak-kpi/ui
 * - Enabled by: probe.websoak-kpi.enabled=true
 * - Auth (recommended): probe.websoak-kpi.key + header X-Probe-Key (or X-Internal-Key)
     * - Query-param key (?key=...) is disabled by default because it can leak to access logs.
     *   Enable only for compatibility with: probe.websoak-kpi.allow-query-param-key=true
 */
@Slf4j
@RestController
@RequestMapping("/internal/probe/websoak-kpi")
public class WebSoakKpiProbeController {

    private static final String HDR_INTERNAL_KEY = "X-Internal-Key";
    private static final String HDR_PROBE_KEY = "X-Probe-Key";
    private static final String HDR_CACHE_CONTROL = HttpHeaders.CACHE_CONTROL;
    private static final String CACHE_NO_STORE = "no-store";

    private final WebSoakKpiProbeService service;
    private final String requiredKey;
    private final boolean requireKey;
    private final boolean allowQueryParamKey;

    // Avoid log spam: warn at most once per process when query-param key is used.
    private final AtomicBoolean queryKeyWarned = new AtomicBoolean(false);

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WebSoakKpiLastStore lastStore;

    public WebSoakKpiProbeController(WebSoakKpiProbeService service, Environment env) {
        this.service = service;
        this.requiredKey = env.getProperty("probe.websoak-kpi.key", "");
        this.requireKey = Boolean.parseBoolean(env.getProperty("probe.websoak-kpi.require-key", "true"));
        this.allowQueryParamKey = Boolean
                .parseBoolean(env.getProperty("probe.websoak-kpi.allow-query-param-key", "false"));
    }

    /**
     * Lightweight HTML UI shell.
     * <p>
     * This page does NOT embed KPI data. It asks for the key in-browser and then
     * fetches {@code /last} / {@code /recent} using the header channel.
     * This avoids putting the key into the URL.
     */
    @GetMapping(value = "/ui", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> ui() {
        String html = buildUiHtml();
        return ResponseEntity.ok()
                .header(HDR_CACHE_CONTROL, CACHE_NO_STORE)
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(
            @RequestBody(required = false) Request req,
            @RequestHeader(value = HDR_INTERNAL_KEY, required = false) String key,
            @RequestHeader(value = HDR_PROBE_KEY, required = false) String probeKey,
            @RequestParam(value = "key", required = false) String queryKey
    ) {
        AuthResult auth = authorize(key, probeKey, queryKey);
        if (!auth.authorized) {
            return unauthorized(auth);
        }

        warnOnceIfQueryKey(auth);

        try {
            WebSoakKpiProbeService.Report report = service.run(req);
            return ResponseEntity.ok()
                    .header(HDR_CACHE_CONTROL, CACHE_NO_STORE)
                    .body(report);
        } catch (IllegalArgumentException bad) {
            recordRunFailure("bad_request", "bad_request", bad);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "message", safeFailureMessage(bad, "bad_request")
            ));
        } catch (Exception e) {
            recordRunFailure("internal_error", failureType(e), e);
            log.error("[WebSoakKPI] probe failed type={} error={}",
                    failureType(e),
                    SafeRedactor.traceLabelOrFallback(String.valueOf(e), ""));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "internal_error",
                    "message", safeFailureMessage(e, "internal_error")
            ));
        }
    }

    private static void recordRunFailure(String stage, String type, Throwable t) {
        String message = t == null ? null : t.getMessage();
        TraceStore.put("probe.websoakKpi.run.failed", true);
        TraceStore.put("probe.websoakKpi.run.failureStage", stage);
        TraceStore.put("probe.websoakKpi.run.failureType", type);
        TraceStore.put("probe.websoakKpi.run.messageHash", SafeRedactor.hashValue(message));
        TraceStore.put("probe.websoakKpi.run.messageLength", message == null ? 0 : message.length());
    }

    @GetMapping(value = "/last", produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE })
    public ResponseEntity<?> last(
            @RequestHeader(value = HDR_INTERNAL_KEY, required = false) String key,
            @RequestHeader(value = HDR_PROBE_KEY, required = false) String probeKey,
            @RequestParam(value = "key", required = false) String queryKey,
            @RequestParam(value = "format", required = false) String format
    ) {
        AuthResult auth = authorize(key, probeKey, queryKey);
        if (!auth.authorized) {
            return unauthorized(auth);
        }

        warnOnceIfQueryKey(auth);

        if (lastStore == null || lastStore.last() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "no_data",
                    "message", "No SOAK_WEB_KPI sample captured yet (enable traffic or run /run)."
            ));
        }

        WebSoakKpiLastStore.Snapshot s = lastStore.last();
        long ageMs = Math.max(0L, System.currentTimeMillis() - s.getCapturedAtMs());

        String fmt = (format == null) ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if ("line".equals(fmt)) {
            String line = s.getJsonLine();
            if (line == null) {
                line = "";
            }
            return ResponseEntity.ok()
                    .header(HDR_CACHE_CONTROL, CACHE_NO_STORE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(line);
        }

        if ("pretty".equals(fmt)) {
            String text = buildPrettyText(s, ageMs, auth.authSource);
            return ResponseEntity.ok()
                    .header(HDR_CACHE_CONTROL, CACHE_NO_STORE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(text);
        }

        return ResponseEntity.ok()
                .header(HDR_CACHE_CONTROL, CACHE_NO_STORE)
                .body(Map.of(
                        "capturedAtMs", s.getCapturedAtMs(),
                        "ageMs", ageMs,
                        "authSource", auth.authSource,
                        "kpi", s.getKpi()
                ));
    }

    @GetMapping(value = "/recent", produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE })
    public ResponseEntity<?> recent(
            @RequestHeader(value = HDR_INTERNAL_KEY, required = false) String key,
            @RequestHeader(value = HDR_PROBE_KEY, required = false) String probeKey,
            @RequestParam(value = "key", required = false) String queryKey,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "format", required = false) String format
    ) {
        AuthResult auth = authorize(key, probeKey, queryKey);
        if (!auth.authorized) {
            return unauthorized(auth);
        }

        warnOnceIfQueryKey(auth);

        if (lastStore == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "no_data",
                    "message", "No SOAK_WEB_KPI store is available (probe enabled but lastStore bean missing)."
            ));
        }

        int lim = clampInt(limit == null ? 60 : limit, 1, 200);
        List<WebSoakKpiLastStore.Snapshot> snaps = lastStore.recent(lim);
        if (snaps == null) {
            snaps = Collections.emptyList();
        }

        String fmt = (format == null) ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if ("line".equals(fmt)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < snaps.size(); i++) {
                WebSoakKpiLastStore.Snapshot s = snaps.get(i);
                if (s == null) {
                    continue;
                }
                if (i > 0) {
                    sb.append('\n');
                }
                String line = s.getJsonLine();
                if (line != null) {
                    sb.append(line);
                }
            }
            return ResponseEntity.ok()
                    .header(HDR_CACHE_CONTROL, CACHE_NO_STORE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(sb.toString());
        }

        long now = System.currentTimeMillis();
        List<Map<String, Object>> items = new ArrayList<>();
        for (WebSoakKpiLastStore.Snapshot s : snaps) {
            if (s == null) {
                continue;
            }
            long ageMs = Math.max(0L, now - s.getCapturedAtMs());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("capturedAtMs", s.getCapturedAtMs());
            m.put("ageMs", ageMs);
            m.put("kpi", s.getKpi());
            m.put("jsonLine", s.getJsonLine());
            items.add(m);
        }

        return ResponseEntity.ok()
                .header(HDR_CACHE_CONTROL, CACHE_NO_STORE)
                .body(Map.of(
                        "limit", lim,
                        "authSource", auth.authSource,
                        "items", items
                ));
    }

    private void warnOnceIfQueryKey(AuthResult auth) {
        if (auth == null) {
            return;
        }
        if (!"query".equalsIgnoreCase(auth.authSource)) {
            return;
        }
        if (!queryKeyWarned.compareAndSet(false, true)) {
            return;
        }
        // NOTE: do not log the key.
        log.warn(
                "[WebSoakKPI] Probe key accepted via query parameter (?key=...). This may leak to access logs. "
                        + "Prefer header {}. Query-param keys are default-off and require explicit opt-in.",
                HDR_PROBE_KEY);
    }

    private ResponseEntity<?> unauthorized(AuthResult auth) {
        String reason = (auth != null) ? auth.denyReason : "unauthorized";
        boolean keyConfigured = !ConfigValueGuards.isMissing(requiredKey);

        String msg;
        if ("key_not_configured".equals(reason)) {
            msg = "Probe key not configured (set probe.websoak-kpi.key). Access denied because probe.websoak-kpi.require-key=true.";
        } else if ("query_param_key_disabled".equals(reason)) {
            msg = "Query-param key is disabled (probe.websoak-kpi.allow-query-param-key=false). Use header X-Probe-Key instead.";
        } else {
            msg = requireKey
                    ? "Missing/invalid probe key (send X-Probe-Key / X-Internal-Key)."
                    : "Missing/invalid probe key";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "unauthorized");
        body.put("code", "PROBE_UNAUTHORIZED");
        body.put("message", msg);
        body.put("requireKey", requireKey);
        body.put("keyConfigured", keyConfigured);
        body.put("allowQueryParamKey", allowQueryParamKey);
        body.put("recommendedHeader", HDR_PROBE_KEY);
        body.put("acceptedHeaders", List.of(HDR_PROBE_KEY, HDR_INTERNAL_KEY));
        body.put("hint", "Prefer headers; query ?key= is compatibility-only and can leak in access logs.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HDR_CACHE_CONTROL, CACHE_NO_STORE)
                .body(body);
    }

    private static String safeFailureMessage(Throwable t, String fallback) {
        if (t == null) {
            return fallback;
        }
        if (isCancellationLike(t)) {
            return "cancelled";
        }
        String safe = SafeRedactor.traceLabelOrFallback(t.getMessage(), "");
        if (safe != null && !safe.isBlank()) {
            return safe;
        }
        return fallback;
    }

    private static String failureType(Throwable t) {
        if (isCancellationLike(t)) {
            return "cancelled";
        }
        if (t == null) {
            return "unknown";
        }
        return "internal_error";
    }

    private static boolean isCancellationLike(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof CancellationException || x instanceof InterruptedException) {
                return true;
            }
            String text = (x.getClass().getName() + " " + String.valueOf(x.getMessage()))
                    .toLowerCase(Locale.ROOT);
            if (text.contains("cancel") || text.contains("interrupt")) {
                return true;
            }
        }
        return false;
    }

    private AuthResult authorize(String internalKey, String probeKey, String queryKey) {
        boolean keyConfigured = !ConfigValueGuards.isMissing(requiredKey);
        if (!keyConfigured) {
            if (requireKey) {
                return AuthResult.deny("key_not_configured");
            }
            // dev/local only: no key configured and requireKey=false
            return AuthResult.ok("none");
        }

        String headerKey = firstNonBlank(internalKey, probeKey);
        if (isAuthorized(headerKey)) {
            return AuthResult.ok("header");
        }

        // If query keys are disabled, fail fast with a better reason.
        if (!allowQueryParamKey && queryKey != null && !queryKey.isBlank()) {
            return AuthResult.deny("query_param_key_disabled");
        }

        if (allowQueryParamKey && isAuthorized(queryKey)) {
            return AuthResult.ok("query");
        }

        return AuthResult.deny("unauthorized");
    }

    private boolean isAuthorized(String gotKey) {
        // If requireKey=true (default), deny unless a non-blank key is configured and presented.
        if (requireKey) {
            if (ConfigValueGuards.isMissing(requiredKey)) {
                return false;
            }
        }

        // If key is not configured and requireKey=false, allow (local/dev only).
        if (ConfigValueGuards.isMissing(requiredKey)) {
            return true;
        }

        if (gotKey == null || gotKey.isBlank()) {
            return false;
        }

        // Constant-time compare.
        return MessageDigest.isEqual(
                requiredKey.getBytes(StandardCharsets.UTF_8),
                gotKey.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String buildPrettyText(WebSoakKpiLastStore.Snapshot s, long ageMs, String authSource) {
        if (s == null) {
            return "(no data)";
        }
        Map<String, Object> kpi = s.getKpi();
        if (kpi == null) {
            kpi = Collections.emptyMap();
        }

        StringBuilder sb = new StringBuilder(2048);
        sb.append("capturedAtMs=").append(s.getCapturedAtMs()).append('\n');
        sb.append("ageMs=").append(ageMs).append('\n');
        sb.append("authSource=").append(authSource == null ? "" : authSource).append('\n');

        sb.append('\n');
        sb.append("outCount=").append(toLong(kpi.get("outCount")))
                .append("  cacheOnly.merged.count=").append(toLong(kpi.get("cacheOnly.merged.count")))
                .append("  tracePool.size=").append(toLong(kpi.get("tracePool.size")))
                .append("  rescueMerge.used=").append(truthy(kpi.get("rescueMerge.used")))
                .append('\n');
        sb.append("starvationFallback.trigger=").append(triggerLabel(kpi.get("starvationFallback.trigger")))
                .append("  poolSafeEmpty=").append(truthy(kpi.get("poolSafeEmpty")))
                .append('\n');
        sb.append("starvationFallback.used=").append(truthy(kpi.get("starvationFallback.used")))
                .append("  starvationFallback.poolUsed=")
                .append(providerReason(kpi, "starvationFallback.poolUsed"))
                .append("  starvationFallback.count=").append(toLong(kpi.get("starvationFallback.count")))
                .append("  starvationFallback.added=").append(toLong(kpi.get("starvationFallback.added")))
                .append('\n');
        sb.append("starvationFallback.pool.safe.size=")
                .append(toLong(kpi.get("starvationFallback.pool.safe.size")))
                .append("  starvationFallback.pool.dev.size=")
                .append(toLong(kpi.get("starvationFallback.pool.dev.size")))
                .append('\n');
        sb.append("vectorFallback.used=").append(truthy(kpi.get("vectorFallback.used")))
                .append("  vectorFallback.reason=").append(providerReason(kpi, "vectorFallback.reason"))
                .append("  vectorFallback.effectiveTopK=").append(toLong(kpi.get("vectorFallback.effectiveTopK")))
                .append('\n');
        sb.append("ecosystem.recirculate.used=").append(truthy(kpi.get("ecosystem.recirculate.used")))
                .append("  ecosystem.recirculate.count=").append(toLong(kpi.get("ecosystem.recirculate.count")))
                .append("  ecosystem.recirculate.safe=").append(toLong(kpi.get("ecosystem.recirculate.safe")))
                .append("  ecosystem.recirculate.allUnverified=")
                .append(truthy(kpi.get("ecosystem.recirculate.allUnverified")))
                .append('\n');
        sb.append("ecosystem.pool.size=").append(toLong(kpi.get("ecosystem.pool.size")))
                .append("  ecosystem.recycled.total=").append(toLong(kpi.get("ecosystem.recycled.total")))
                .append('\n');
        sb.append("ecosystem.ammonia.score=").append(oneLine(Objects.toString(kpi.get("ecosystem.ammonia.score"), ""), 32))
                .append("  ecosystem.ammonia.quarantined=").append(toLong(kpi.get("ecosystem.ammonia.quarantined")))
                .append("  ecosystem.ammonia.safe=").append(toLong(kpi.get("ecosystem.ammonia.safe")))
                .append("  ecosystem.ammonia.threshold=")
                .append(oneLine(Objects.toString(kpi.get("ecosystem.ammonia.threshold"), ""), 32))
                .append("  ecosystem.ammonia.surgeBlocked=")
                .append(truthy(kpi.get("ecosystem.ammonia.surgeBlocked")))
                .append('\n');
        sb.append("stageCountsSelectedFromOut=")
                .append(oneLine(Objects.toString(kpi.get("stageCountsSelectedFromOut"), ""), 260))
                .append('\n');

        sb.append('\n');
        sb.append("providers: naver=").append(oneLine(Objects.toString(kpi.get("provider.naver"), ""), 64))
                .append("  brave=").append(oneLine(Objects.toString(kpi.get("provider.brave"), ""), 64))
                .append("  serpapi=").append(oneLine(Objects.toString(kpi.get("provider.serpapi"), ""), 64))
                .append("  tavily=").append(oneLine(Objects.toString(kpi.get("provider.tavily"), ""), 64))
                .append('\n');
        sb.append("provider reasons: naver.reason=").append(providerReason(kpi, "web.naver.skipped.reason"))
                .append("  brave.reason=").append(providerReason(kpi, "web.brave.skipped.reason"))
                .append("  serpapi.reason=").append(providerReason(kpi, "web.serpapi.skipped.reason"))
                .append("  tavily.reason=").append(providerReason(kpi, "web.tavily.skipped.reason"))
                .append('\n');
        appendProviderTaxonomy(sb, kpi, "naver");
        appendProviderTaxonomy(sb, kpi, "brave");
        appendProviderTaxonomy(sb, kpi, "serpapi");
        appendProviderTaxonomy(sb, kpi, "tavily");

        sb.append('\n');
        sb.append("await: ok=").append(toLong(kpi.get("web.await.ok.count")))
                .append("  skipped=").append(toLong(kpi.get("web.await.skipped.count")))
                .append("  timeout=").append(toLong(kpi.get("web.await.timeout.count")))
                .append("  timeoutAll=").append(truthy(kpi.get("web.await.timeout.all")))
                .append("  missingFutureAny=").append(truthy(kpi.get("web.await.missing_future.any")))
                .append('\n');

        sb.append('\n');
        sb.append("backoff: skipped.cooldown.count=")
                .append(toLong(kpi.get("web.failsoft.rateLimitBackoff.skipped.cooldown.count")))
                .append("  max.delayMs=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.max.delayMs")))
                .append("  max.remainingMs=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.max.remainingMs")))
                .append('\n');
        sb.append("  naver last.kind=")
                .append(oneLine(Objects.toString(kpi.get("web.failsoft.rateLimitBackoff.naver.last.kind"), ""), 32))
                .append(" delayMs=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.naver.last.delayMs")))
                .append(" base=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.naver.last.baseMs")))
                .append(" jitter=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.naver.last.jitterMs")))
                .append(" cap=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.naver.last.capMs")))
                .append(" timeoutHint=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.naver.last.timeoutHintMs")))
                .append(" retryAfter=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.naver.last.retryAfterMs")))
                .append('\n');
        sb.append("  brave last.kind=")
                .append(oneLine(Objects.toString(kpi.get("web.failsoft.rateLimitBackoff.brave.last.kind"), ""), 32))
                .append(" delayMs=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.brave.last.delayMs")))
                .append(" base=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.brave.last.baseMs")))
                .append(" jitter=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.brave.last.jitterMs")))
                .append(" cap=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.brave.last.capMs")))
                .append(" timeoutHint=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.brave.last.timeoutHintMs")))
                .append(" retryAfter=").append(toLong(kpi.get("web.failsoft.rateLimitBackoff.brave.last.retryAfterMs")))
                .append('\n');
        for (String p : List.of("serpapi", "tavily")) {
            String prefix = "web.failsoft.rateLimitBackoff." + p + ".last.";
            sb.append("  ").append(p).append(" last.kind=")
                    .append(oneLine(Objects.toString(kpi.get(prefix + "kind"), ""), 32))
                    .append(" delayMs=").append(toLong(kpi.get(prefix + "delayMs")))
                    .append(" base=").append(toLong(kpi.get(prefix + "baseMs")))
                    .append(" jitter=").append(toLong(kpi.get(prefix + "jitterMs")))
                    .append(" cap=").append(toLong(kpi.get(prefix + "capMs")))
                    .append(" timeoutHint=").append(toLong(kpi.get(prefix + "timeoutHintMs")))
                    .append(" retryAfter=").append(toLong(kpi.get(prefix + "retryAfterMs")))
                    .append('\n');
        }

        sb.append('\n');
        sb.append("qtx: softCooldown.active=").append(truthy(kpi.get("qtx.softCooldown.active")))
                .append(" remainingMs=").append(toLong(kpi.get("qtx.softCooldown.remainingMs")))
                .append(" keywordSelection.mode=")
                .append(oneLine(Objects.toString(kpi.get("keywordSelection.mode"), ""), 80))
                .append(" oneShot.used=")
                .append(truthy(kpi.get("keywordSelection.qtxSoftCooldown.oneShot.used")))
                .append('\n');

        return sb.toString();
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            if (n instanceof Double d && !Double.isFinite(d)) {
                log.debug("[WebSoakKPI] numeric parse fallback stage={} errorType={}",
                        "controller.toLong", "invalid_number");
                return 0L;
            }
            if (n instanceof Float f && !Float.isFinite(f)) {
                log.debug("[WebSoakKPI] numeric parse fallback stage={} errorType={}",
                        "controller.toLong", "invalid_number");
                return 0L;
            }
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ignore) {
            log.debug("[WebSoakKPI] numeric parse fallback stage={} errorType={}",
                    "controller.toLong", "invalid_number");
            return 0L;
        }
    }

    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "y".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        while (t.contains("  ")) {
            t = t.replace("  ", " ");
        }
        if (max > 0 && t.length() > max) {
            return t.substring(0, max) + "…";
        }
        return t;
    }

    private static String providerReason(Map<String, Object> kpi, String key) {
        if (kpi == null || key == null) {
            return "";
        }
        return oneLine(SafeRedactor.traceLabelOrFallback(kpi.get(key), ""), 96);
    }

    private static String triggerLabel(Object value) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (raw.matches("[A-Za-z0-9_.:-]{1,80}(->[A-Za-z0-9_.:-]{1,80})?")) {
            return raw;
        }
        return oneLine(SafeRedactor.traceLabelOrFallback(raw, ""), 96);
    }

    private static void appendProviderTaxonomy(StringBuilder sb, Map<String, Object> kpi, String provider) {
        String prefix = "web." + provider + ".";
        sb.append(provider).append(".taxonomy disabled=").append(truthy(kpi.get(prefix + "providerDisabled")))
                .append("/").append(providerReason(kpi, prefix + "disabledReason"))
                .append("  failure=").append(providerReason(kpi, prefix + "failureReason"))
                .append("  timeout=").append(truthy(kpi.get(prefix + "timeout")))
                .append("  providerEmpty=").append(truthy(kpi.get(prefix + "providerEmpty")))
                .append("  afterFilterStarved=").append(truthy(kpi.get(prefix + "afterFilterStarved")))
                .append("  rateLimited=").append(truthy(kpi.get(prefix + "rateLimited")))
                .append("  cancelled=").append(truthy(kpi.get(prefix + "cancelled")))
                .append("  exceptionType=").append(providerReason(kpi, prefix + "exceptionType"))
                .append("  retryAfterMs=").append(toLong(kpi.get(prefix + "retryAfterMs")))
                .append('\n');
    }

    private static int clampInt(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private static String buildUiHtml() {
        // Intentionally small and dependency-free (no template engine).
        // Data is fetched via header X-Probe-Key to avoid putting secrets into the URL.
        return """
<!doctype html>
<html lang=\"en\">
<head>
  <meta charset=\"utf-8\"/>
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>
  <title>WebSoak KPI</title>
  <style>
    body{font-family:system-ui,-apple-system,Segoe UI,Roboto,Arial,sans-serif;margin:16px;line-height:1.35}
    .row{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:10px 0}
    .muted{color:#666;font-size:12px}
    .card{border:1px solid #eee;border-radius:10px;padding:12px 14px;margin:12px 0;background:#fff}
    .mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace}
    label{font-size:12px;color:#444}
    input[type=password]{padding:6px 8px;border:1px solid #ddd;border-radius:6px;min-width:240px}
    input[type=number]{padding:6px 8px;border:1px solid #ddd;border-radius:6px;width:90px}
    button{padding:6px 10px;border:1px solid #ddd;border-radius:8px;background:#f7f7f7;cursor:pointer}
    button:hover{background:#f0f0f0}
    pre{white-space:pre-wrap;word-break:break-word}
    table{border-collapse:collapse;width:100%}
    td,th{border-top:1px solid #eee;padding:6px 8px;font-size:13px;vertical-align:top}
    th{background:#fafafa;text-align:left}
    .badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;border:1px solid #ddd;background:#fafafa}
    .bad{border-color:#f3c0c0;background:#fff6f6}
    .good{border-color:#bfe8bf;background:#f6fff6}
  </style>
</head>
<body>
  <h2 style=\"margin:0\">WebSoak KPI</h2>
  <div class=\"muted\" style=\"margin-top:6px\">
    Key 전달은 <b>헤더(X-Probe-Key)</b>를 권장합니다. <span class=\"mono\">?key=</span>는 호환용(비권장)이며 Access Log에 남을 수 있습니다.
  </div>

  <div class=\"card\">
    <div class=\"row\">
      <div>
        <label>Probe Key (stored in sessionStorage for this tab)</label><br/>
        <input id=\"k\" type=\"password\" placeholder=\"paste probe key\"/>
      </div>
      <div>
        <label>Auto refresh (ms)</label><br/>
        <input id=\"refreshMs\" type=\"number\" min=\"500\" step=\"500\" value=\"2000\"/>
      </div>
      <div style=\"margin-top:18px\">
        <button id=\"save\" type=\"button\">Save</button>
        <button id=\"refresh\" type=\"button\">Refresh now</button>
        <button id=\"toggleRecent\" type=\"button\">Toggle recent</button>
      </div>
      <span id=\"auth\" class=\"badge\">auth: ?</span>
    </div>
    <div class=\"muted\">Recommended poll: <span class=\"mono\">curl -H \"X-Probe-Key: $PROBE_KEY\" http://HOST/internal/probe/websoak-kpi/last?format=line</span></div>
  </div>

  <div id=\"status\" class=\"card\"><span class=\"muted\">No data yet.</span></div>
  <div id=\"kpi\" class=\"card\" style=\"display:none\"></div>
  <div id=\"recent\" class=\"card\" style=\"display:none\"></div>

<script>
(function(){
  const BASE = '/internal/probe/websoak-kpi';
  const LS_KEY = 'websoak_probe_key_v1';

  const $k = document.getElementById('k');
  const $save = document.getElementById('save');
  const $refresh = document.getElementById('refresh');
  const $refreshMs = document.getElementById('refreshMs');
  const $status = document.getElementById('status');
  const $kpi = document.getElementById('kpi');
  const $recent = document.getElementById('recent');
  const $toggleRecent = document.getElementById('toggleRecent');
  const $auth = document.getElementById('auth');

  function getKey(){ try { return sessionStorage.getItem(LS_KEY) || ''; } catch(e){ console.debug('websoak ui storage skipped stage=get_key'); return ''; } }
  function setKey(v){ try { sessionStorage.setItem(LS_KEY, v||''); } catch(e){ console.debug('websoak ui storage skipped stage=set_key'); } }

  $k.value = getKey();

  function hdrs(){
    const key = getKey();
    const h = {};
    if (key) h['X-Probe-Key'] = key;
    return h;
  }

  function esc(s){
    return String(s==null?'':s)
      .replaceAll('&','&amp;')
      .replaceAll('<','&lt;')
      .replaceAll('>','&gt;')
      .replaceAll('"','&quot;')
      .replaceAll("'",'&#39;');
  }

  function truthy(v){
    if (v === true) return true;
    const s = String(v==null?'':v).trim().toLowerCase();
    return s==='true' || s==='1' || s==='yes' || s==='y' || s==='on';
  }

  function toNum(v){
    if (typeof v === 'number') return v;
    const n = parseFloat(String(v||'').trim());
    return isFinite(n) ? n : 0;
  }

  async function fetchJson(path){
    const res = await fetch(BASE + path, { headers: hdrs(), cache: 'no-store' });
    const txt = await res.text();
    let obj = null;
    try { obj = txt ? JSON.parse(txt) : null; } catch(e) {
      console.debug('websoak ui json parse skipped stage=fetch_json');
      obj = { error: 'bad_json', rawPresent: !!txt, rawLength: txt ? txt.length : 0 };
    }
    if (!res.ok) {
      obj = obj || {};
      obj.httpStatus = res.status;
    }
    return obj;
  }

  function renderLast(data){
    if (!data || data.error) {
      const msg = data && data.message ? data.message : 'No data.';
      $status.innerHTML = '<b>Status</b>: <span class="mono">' + esc(msg) + '</span>' + (data && data.httpStatus ? ' <span class="badge bad">HTTP ' + data.httpStatus + '</span>' : '');
      $kpi.style.display = 'none';
      return;
    }

    const kpi = data.kpi || {};
    const out = toNum(kpi.outCount);
    const timeoutAll = truthy(kpi['web.await.timeout.all']);
    const badgeClass = (out === 0 || timeoutAll) ? 'badge bad' : 'badge good';

    $auth.textContent = 'auth: ' + (data.authSource || '?');

    $status.innerHTML =
      '<div><b>Captured</b>: <span class="mono">' + esc(data.capturedAtMs) + '</span> <span class="muted">ageMs=' + esc(data.ageMs) + '</span></div>' +
      '<div style="margin-top:6px">' +
      '<span class="' + badgeClass + '">outCount=' + esc(out) + '</span> ' +
      '<span class="badge">cacheOnly.merged.count=' + esc(toNum(kpi['cacheOnly.merged.count'])) + '</span> ' +
      '<span class="badge">await.timeoutAll=' + esc(timeoutAll) + '</span> ' +
      '<span class="badge">keywordSelection.mode=' + esc(kpi['keywordSelection.mode']||'') + '</span>' +
      '</div>';

    const rows = [];
    function add(k, v){ rows.push('<tr><th class="mono">' + esc(k) + '</th><td class="mono">' + esc(v) + '</td></tr>'); }
    function providerMetric(p, metric){
      const prefix = 'web.' + p;
      const value = kpi[prefix + '.' + metric];
      if (!truthy(kpi[prefix + '.traceObserved'])) return 'unknown';
      return value === undefined || value === null || value === '' ? 'unknown' : value;
    }
    add('stageCountsSelectedFromOut', JSON.stringify(kpi.stageCountsSelectedFromOut || ''));
    add('cacheOnly.merged.count', kpi['cacheOnly.merged.count'] || 0);
    add('tracePool.size', kpi['tracePool.size'] || 0);
    add('rescueMerge.used', kpi['rescueMerge.used'] || false);
    add('provider.naver', kpi['provider.naver'] || '');
    add('provider.brave', kpi['provider.brave'] || '');
    add('provider.serpapi', kpi['provider.serpapi'] || '');
    add('provider.tavily', kpi['provider.tavily'] || '');
    add('web.naver.skipped.reason', kpi['web.naver.skipped.reason'] || '');
    add('web.brave.skipped.reason', kpi['web.brave.skipped.reason'] || '');
    add('web.serpapi.skipped.reason', kpi['web.serpapi.skipped.reason'] || '');
    add('web.tavily.skipped.reason', kpi['web.tavily.skipped.reason'] || '');
    ['naver','brave','serpapi','tavily'].forEach(function(p){
      const prefix = 'web.' + p;
      add(prefix + '.traceObserved', truthy(kpi[prefix + '.traceObserved']));
      ['failureReason','requestedCount','returnedCount','afterFilterCount','providerEmpty',
       'afterFilterStarved','timeout','timeoutMs','rateLimited','retryAfterMs','cancelled','exceptionType']
        .forEach(function(metric){ add(prefix + '.' + metric, providerMetric(p, metric)); });
    });
    add('vectorFallback.used', kpi['vectorFallback.used'] || false);
    add('vectorFallback.reason', kpi['vectorFallback.reason'] || '');
    add('vectorFallback.effectiveTopK', kpi['vectorFallback.effectiveTopK'] || 0);
    add('starvationFallback.trigger', kpi['starvationFallback.trigger'] || '');
    add('starvationFallback.used', kpi['starvationFallback.used'] || false);
    add('starvationFallback.poolUsed', kpi['starvationFallback.poolUsed'] || '');
    add('starvationFallback.count', kpi['starvationFallback.count'] || 0);
    add('starvationFallback.added', kpi['starvationFallback.added'] || 0);
    add('starvationFallback.pool.safe.size', kpi['starvationFallback.pool.safe.size'] || 0);
    add('starvationFallback.pool.dev.size', kpi['starvationFallback.pool.dev.size'] || 0);
    add('poolSafeEmpty', kpi['poolSafeEmpty'] || false);
    add('ecosystem.recirculate.used', kpi['ecosystem.recirculate.used'] || false);
    add('ecosystem.recirculate.count', kpi['ecosystem.recirculate.count'] || 0);
    add('ecosystem.recirculate.safe', kpi['ecosystem.recirculate.safe'] || 0);
    add('ecosystem.recirculate.allUnverified', kpi['ecosystem.recirculate.allUnverified'] || false);
    add('ecosystem.pool.size', kpi['ecosystem.pool.size'] || 0);
    add('ecosystem.recycled.total', kpi['ecosystem.recycled.total'] || 0);
    add('ecosystem.ammonia.score', kpi['ecosystem.ammonia.score'] || '');
    add('ecosystem.ammonia.quarantined', kpi['ecosystem.ammonia.quarantined'] || 0);
    add('ecosystem.ammonia.safe', kpi['ecosystem.ammonia.safe'] || 0);
    add('ecosystem.ammonia.threshold', kpi['ecosystem.ammonia.threshold'] || '');
    add('ecosystem.ammonia.surgeBlocked', kpi['ecosystem.ammonia.surgeBlocked'] || false);
    add('web.await.ok.count', kpi['web.await.ok.count']||0);
    add('web.await.skipped.count', kpi['web.await.skipped.count']||0);
    add('web.await.timeout.count', kpi['web.await.timeout.count']||0);
    add('web.await.timeout.all', kpi['web.await.timeout.all']||false);
    add('web.await.events.timeoutAll', kpi['web.await.events.timeoutAll']||false);
    add('web.failsoft.rateLimitBackoff.max.remainingMs', kpi['web.failsoft.rateLimitBackoff.max.remainingMs']||0);
    ['naver','brave','serpapi','tavily'].forEach(p => {
      add('web.failsoft.rateLimitBackoff.' + p + '.last.kind', kpi['web.failsoft.rateLimitBackoff.' + p + '.last.kind'] || '');
      add('web.failsoft.rateLimitBackoff.' + p + '.last.delayMs', kpi['web.failsoft.rateLimitBackoff.' + p + '.last.delayMs'] || 0);
      add('web.failsoft.rateLimitBackoff.' + p + '.last.baseMs', kpi['web.failsoft.rateLimitBackoff.' + p + '.last.baseMs'] || 0);
      add('web.failsoft.rateLimitBackoff.' + p + '.last.jitterMs', kpi['web.failsoft.rateLimitBackoff.' + p + '.last.jitterMs'] || 0);
      add('web.failsoft.rateLimitBackoff.' + p + '.last.capMs', kpi['web.failsoft.rateLimitBackoff.' + p + '.last.capMs'] || 0);
      add('web.failsoft.rateLimitBackoff.' + p + '.last.timeoutHintMs', kpi['web.failsoft.rateLimitBackoff.' + p + '.last.timeoutHintMs'] || 0);
      add('web.failsoft.rateLimitBackoff.' + p + '.last.retryAfterMs', kpi['web.failsoft.rateLimitBackoff.' + p + '.last.retryAfterMs'] || 0);
    });
    add('qtx.softCooldown.active', kpi['qtx.softCooldown.active']||false);
    add('qtx.softCooldown.remainingMs', kpi['qtx.softCooldown.remainingMs']||0);
    add('keywordSelection.qtxSoftCooldown.oneShot.used', kpi['keywordSelection.qtxSoftCooldown.oneShot.used']||false);

    $kpi.innerHTML = '<div style="display:flex;justify-content:space-between;align-items:center;gap:10px">' +
      '<b>Key KPIs</b>' +
      '<span class="muted">(stable schema; see /last?format=line for NDJSON)</span>' +
      '</div>' +
      '<table style="margin-top:8px">' + rows.join('') + '</table>';
    $kpi.style.display = 'block';
  }

  function computeStreak(items, pred){
    let s = 0;
    for (let i = 0; i < items.length; i++) {
      const it = items[i] || {};
      const k = it.kpi || {};
      if (pred(k)) s++; else break;
    }
    return s;
  }

  function renderRecent(data){
    if (!data || data.error) {
      $recent.innerHTML = '<b>Recent</b>: <span class="muted">(unavailable)</span>';
      return;
    }
    const items = data.items || [];
    const outZero = computeStreak(items, (k) => toNum(k.outCount) === 0);
    const awaitAll = computeStreak(items, (k) => truthy(k['web.await.timeout.all']));

    const head =
      '<div style="display:flex;justify-content:space-between;align-items:center">' +
      '<b>Recent</b> <span class="muted">limit=' + esc(data.limit) + '</span>' +
      '<span class="badge">outCount=0 streak=' + esc(outZero) + '</span> ' +
      '<span class="badge">await.timeoutAll streak=' + esc(awaitAll) + '</span>' +
      '</div>';

    let pre = '';
    const show = Math.min(12, items.length);
    for (let i = 0; i < show; i++) {
      const it = items[i] || {};
      const line = it.jsonLine || '';
      pre += line + (i < show-1 ? '\n' : '');
    }
    $recent.innerHTML = head + '<pre class="mono" style="margin-top:10px;max-height:260px;overflow:auto;border:1px solid #eee;border-radius:8px;padding:10px;background:#fbfbfb">' + esc(pre) + '</pre>';
  }

  async function refreshAll(){
    const last = await fetchJson('/last?format=json');
    renderLast(last);
    if ($recent.style.display !== 'none') {
      const rec = await fetchJson('/recent?limit=60&format=json');
      renderRecent(rec);
    }
  }

  $save.addEventListener('click', function(){ setKey($k.value || ''); refreshAll(); });
  $refresh.addEventListener('click', function(){ refreshAll(); });
  $toggleRecent.addEventListener('click', function(){
    $recent.style.display = ($recent.style.display === 'none') ? 'block' : 'none';
    if ($recent.style.display !== 'none') refreshAll();
  });

  let timer = null;
  function resetTimer(){
    if (timer) { clearInterval(timer); timer = null; }
    const ms = Math.max(500, toNum($refreshMs.value));
    timer = setInterval(refreshAll, ms);
  }
  $refreshMs.addEventListener('change', resetTimer);

  resetTimer();
  refreshAll();
})();
</script>
</body>
</html>
""";
    }

    private static class AuthResult {
        final boolean authorized;
        final String authSource; // header | query | none
        final String denyReason;

        private AuthResult(boolean authorized, String authSource, String denyReason) {
            this.authorized = authorized;
            this.authSource = authSource;
            this.denyReason = denyReason;
        }

        static AuthResult ok(String authSource) {
            return new AuthResult(true, authSource, null);
        }

        static AuthResult deny(String reason) {
            return new AuthResult(false, "", reason);
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }


    @Data
    public static class Request {
        /**
         * Total number of calls (20~50 recommended).
         */
        private Integer iterations;

        /**
         * topK for HybridWebSearchProvider.search(query, topK)
         */
        private Integer topK;

        /**
         * Single query (used when queries is empty and baseline query extraction is off/failed)
         */
        private String query;

        /**
         * Explicit queries (cycled). If provided, baseline query extraction is skipped.
         */
        private java.util.List<String> queries;

        /**
         * Baseline log file path (e.g., ./X_Brave.txt)
         */
        private String baselineFile;

        /**
         * When true (default), tries to extract queries from baselineFile ("Search Trace - query: ...")
         */
        private Boolean useBaselineQueries;

        /**
         * Optional: BRAVE / NAVER. If set, will be applied to GuardContext.webPrimary for each run.
         */
        private String webPrimary;

        /**
         * Optional: sleep between calls (ms) to reduce rate-limit pressure.
         */
        private Long sleepMsBetween;

        /**
         * Optional: when true, sets MDC dbgSearch=1 for richer await event traces.
         */
        private Boolean dbgSearch;
    }
}
