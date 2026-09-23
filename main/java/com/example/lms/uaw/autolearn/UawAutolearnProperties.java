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

    /** Minimum evidence count required to accept a learning sample. */
    private int minEvidenceCount = 3;

    /** Minimum 3-gram diversity across accepted evidence snippets. */
    private double minContextDiversity = 0.30d;

    private Idle idle = new Idle();
    private Dataset dataset = new Dataset();
    private Retrain retrain = new Retrain();
    private AgentHandoff agentHandoff = new AgentHandoff();
    private Budget budget = new Budget();
    private ExternalQuota externalQuota = new ExternalQuota();
    private MemoryReinforcement memoryReinforcement = new MemoryReinforcement();
    private IdleTrigger idleTrigger = new IdleTrigger();
    private Validation validation = new Validation();
    private RuntimeNode runtimeNode = new RuntimeNode();

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
        /** Enables the heavy retrain/reindex step after dataset curation. */
        private boolean enabled = true;
        /** Minimum accepted samples in this cycle to trigger ingest/reindex. */
        private int minAcceptedToTrain = 10;
        /** Max retrain runs per day. */
        private int maxRunsPerDay = 2;
        /** Max lines to ingest per run (for fast preemption). */
        private int maxIngestLinesPerRun = 200;
        /** Ingest checkpoint state file path. */
        private String ingestStatePath = "data/train/ingest_state.json";
        /** Default false: keep raw AutoLearn Q/A out of vector content. */
        private boolean rawContentToVector = false;
        /** NONE, METADATA_ONLY, or RAW_SHADOW_QUARANTINE. */
        private String vectorProjectionMode = "METADATA_ONLY";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinAcceptedToTrain() { return minAcceptedToTrain; }
        public void setMinAcceptedToTrain(int minAcceptedToTrain) { this.minAcceptedToTrain = minAcceptedToTrain; }
        public int getMaxRunsPerDay() { return maxRunsPerDay; }
        public void setMaxRunsPerDay(int maxRunsPerDay) { this.maxRunsPerDay = maxRunsPerDay; }
        public int getMaxIngestLinesPerRun() { return maxIngestLinesPerRun; }
        public void setMaxIngestLinesPerRun(int maxIngestLinesPerRun) { this.maxIngestLinesPerRun = maxIngestLinesPerRun; }
        public String getIngestStatePath() { return ingestStatePath; }
        public void setIngestStatePath(String ingestStatePath) { this.ingestStatePath = ingestStatePath; }
        public boolean isRawContentToVector() { return rawContentToVector; }
        public void setRawContentToVector(boolean rawContentToVector) { this.rawContentToVector = rawContentToVector; }
        public String getVectorProjectionMode() { return vectorProjectionMode; }
        public void setVectorProjectionMode(String vectorProjectionMode) { this.vectorProjectionMode = vectorProjectionMode; }
    }

    public static class AgentHandoff {
        private boolean enabled = true;
        private String rootPath = "data/agent-handoff/codex";
        private String acceptedPath = "data/agent-handoff/codex/accepted.jsonl";
        private String rejectedPath = "data/agent-handoff/codex/rejected.jsonl";
        private String cyclePath = "data/agent-handoff/codex/cycles.jsonl";
        private String manifestPath = "data/agent-handoff/codex/manifest.json";
        private boolean writeFullAcceptedText = false;
        private int rejectedPreviewChars = 800;
        private int maxLineBytes = 32768;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getRootPath() { return rootPath; }
        public void setRootPath(String rootPath) { this.rootPath = rootPath; }
        public String getAcceptedPath() { return acceptedPath; }
        public void setAcceptedPath(String acceptedPath) { this.acceptedPath = acceptedPath; }
        public String getRejectedPath() { return rejectedPath; }
        public void setRejectedPath(String rejectedPath) { this.rejectedPath = rejectedPath; }
        public String getCyclePath() { return cyclePath; }
        public void setCyclePath(String cyclePath) { this.cyclePath = cyclePath; }
        public String getManifestPath() { return manifestPath; }
        public void setManifestPath(String manifestPath) { this.manifestPath = manifestPath; }
        public boolean isWriteFullAcceptedText() { return writeFullAcceptedText; }
        public void setWriteFullAcceptedText(boolean writeFullAcceptedText) { this.writeFullAcceptedText = writeFullAcceptedText; }
        public int getRejectedPreviewChars() { return rejectedPreviewChars; }
        public void setRejectedPreviewChars(int rejectedPreviewChars) { this.rejectedPreviewChars = rejectedPreviewChars; }
        public int getMaxLineBytes() { return maxLineBytes; }
        public void setMaxLineBytes(int maxLineBytes) { this.maxLineBytes = maxLineBytes; }
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

    public static class ExternalQuota {
        /** Guard AutoLearn calls routed to an optional external free-tier provider. */
        private boolean enabled = false;
        /** Logical model id that triggers the guard. */
        private String routeModel = "llmrouter.external";
        /** Expected provider host for the guarded route. */
        private String providerHost = "opencode.ai";
        /** Only this documented free model is allowed when strictFreeModelOnly is true. */
        private String freeModel = "deepseek-v4-flash-free";
        /** Max guarded external calls per day; <=0 means no call-count cap. */
        private int maxCallsPerDay = 3;
        /** Conservative daily output token reservation; <=0 means no token cap. */
        private int maxOutputTokensPerDay = 1536;
        /** Per-call output token ceiling for guarded external AutoLearn calls; <=0 means no per-call cap. */
        private int maxOutputTokensPerCall = 512;
        /** Max guarded external calls per AutoLearn cycle; <=0 means no cycle cap. */
        private int maxCallsPerCycle = 1;
        /** Cooldown to latch after provider quota/rate-limit failures. */
        private int rateLimitCooldownSeconds = 86_400;
        /** Fail closed if the route model is not the documented free model. */
        private boolean strictFreeModelOnly = true;
        /** Provider daily reset zone for the local quota ledger. */
        private String resetZone = "UTC";
        /** External free routes default to static synthetic seeds only. */
        private String privacyMode = "STATIC_SYNTHETIC_ONLY";
        /** Canonical training policy for external free/trial outputs. */
        private String canonicalTrainingPolicy = "EXTERNAL_FREE_CURATE_ONLY";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getRouteModel() { return routeModel; }
        public void setRouteModel(String routeModel) { this.routeModel = routeModel; }
        public String getProviderHost() { return providerHost; }
        public void setProviderHost(String providerHost) { this.providerHost = providerHost; }
        public String getFreeModel() { return freeModel; }
        public void setFreeModel(String freeModel) { this.freeModel = freeModel; }
        public int getMaxCallsPerDay() { return maxCallsPerDay; }
        public void setMaxCallsPerDay(int maxCallsPerDay) { this.maxCallsPerDay = maxCallsPerDay; }
        public int getMaxOutputTokensPerDay() { return maxOutputTokensPerDay; }
        public void setMaxOutputTokensPerDay(int maxOutputTokensPerDay) { this.maxOutputTokensPerDay = maxOutputTokensPerDay; }
        public int getMaxOutputTokensPerCall() { return maxOutputTokensPerCall; }
        public void setMaxOutputTokensPerCall(int maxOutputTokensPerCall) { this.maxOutputTokensPerCall = maxOutputTokensPerCall; }
        public int getMaxCallsPerCycle() { return maxCallsPerCycle; }
        public void setMaxCallsPerCycle(int maxCallsPerCycle) { this.maxCallsPerCycle = maxCallsPerCycle; }
        public int getRateLimitCooldownSeconds() { return rateLimitCooldownSeconds; }
        public void setRateLimitCooldownSeconds(int rateLimitCooldownSeconds) { this.rateLimitCooldownSeconds = rateLimitCooldownSeconds; }
        public boolean isStrictFreeModelOnly() { return strictFreeModelOnly; }
        public void setStrictFreeModelOnly(boolean strictFreeModelOnly) { this.strictFreeModelOnly = strictFreeModelOnly; }
        public String getResetZone() { return resetZone; }
        public void setResetZone(String resetZone) { this.resetZone = resetZone; }
        public String getPrivacyMode() { return privacyMode; }
        public void setPrivacyMode(String privacyMode) { this.privacyMode = privacyMode; }
        public String getCanonicalTrainingPolicy() { return canonicalTrainingPolicy; }
        public void setCanonicalTrainingPolicy(String canonicalTrainingPolicy) { this.canonicalTrainingPolicy = canonicalTrainingPolicy; }
    }

    public static class SafetyPin {
        /** Allow at most N samples per cycle even when evidenceCount == 0 (internal/static seeds only). */
        private int maxZeroEvidenceAcceptedPerCycle = 0;
        /** If false, keep strict evidence requirement. */
        private boolean allowZeroEvidenceForStaticSeeds = false;

        public int getMaxZeroEvidenceAcceptedPerCycle() { return maxZeroEvidenceAcceptedPerCycle; }
        public void setMaxZeroEvidenceAcceptedPerCycle(int maxZeroEvidenceAcceptedPerCycle) { this.maxZeroEvidenceAcceptedPerCycle = maxZeroEvidenceAcceptedPerCycle; }
        public boolean isAllowZeroEvidenceForStaticSeeds() { return allowZeroEvidenceForStaticSeeds; }
        public void setAllowZeroEvidenceForStaticSeeds(boolean allowZeroEvidenceForStaticSeeds) { this.allowZeroEvidenceForStaticSeeds = allowZeroEvidenceForStaticSeeds; }
    }

    public static class MemoryReinforcement {
        /**
         * AutoLearn samples stay out of active memory by default. When enabled,
         * metadata-aware reinforcement stores accepted samples as PENDING only.
         */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class IdleTrigger {
        /** Enables the scheduler-driven AutoLearn trigger. Runtime gates still decide if work may start. */
        private boolean enabled = true;
        /** Emits MLA breadcrumbs for idle AutoLearn decision points. */
        private boolean breadcrumbEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isBreadcrumbEnabled() {
            return breadcrumbEnabled;
        }

        public void setBreadcrumbEnabled(boolean breadcrumbEnabled) {
            this.breadcrumbEnabled = breadcrumbEnabled;
        }
    }

    public static class RuntimeNode {
        /** Logical role label for trace/health correlation. */
        private String role = "desktop-gpu-executor";
        /** Physical or logical execution node label. */
        private String executionNode = "desktop";
        /** Whether this node is acting as a control-plane/observer. */
        private boolean controlPlane = false;
        /** Whether heavy LLM/embedding/rerank/retrain work is allowed here. */
        private boolean heavyWorkloadsAllowed = true;
        /** Whether this node is allowed to assist scheduling only. */
        private boolean schedulingAssistant = false;
        /** Learning-loop mode label for Dataset/IdleTrain diagnostics. */
        private String learningLoopMode = "execute-and-curate";

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getExecutionNode() {
            return executionNode;
        }

        public void setExecutionNode(String executionNode) {
            this.executionNode = executionNode;
        }

        public boolean isControlPlane() {
            return controlPlane;
        }

        public void setControlPlane(boolean controlPlane) {
            this.controlPlane = controlPlane;
        }

        public boolean isHeavyWorkloadsAllowed() {
            return heavyWorkloadsAllowed;
        }

        public void setHeavyWorkloadsAllowed(boolean heavyWorkloadsAllowed) {
            this.heavyWorkloadsAllowed = heavyWorkloadsAllowed;
        }

        public boolean isSchedulingAssistant() {
            return schedulingAssistant;
        }

        public void setSchedulingAssistant(boolean schedulingAssistant) {
            this.schedulingAssistant = schedulingAssistant;
        }

        public String getLearningLoopMode() {
            return learningLoopMode;
        }

        public void setLearningLoopMode(String learningLoopMode) {
            this.learningLoopMode = learningLoopMode;
        }
    }

    public static class Validation {
        /** Enables per-sample AutoLearn validation metrics and trace/debug emission. */
        private boolean metricsEnabled = true;
        /** Enables sampleScore/contamination dynamic threshold tuning. */
        private boolean dynamicThresholdEnabled = true;
        /** Bounded in-memory window for error-rate and anomaly detection. */
        private int windowSize = 64;
        /** EWMA alpha for sample score, contamination, and error rate. */
        private double ewmaAlpha = 0.20d;
        /** Allows validation anomalies to keep vector ingestion in quarantine. */
        private boolean autoQuarantineEnabled = true;
        /** Base contradiction line used by AutoLearn validation and ingest. */
        private double contradictionThreshold = 0.60d;
        /** Lower bound for dynamic contradiction threshold tuning. */
        private double contradictionThresholdMin = 0.50d;
        /** Upper bound for dynamic contradiction threshold tuning. */
        private double contradictionThresholdMax = 0.75d;
        /** Target rolling error-rate for dynamic threshold tuning. */
        private double targetErrorRate = 0.25d;
        /** Multiplier used to convert error-rate gap into a threshold delta. */
        private double tuningScale = 0.20d;
        /** Lower bound for dynamic threshold delta. */
        private double tuningDeltaMin = -0.04d;
        /** Upper bound for dynamic threshold delta. */
        private double tuningDeltaMax = 0.08d;
        /** Retrain/reindex is blocked while the rolling error-rate is above this line. */
        private double maxTrainErrorRate = 0.35d;
        /** Absolute error-rate line that marks a short-term spike. */
        private double spikeErrorRate = 0.50d;
        /** EWMA-relative error-rate rise that marks a short-term spike. */
        private double spikeErrorRateRise = 0.25d;
        /** Absolute context-contamination line that marks a short-term spike. */
        private double spikeContaminationScore = 0.55d;
        /** EWMA-relative contamination rise that marks a short-term spike. */
        private double spikeContaminationRise = 0.20d;
        /** Minimum bounded-window size required before drift detection becomes active. */
        private int driftMinWindow = 8;
        /** EWMA-relative sample-score drop that marks drift. */
        private double driftSampleScoreDrop = 0.20d;
        /** EWMA-relative contamination rise that marks drift. */
        private double driftContaminationRise = 0.15d;
        /** Emits one compact self-diagnosis event at the end of each AutoLearn cycle. */
        private boolean cycleDebugEnabled = true;

        public boolean isMetricsEnabled() {
            return metricsEnabled;
        }

        public void setMetricsEnabled(boolean metricsEnabled) {
            this.metricsEnabled = metricsEnabled;
        }

        public boolean isDynamicThresholdEnabled() {
            return dynamicThresholdEnabled;
        }

        public void setDynamicThresholdEnabled(boolean dynamicThresholdEnabled) {
            this.dynamicThresholdEnabled = dynamicThresholdEnabled;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        public double getEwmaAlpha() {
            return ewmaAlpha;
        }

        public void setEwmaAlpha(double ewmaAlpha) {
            this.ewmaAlpha = ewmaAlpha;
        }

        public boolean isAutoQuarantineEnabled() {
            return autoQuarantineEnabled;
        }

        public void setAutoQuarantineEnabled(boolean autoQuarantineEnabled) {
            this.autoQuarantineEnabled = autoQuarantineEnabled;
        }

        public double getContradictionThreshold() {
            return contradictionThreshold;
        }

        public void setContradictionThreshold(double contradictionThreshold) {
            this.contradictionThreshold = contradictionThreshold;
        }

        public double getContradictionThresholdMin() {
            return contradictionThresholdMin;
        }

        public void setContradictionThresholdMin(double contradictionThresholdMin) {
            this.contradictionThresholdMin = contradictionThresholdMin;
        }

        public double getContradictionThresholdMax() {
            return contradictionThresholdMax;
        }

        public void setContradictionThresholdMax(double contradictionThresholdMax) {
            this.contradictionThresholdMax = contradictionThresholdMax;
        }

        public double getTargetErrorRate() {
            return targetErrorRate;
        }

        public void setTargetErrorRate(double targetErrorRate) {
            this.targetErrorRate = targetErrorRate;
        }

        public double getTuningScale() {
            return tuningScale;
        }

        public void setTuningScale(double tuningScale) {
            this.tuningScale = tuningScale;
        }

        public double getTuningDeltaMin() {
            return tuningDeltaMin;
        }

        public void setTuningDeltaMin(double tuningDeltaMin) {
            this.tuningDeltaMin = tuningDeltaMin;
        }

        public double getTuningDeltaMax() {
            return tuningDeltaMax;
        }

        public void setTuningDeltaMax(double tuningDeltaMax) {
            this.tuningDeltaMax = tuningDeltaMax;
        }

        public double getMaxTrainErrorRate() {
            return maxTrainErrorRate;
        }

        public void setMaxTrainErrorRate(double maxTrainErrorRate) {
            this.maxTrainErrorRate = maxTrainErrorRate;
        }

        public double getSpikeErrorRate() {
            return spikeErrorRate;
        }

        public void setSpikeErrorRate(double spikeErrorRate) {
            this.spikeErrorRate = spikeErrorRate;
        }

        public double getSpikeErrorRateRise() {
            return spikeErrorRateRise;
        }

        public void setSpikeErrorRateRise(double spikeErrorRateRise) {
            this.spikeErrorRateRise = spikeErrorRateRise;
        }

        public double getSpikeContaminationScore() {
            return spikeContaminationScore;
        }

        public void setSpikeContaminationScore(double spikeContaminationScore) {
            this.spikeContaminationScore = spikeContaminationScore;
        }

        public double getSpikeContaminationRise() {
            return spikeContaminationRise;
        }

        public void setSpikeContaminationRise(double spikeContaminationRise) {
            this.spikeContaminationRise = spikeContaminationRise;
        }

        public int getDriftMinWindow() {
            return driftMinWindow;
        }

        public void setDriftMinWindow(int driftMinWindow) {
            this.driftMinWindow = driftMinWindow;
        }

        public double getDriftSampleScoreDrop() {
            return driftSampleScoreDrop;
        }

        public void setDriftSampleScoreDrop(double driftSampleScoreDrop) {
            this.driftSampleScoreDrop = driftSampleScoreDrop;
        }

        public double getDriftContaminationRise() {
            return driftContaminationRise;
        }

        public void setDriftContaminationRise(double driftContaminationRise) {
            this.driftContaminationRise = driftContaminationRise;
        }

        public boolean isCycleDebugEnabled() {
            return cycleDebugEnabled;
        }

        public void setCycleDebugEnabled(boolean cycleDebugEnabled) {
            this.cycleDebugEnabled = cycleDebugEnabled;
        }
    }

    public long getTickMs() { return tickMs; }
    public void setTickMs(long tickMs) { this.tickMs = tickMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxCycleSeconds() { return maxCycleSeconds; }
    public void setMaxCycleSeconds(int maxCycleSeconds) { this.maxCycleSeconds = maxCycleSeconds; }
    public int getMinEvidenceCount() { return minEvidenceCount; }
    public void setMinEvidenceCount(int minEvidenceCount) { this.minEvidenceCount = minEvidenceCount; }
    public double getMinContextDiversity() { return minContextDiversity; }
    public void setMinContextDiversity(double minContextDiversity) { this.minContextDiversity = minContextDiversity; }

    public Idle getIdle() { return idle; }
    public void setIdle(Idle idle) { this.idle = idle; }
    public Dataset getDataset() { return dataset; }
    public void setDataset(Dataset dataset) { this.dataset = dataset; }
    public Retrain getRetrain() { return retrain; }
    public void setRetrain(Retrain retrain) { this.retrain = retrain; }
    public AgentHandoff getAgentHandoff() { return agentHandoff; }
    public void setAgentHandoff(AgentHandoff agentHandoff) {
        this.agentHandoff = agentHandoff == null ? new AgentHandoff() : agentHandoff;
    }
    public Budget getBudget() { return budget; }
    public void setBudget(Budget budget) { this.budget = budget; }
    public ExternalQuota getExternalQuota() { return externalQuota; }
    public void setExternalQuota(ExternalQuota externalQuota) {
        this.externalQuota = externalQuota == null ? new ExternalQuota() : externalQuota;
    }
    public MemoryReinforcement getMemoryReinforcement() { return memoryReinforcement; }
    public void setMemoryReinforcement(MemoryReinforcement memoryReinforcement) { this.memoryReinforcement = memoryReinforcement; }
    public IdleTrigger getIdleTrigger() { return idleTrigger; }
    public void setIdleTrigger(IdleTrigger idleTrigger) { this.idleTrigger = idleTrigger; }
    public Validation getValidation() { return validation; }
    public void setValidation(Validation validation) { this.validation = validation; }
    public RuntimeNode getRuntimeNode() { return runtimeNode; }
    public void setRuntimeNode(RuntimeNode runtimeNode) { this.runtimeNode = runtimeNode; }

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
