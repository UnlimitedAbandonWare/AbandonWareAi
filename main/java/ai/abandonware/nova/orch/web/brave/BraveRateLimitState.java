package ai.abandonware.nova.orch.web.brave;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, fail-soft state for Brave rate-limit handling.
 *
 * <p>We remember the last known reset times and (most importantly) a
 * "quota exhausted until" timestamp so orchestration can avoid scheduling
 * Brave work while the provider is known to be unavailable, and auto-recover
 * once the reset time passes.</p>
 */
public class BraveRateLimitState {

    private final AtomicLong lastUpdatedEpochMs = new AtomicLong(0L);

    /** Per-second window reset time (epoch ms), if known. */
    private final AtomicLong secResetAtEpochMs = new AtomicLong(0L);

    /** Per-month window reset time (epoch ms), if known. */
    private final AtomicLong monthResetAtEpochMs = new AtomicLong(0L);

    /** When Brave is known to be quota-exhausted, do not schedule until this time. */
    private final AtomicLong quotaExhaustedUntilEpochMs = new AtomicLong(0L);

    public long lastUpdatedEpochMs() {
        return lastUpdatedEpochMs.get();
    }

    public long secResetAtEpochMs() {
        return secResetAtEpochMs.get();
    }

    public long monthResetAtEpochMs() {
        return monthResetAtEpochMs.get();
    }

    public long quotaExhaustedUntilEpochMs() {
        return quotaExhaustedUntilEpochMs.get();
    }

    public void clearQuotaLatch() {
        quotaExhaustedUntilEpochMs.set(0L);
    }

    /**
     * Update reset timestamps from Brave's X-RateLimit-Reset header values.
     *
     * @param secResetSec per-second window reset (usually seconds-from-now)
     * @param monthResetSec per-month window reset (usually seconds-from-now)
     * @param nowEpochMs current time
     */
    public void updateResets(long secResetSec, long monthResetSec, long nowEpochMs) {
        lastUpdatedEpochMs.set(nowEpochMs);

        long secAt = parseResetToEpochMs(secResetSec, nowEpochMs);
        long monAt = parseResetToEpochMs(monthResetSec, nowEpochMs);

        if (secAt > 0L) {
            bumpMax(secResetAtEpochMs, secAt);
        }
        if (monAt > 0L) {
            bumpMax(monthResetAtEpochMs, monAt);
        }
    }

    /** Latch quota-exhausted until the provided epoch time. Monotonic: will not shrink. */
    public void latchQuotaUntil(long untilEpochMs) {
        if (untilEpochMs <= 0L) {
            return;
        }
        bumpMax(quotaExhaustedUntilEpochMs, untilEpochMs);
    }

    /** If no reset header is available, fall back to the next UTC month boundary. */
    public static long computeNextMonthStartUtcEpochMs(long nowEpochMs) {
        Instant now = Instant.ofEpochMilli(nowEpochMs);
        ZonedDateTime z = ZonedDateTime.ofInstant(now, ZoneOffset.UTC);
        ZonedDateTime startOfThisMonth = z.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime startNextMonth = startOfThisMonth.plusMonths(1);
        return startNextMonth.toInstant().toEpochMilli();
    }

    /**
     * Parse reset values into epoch ms.
     *
     * <p>Brave docs describe X-RateLimit-Reset as "seconds from now". Some gateways use
     * epoch-seconds for x-ratelimit-reset, so we support both.</p>
     */
    static long parseResetToEpochMs(long resetValueSeconds, long nowEpochMs) {
        if (resetValueSeconds <= 0L) {
            return 0L;
        }
        long nowSec = Math.max(0L, nowEpochMs / 1000L);

        // Heuristic:
        // - If value is far in the future relative to now, treat it as epoch-seconds.
        // - Else treat it as delta-seconds.
        boolean looksLikeEpochSeconds = resetValueSeconds > (nowSec + 60L);

        long targetSec = looksLikeEpochSeconds ? resetValueSeconds : (nowSec + resetValueSeconds);
        if (targetSec <= 0L) {
            return 0L;
        }
        return targetSec * 1000L;
    }

    private static void bumpMax(AtomicLong ref, long candidate) {
        if (candidate <= 0L) {
            return;
        }
        ref.updateAndGet(prev -> Math.max(prev, candidate));
    }
}
