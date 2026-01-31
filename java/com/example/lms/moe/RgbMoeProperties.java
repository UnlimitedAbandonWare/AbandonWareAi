package com.example.lms.moe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the offline RGB(MoE) auto-evolve pipeline.
 *
 * <p>Policy: BLUE(Gemini) is only used from {@code TrainingJobRunner} (idle/offline).
 * Real-time chat workflow is intentionally unchanged.</p>
 */
@ConfigurationProperties(prefix = "rgb.moe")
public class RgbMoeProperties {

    /** Master enable switch. */
    private boolean enabled = false;

    /** Path to ERRORLW_AFR_TR.txt (or equivalent). */
    private String logPath = "./ERRORLW_AFR_TR.txt";

    /** Tail lines to read from logPath. */
    private int logTailLines = 4000;

    /** Directory where RGB soak reports will be written. */
    private String soakReportDir = "./soak_reports";

    /** Minimum idle minutes before auto-evolve is allowed to run. */
    private int idleMinMinutes = 30;

    /** Idle window start (HH:mm, local server time). */
    private String idleWindowStart = "02:00";

    /** Idle window end (HH:mm, local server time). */
    private String idleWindowEnd = "06:00";

    /** Whether BLUE is even considered (still gated by key/quota/cooldown). */
    private boolean blueEnabled = true;

    /** Max BLUE calls in a single TrainingJobRunner run. */
    private int blueMaxCallsPerRun = 6;

    /** Cooldown seconds between BLUE calls (best-effort). */
    private int blueCooldownSeconds = 60;

    /** Debug/ops helpers for observing auto-evolve behaviour (kept lightweight). */
    private Debug debug = new Debug();

    private Probe probe = new Probe();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLogPath() {
        return logPath;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    public int getLogTailLines() {
        return logTailLines;
    }

    public void setLogTailLines(int logTailLines) {
        this.logTailLines = logTailLines;
    }

    public String getSoakReportDir() {
        return soakReportDir;
    }

    public void setSoakReportDir(String soakReportDir) {
        this.soakReportDir = soakReportDir;
    }

    public int getIdleMinMinutes() {
        return idleMinMinutes;
    }

    public void setIdleMinMinutes(int idleMinMinutes) {
        this.idleMinMinutes = idleMinMinutes;
    }

    public String getIdleWindowStart() {
        return idleWindowStart;
    }

    public void setIdleWindowStart(String idleWindowStart) {
        this.idleWindowStart = idleWindowStart;
    }

    public String getIdleWindowEnd() {
        return idleWindowEnd;
    }

    public void setIdleWindowEnd(String idleWindowEnd) {
        this.idleWindowEnd = idleWindowEnd;
    }

    public boolean isBlueEnabled() {
        return blueEnabled;
    }

    public void setBlueEnabled(boolean blueEnabled) {
        this.blueEnabled = blueEnabled;
    }

    public int getBlueMaxCallsPerRun() {
        return blueMaxCallsPerRun;
    }

    public void setBlueMaxCallsPerRun(int blueMaxCallsPerRun) {
        this.blueMaxCallsPerRun = blueMaxCallsPerRun;
    }

    public int getBlueCooldownSeconds() {
        return blueCooldownSeconds;
    }

    public void setBlueCooldownSeconds(int blueCooldownSeconds) {
        this.blueCooldownSeconds = blueCooldownSeconds;
    }

    public Debug getDebug() {
        return debug;
    }

    public void setDebug(Debug debug) {
        this.debug = debug;
    }

    public Probe getProbe() {
        return probe;
    }

    public void setProbe(Probe probe) {
        this.probe = probe;
    }

    public static class Probe {
        /** Optional GPU probing via nvidia-smi (disabled by default). */
        private boolean nvidiaSmiEnabled = false;

        public boolean isNvidiaSmiEnabled() {
            return nvidiaSmiEnabled;
        }

        public void setNvidiaSmiEnabled(boolean nvidiaSmiEnabled) {
            this.nvidiaSmiEnabled = nvidiaSmiEnabled;
        }
    }

    /**
     * Debug options (in-memory only).
     *
     * <p>These are used by internal endpoints (e.g. /internal/autoevolve/status)
     * and by the auto-evolve runner to keep recent run details for troubleshooting.</p>
     */
    public static class Debug {
        /** Master toggle for keeping debug history in memory. */
        private boolean enabled = true;

        /** Ring buffer size (recent N runs). */
        private int ringSize = 30;

        /** Max chars for error body preview (to avoid huge payloads). */
        private int maxErrorBodyChars = 800;

        /**
         * Persist recent debug runs to disk (ndjson + summary index).
         *
         * <p>Default is off to avoid unexpected disk writes in production.
         * Enable only in controlled environments (local/dev/ops debugging).</p>
         */
        private boolean persistEnabled = false;

        /** Directory where debug files will be stored when persistEnabled=true. */
        private String persistDir = "./autoevolve_debug";

        /** Active ndjson file name inside persistDir. */
        private String ndjsonFileName = "autoevolve.ndjson";

        /** Summary index file name inside persistDir. */
        private String indexFileName = "autoevolve_index.json";

        /** Rotate the active ndjson file when it exceeds this size (bytes). */
        private long ndjsonMaxBytes = 5_000_000L;

        /** Keep at most this many rotated ndjson files. */
        private int ndjsonMaxFiles = 10;

        /** Max entries written to the summary index file (defaults to ringSize). */
        private int indexMaxEntries = 0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRingSize() {
            return ringSize;
        }

        public void setRingSize(int ringSize) {
            this.ringSize = ringSize;
        }

        public int getMaxErrorBodyChars() {
            return maxErrorBodyChars;
        }

        public void setMaxErrorBodyChars(int maxErrorBodyChars) {
            this.maxErrorBodyChars = maxErrorBodyChars;
        }

        public boolean isPersistEnabled() {
            return persistEnabled;
        }

        public void setPersistEnabled(boolean persistEnabled) {
            this.persistEnabled = persistEnabled;
        }

        public String getPersistDir() {
            return persistDir;
        }

        public void setPersistDir(String persistDir) {
            this.persistDir = persistDir;
        }

        public String getNdjsonFileName() {
            return ndjsonFileName;
        }

        public void setNdjsonFileName(String ndjsonFileName) {
            this.ndjsonFileName = ndjsonFileName;
        }

        public String getIndexFileName() {
            return indexFileName;
        }

        public void setIndexFileName(String indexFileName) {
            this.indexFileName = indexFileName;
        }

        public long getNdjsonMaxBytes() {
            return ndjsonMaxBytes;
        }

        public void setNdjsonMaxBytes(long ndjsonMaxBytes) {
            this.ndjsonMaxBytes = ndjsonMaxBytes;
        }

        public int getNdjsonMaxFiles() {
            return ndjsonMaxFiles;
        }

        public void setNdjsonMaxFiles(int ndjsonMaxFiles) {
            this.ndjsonMaxFiles = ndjsonMaxFiles;
        }

        public int getIndexMaxEntries() {
            return indexMaxEntries;
        }

        public void setIndexMaxEntries(int indexMaxEntries) {
            this.indexMaxEntries = indexMaxEntries;
        }
    }
}
