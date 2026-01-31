package ai.abandonware.nova.orch.web.brave;

import ai.abandonware.nova.config.NovaBraveAdaptiveQpsProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.web.BraveSearchService;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RestTemplate interceptor that reads Brave {@code X-RateLimit-*} headers and:
 * <ul>
 * <li>Updates local monthlyRemaining (best-effort)</li>
 * <li>Installs a short cooldown when per-second remaining reaches 0</li>
 * <li>Dynamically downshifts the local QPS when monthly quota is near
 * exhaustion</li>
 * <li>Optionally applies an EMA-based penalty when per-second remaining=0 is
 * frequently observed</li>
 * </ul>
 *
 * <p>
 * This is intentionally <b>fail-soft</b>: it must never interfere with the HTTP
 * call.
 * </p>
 */
public class BraveAdaptiveQpsRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BraveAdaptiveQpsRestTemplateInterceptor.class);

    private final NovaBraveAdaptiveQpsProperties props;
    private final RateLimiter rateLimiter; // may be null (fail-soft)
    private final AtomicLong cooldownUntilEpochMs; // may be null
    private final AtomicInteger monthlyRemaining; // may be null
    private final BraveSearchService brave;
    private final Field quotaExhaustedField; // may be null
    private final Field enabledField; // may be null
    private final Field disabledReasonField; // may be null
    private final BraveRateLimitState state;

    /** Base local QPS target we try to recover towards (clamped). */
    private final double baseQps;

    /**
     * EMA for "per-second remaining hit 0" pressure.
     * Stored as milli (0..1000) to keep updates lock-free.
     */
    private final AtomicLong perSecondPenaltyEmaMilli = new AtomicLong(0L);



    /** Consecutive HTTP 429 counter for local exponential backoff (best-effort). */
    private final AtomicInteger consecutive429 = new AtomicInteger(0);
    /** Last applied rate limiter value (best-effort). */
    private volatile double lastAppliedQps;

    public BraveAdaptiveQpsRestTemplateInterceptor(
            NovaBraveAdaptiveQpsProperties props,
            RateLimiter rateLimiter,
            AtomicLong cooldownUntilEpochMs,
            AtomicInteger monthlyRemaining,
            BraveSearchService brave,
            Field quotaExhaustedField,
            Field enabledField,
            Field disabledReasonField,
            BraveRateLimitState state) {

        this.props = Objects.requireNonNull(props);
        this.rateLimiter = rateLimiter;
        this.cooldownUntilEpochMs = cooldownUntilEpochMs;
        this.monthlyRemaining = monthlyRemaining;
        this.brave = Objects.requireNonNull(brave);
        this.quotaExhaustedField = quotaExhaustedField;
        this.enabledField = enabledField;
        this.disabledReasonField = disabledReasonField;
        this.state = Objects.requireNonNull(state);
        if (this.quotaExhaustedField != null) {
            this.quotaExhaustedField.setAccessible(true);
        }
        if (this.enabledField != null) {
            this.enabledField.setAccessible(true);
        }
        if (this.disabledReasonField != null) {
            this.disabledReasonField.setAccessible(true);
        }

        double cur = safeGetRate(rateLimiter);
        if (cur <= 0d) {
            cur = props.getMaxQps();
        }
        this.baseQps = clamp(cur, props.getMinQps(), props.getMaxQps());
        this.lastAppliedQps = cur;
    }

    @Override
    public ClientHttpResponse intercept(org.springframework.http.HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse resp = execution.execute(request, body);

        try {
            onResponse(resp);
        } catch (Throwable t) {
            // Fail-soft: never interfere.
            log.debug("[nova][brave-adaptive-qps] interceptor failed (ignored): {}", t.toString());
        }

        return resp;
    }

    private void onResponse(ClientHttpResponse resp) throws IOException {
        if (resp == null) {
            return;
        }

        HttpStatus status;
        try {
            status = HttpStatus.resolve(resp.getStatusCode().value());
        } catch (Exception ignore) {
            status = null;
        }

        HttpHeaders h;
        try {
            h = resp.getHeaders();
        } catch (Exception ignore) {
            return;
        }
        if (h == null) {
            return;
        }

        // Brave: comma-separated values (per-second, per-month)
        RatePair remaining = RatePair.parse(h.getFirst("X-RateLimit-Remaining"));
        RatePair reset = RatePair.parse(h.getFirst("X-RateLimit-Reset"));
        RatePair limit = RatePair.parse(h.getFirst("X-RateLimit-Limit"));

        boolean is429 = (status != null && status.value() == 429);


        if (!is429) {
            consecutive429.set(0);
        }

        // Trace headers (best-effort)
        if (props.isTraceHeaders()) {
            try {
                if (limit != null) {
                    TraceStore.put("brave.ratelimit.limit.second", limit.first);
                    TraceStore.put("brave.ratelimit.limit.month", limit.second);
                }
                if (remaining != null) {
                    TraceStore.put("brave.ratelimit.remaining.second", remaining.first);
                    TraceStore.put("brave.ratelimit.remaining.month", remaining.second);
                }
                if (reset != null) {
                    TraceStore.put("brave.ratelimit.reset.second", reset.first);
                    TraceStore.put("brave.ratelimit.reset.month", reset.second);
                }
                if (status != null) {
                    TraceStore.put("brave.ratelimit.lastStatus", status.value());
                }
            } catch (Exception ignore) {
            }
        }

        final long now = System.currentTimeMillis();

        // Monthly (second window is monthly)
        long monthLimit = (limit == null) ? -1L : limit.second;
        long monthRemainingVal = (remaining == null) ? -1L : remaining.second;
        long monthResetRawSec = (reset == null) ? -1L : reset.second;

        boolean monthlyUnlimited = (monthLimit == 0L);

        // Per-second values (first window is per-second)
        long secLimit = (limit == null) ? -1L : limit.first;
        long secRemaining = (remaining == null) ? -1L : remaining.first;
        long secResetRawSec = (reset == null) ? -1L : reset.first;

        // Brave docs describe reset values as seconds-from-now; some gateways use epoch-seconds.
        // Normalize to delta-seconds for calculations, and compute epoch-ms snapshots in state.
        long monthResetSec = normalizeResetSeconds(monthResetRawSec, now);
        long secResetSec = normalizeResetSeconds(secResetRawSec, now);

        try {
            state.updateResets(secResetRawSec, monthResetRawSec, now);
            if (props.isTraceHeaders()) {
                TraceStore.put("brave.ratelimit.resetAt.secondEpochMs", state.secResetAtEpochMs());
                TraceStore.put("brave.ratelimit.resetAt.monthEpochMs", state.monthResetAtEpochMs());
                TraceStore.put("brave.ratelimit.reset.raw.second", secResetRawSec);
                TraceStore.put("brave.ratelimit.reset.raw.month", monthResetRawSec);
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        // (A) Update BraveSearchService.monthlyRemaining when present and finite.
        if (!monthlyUnlimited && monthRemainingVal >= 0L && this.monthlyRemaining != null) {
            int v = (monthRemainingVal > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) monthRemainingVal;
            this.monthlyRemaining.set(v);
        }

        // (A2) If Brave indicates monthly remaining > 0, clear any previous quota-exhausted latch.
        if (!monthlyUnlimited && monthLimit > 0L && monthRemainingVal > 0L) {
            tryClearQuotaLatchIfAny("monthRemaining>0");
        }

        // (B) Per-second remaining hit 0 → short cooldown until reset.
        if (props.isApplyPerSecondCooldown() && secRemaining == 0L && secResetSec > 0L) {
            long jitter = Math.max(0L, props.getPerSecondCooldownJitterMs());
            long extra = (jitter == 0L) ? 0L : ThreadLocalRandom.current().nextLong(0L, jitter + 1L);
            long ms = Math.min(props.getMaxCooldownMs(), secResetSec * 1000L + extra);
            installCooldownUntil(now + ms, "perSecondRemaining=0");
        }

        // (C) 429 → cooldown. Prefer Retry-After if present, but also apply local exp-backoff to
        // avoid repeated fast re-entry when Retry-After is missing/short.
        if (is429) {
            int n429 = consecutive429.updateAndGet(v -> (v >= 1000) ? 1000 : (v + 1));

            long retryAfterMs = -1L;
            if (props.isUseRetryAfter()) {
                retryAfterMs = parseRetryAfterMs(h.getFirst("Retry-After"));
            }

            long headerMs = retryAfterMs;
            if (headerMs <= 0L && secResetSec > 0L) {
                headerMs = secResetSec * 1000L;
            }

            long expBackoffMs = 0L;
            if (props.isHttp429ExpBackoffEnabled()) {
                expBackoffMs = computeExpBackoffMs(n429, props.getHttp429ExpBackoffBaseMs(),
                        props.getHttp429ExpBackoffMaxMs());
            }

            long baseMs = Math.max(0L, Math.max(headerMs, expBackoffMs));

            if (baseMs > 0L) {
                baseMs = Math.min(baseMs, props.getMaxCooldownMs());

                long jitter = Math.max(0L, props.getPerSecondCooldownJitterMs());
                long extra = (jitter == 0L) ? 0L : ThreadLocalRandom.current().nextLong(0L, jitter + 1L);
                long effectiveDelayMs = Math.min(props.getMaxCooldownMs(), baseMs + extra);

                installCooldownUntil(now + effectiveDelayMs, "http429");

                try {
                    TraceStore.put("web.brave.cooldown.retryAfterMs", retryAfterMs);
                    TraceStore.put("web.brave.cooldown.expBackoffMs", expBackoffMs);
                    TraceStore.put("web.brave.cooldown.jitterMs", extra);
                    TraceStore.put("web.brave.cooldown.effectiveDelayMs", effectiveDelayMs);
                    TraceStore.put("web.brave.cooldown.consecutive429", n429);
                } catch (Exception ignore) {
                }
            }

            // Mark request-level webRateLimited (best-effort)
            trySetWebRateLimited("http429");
        }

        // (D) Monthly quota exhausted → latch until reset and mark provider as operationally disabled.
        if (!monthlyUnlimited && monthLimit > 0L && monthRemainingVal == 0L) {
            long resetAtEpochMs = 0L;
            try {
                resetAtEpochMs = state.monthResetAtEpochMs();
            } catch (Exception ignore) {
                // fail-soft
            }
            if (resetAtEpochMs <= 0L && monthResetSec > 0L) {
                resetAtEpochMs = now + (monthResetSec * 1000L);
            }
            if (resetAtEpochMs <= 0L) {
                resetAtEpochMs = BraveRateLimitState.computeNextMonthStartUtcEpochMs(now);
            }

            try {
                state.latchQuotaUntil(resetAtEpochMs);
            } catch (Exception ignore) {
                // fail-soft
            }

            setQuotaExhaustedTrue();
            setOperationallyDisabledUntil(resetAtEpochMs, "quota_exhausted");

            long remainingMs = Math.max(0L, resetAtEpochMs - now);

            // Also apply a short cooldown to reduce near-term repeated probes (cap applies).
            if (remainingMs > 0L) {
                long ms = Math.min(props.getMaxCooldownMs(), remainingMs);
                installCooldownUntil(now + ms, "monthRemaining=0");
            }

            try {
                TraceStore.put("web.brave.quota.exhaustedUntilEpochMs", resetAtEpochMs);
                TraceStore.put("web.brave.quota.exhaustedRemainingMs", remainingMs);
            } catch (Exception ignore) {
                // ignore
            }

            trySetWebRateLimited("monthRemaining=0");
        }

        // (E) Compute monthly-based base target (spread remaining over time-to-reset).
        double monthlyTarget = baseQps;
        boolean monthlyAdaptive = false;
        if (rateLimiter != null && !monthlyUnlimited && monthLimit > 0L && monthRemainingVal > 0L
                && monthResetSec > 0L) {
            long threshold = Math.max(0L, props.getMonthlyRemainingThreshold());
            if (monthRemainingVal <= threshold) {
                double spread = (monthRemainingVal / (double) monthResetSec);
                double target = spread * Math.max(0.01d, props.getSafetyFactor());
                monthlyTarget = clamp(target, props.getMinQps(), props.getMaxQps());
                monthlyAdaptive = true;
            }
        }

        // (F) Per-second penalty (EMA) when remaining=0 is frequently observed.
        double penaltyFactor = 1.0d;
        boolean penaltyActive = false;
        if (rateLimiter != null && props.isPerSecondPenaltyEnabled()) {
            boolean haveSecInfo = (secRemaining >= 0L || secLimit >= 0L || secResetSec >= 0L);
            boolean event = (secRemaining == 0L)
                    || (props.isPerSecondPenaltyIncludeHttp429() && is429);

            // Update EMA when we have a signal. Also update with event=0 when we have
            // headers (recovery).
            if (haveSecInfo || is429) {
                updatePerSecondPenaltyEma(event);
            }

            double ema = perSecondPenaltyEmaMilli.get() / 1000.0d;
            double minFactor = clamp(props.getPerSecondPenaltyMinFactor(), 0.05d, 1.0d);
            penaltyFactor = 1.0d - ema * (1.0d - minFactor);
            penaltyFactor = clamp(penaltyFactor, minFactor, 1.0d);
            penaltyActive = ema >= 0.01d;

            if (props.isTracePenalty()) {
                try {
                    TraceStore.put("brave.ratelimit.penalty.ema", ema);
                    TraceStore.put("brave.ratelimit.penalty.factor", penaltyFactor);
                    TraceStore.put("brave.ratelimit.penalty.event", event);
                    TraceStore.put("brave.ratelimit.baseQps", baseQps);
                } catch (Exception ignore) {
                }
            }
        }

        // (G) Apply effective QPS target.
        if (rateLimiter != null) {
            double target = clamp(monthlyTarget * penaltyFactor, props.getMinQps(), props.getMaxQps());

            double prev = lastAppliedQps;
            if (prev <= 0d) {
                prev = safeGetRate(rateLimiter);
            }
            if (prev <= 0d) {
                prev = target;
            }

            // Avoid churn.
            if (Math.abs(target - prev) >= 0.005d) {
                rateLimiter.setRate(target);
                lastAppliedQps = target;
                if (props.isTracePenalty() || props.isTraceHeaders()) {
                    try {
                        TraceStore.put("brave.ratelimit.effectiveQps", target);
                        if (monthlyAdaptive) {
                            TraceStore.put("brave.ratelimit.effectiveQps.reason", "monthly_spread");
                        } else if (penaltyActive) {
                            TraceStore.put("brave.ratelimit.effectiveQps.reason", "perSecond_penalty");
                        }
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    private void updatePerSecondPenaltyEma(boolean event) {
        double alpha = props.getPerSecondPenaltyEmaAlpha();
        if (Double.isNaN(alpha) || alpha <= 0d) {
            return;
        }
        if (alpha > 1d) {
            alpha = 1d;
        }
        long prev = perSecondPenaltyEmaMilli.get();
        double prevEma = prev / 1000.0d;
        double x = event ? 1.0d : 0.0d;
        double next = prevEma + (x - prevEma) * alpha;

        long nextMilli = Math.max(0L, Math.min(1000L, Math.round(next * 1000.0d)));
        perSecondPenaltyEmaMilli.set(nextMilli);
    }

    private void trySetWebRateLimited(String reason) {
        try {
            // Brave 429 / quota exhaustion should be treated as provider-scoped
            // partial-down.
            // Do NOT flip GuardContext.webRateLimited here (that is reserved for effective
            // web-down).
            GuardContext ctx = GuardContextHolder.getOrDefault();
            if (ctx != null) {
                // Hint the hybrid layer to prefer Naver for this request.
                ctx.putPlanOverride("search.web.preferNaver", true);
                ctx.putPlanOverride("web.preferNaver", true);
                String cur = ctx.getWebPrimary();
                if (cur == null || cur.isBlank() || "BRAVE".equalsIgnoreCase(cur)) {
                    ctx.setWebPrimary("NAVER");
                }
            }

            // Trace: mark partial-down, not global webRateLimited.
            TraceStore.put("orch.webPartialDown", true);
            TraceStore.put("orch.webPartialDown.braveDown", true);
            TraceStore.put("orch.webRateLimited.anyDown", true);
            TraceStore.put("orch.webRateLimited.braveDown", true);
            if (reason != null && !reason.isBlank()) {
                TraceStore.putIfAbsent("orch.webPartialDown.reason", reason);
            }
        } catch (Exception ignore) {
        }
    }

    private void installCooldownUntil(long untilEpochMs, String reason) {
        if (cooldownUntilEpochMs == null) {
            return;
        }
        long prev = cooldownUntilEpochMs.get();
        if (untilEpochMs > prev) {
            cooldownUntilEpochMs.set(untilEpochMs);
            if (props.isTraceHeaders() || props.isTracePenalty()) {
                try {
                    TraceStore.put("brave.ratelimit.cooldownUntilEpochMs", untilEpochMs);
                    if (reason != null) {
                        TraceStore.put("brave.ratelimit.cooldownReason", reason);
                    }
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void setQuotaExhaustedTrue() {
        if (quotaExhaustedField == null) {
            return;
        }
        try {
            quotaExhaustedField.set(brave, Boolean.TRUE);
        } catch (Exception ignore) {
        }
    }

    private void setQuotaExhaustedFalse() {
        if (quotaExhaustedField == null) {
            return;
        }
        try {
            quotaExhaustedField.set(brave, Boolean.FALSE);
        } catch (Exception ignore) {
        }
    }

    private void tryClearQuotaLatchIfAny(String why) {
        try {
            state.clearQuotaLatch();
        } catch (Exception ignore) {
        }

        // Clear the underlying latch too (best-effort).
        setQuotaExhaustedFalse();

        // If we operationally disabled Brave due to quota, re-enable.
        try {
            if (enabledField != null && disabledReasonField != null) {
                Object cur = disabledReasonField.get(brave);
                String s = (cur == null) ? "" : String.valueOf(cur);
                if (s.startsWith("quota_exhausted")) {
                    enabledField.set(brave, Boolean.TRUE);
                    disabledReasonField.set(brave, "");
                }
            }
        } catch (Exception ignore) {
        }

        try {
            TraceStore.put("brave.ratelimit.quotaLatch.cleared", true);
            if (why != null) {
                TraceStore.put("brave.ratelimit.quotaLatch.cleared.reason", why);
            }
        } catch (Exception ignore) {
        }
    }

    private void setOperationallyDisabledUntil(long untilEpochMs, String kind) {
        long now = System.currentTimeMillis();
        long remainingMs = Math.max(0L, untilEpochMs - now);
        String reason = (kind == null || kind.isBlank())
                ? ("quota_exhausted remainingMs=" + remainingMs)
                : (kind + " remainingMs=" + remainingMs);

        // Request-scoped visibility (EvidenceListTraceInjectionAspect reads this key).
        try {
            TraceStore.put("web.await.brave.disabledReason", reason);
        } catch (Exception ignore) {
        }

        // Also mark canonical skip keys so downstream policy can reason about it.
        try {
            TraceStore.put("web.brave.skipped", true);
            TraceStore.putIfAbsent("web.brave.skipped.reason", "disabled");
            TraceStore.putIfAbsent("web.brave.skipped.stage", "quota_exhausted");
            TraceStore.put("web.brave.disabled.remainingMs", remainingMs);
            TraceStore.put("web.brave.disabled.untilEpochMs", untilEpochMs);
        } catch (Exception ignore) {
        }

        // Make HybridWebSearchProvider skip scheduling Brave (it checks braveService.isEnabled()).
        try {
            if (enabledField != null && disabledReasonField != null) {
                Object cur = disabledReasonField.get(brave);
                String s = (cur == null) ? "" : String.valueOf(cur);

                // Do not override a hard disable due to config/key.
                boolean hardDisabled = s.startsWith("missing_api_key") || s.startsWith("disabled_by_config");
                if (!hardDisabled) {
                    enabledField.set(brave, Boolean.FALSE);
                    disabledReasonField.set(brave, "quota_exhausted untilEpochMs=" + untilEpochMs);
                }
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * Brave docs describe X-RateLimit-Reset values as seconds-from-now.
     * Some gateways return epoch-seconds for x-ratelimit-reset, so normalize to delta seconds.
     */
    private static long normalizeResetSeconds(long rawResetSeconds, long nowEpochMs) {
        if (rawResetSeconds <= 0L) {
            return -1L;
        }
        long nowSec = Math.max(0L, nowEpochMs / 1000L);
        if (rawResetSeconds > (nowSec + 60L)) {
            long delta = rawResetSeconds - nowSec;
            return delta > 0L ? delta : -1L;
        }
        return rawResetSeconds;
    }

    private static double safeGetRate(RateLimiter rl) {
        if (rl == null) {
            return -1d;
        }
        try {
            return rl.getRate();
        } catch (Throwable ignore) {
            return -1d;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return lo;
        }
        double a = Math.max(lo, v);
        return Math.min(hi, a);
    }

    /**
     * Parses Retry-After into milliseconds.
     *
     * <p>
     * HTTP allows either a delta-seconds value or an HTTP-date. We treat
     * delta-seconds
     * as primary, and try an RFC1123 parse as a best-effort fallback.
     * </p>
     */
    
    private static long computeExpBackoffMs(int consecutive429, long baseMs, long maxMs) {
        if (consecutive429 <= 1) {
            return Math.max(0L, baseMs);
        }

        long backoff = Math.max(0L, baseMs);
        long cap = Math.max(0L, maxMs);

        // cap the exponent to avoid overflow / extremely long delays
        int steps = Math.min(20, Math.max(0, consecutive429 - 1));
        for (int i = 0; i < steps; i++) {
            if (cap > 0L && backoff >= cap) {
                backoff = cap;
                break;
            }
            if (cap > 0L && backoff > cap / 2L) {
                backoff = cap;
                break;
            }
            backoff = backoff * 2L;
        }

        if (cap > 0L) {
            backoff = Math.min(backoff, cap);
        }
        return backoff;
    }

private static long parseRetryAfterMs(String raw) {
        if (raw == null) {
            return -1L;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return -1L;
        }

        // delta-seconds
        boolean digits = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                digits = false;
                break;
            }
        }
        if (digits) {
            try {
                long sec = Long.parseLong(t);
                if (sec <= 0L) {
                    return -1L;
                }
                return sec * 1000L;
            } catch (NumberFormatException ignore) {
                return -1L;
            }
        }

        // HTTP-date (RFC1123). Rare for APIs but supported by spec.
        try {
            ZonedDateTime dt = ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME);
            long target = dt.toInstant().toEpochMilli();
            long now = System.currentTimeMillis();
            long delta = target - now;
            return delta <= 0 ? -1L : delta;
        } catch (DateTimeParseException ignore) {
            return -1L;
        }
    }

    /**
     * Simple two-number pair (first window, second window).
     *
     * <p>
     * Brave uses a comma-separated list like: {@code "1, 15000"}.
     * </p>
     */
    static final class RatePair {
        final long first;
        final long second;

        RatePair(long first, long second) {
            this.first = first;
            this.second = second;
        }

        static RatePair parse(String raw) {
            if (raw == null) {
                return null;
            }
            String t = raw.trim();
            if (t.isEmpty()) {
                return null;
            }
            String[] parts = t.split(",");
            long a = -1L;
            long b = -1L;
            if (parts.length >= 1) {
                a = toLong(parts[0]);
            }
            if (parts.length >= 2) {
                b = toLong(parts[1]);
            }
            return new RatePair(a, b);
        }

        private static long toLong(String s) {
            if (s == null) {
                return -1L;
            }
            String t = s.trim();
            if (t.isEmpty()) {
                return -1L;
            }
            // Some servers may append params; strip non-digit except leading minus.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '-' && sb.length() == 0) {
                    sb.append(c);
                    continue;
                }
                if (c >= '0' && c <= '9') {
                    sb.append(c);
                } else {
                    // stop at first non-digit
                    if (sb.length() > 0) {
                        break;
                    }
                }
            }
            if (sb.length() == 0) {
                return -1L;
            }
            try {
                return Long.parseLong(sb.toString());
            } catch (NumberFormatException ignore) {
                return -1L;
            }
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d,%d", first, second);
        }
    }
}
