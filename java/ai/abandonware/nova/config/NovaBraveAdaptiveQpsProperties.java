package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Brave Search API – adaptive QPS downshift based on rate-limit response headers.
 *
 * <p>
 * Brave Search API returns comma-separated values for multiple windows in the
 * {@code X-RateLimit-*} response headers (per-second, per-month, ...).
 * This overlay reads those headers and dynamically reduces the local QPS
 * so production traffic does not spiral into repeated 429 / breaker-open loops.
 * </p>
 */
@Validated
@ConfigurationProperties(prefix = "nova.orch.brave.adaptive-qps")
public class NovaBraveAdaptiveQpsProperties {

    /** Master toggle. */
    private boolean enabled = true;

    /** Hard floor for the local QPS. (Never set 0; Guava RateLimiter dislikes 0.) */
    private double minQps = 0.05d;

    /** Hard cap for the local QPS. Default aligns with existing BraveSearchService clamp. */
    private double maxQps = 0.8d;

    /** Multiply computed targets by this safety factor (headroom). */
    private double safetyFactor = 0.90d;

    /**
     * When the monthly remaining quota falls below this threshold, compute a
     * "spread evenly until reset" QPS using remaining/reset.
     */
    private long monthlyRemainingThreshold = 1000L;

    /**
     * If per-second remaining reaches 0, install a short cooldown until the
     * per-second reset (plus jitter) to reduce 429 bursts in multi-instance
     * deployments.
     */
    private boolean applyPerSecondCooldown = true;

    /** Extra jitter added to per-second cooldown to avoid herd effects. */
    private long perSecondCooldownJitterMs = 25L;

    /**
     * If the server responds with Retry-After (429), prefer it (capped) for
     * cooldown calculation.
     */
    private boolean useRetryAfter = true;



    /**
     * Apply local exponential backoff for HTTP 429, in addition to any server-provided Retry-After.
     *
     * <p>Effective delay: max(retryAfterMs, expBackoffMs) + jitter</p>
     */
    private boolean http429ExpBackoffEnabled = true;

    /** Base delay (ms) for the exponential backoff ladder on 429. */
    private long http429ExpBackoffBaseMs = 250L;

    /** Upper bound (ms) for the exponential backoff ladder on 429. */
    private long http429ExpBackoffMaxMs = 15_000L;

    /** Upper bound for any derived cooldown (ms). */
    private long maxCooldownMs = 30_000L;

    /** Record last seen rate-limit headers into TraceStore for debugging. */
    private boolean traceHeaders = true;

    /**
     * When per-second remaining hits 0 frequently (multi-instance bursts), apply an EMA-based
     * penalty factor to the local RateLimiter QPS and gradually recover when the signal clears.
     */
    private boolean perSecondPenaltyEnabled = true;

    /**
     * EMA alpha for per-second penalty (0..1).
     * Higher reacts faster; lower recovers smoother.
     */
    private double perSecondPenaltyEmaAlpha = 0.25d;

    /**
     * Minimum multiplicative factor applied to QPS when the penalty EMA saturates (0..1).
     * Example: 0.25 means "at worst, clamp to 25% of base target".
     */
    private double perSecondPenaltyMinFactor = 0.25d;

    /** Treat HTTP 429 as a penalty event even if per-second headers are absent. */
    private boolean perSecondPenaltyIncludeHttp429 = true;

    /** Record penalty EMA/factor/target into TraceStore (best-effort). */
    private boolean tracePenalty = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getMinQps() {
        return minQps;
    }

    public void setMinQps(double minQps) {
        this.minQps = minQps;
    }

    public double getMaxQps() {
        return maxQps;
    }

    public void setMaxQps(double maxQps) {
        this.maxQps = maxQps;
    }

    public double getSafetyFactor() {
        return safetyFactor;
    }

    public void setSafetyFactor(double safetyFactor) {
        this.safetyFactor = safetyFactor;
    }

    public long getMonthlyRemainingThreshold() {
        return monthlyRemainingThreshold;
    }

    public void setMonthlyRemainingThreshold(long monthlyRemainingThreshold) {
        this.monthlyRemainingThreshold = monthlyRemainingThreshold;
    }

    public boolean isApplyPerSecondCooldown() {
        return applyPerSecondCooldown;
    }

    public void setApplyPerSecondCooldown(boolean applyPerSecondCooldown) {
        this.applyPerSecondCooldown = applyPerSecondCooldown;
    }

    public long getPerSecondCooldownJitterMs() {
        return perSecondCooldownJitterMs;
    }

    public void setPerSecondCooldownJitterMs(long perSecondCooldownJitterMs) {
        this.perSecondCooldownJitterMs = perSecondCooldownJitterMs;
    }

    public boolean isUseRetryAfter() {
        return useRetryAfter;
    }

    public void setUseRetryAfter(boolean useRetryAfter) {
        this.useRetryAfter = useRetryAfter;
    }



    public boolean isHttp429ExpBackoffEnabled() {
        return http429ExpBackoffEnabled;
    }

    public void setHttp429ExpBackoffEnabled(boolean http429ExpBackoffEnabled) {
        this.http429ExpBackoffEnabled = http429ExpBackoffEnabled;
    }

    public long getHttp429ExpBackoffBaseMs() {
        return http429ExpBackoffBaseMs;
    }

    public void setHttp429ExpBackoffBaseMs(long http429ExpBackoffBaseMs) {
        this.http429ExpBackoffBaseMs = http429ExpBackoffBaseMs;
    }

    public long getHttp429ExpBackoffMaxMs() {
        return http429ExpBackoffMaxMs;
    }

    public void setHttp429ExpBackoffMaxMs(long http429ExpBackoffMaxMs) {
        this.http429ExpBackoffMaxMs = http429ExpBackoffMaxMs;
    }

    public long getMaxCooldownMs() {
        return maxCooldownMs;
    }

    public void setMaxCooldownMs(long maxCooldownMs) {
        this.maxCooldownMs = maxCooldownMs;
    }

    public boolean isTraceHeaders() {
        return traceHeaders;
    }

    public void setTraceHeaders(boolean traceHeaders) {
        this.traceHeaders = traceHeaders;
    }

    public boolean isPerSecondPenaltyEnabled() {
        return perSecondPenaltyEnabled;
    }

    public void setPerSecondPenaltyEnabled(boolean perSecondPenaltyEnabled) {
        this.perSecondPenaltyEnabled = perSecondPenaltyEnabled;
    }

    public double getPerSecondPenaltyEmaAlpha() {
        return perSecondPenaltyEmaAlpha;
    }

    public void setPerSecondPenaltyEmaAlpha(double perSecondPenaltyEmaAlpha) {
        this.perSecondPenaltyEmaAlpha = perSecondPenaltyEmaAlpha;
    }

    public double getPerSecondPenaltyMinFactor() {
        return perSecondPenaltyMinFactor;
    }

    public void setPerSecondPenaltyMinFactor(double perSecondPenaltyMinFactor) {
        this.perSecondPenaltyMinFactor = perSecondPenaltyMinFactor;
    }

    public boolean isPerSecondPenaltyIncludeHttp429() {
        return perSecondPenaltyIncludeHttp429;
    }

    public void setPerSecondPenaltyIncludeHttp429(boolean perSecondPenaltyIncludeHttp429) {
        this.perSecondPenaltyIncludeHttp429 = perSecondPenaltyIncludeHttp429;
    }

    public boolean isTracePenalty() {
        return tracePenalty;
    }

    public void setTracePenalty(boolean tracePenalty) {
        this.tracePenalty = tracePenalty;
    }
}
