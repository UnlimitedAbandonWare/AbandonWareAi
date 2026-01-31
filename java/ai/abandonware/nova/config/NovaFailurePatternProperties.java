package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nova Overlay – failure-pattern instrumentation & weak feedback loop.
 *
 * <p>
 * Purpose:
 * <ul>
 * <li>Micrometer metrics for failure patterns detected from logs</li>
 * <li>Optional JSONL persistence (failure-pattern ledger)</li>
 * <li>Weak feedback into retrieval order (reorder/skip) based on cooldown</li>
 * </ul>
 *
 * <p>
 * All knobs are fail-soft and default to safe settings.
 */
@ConfigurationProperties(prefix = "nova.orch.failure")
public class NovaFailurePatternProperties {

    /** Master switch for this overlay block. */
    private boolean enabled = true;

    /**
     * Install a Logback appender at runtime that watches for failure patterns.
     * If disabled, you can still seed cooldown from JSONL (reader only).
     */
    private boolean logAppenderEnabled = true;

    private final Metrics metrics = new Metrics();
    private final Jsonl jsonl = new Jsonl();
    private final Feedback feedback = new Feedback();
    private final Resilience4jEvents resilience4jEvents = new Resilience4jEvents();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLogAppenderEnabled() {
        return logAppenderEnabled;
    }

    public void setLogAppenderEnabled(boolean logAppenderEnabled) {
        this.logAppenderEnabled = logAppenderEnabled;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Jsonl getJsonl() {
        return jsonl;
    }

    public Feedback getFeedback() {
        return feedback;
    }

    public Resilience4jEvents getResilience4jEvents() {
        return resilience4jEvents;
    }

    public static class Metrics {
        /** Enable/disable Micrometer counters. */
        private boolean enabled = true;

        /**
         * Counter name. In Prometheus this will appear as <name>_total.
         * Example: nova.failure.pattern -> nova_failure_pattern_total
         */
        private String counterName = "nova.failure.pattern";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCounterName() {
            return counterName;
        }

        public void setCounterName(String counterName) {
            this.counterName = counterName;
        }
    }

    public static class Jsonl {
        /** Write matched failure patterns as JSONL. */
        private boolean writeEnabled = true;

        /** Enable reading from JSONL for cooldown state initialization. */
        private boolean readEnabled = true;

        /** Where to append JSONL lines. */
        private String path = "logs/failure-pattern.jsonl";

        /** Max file size to read on reload (bytes). If exceeded, reload is skipped. */
        private long maxFileBytes = 10 * 1024 * 1024L;

        /** When reloading, keep only the last N lines of the file. */
        private int reloadMaxLines = 2000;

        /** Read window for seeding cooldown from JSONL. */
        private long lookbackSeconds = 15 * 60;

        /** Reload JSONL at most once per interval. */
        private long reloadMinIntervalMs = 30_000;

        public boolean isWriteEnabled() {
            return writeEnabled;
        }

        public void setWriteEnabled(boolean writeEnabled) {
            this.writeEnabled = writeEnabled;
        }

        public boolean isReadEnabled() {
            return readEnabled;
        }

        public void setReadEnabled(boolean readEnabled) {
            this.readEnabled = readEnabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public int getReloadMaxLines() {
            return reloadMaxLines;
        }

        public void setReloadMaxLines(int reloadMaxLines) {
            this.reloadMaxLines = reloadMaxLines;
        }

        public long getLookbackSeconds() {
            return lookbackSeconds;
        }

        public void setLookbackSeconds(long lookbackSeconds) {
            this.lookbackSeconds = lookbackSeconds;
        }

        public long getReloadMinIntervalMs() {
            return reloadMinIntervalMs;
        }

        public void setReloadMinIntervalMs(long reloadMinIntervalMs) {
            this.reloadMinIntervalMs = reloadMinIntervalMs;
        }
    }

    public static class Feedback {
        /** Enable/disable weak feedback into retrieval ordering. */
        private boolean enabled = true;

        /** How aggressively to apply cooldown to retrieval order. */
        private Mode mode = Mode.WEAK_REORDER;

        /** Cooldown durations per failure kind. */
        private long naverTraceTimeoutCooldownSeconds = 30;
        private long circuitOpenCooldownSeconds = 60;
        /**
         * Circuit-open cooldown for LLM-adjacent aux stages (e.g., query-transformer /
         * disambiguation).
         *
         * Motivation: these breakers often open for only a short duration (seconds).
         * Using the same
         * (longer) circuitOpenCooldownSeconds can over-suppress aux stages and cause
         * prolonged
         * degradation even after the breaker has already recovered.
         */
        private long llmCircuitOpenCooldownSeconds = 10;

        /**
         * Adaptive throttle for LLM circuit-open feedback.
         * When CIRCUIT_OPEN repeats quickly, we temporarily extend the cooldown to
         * reduce retry churn.
         */
        private boolean llmCircuitOpenAdaptiveEnabled = true;

        /**
         * Sliding window for counting consecutive CIRCUIT_OPEN events (seconds).
         */
        private long llmCircuitOpenAdaptiveWindowSeconds = 60;

        /**
         * Strike threshold within the window to escalate the cooldown to the default
         * circuit-open cooldown.
         */
        private int llmCircuitOpenAdaptiveStrikeThreshold = 3;
        private long disambigFallbackCooldownSeconds = 15;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public long getNaverTraceTimeoutCooldownSeconds() {
            return naverTraceTimeoutCooldownSeconds;
        }

        public void setNaverTraceTimeoutCooldownSeconds(long naverTraceTimeoutCooldownSeconds) {
            this.naverTraceTimeoutCooldownSeconds = naverTraceTimeoutCooldownSeconds;
        }

        public long getCircuitOpenCooldownSeconds() {
            return circuitOpenCooldownSeconds;
        }

        public void setCircuitOpenCooldownSeconds(long circuitOpenCooldownSeconds) {
            this.circuitOpenCooldownSeconds = circuitOpenCooldownSeconds;
        }

        public long getLlmCircuitOpenCooldownSeconds() {
            return llmCircuitOpenCooldownSeconds;
        }

        public void setLlmCircuitOpenCooldownSeconds(long llmCircuitOpenCooldownSeconds) {
            this.llmCircuitOpenCooldownSeconds = llmCircuitOpenCooldownSeconds;
        }

        public boolean isLlmCircuitOpenAdaptiveEnabled() {
            return llmCircuitOpenAdaptiveEnabled;
        }

        public void setLlmCircuitOpenAdaptiveEnabled(boolean llmCircuitOpenAdaptiveEnabled) {
            this.llmCircuitOpenAdaptiveEnabled = llmCircuitOpenAdaptiveEnabled;
        }

        public long getLlmCircuitOpenAdaptiveWindowSeconds() {
            return llmCircuitOpenAdaptiveWindowSeconds;
        }

        public void setLlmCircuitOpenAdaptiveWindowSeconds(long llmCircuitOpenAdaptiveWindowSeconds) {
            this.llmCircuitOpenAdaptiveWindowSeconds = llmCircuitOpenAdaptiveWindowSeconds;
        }

        public int getLlmCircuitOpenAdaptiveStrikeThreshold() {
            return llmCircuitOpenAdaptiveStrikeThreshold;
        }

        public void setLlmCircuitOpenAdaptiveStrikeThreshold(int llmCircuitOpenAdaptiveStrikeThreshold) {
            this.llmCircuitOpenAdaptiveStrikeThreshold = llmCircuitOpenAdaptiveStrikeThreshold;
        }

        public long getDisambigFallbackCooldownSeconds() {
            return disambigFallbackCooldownSeconds;
        }

        public void setDisambigFallbackCooldownSeconds(long disambigFallbackCooldownSeconds) {
            this.disambigFallbackCooldownSeconds = disambigFallbackCooldownSeconds;
        }
    }

    public static class Resilience4jEvents {
        /**
         * If true, also count circuit OPEN transitions from Resilience4j state
         * transition events.
         * This is more precise than log-pattern matching.
         */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public enum Mode {
        /** Keep all sources, but demote cooled-down sources to the end (weak). */
        WEAK_REORDER,
        /**
         * Skip cooled-down sources for this turn (stronger, but still only per-call).
         */
        SKIP_TURN
    }
}
