package com.example.lms.uaw.autolearn;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the UAW "spare time" autolearn orchestrator.
 *
 * <p>Note: enabled flag is resolved with backward-compatible fallback
 * (uaw.autolearn.enabled -> autolearn.enabled -> train_idle.enabled).
 */
@ConfigurationProperties(prefix = "uaw.autolearn")
public class UawAutolearnProperties {

    /** Orchestrator tick interval (ms). */
    private long tickMs = 60_000L;

    /** How many questions to attempt per cycle. */
    private int batchSize = 3;

    /** Hard time budget per cycle (seconds). */
    private int maxCycleSeconds = 20;

    /** Minimum evidence count required to accept a sample. */
    private int minEvidenceCount = 1;

    private Idle idle = new Idle();
    private Dataset dataset = new Dataset();
    private Retrain retrain = new Retrain();
    private Budget budget = new Budget();

    /** Safety-pin behavior to avoid "always skip" deadlocks. */
    private SafetyPin safetyPin = new SafetyPin();

	/** Optional override seeds (if empty, built-in defaults are used). */
	private List<String> defaultSeeds = new ArrayList<>();

	/** Controls how autolearn chooses seed questions. */
	private Seed seed = new Seed();

    public static class Idle {
        /** Optional CPU threshold (0.0~1.0). If negative, CPU check is disabled. */
        private double cpuThreshold = 0.75;
        public double getCpuThreshold() { return cpuThreshold; }
        public void setCpuThreshold(double cpuThreshold) { this.cpuThreshold = cpuThreshold; }
    }

    public static class Dataset {
        /** JSONL output path. */
        private String path = "data/train_rag.jsonl";
        /** Logical dataset name (stored as metadata). */
        private String name = "uaw-train";
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class Retrain {
        /** Minimum accepted samples in this cycle to trigger ingest/reindex. */
        private int minAcceptedToTrain = 10;
        /** Max retrain runs per day. */
        private int maxRunsPerDay = 2;
        /** Max lines to ingest per run (for fast preemption). */
        private int maxIngestLinesPerRun = 200;
        /** Ingest checkpoint state file path. */
        private String ingestStatePath = "data/train/ingest_state.json";

        public int getMinAcceptedToTrain() { return minAcceptedToTrain; }
        public void setMinAcceptedToTrain(int minAcceptedToTrain) { this.minAcceptedToTrain = minAcceptedToTrain; }
        public int getMaxRunsPerDay() { return maxRunsPerDay; }
        public void setMaxRunsPerDay(int maxRunsPerDay) { this.maxRunsPerDay = maxRunsPerDay; }
        public int getMaxIngestLinesPerRun() { return maxIngestLinesPerRun; }
        public void setMaxIngestLinesPerRun(int maxIngestLinesPerRun) { this.maxIngestLinesPerRun = maxIngestLinesPerRun; }
        public String getIngestStatePath() { return ingestStatePath; }
        public void setIngestStatePath(String ingestStatePath) { this.ingestStatePath = ingestStatePath; }
    }

    public static class Budget {
        /** Max autolearn cycles per day. */
        private int maxRunsPerDay = 24;
        /** Minimum seconds between cycles. */
        private int minIntervalSeconds = 300;
        /** Backoff seconds after failures (multiplied by consecutive failures, capped). */
        private int baseBackoffSeconds = 900;
        /** Cap for failure backoff (seconds). */
        private int maxBackoffSeconds = 7200;
        /** State file for budget tracking. */
        private String statePath = "data/uaw/autolearn_state.json";

        /**
         * Optional lightweight probe interval (seconds) when normal budget gates would skip.
         * A probe run is meant to detect recovery without permanently hiding backoff conditions.
         */
        private int probeIntervalSeconds = 1800;

        /** Maximum probe runs per day (in addition to maxRunsPerDay). */
        private int probeMaxRunsPerDay = 2;

        public int getMaxRunsPerDay() { return maxRunsPerDay; }
        public void setMaxRunsPerDay(int maxRunsPerDay) { this.maxRunsPerDay = maxRunsPerDay; }
        public int getMinIntervalSeconds() { return minIntervalSeconds; }
        public void setMinIntervalSeconds(int minIntervalSeconds) { this.minIntervalSeconds = minIntervalSeconds; }
        public int getBaseBackoffSeconds() { return baseBackoffSeconds; }
        public void setBaseBackoffSeconds(int baseBackoffSeconds) { this.baseBackoffSeconds = baseBackoffSeconds; }
        public int getMaxBackoffSeconds() { return maxBackoffSeconds; }
        public void setMaxBackoffSeconds(int maxBackoffSeconds) { this.maxBackoffSeconds = maxBackoffSeconds; }
        public String getStatePath() { return statePath; }
        public void setStatePath(String statePath) { this.statePath = statePath; }
        public int getProbeIntervalSeconds() { return probeIntervalSeconds; }
        public void setProbeIntervalSeconds(int probeIntervalSeconds) { this.probeIntervalSeconds = probeIntervalSeconds; }
        public int getProbeMaxRunsPerDay() { return probeMaxRunsPerDay; }
        public void setProbeMaxRunsPerDay(int probeMaxRunsPerDay) { this.probeMaxRunsPerDay = probeMaxRunsPerDay; }
    }

    public static class SafetyPin {
        /** Allow at most N samples per cycle even when evidenceCount == 0 (internal/static seeds only). */
        private int maxZeroEvidenceAcceptedPerCycle = 1;
        /** If false, keep strict evidence requirement. */
        private boolean allowZeroEvidenceForStaticSeeds = true;

        public int getMaxZeroEvidenceAcceptedPerCycle() { return maxZeroEvidenceAcceptedPerCycle; }
        public void setMaxZeroEvidenceAcceptedPerCycle(int maxZeroEvidenceAcceptedPerCycle) { this.maxZeroEvidenceAcceptedPerCycle = maxZeroEvidenceAcceptedPerCycle; }
        public boolean isAllowZeroEvidenceForStaticSeeds() { return allowZeroEvidenceForStaticSeeds; }
        public void setAllowZeroEvidenceForStaticSeeds(boolean allowZeroEvidenceForStaticSeeds) { this.allowZeroEvidenceForStaticSeeds = allowZeroEvidenceForStaticSeeds; }
    }

    public long getTickMs() { return tickMs; }
    public void setTickMs(long tickMs) { this.tickMs = tickMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxCycleSeconds() { return maxCycleSeconds; }
    public void setMaxCycleSeconds(int maxCycleSeconds) { this.maxCycleSeconds = maxCycleSeconds; }
    public int getMinEvidenceCount() { return minEvidenceCount; }
    public void setMinEvidenceCount(int minEvidenceCount) { this.minEvidenceCount = minEvidenceCount; }

    public Idle getIdle() { return idle; }
    public void setIdle(Idle idle) { this.idle = idle; }
    public Dataset getDataset() { return dataset; }
    public void setDataset(Dataset dataset) { this.dataset = dataset; }
    public Retrain getRetrain() { return retrain; }
    public void setRetrain(Retrain retrain) { this.retrain = retrain; }
    public Budget getBudget() { return budget; }
    public void setBudget(Budget budget) { this.budget = budget; }

    public SafetyPin getSafetyPin() { return safetyPin; }
    public void setSafetyPin(SafetyPin safetyPin) { this.safetyPin = safetyPin; }

	public List<String> getDefaultSeeds() {
		return defaultSeeds;
	}

	public void setDefaultSeeds(List<String> defaultSeeds) {
		this.defaultSeeds = defaultSeeds;
	}

	public Seed getSeed() {
		return seed;
	}

	public void setSeed(Seed seed) {
		this.seed = seed;
	}

	public static class Seed {
		/** If true, sample seeds from recent real user chat messages (role=user). */
		private boolean historyEnabled = true;

		/** Max number of recent user messages to consider as a pool. */
		private int historyPoolSize = 120;

		/** Minimum seed length (characters) to keep. */
		private int minChars = 12;

		/** Maximum seed length (characters) to keep (longer will be truncated). */
		private int maxChars = 240;

		/** If history pool is empty, allow fallback to defaultSeeds/built-ins. */
		private boolean allowStaticFallback = true;

		public boolean isHistoryEnabled() {
			return historyEnabled;
		}

		public void setHistoryEnabled(boolean historyEnabled) {
			this.historyEnabled = historyEnabled;
		}

		public int getHistoryPoolSize() {
			return historyPoolSize;
		}

		public void setHistoryPoolSize(int historyPoolSize) {
			this.historyPoolSize = historyPoolSize;
		}

		public int getMinChars() {
			return minChars;
		}

		public void setMinChars(int minChars) {
			this.minChars = minChars;
		}

		public int getMaxChars() {
			return maxChars;
		}

		public void setMaxChars(int maxChars) {
			this.maxChars = maxChars;
		}

		public boolean isAllowStaticFallback() {
			return allowStaticFallback;
		}

		public void setAllowStaticFallback(boolean allowStaticFallback) {
			this.allowStaticFallback = allowStaticFallback;
		}
	}
}
