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
 *     <li>Updates local monthlyRemaining (best-effort)</li>
 *     <li>Installs a short cooldown when per-second remaining reaches 0</li>
 *     <li>Dynamically downshifts the local QPS when monthly quota is near exhaustion</li>
 *     <li>Optionally applies an EMA-based penalty when per-second remaining=0 is frequently observed</li>
 * </ul>
 *
 * <p>This is intentionally <b>fail-soft</b>: it must never interfere with the HTTP call.</p>
 */
public class BraveAdaptiveQpsRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BraveAdaptiveQpsRestTemplateInterceptor.class);

    private final NovaBraveAdaptiveQpsProperties props;
    private final RateLimiter rateLimiter; // may be null (fail-soft)
    private final AtomicLong cooldownUntilEpochMs; // may be null
    private final AtomicInteger monthlyRemaining; // may be null
    private final BraveSearchService brave;
    private final Field quotaExhaustedField; // may be null

    /** Base local QPS target we try to recover towards (clamped). */
    private final double baseQps;

    /**
     * EMA for "per-second remaining hit 0" pressure.
     * Stored as milli (0..1000) to keep updates lock-free.
     */
    private final AtomicLong perSecondPenaltyEmaMilli = new AtomicLong(0L);

    /** Last applied rate limiter value (best-effort). */
    private volatile double lastAppliedQps;

    public BraveAdaptiveQpsRestTemplateInterceptor(
            NovaBraveAdaptiveQpsProperties props,
            RateLimiter rateLimiter,
            AtomicLong cooldownUntilEpochMs,
            AtomicInteger monthlyRemaining,
            BraveSearchService brave,
            Field quotaExhaustedField) {

        this.props = Objects.requireNonNull(props);
        this.rateLimiter = rateLimiter;
        this.cooldownUntilEpochMs = cooldownUntilEpochMs;
        this.monthlyRemaining = monthlyRemaining;
        this.brave = Objects.requireNonNull(brave);
        this.quotaExhaustedField = quotaExhaustedField;
        if (this.quotaExhaustedField != null) {
            this.quotaExhaustedField.setAccessible(true);
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
            status = HttpStatus.resolve(resp.getRawStatusCode());
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
        long monthResetSec = (reset == null) ? -1L : reset.second;

        boolean monthlyUnlimited = (monthLimit == 0L);

        // Per-second values (first window is per-second)
        long secLimit = (limit == null) ? -1L : limit.first;
        long secRemaining = (remaining == null) ? -1L : remaining.first;
        long secResetSec = (reset == null) ? -1L : reset.first;

        // (A) Update BraveSearchService.monthlyRemaining when present and finite.
        if (!monthlyUnlimited && monthRemainingVal >= 0L && this.monthlyRemaining != null) {
            int v = (monthRemainingVal > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) monthRemainingVal;
            this.monthlyRemaining.set(v);
        }

        // (B) Per-second remaining hit 0 → short cooldown until reset.
        if (props.isApplyPerSecondCooldown() && secRemaining == 0L && secResetSec > 0L) {
            long jitter = Math.max(0L, props.getPerSecondCooldownJitterMs());
            long extra = (jitter == 0L) ? 0L : ThreadLocalRandom.current().nextLong(0L, jitter + 1L);
            long ms = Math.min(props.getMaxCooldownMs(), secResetSec * 1000L + extra);
            installCooldownUntil(now + ms, "perSecondRemaining=0");
        }

        // (C) 429 → cooldown. Prefer Retry-After if present.
        if (is429) {
            long ms = -1L;
            if (props.isUseRetryAfter()) {
                ms = parseRetryAfterMs(h.getFirst("Retry-After"));
            }
            if (ms <= 0L && secResetSec > 0L) {
                ms = secResetSec * 1000L;
            }
            if (ms > 0L) {
                ms = Math.min(ms, props.getMaxCooldownMs());
                long jitter = Math.max(0L, props.getPerSecondCooldownJitterMs());
                long extra = (jitter == 0L) ? 0L : ThreadLocalRandom.current().nextLong(0L, jitter + 1L);
                installCooldownUntil(now + ms + extra, "http429");
            }

            // Mark request-level webRateLimited (best-effort)
            trySetWebRateLimited("http429");
        }

        // (D) Monthly quota exhausted → mark quotaExhausted and cool down aggressively.
        if (!monthlyUnlimited && monthLimit > 0L && monthRemainingVal == 0L) {
            setQuotaExhaustedTrue();
            if (monthResetSec > 0L) {
                long ms = Math.min(props.getMaxCooldownMs(), monthResetSec * 1000L);
                installCooldownUntil(now + ms, "monthRemaining=0");
            }
            trySetWebRateLimited("monthRemaining=0");
        }

        // (E) Compute monthly-based base target (spread remaining over time-to-reset).
        double monthlyTarget = baseQps;
        boolean monthlyAdaptive = false;
        if (rateLimiter != null && !monthlyUnlimited && monthLimit > 0L && monthRemainingVal > 0L && monthResetSec > 0L) {
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

            // Update EMA when we have a signal. Also update with event=0 when we have headers (recovery).
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
            GuardContext ctx = GuardContextHolder.getOrDefault();
            if (ctx != null && !ctx.isWebRateLimited()) {
                ctx.setWebRateLimited(true);
            }
            TraceStore.put("orch.webRateLimited", true);
            if (reason != null && !reason.isBlank()) {
                TraceStore.put("orch.webRateLimited.reason", reason);
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
     * HTTP allows either a delta-seconds value or an HTTP-date. We treat delta-seconds
     * as primary, and try an RFC1123 parse as a best-effort fallback.
     * </p>
     */
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
     * <p>Brave uses a comma-separated list like: {@code "1, 15000"}.</p>
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
