package ai.abandonware.nova.orch.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;

import com.example.lms.search.TraceStore;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A tiny in-process backoff/jitter scheduler for HTTP 429 / rate-limit signals.
 *
 * <p>Service 레이어 수정이 어려운 환경에서도, AOP 레이어에서 "지연/스케줄링" 우회 적용이 가능하도록
 * "지금 호출해도 되는가"(cooldown gate)와 "429이 발생했을 때 다음 허용 시각"을 중앙에서 관리합니다.
 *
 * <p>아키텍처:
 * <ul>
 *   <li>429(또는 rate-limit 계열) 신호를 받으면 retry-after(있으면) + exp backoff + jitter로
 *       cooldownUntil을 계산해 저장</li>
 *   <li>다음 호출이 들어오면 cooldownUntil 전까지는 "즉시 스킵"(sleep 대신 gate)하여
 *       burst/retry 폭주를 완화</li>
 * </ul>
 */
public class RateLimitBackoffCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RateLimitBackoffCoordinator.class);

    public static final String PROVIDER_BRAVE = "brave";
    public static final String PROVIDER_NAVER = "naver";
    public static final String PROVIDER_SERPAPI = "serpapi";
    public static final String PROVIDER_TAVILY = "tavily";

    /** Non-429 failure kinds that should participate in the same backoff gate. */
    public enum FailureKind {
        TIMEOUT,
        CANCELLED,
        /** Local join/await timebox expired (not a provider I/O timeout). Keep this shallow. */
        AWAIT_TIMEOUT
    }

    private final Environment env;
    private final ConcurrentHashMap<String, BackoffState> states = new ConcurrentHashMap<>();

    public RateLimitBackoffCoordinator(Environment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    public Decision shouldSkip(String provider) {
        if (!isEnabled()) {
            return Decision.allow();
        }
        BackoffState st = states.computeIfAbsent(safeProvider(provider), p -> new BackoffState());
        long now = System.currentTimeMillis();
        long until = st.cooldownUntilEpochMs.get();
        if (until <= now) {
            return Decision.allow();
        }

        boolean justStarted = false;
        long js = justStartedMs();
        if (js > 0L) {
            long last = st.lastRateLimitEpochMs.get();
            if (last > 0L) {
                justStarted = (now - last) <= js;
            }
        }

        return Decision.skip(until - now, st.lastReason.get(), justStarted);
    }


    public void recordRateLimited(String provider, Long retryAfterMs, String reason) {
        recordRateLimited(provider, retryAfterMs, reason, null);
    }

    /**
     * @param retryAfterValue raw Retry-After header value (delta-seconds or HTTP-date) if available
     */
    public void recordRateLimited(String provider, Long retryAfterMs, String reason, String retryAfterValue) {
        if (!isEnabled()) {
            return;
        }
        BackoffState st = states.computeIfAbsent(safeProvider(provider), p -> new BackoffState());
        long now = System.currentTimeMillis();

        long parsedRetryAfterMs = (retryAfterValue == null || retryAfterValue.isBlank())
                ? 0L
                : parseRetryAfterMs(retryAfterValue, now);
        long expBackoffMs = computeExpBackoffMs(st.consecutive429.get() + 1, minBackoffMs(), maxBackoffMs());

        // Combine Retry-After (provider hint) with exp-backoff (self-protection) using MAX,
        // so we don't underestimate cooldown and cause retry storms.
        long baseMs = maxPositive(
                retryAfterMs,
                (parsedRetryAfterMs > 0L) ? parsedRetryAfterMs : null,
                expBackoffMs
        );
        long clampedBaseMs = clampMs(baseMs, minBackoffMs(), maxBackoffMs());
        long jitterMs = computeJitterMs(clampedBaseMs);
        long delayMs = clampMs(clampedBaseMs + jitterMs, minBackoffMs(), maxBackoffMs());

        long newUntil = now + delayMs;
        st.cooldownUntilEpochMs.updateAndGet(prev -> Math.max(prev, newUntil));
        st.lastRateLimitEpochMs.set(now);
        st.lastReason.set(safeReason(reason));
        st.consecutive429.incrementAndGet();

        // Local throttling streak should not leak into real 429 backoff.
        st.consecutiveLocalRateLimit.set(0);

        // Reset non-429 streaks when we hit a real 429.
        st.consecutiveTimeout.set(0);
        st.consecutiveCancelled.set(0);
        st.consecutiveAwaitTimeout.set(0);

        // Pin-point debugging: always record last backoff decision fields (trace/grep friendly).
        try {
            String p = safeProvider(provider);
            long cap = maxBackoffMs();
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.kind", "RATE_LIMIT");
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.streak", st.consecutive429.get());

            // breakdown: base = max(retryAfter, expBackoff) -> jitter -> cap
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.retryAfterMs", (retryAfterMs == null) ? 0L : retryAfterMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.retryAfterParsedMs", parsedRetryAfterMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.expBackoffMs", expBackoffMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.baseMs", clampedBaseMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.jitterMs", jitterMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.capMs", cap);

            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.delayMs", delayMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.capHit", delayMs >= cap);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.reasonHash", com.example.lms.trace.SafeRedactor.hashValue(reason));
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.reasonLength", reason == null ? 0 : reason.length());
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.tsMs", now);
        } catch (Throwable e) {
            traceSuppressed("recordRateLimited.trace", e);
            // best-effort
        }

        if (isDebug()) {
            log.debug("[Nova] rate-limit backoff set: provider={}, delayMs={}, baseMs={}, jitterMs={}, retryAfterMs={}, retryAfterValueHash={}, reasonHash={}, reasonLength={}",
                    safeProvider(provider), delayMs, clampedBaseMs, jitterMs, retryAfterMs, com.example.lms.trace.SafeRedactor.hashValue(retryAfterValue), com.example.lms.trace.SafeRedactor.hashValue(reason), reason == null ? 0 : reason.length());
        }
    }

    /**
     * Local rate-limit (RATE_LIMIT_LOCAL / COOLDOWN) handling.
     *
     * <p>Unlike real HTTP 429, this is a short, client-side throttle used to smooth QPS bursts.
     * We still apply the same shape (hint + truncated exp backoff + jitter + cap), but keep the
     * cap shallow and do <b>not</b> inflate the global 429 streak.
     */
    public void recordLocalRateLimit(String provider, Long cooldownHintMs, String reason) {
        if (!isEnabled()) {
            return;
        }
        BackoffState st = states.computeIfAbsent(safeProvider(provider), p -> new BackoffState());
        long now = System.currentTimeMillis();

        long min = localMinBackoffMs();
        long max = localMaxBackoffMs();
        int streak = Math.max(1, st.consecutiveLocalRateLimit.incrementAndGet());

        long expBackoffMs = computeExpBackoffMs(streak, min, max);
        long baseMs = maxPositive(cooldownHintMs, null, expBackoffMs);
        long clampedBaseMs = clampMs(baseMs, min, max);
        long jitterMs = computeJitterMs(clampedBaseMs);
        long delayMs = clampMs(clampedBaseMs + jitterMs, min, max);

        long newUntil = now + delayMs;
        st.cooldownUntilEpochMs.updateAndGet(prev -> Math.max(prev, newUntil));
        st.lastRateLimitEpochMs.set(now);
        st.lastReason.set(safeReason(reason));

        // Local throttling should not be treated as hard failure; keep other streaks shallow.
        st.consecutiveTimeout.set(0);
        st.consecutiveCancelled.set(0);
        st.consecutiveAwaitTimeout.set(0);

        // Pin-point debugging: always record last backoff decision fields (trace/grep friendly).
        try {
            String p = safeProvider(provider);
            long cap = localMaxBackoffMs();
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.kind", "RATE_LIMIT_LOCAL");
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.streak", streak);

            // breakdown: base = max(hint, expBackoff) -> jitter -> cap
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.hintMs", (cooldownHintMs == null) ? 0L : cooldownHintMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.expBackoffMs", expBackoffMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.baseMs", clampedBaseMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.jitterMs", jitterMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.capMs", cap);

            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.delayMs", delayMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.capHit", delayMs >= cap);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.reasonHash", com.example.lms.trace.SafeRedactor.hashValue(reason));
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.reasonLength", reason == null ? 0 : reason.length());
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.tsMs", now);
        } catch (Throwable e) {
            traceSuppressed("recordLocalRateLimit.trace", e);
            // best-effort
        }

        if (isDebug()) {
            log.debug("[Nova] local rate-limit backoff set: provider={}, delayMs={}, baseMs={}, jitterMs={}, hintMs={}, reasonHash={}, reasonLength={}",
                    safeProvider(provider), delayMs, clampedBaseMs, jitterMs, cooldownHintMs, com.example.lms.trace.SafeRedactor.hashValue(reason), reason == null ? 0 : reason.length());
        }
    }

    public void recordSuccess(String provider) {
        BackoffState st = states.get(safeProvider(provider));
        if (st == null) {
            return;
        }
        // conservative reset: keep cooldownUntil but reset consecutive counter, so subsequent 429 doesn't explode.
        st.consecutive429.set(0);
        st.consecutiveTimeout.set(0);
        st.consecutiveCancelled.set(0);
        st.consecutiveAwaitTimeout.set(0);
        st.consecutiveLocalRateLimit.set(0);
        st.lastReason.set("");
    }

    /**
     * Record a non-429 failure (e.g., hard timeout) and apply a short, exponential backoff.
     *
     * <p>Motivation: In Hybrid(Brave+Naver) joins, repeated hard timeouts can starve
     * the time budget and cause the other provider to hit {@code budget_exhausted(timeoutMs=0)}.
     * A small cooldown gate prevents tight retry loops within the same request.
     */
    public void recordFailure(String provider, FailureKind kind, String reason) {
        recordFailure(provider, kind, reason, null);
    }

    /**
     * @param detail optional detail string (e.g., exception class) for debugging
     */
    public void recordFailure(String provider, FailureKind kind, String reason, String detail) {
        if (!isEnabled()) {
            return;
        }
        FailureKind k = (kind == null) ? FailureKind.TIMEOUT : kind;
        BackoffState st = states.computeIfAbsent(safeProvider(provider), p -> new BackoffState());
        long now = System.currentTimeMillis();

        if (k == FailureKind.AWAIT_TIMEOUT && !awaitTimeoutEnabled()) {
            try {
                String p = safeProvider(provider);
                TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".awaitTimeout.disabled", true);
            } catch (Throwable e) {
                traceSuppressed("recordFailure.awaitDisabledTrace", e);
                // best-effort
            }
            return;
        }

        long min = switch (k) {
            case CANCELLED -> cancelMinBackoffMs();
            case AWAIT_TIMEOUT -> awaitTimeoutMinBackoffMs();
            default -> timeoutMinBackoffMs();
        };
        long max = switch (k) {
            case CANCELLED -> cancelMaxBackoffMs();
            case AWAIT_TIMEOUT -> awaitTimeoutMaxBackoffMs();
            default -> timeoutMaxBackoffMs();
        };

        int consecutive;
        if (k == FailureKind.CANCELLED) {
            consecutive = st.consecutiveCancelled.incrementAndGet();
            // Cancellation should not be treated as hard failure; keep it shallow.
            st.consecutiveTimeout.set(0);
            st.consecutiveAwaitTimeout.set(0);
        } else if (k == FailureKind.AWAIT_TIMEOUT) {
            // Local join/await timebox: keep shallow and do not let it inflate TIMEOUT streaks.
            consecutive = st.consecutiveAwaitTimeout.incrementAndGet();
            st.consecutiveTimeout.set(0);
            st.consecutiveCancelled.set(0);
        } else {
            consecutive = st.consecutiveTimeout.incrementAndGet();
            st.consecutiveCancelled.set(0);
            st.consecutiveAwaitTimeout.set(0);
        }

        long expBackoffMs = computeExpBackoffMs(consecutive, min, max);

        // Retry-After 패턴을 AWAIT_TIMEOUT(조인/await 타임아웃)에도 확장한다:
        // - expBackoff(연속 실패) + hint(timeoutMs) 중 큰 값을 base로 삼고
        // - jitter를 더한 뒤 cap(max/hardCap)으로 제한한다.
        //
        // Motivation: join timeout 직후에 즉시 재시도하면 같은 지연/슬로우콜이 반복되며 merged=0을
        // 악화시킨다. timeoutMs를 "기다려야 할 힌트"로 취급하면 회복 시간을 벌 수 있다.
        Long timeoutHintMs = (k == FailureKind.AWAIT_TIMEOUT) ? extractTimeoutHintMs(detail) : null;
        long baseWithHint = expBackoffMs;
        if (timeoutHintMs != null && timeoutHintMs > 0L) {
            baseWithHint = Math.max(expBackoffMs, timeoutHintMs);
        }

        long capMs = max;
        long baseClamped = clampMs(baseWithHint, min, capMs);

        if (k == FailureKind.AWAIT_TIMEOUT && timeoutHintMs != null && timeoutHintMs > 0L) {
            // Ensure small headroom so "jitter" remains effective even when base==cap (typical: timeoutMs==max).
            long headroom = Math.max(1L, (long) Math.ceil(baseClamped * jitterRatio()));
            capMs = Math.min(hardCapMs(), Math.max(capMs, baseClamped + headroom));
        }

        long jitterMs = computeJitterMs(baseClamped);
        long delayMs = clampMs(baseClamped + jitterMs, min, capMs);

        long newUntil = now + delayMs;
        st.cooldownUntilEpochMs.updateAndGet(prev -> Math.max(prev, newUntil));
        st.lastRateLimitEpochMs.set(now);
        st.lastReason.set(safeReason(reason));

        // Local rate-limit streak should not leak into TIMEOUT/CANCELLED/AWAIT_TIMEOUT backoff.
        st.consecutiveLocalRateLimit.set(0);

        // Pin-point debugging: always record last backoff decision fields (trace/grep friendly).
        try {
            String p = safeProvider(provider);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.kind", k.name());
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.streak", consecutive);

            // breakdown: base = max(expBackoff, hint) -> jitter -> cap
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.expBackoffMs", expBackoffMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.baseMs", baseClamped);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.jitterMs", jitterMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.capMs", capMs);

            if (timeoutHintMs != null && timeoutHintMs > 0L) {
                // keep backward compatible key
                TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.hintMs", timeoutHintMs);
                TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.timeoutHintMs", timeoutHintMs);
            }

            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.delayMs", delayMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.capHit", delayMs >= capMs);
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.reasonHash", com.example.lms.trace.SafeRedactor.hashValue(reason));
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.reasonLength", reason == null ? 0 : reason.length());
            TraceStore.put("web.failsoft.rateLimitBackoff." + p + ".last.tsMs", now);
        } catch (Throwable e) {
            traceSuppressed("recordFailure.trace", e);
            // best-effort
        }

        if (isDebug()) {
            log.debug("[Nova] backoff set: provider={}, kind={}, delayMs={}, expBackoffMs={}, baseMs={}, jitterMs={}, capMs={}, reasonHash={}, reasonLength={}, detailHash={}, detailLength={}",
                    safeProvider(provider), k, delayMs, expBackoffMs, baseClamped, jitterMs, capMs, com.example.lms.trace.SafeRedactor.hashValue(reason), reason == null ? 0 : reason.length(), com.example.lms.trace.SafeRedactor.hashValue(detail), detail == null ? 0 : detail.length());
        }
    }

    private long localMinBackoffMs() {
        return clampMs(getLong(120L,
                "nova.orch.web.failsoft.ratelimit-backoff.local.min-ms",
                "nova.orch.web.failsoft.rateLimitBackoff.local.min-ms"), 0L, 5_000L);
    }

    private long localMaxBackoffMs() {
        long cfg = clampMs(getLong(1_500L,
                "nova.orch.web.failsoft.ratelimit-backoff.local.max-ms",
                "nova.orch.web.failsoft.rateLimitBackoff.local.max-ms"), 200L, 60_000L);
        return Math.min(cfg, hardCapMs());
    }

    private boolean isEnabled() {
        return getBoolean(true,
                "nova.orch.web.failsoft.ratelimit-backoff.enabled",
                "nova.orch.web.failsoft.rateLimitBackoff.enabled",
                "nova.orch.web.ratelimit.aop-backoff.enabled");
    }

    private boolean awaitTimeoutEnabled() {
        return getBoolean(true,
                "nova.orch.web.failsoft.ratelimit-backoff.await-timeout.enabled",
                "nova.orch.web.failsoft.rateLimitBackoff.awaitTimeout.enabled",
                "nova.orch.web.failsoft.rateLimitBackoff.await-timeout.enabled");
    }

    private boolean isDebug() {
        return getBoolean(false,
                "nova.orch.web.failsoft.ratelimit-backoff.debug",
                "nova.orch.web.failsoft.rateLimitBackoff.debug",
                "nova.orch.web.ratelimit.aop-backoff.debug");
    }

    private long minBackoffMs() {
        long cfg = clampMs(getLong(200L,
                "nova.orch.web.failsoft.ratelimit-backoff.min-ms",
                "nova.orch.web.ratelimit.aop-backoff.min-ms"), 0L, 60_000L);
        return Math.min(cfg, hardCapMs());
    }

    /**
     * Hard safety cap for any "soft cooldown" backoff.
     *
     * <p>
     * We intentionally clamp to <=10s to avoid long provider cooldowns causing
     * evidence starvation (merged=0) in fail-soft loops.
     * </p>
     */
    private long hardCapMs() {
        // Always keep this at <= 10s even if misconfigured.
        // Zero100: allow a per-request hard cap (smaller) so overlapping provider cooldowns
        // don't create long "all skipped" windows that amplify starvation.
        long dyn = 0L;
        try {
            Object v = TraceStore.get("zero100.backoff.hardCapMs");
            if (v != null) {
                dyn = toLong(v);
            }
        } catch (Throwable e) {
            traceSuppressed("hardCap.zero100Trace", e);
            dyn = 0L;
        }

        long cfg = clampMs(getLong(10_000L,
                "nova.orch.web.failsoft.ratelimit-backoff.hard-cap-ms",
                "nova.orch.web.failsoft.ratelimit-backoff.hardCapMs",
                "nova.orch.web.failsoft.rateLimitBackoff.hardCapMs"), 200L, 10_000L);

        if (dyn > 0L) {
            cfg = Math.min(cfg, clampMs(dyn, 200L, 10_000L));
        }

        return cfg;
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                traceSuppressed("toLong", null);
                return 0L;
            }
            return n.longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            traceSuppressed("toLong", e);
            return 0L;
        }
    }

    private long maxBackoffMs() {
        long cfg = clampMs(getLong(30_000L,
                "nova.orch.web.failsoft.ratelimit-backoff.max-ms",
                "nova.orch.web.ratelimit.aop-backoff.max-ms"), 1_000L, 5 * 60_000L);
        return Math.min(cfg, hardCapMs());
    }

    private long justStartedMs() {
        return clampMs(getLong(80L,
                "nova.orch.web.failsoft.ratelimit-backoff.just-started-ms",
                "nova.orch.web.ratelimit.aop-backoff.just-started-ms"), 0L, 5_000L);
    }

    private double jitterRatio() {
        return clampDouble(getDouble(0.20,
                "nova.orch.web.failsoft.ratelimit-backoff.jitter-ratio",
                "nova.orch.web.ratelimit.aop-backoff.jitter-ratio"), 0.0, 1.0);
    }

    private long computeJitterMs(long baseMs) {
        double r = jitterRatio();
        if (r <= 0.0 || baseMs <= 0L) {
            return 0L;
        }
        long maxJitter = (long) Math.floor(baseMs * r);
        if (maxJitter <= 0L) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(0L, maxJitter + 1L);
    }

    private long computeExpBackoffMs(int consecutive, long baseMinMs, long baseMaxMs) {
        int n = Math.max(1, consecutive);
        int capped = Math.min(n, 10); // 2^10 = 1024x cap
        long min = Math.max(1L, baseMinMs);
        long backoff = min * (1L << (capped - 1));
        return clampMs(backoff, baseMinMs, baseMaxMs);
    }

    private long timeoutMinBackoffMs() {
        return clampMs(getLong(800L,
                "nova.orch.web.failsoft.ratelimit-backoff.timeout.min-ms",
                "nova.orch.web.failsoft.rateLimitBackoff.timeout.min-ms"), 0L, 60_000L);
    }

    private long timeoutMaxBackoffMs() {
        long cfg = clampMs(getLong(20_000L,
                "nova.orch.web.failsoft.ratelimit-backoff.timeout.max-ms",
                "nova.orch.web.failsoft.rateLimitBackoff.timeout.max-ms"), 1_000L, 5 * 60_000L);
        return Math.min(cfg, hardCapMs());
    }

    private long cancelMinBackoffMs() {
        return clampMs(getLong(120L,
                "nova.orch.web.failsoft.ratelimit-backoff.cancel.min-ms",
                "nova.orch.web.failsoft.rateLimitBackoff.cancel.min-ms"), 0L, 5_000L);
    }

    private long cancelMaxBackoffMs() {
        long cfg = clampMs(getLong(2_000L,
                "nova.orch.web.failsoft.ratelimit-backoff.cancel.max-ms",
                "nova.orch.web.failsoft.rateLimitBackoff.cancel.max-ms"), 200L, 60_000L);
        return Math.min(cfg, hardCapMs());
    }

    /**
     * Join/await timebox exceeded (await_timeout) is not a provider I/O timeout; keep it shallow.
     */
    private long awaitTimeoutMinBackoffMs() {
        return clampMs(getLong(350L,
                "nova.orch.web.failsoft.ratelimit-backoff.await-timeout.min-ms",
                "nova.orch.web.failsoft.rateLimitBackoff.awaitTimeout.min-ms"), 0L, 5_000L);
    }

    private long awaitTimeoutMaxBackoffMs() {
        long cfg = clampMs(getLong(3_000L,
                "nova.orch.web.failsoft.ratelimit-backoff.await-timeout.max-ms",
                "nova.orch.web.failsoft.rateLimitBackoff.awaitTimeout.max-ms"), 200L, 60_000L);
        return Math.min(cfg, hardCapMs());
    }

    /**
     * Best-effort parse of a join/await timeout hint from a free-form detail string.
     *
     * <p>Expected patterns:
     * <ul>
     *   <li>{@code timeoutMs=3000}</li>
     *   <li>{@code timeout_ms=3000}</li>
     * </ul>
     *
     * <p>Returns {@code null} when no usable hint exists.
     */
    private static Long extractTimeoutHintMs(String detail) {
        if (detail == null) {
            return null;
        }
        String d = detail;
        String[] keys = new String[] { "timeoutMs=", "timeout_ms=", "timeoutMS=" };
        for (String key : keys) {
            int idx = d.indexOf(key);
            if (idx < 0) {
                continue;
            }
            int start = idx + key.length();
            int end = start;
            while (end < d.length()) {
                char c = d.charAt(end);
                if (c < '0' || c > '9') {
                    break;
                }
                end++;
            }
            if (end <= start) {
                continue;
            }
            try {
                long v = Long.parseLong(d.substring(start, end));
                if (v > 0L) {
                    return v;
                }
            } catch (NumberFormatException e) {
                traceSuppressed("extractTimeoutHintMs", e);
                // ignore
            }
        }
        return null;
    }

    /**
     * Retry-After: delta-seconds or HTTP-date.
     */
    public static long parseRetryAfterMs(String value, long nowEpochMs) {
        String v = safe(value).trim();
        if (v.isEmpty()) {
            return 0L;
        }
        // delta-seconds
        try {
            long seconds = Long.parseLong(v);
            if (seconds <= 0L) {
                return 0L;
            }
            return Math.min(seconds, 5 * 60L) * 1000L;
        } catch (NumberFormatException e) {
            traceSuppressed("parseRetryAfterMs.delta", e);
            // fall through
        }
        // HTTP-date (best effort)
        try {
            ZonedDateTime dt = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            long target = dt.toInstant().toEpochMilli();
            long delta = target - nowEpochMs;
            return Math.max(0L, delta);
        } catch (DateTimeParseException e) {
            traceSuppressed("parseRetryAfterMs.rfc1123", e);
            // best effort: try parse in local zone if missing
            try {
                ZonedDateTime dt = ZonedDateTime.parse(v);
                long target = dt.toInstant().toEpochMilli();
                long delta = target - nowEpochMs;
                return Math.max(0L, delta);
            } catch (Throwable e2) {
                traceSuppressed("parseRetryAfterMs.zoned", e2);
                return 0L;
            }
        }
    }

    private static String safeProvider(String provider) {
        String p = safe(provider).trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty()) {
            return "unknown";
        }
        String label = com.example.lms.trace.SafeRedactor.traceLabel(p);
        if (label == null || label.isBlank()) {
            return "unknown";
        }
        return label.toLowerCase(java.util.Locale.ROOT)
                .replace("hash:", "hash_")
                .replaceAll("[^a-z0-9_.-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private boolean getBoolean(boolean def, String... keys) {
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            String v = env.getProperty(k);
            if (v == null) continue;
            String t = v.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("on")) return true;
            if (t.equals("false") || t.equals("0") || t.equals("no") || t.equals("off")) return false;
        }
        return def;
    }

    private long getLong(long def, String... keys) {
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            String v = env.getProperty(k);
            if (v == null) continue;
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                traceSuppressed("getLong", e);
                // continue
            }
        }
        return def;
    }

    private double getDouble(double def, String... keys) {
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            String v = env.getProperty(k);
            if (v == null) continue;
            try {
                double parsed = Double.parseDouble(v.trim());
                if (!Double.isFinite(parsed)) {
                    throw new NumberFormatException("non-finite");
                }
                return parsed;
            } catch (NumberFormatException e) {
                traceSuppressed("getDouble", e);
                // continue
            }
        }
        return def;
    }

    private static void traceSuppressed(String stage, Throwable e) {
        try {
            TraceStore.inc("web.failsoft.rateLimitBackoff.suppressed.count");
            TraceStore.put("web.failsoft.rateLimitBackoff.suppressed.stage", stage);
            TraceStore.put("web.failsoft.rateLimitBackoff.suppressed.errorType", errorType(stage, e));
        } catch (Throwable traceFailure) {
            MDC.put("web.failsoft.rateLimitBackoff.suppressed.stage", stage);
            MDC.put("web.failsoft.rateLimitBackoff.suppressed.errorType", errorType(stage, e));
            MDC.put("web.failsoft.rateLimitBackoff.suppressed.traceFailureType", errorType(traceFailure));
        }
    }

    private static String errorType(String stage, Throwable e) {
        if (isNumericParseStage(stage)) {
            return "invalid_number";
        }
        if (isDateParseStage(stage)) {
            return "invalid_date";
        }
        return errorType(e);
    }

    private static boolean isNumericParseStage(String stage) {
        return "toLong".equals(stage)
                || "extractTimeoutHintMs".equals(stage)
                || "parseRetryAfterMs.delta".equals(stage)
                || "getLong".equals(stage)
                || "getDouble".equals(stage);
    }

    private static boolean isDateParseStage(String stage) {
        return "parseRetryAfterMs.rfc1123".equals(stage)
                || "parseRetryAfterMs.zoned".equals(stage);
    }

    private static String errorType(Throwable e) {
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    private static long clampMs(long v, long min, long max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static double clampDouble(double v, double min, double max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static long maxPositive(Long a, Long b, Long c) {
        long m = 0L;
        if (a != null && a > m) m = a;
        if (b != null && b > m) m = b;
        if (c != null && c > m) m = c;
        return m;
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static String safeReason(String s) {
        return com.example.lms.trace.SafeRedactor.traceLabelOrFallback(s, "");
    }

    private static final class BackoffState {
        private final AtomicLong cooldownUntilEpochMs = new AtomicLong(0L);
        private final AtomicInteger consecutive429 = new AtomicInteger(0);
        private final AtomicInteger consecutiveTimeout = new AtomicInteger(0);
        private final AtomicInteger consecutiveCancelled = new AtomicInteger(0);
        private final AtomicInteger consecutiveAwaitTimeout = new AtomicInteger(0);
        private final AtomicInteger consecutiveLocalRateLimit = new AtomicInteger(0);
        private final AtomicLong lastRateLimitEpochMs = new AtomicLong(0L);
        private final AtomicReference<String> lastReason = new AtomicReference<>("");
    }

    public record Decision(boolean skip, long remainingMs, String reason, boolean justStarted) {
        public static Decision allow() {
            return new Decision(false, 0L, "", false);
        }

        public static Decision skip(long remainingMs, String reason, boolean justStarted) {
            return new Decision(true, Math.max(0L, remainingMs), safeReason(reason), justStarted);
        }

        public boolean shouldSkip() {
            return skip;
        }
    }
}
