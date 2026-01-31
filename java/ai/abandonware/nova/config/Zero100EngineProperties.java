package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nova.orch.zero100")
public class Zero100EngineProperties {

    /** Master toggle for bean wiring. */
    private boolean engineEnabled = true;

    /** Absolute upper bound for a pro session. */
    private long maxMinutes = 100L;

    /** Micro-slice (compass tick) duration. */
    private long sliceMs = 400L;

    /** Auto-expire an idle pro-session to avoid memory leaks. */
    private long sessionIdleTtlMs = 10 * 60_000L;

    /** Hard clamp for HybridWebSearchProvider.search* wall time (ms). */
    private long webCallTimeboxMs = 2_500L;

    /** When provider is already rate-limited/cooling down, clamp even tighter. */
    private long webCallTimeboxMsWhenRateLimited = 650L;

    /** Optional per-request hard cap for RateLimitBackoffCoordinator (ms). */
    private long backoffHardCapMs = 2_500L;

    /** Capture Mp-Intent (anchor) early and keep it stable per pro-session. */
    private boolean mpIntentClampEnabled = true;

    /** Expand cache-only probe candidates using Mp-Intent anchor variants. */
    private boolean anchorProbeEnabled = true;

    /** Cache-only probe max candidates (upper bound). */
    private int cacheProbeMaxCandidates = 9;

    /** Extra candidates derived from Mp-Intent in addition to normal probe list. */
    private int cacheProbeExtraAnchorCandidates = 3;

    /** If tracePool rescue misses for the current query, try again with Mp-Intent. */
    private boolean secondaryTracePoolRescueEnabled = true;


    public boolean isEngineEnabled() {
        return engineEnabled;
    }

    public void setEngineEnabled(boolean engineEnabled) {
        this.engineEnabled = engineEnabled;
    }

    public long getMaxMinutes() {
        return maxMinutes;
    }

    public void setMaxMinutes(long maxMinutes) {
        this.maxMinutes = maxMinutes;
    }

    public long getSliceMs() {
        return sliceMs;
    }

    public void setSliceMs(long sliceMs) {
        this.sliceMs = sliceMs;
    }

    public long getSessionIdleTtlMs() {
        return sessionIdleTtlMs;
    }

    public void setSessionIdleTtlMs(long sessionIdleTtlMs) {
        this.sessionIdleTtlMs = sessionIdleTtlMs;
    }

    public long getWebCallTimeboxMs() {
        return webCallTimeboxMs;
    }

    public void setWebCallTimeboxMs(long webCallTimeboxMs) {
        this.webCallTimeboxMs = webCallTimeboxMs;
    }

    public long getWebCallTimeboxMsWhenRateLimited() {
        return webCallTimeboxMsWhenRateLimited;
    }

    public void setWebCallTimeboxMsWhenRateLimited(long webCallTimeboxMsWhenRateLimited) {
        this.webCallTimeboxMsWhenRateLimited = webCallTimeboxMsWhenRateLimited;
    }

    public long getBackoffHardCapMs() {
        return backoffHardCapMs;
    }

    public void setBackoffHardCapMs(long backoffHardCapMs) {
        this.backoffHardCapMs = backoffHardCapMs;
    }

    public boolean isMpIntentClampEnabled() {
        return mpIntentClampEnabled;
    }

    public void setMpIntentClampEnabled(boolean mpIntentClampEnabled) {
        this.mpIntentClampEnabled = mpIntentClampEnabled;
    }

    public boolean isAnchorProbeEnabled() {
        return anchorProbeEnabled;
    }

    public void setAnchorProbeEnabled(boolean anchorProbeEnabled) {
        this.anchorProbeEnabled = anchorProbeEnabled;
    }

    public int getCacheProbeMaxCandidates() {
        return cacheProbeMaxCandidates;
    }

    public void setCacheProbeMaxCandidates(int cacheProbeMaxCandidates) {
        this.cacheProbeMaxCandidates = cacheProbeMaxCandidates;
    }

    public int getCacheProbeExtraAnchorCandidates() {
        return cacheProbeExtraAnchorCandidates;
    }

    public void setCacheProbeExtraAnchorCandidates(int cacheProbeExtraAnchorCandidates) {
        this.cacheProbeExtraAnchorCandidates = cacheProbeExtraAnchorCandidates;
    }

    public boolean isSecondaryTracePoolRescueEnabled() {
        return secondaryTracePoolRescueEnabled;
    }

    public void setSecondaryTracePoolRescueEnabled(boolean secondaryTracePoolRescueEnabled) {
        this.secondaryTracePoolRescueEnabled = secondaryTracePoolRescueEnabled;
    }
}
