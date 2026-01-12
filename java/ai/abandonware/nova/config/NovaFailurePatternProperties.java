package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nova Overlay – failure-pattern instrumentation & weak feedback loop.
 *
 * <p>Purpose:
 * <ul>
 *     <li>Micrometer metrics for failure patterns detected from logs</li>
 *     <li>Optional JSONL persistence (failure-pattern ledger)</li>
 *     <li>Weak feedback into retrieval order (reorder/skip) based on cooldown</li>
 * </ul>
 *
 * <p>All knobs are fail-soft and default to safe settings.
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

        /** Where to append JSONL lines. */
        private String path = "logs/failure-pattern.jsonl";

        /** Max file size to read on reload (bytes). If exceeded, reload is skipped. */
        private long maxFileBytes = 10 * 1024 * 1024L;

        /** When reloading, keep only the last N lines of the file. */
        private int readTailLines = 2000;

        /** Read window for seeding cooldown from JSONL. */
        private long lookbackSeconds = 15 * 60;

        /** Reload JSONL at most once per interval. */
        private long reloadIntervalMs = 30_000;

        public boolean isWriteEnabled() {
            return writeEnabled;
        }

        public void setWriteEnabled(boolean writeEnabled) {
            this.writeEnabled = writeEnabled;
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

        public int getReadTailLines() {
            return readTailLines;
        }

        public void setReadTailLines(int readTailLines) {
            this.readTailLines = readTailLines;
        }

        public long getLookbackSeconds() {
            return lookbackSeconds;
        }

        public void setLookbackSeconds(long lookbackSeconds) {
            this.lookbackSeconds = lookbackSeconds;
        }

        public long getReloadIntervalMs() {
            return reloadIntervalMs;
        }

        public void setReloadIntervalMs(long reloadIntervalMs) {
            this.reloadIntervalMs = reloadIntervalMs;
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

        public long getDisambigFallbackCooldownSeconds() {
            return disambigFallbackCooldownSeconds;
        }

        public void setDisambigFallbackCooldownSeconds(long disambigFallbackCooldownSeconds) {
            this.disambigFallbackCooldownSeconds = disambigFallbackCooldownSeconds;
        }
    }

    public static class Resilience4jEvents {
        /**
         * If true, also count circuit OPEN transitions from Resilience4j state transition events.
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
        /** Skip cooled-down sources for this turn (stronger, but still only per-call). */
        SKIP_TURN
    }
}
