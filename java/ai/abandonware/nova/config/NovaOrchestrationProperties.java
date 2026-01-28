package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Nova orchestration overlay properties. */
@ConfigurationProperties(prefix = "nova.orch")
public class NovaOrchestrationProperties {

    private boolean enabled = true;

    private QueryTransformerProps queryTransformer = new QueryTransformerProps();
    private RagCompressorProps ragCompressor = new RagCompressorProps();

    /** Overdrive (evidence scarcity/contradiction) auto-trigger knobs. */
    private OverdriveProps overdrive = new OverdriveProps();

    /** Massive Parallel Query Expansion ("ExtremeZ") knobs. Disabled by default. */
    private ExtremeZProps extremeZ = new ExtremeZProps();

    /** Thread-interrupt hygiene knobs. */
    private InterruptHygieneProps interruptHygiene = new InterruptHygieneProps();


    /** Breadcrumb/correlation propagation knobs (conversation-sid + requestSid dual breadcrumb). */
    private BreadcrumbProps breadcrumb = new BreadcrumbProps();

    /** Long-input chunking & rolling summary knobs. */
    private ChunkingProps chunking = new ChunkingProps();

    private DegradedStorageProps degradedStorage = new DegradedStorageProps();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public QueryTransformerProps getQueryTransformer() {
        return queryTransformer;
    }

    public void setQueryTransformer(QueryTransformerProps queryTransformer) {
        this.queryTransformer = queryTransformer;
    }

    public RagCompressorProps getRagCompressor() {
        return ragCompressor;
    }

    public void setRagCompressor(RagCompressorProps ragCompressor) {
        this.ragCompressor = ragCompressor;
    }

    public OverdriveProps getOverdrive() {
        return overdrive;
    }

    public void setOverdrive(OverdriveProps overdrive) {
        this.overdrive = overdrive;
    }

    public ExtremeZProps getExtremeZ() {
        return extremeZ;
    }

    public void setExtremeZ(ExtremeZProps extremeZ) {
        this.extremeZ = extremeZ;
    }

    public InterruptHygieneProps getInterruptHygiene() {
        return interruptHygiene;
    }

    public void setInterruptHygiene(InterruptHygieneProps interruptHygiene) {
        this.interruptHygiene = interruptHygiene;
    }

    public BreadcrumbProps getBreadcrumb() {
        return breadcrumb;
    }

    public void setBreadcrumb(BreadcrumbProps breadcrumb) {
        this.breadcrumb = (breadcrumb == null) ? new BreadcrumbProps() : breadcrumb;
    }


    public ChunkingProps getChunking() {
        return chunking;
    }

    public void setChunking(ChunkingProps chunking) {
        this.chunking = (chunking == null) ? new ChunkingProps() : chunking;
    }

    public DegradedStorageProps getDegradedStorage() {
        return degradedStorage;
    }

    public void setDegradedStorage(DegradedStorageProps degradedStorage) {
        this.degradedStorage = degradedStorage;
    }

    public static class QueryTransformerProps {
        private boolean enabled = true;
        private boolean bypassOnStrike = true;
        private int cheapVariants = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isBypassOnStrike() {
            return bypassOnStrike;
        }

        public void setBypassOnStrike(boolean bypassOnStrike) {
            this.bypassOnStrike = bypassOnStrike;
        }

        public int getCheapVariants() {
            return cheapVariants;
        }

        public void setCheapVariants(int cheapVariants) {
            this.cheapVariants = cheapVariants;
        }
    }

    public static class RagCompressorProps {
        private boolean enabled = true;
        private int targetChars = 5200;
        private int minDocs = 3;
        private int maxDocs = 10;
        private int anchorWindowChars = 640;
        private int maxContents = 8;
        private int maxCharsPerContent = 800;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTargetChars() {
            return targetChars;
        }

        public void setTargetChars(int targetChars) {
            this.targetChars = targetChars;
        }

        public int getMinDocs() {
            return minDocs;
        }

        public void setMinDocs(int minDocs) {
            this.minDocs = minDocs;
        }

        public int getMaxDocs() {
            return maxDocs;
        }

        public void setMaxDocs(int maxDocs) {
            this.maxDocs = maxDocs;
        }

        public int getAnchorWindowChars() {
            return anchorWindowChars;
        }

        public void setAnchorWindowChars(int anchorWindowChars) {
            this.anchorWindowChars = anchorWindowChars;
        }

        public int getMaxContents() {
            return maxContents;
        }

        public void setMaxContents(int maxContents) {
            this.maxContents = maxContents;
        }

        public int getMaxCharsPerContent() {
            return maxCharsPerContent;
        }

        public void setMaxCharsPerContent(int maxCharsPerContent) {
            this.maxCharsPerContent = maxCharsPerContent;
        }
    }

    /**
     * Overdrive (evidence scarcity/contradiction) auto-trigger knobs.
     *
     * <p>
     * Note: core scoring logic lives in
     * {@code com.example.lms.service.rag.overdrive.OverdriveGuard}.
     * Nova only wires it into retrieval orchestration (fail-soft).
     */
    public static class OverdriveProps {
        private boolean enabled = true;
        private boolean autoActivateCompression = true;
        private boolean markCompressionMode = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAutoActivateCompression() {
            return autoActivateCompression;
        }

        public void setAutoActivateCompression(boolean autoActivateCompression) {
            this.autoActivateCompression = autoActivateCompression;
        }

        public boolean isMarkCompressionMode() {
            return markCompressionMode;
        }

        public void setMarkCompressionMode(boolean markCompressionMode) {
            this.markCompressionMode = markCompressionMode;
        }
    }

    /**
     * Massive Parallel Query Expansion ("ExtremeZ") knobs.
     *
     * <p>
     * Disabled by default to avoid accidental traffic bursts.
     */
    public static class ExtremeZProps {
        private boolean enabled = false;
        private int minBaseDocs = 3;
        private int maxSubQueries = 6;
        private long budgetMs = 1500L;
        private int maxMergedDocs = 16;
        private boolean skipWhenStrikeMode = true;
        private boolean skipWhenWebRateLimited = true;
        private boolean skipWhenAuxDown = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinBaseDocs() {
            return minBaseDocs;
        }

        public void setMinBaseDocs(int minBaseDocs) {
            this.minBaseDocs = minBaseDocs;
        }

        public int getMaxSubQueries() {
            return maxSubQueries;
        }

        public void setMaxSubQueries(int maxSubQueries) {
            this.maxSubQueries = maxSubQueries;
        }

        public long getBudgetMs() {
            return budgetMs;
        }

        public void setBudgetMs(long budgetMs) {
            this.budgetMs = budgetMs;
        }

        public int getMaxMergedDocs() {
            return maxMergedDocs;
        }

        public void setMaxMergedDocs(int maxMergedDocs) {
            this.maxMergedDocs = maxMergedDocs;
        }

        public boolean isSkipWhenStrikeMode() {
            return skipWhenStrikeMode;
        }

        public void setSkipWhenStrikeMode(boolean skipWhenStrikeMode) {
            this.skipWhenStrikeMode = skipWhenStrikeMode;
        }

        public boolean isSkipWhenWebRateLimited() {
            return skipWhenWebRateLimited;
        }

        public void setSkipWhenWebRateLimited(boolean skipWhenWebRateLimited) {
            this.skipWhenWebRateLimited = skipWhenWebRateLimited;
        }

        public boolean isSkipWhenAuxDown() {
            return skipWhenAuxDown;
        }

        public void setSkipWhenAuxDown(boolean skipWhenAuxDown) {
            this.skipWhenAuxDown = skipWhenAuxDown;
        }
    }

    /** Thread-interrupt hygiene knobs. */
    public static class InterruptHygieneProps {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    
    /**
     * Breadcrumb/correlation propagation knobs.
     *
     * <p>Primary goal: keep a stable conversation-level sid (<code>chat-&lt;sessionId&gt;</code>) across
     * chunked requests, while preserving the raw/browser sid in <code>MDC[requestSid]</code> for
     * observability.</p>
     */
    public static class BreadcrumbProps {
        private boolean enabled = true;
        /** If true, chat flows override MDC["sid"] with "chat-<sessionId>". */
        private boolean overrideSidWithConversationSid = true;
        /** Prefix used for conversation sids. Default: "chat-". */
        private String conversationSidPrefix = "chat-";
        /** Keep the original sid in MDC[requestSid] when overriding MDC[sid]. */
        private boolean keepRequestSid = true;
        /** MDC key for the raw/browser sid. Default: "requestSid". */
        private String requestSidKey = "requestSid";
        /** MDC key for the numeric chat session id hint. Default: "chatSessionId". */
        private String chatSessionIdKey = "chatSessionId";
        /** TraceStore key for the raw/browser sid. Default: "req.sid". */
        private String requestSidTraceKey = "req.sid";
        /** TraceStore key for the numeric chat session id hint. Default: "chatSessionId". */
        private String chatSessionIdTraceKey = "chatSessionId";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isOverrideSidWithConversationSid() {
            return overrideSidWithConversationSid;
        }

        public void setOverrideSidWithConversationSid(boolean overrideSidWithConversationSid) {
            this.overrideSidWithConversationSid = overrideSidWithConversationSid;
        }

        public String getConversationSidPrefix() {
            return conversationSidPrefix;
        }

        public void setConversationSidPrefix(String conversationSidPrefix) {
            this.conversationSidPrefix = conversationSidPrefix;
        }

        public boolean isKeepRequestSid() {
            return keepRequestSid;
        }

        public void setKeepRequestSid(boolean keepRequestSid) {
            this.keepRequestSid = keepRequestSid;
        }

        public String getRequestSidKey() {
            return requestSidKey;
        }

        public void setRequestSidKey(String requestSidKey) {
            this.requestSidKey = requestSidKey;
        }

        public String getChatSessionIdKey() {
            return chatSessionIdKey;
        }

        public void setChatSessionIdKey(String chatSessionIdKey) {
            this.chatSessionIdKey = chatSessionIdKey;
        }

        public String getRequestSidTraceKey() {
            return requestSidTraceKey;
        }

        public void setRequestSidTraceKey(String requestSidTraceKey) {
            this.requestSidTraceKey = requestSidTraceKey;
        }

        public String getChatSessionIdTraceKey() {
            return chatSessionIdTraceKey;
        }

        public void setChatSessionIdTraceKey(String chatSessionIdTraceKey) {
            this.chatSessionIdTraceKey = chatSessionIdTraceKey;
        }
    }

/**
     * Long-input chunking & rolling summary knobs.
     *
     * <p>
     * Primary goal: avoid context truncation when the user uploads large text/logs
     * across multiple turns.
     * The server stores a rolling summary (⎔RSUM⎔ meta) and prepends it to prompt
     * history without consuming
     * the message-limit budget.
     */
    public static class ChunkingProps {
        private boolean enabled = true;
        /** If user message length >= this, treat as a "long/chunk" candidate. */
        private int userMinChars = 3500;
        /** Max characters for a single chunk block in rolling summary input. */
        private int chunkMaxChars = 1500;
        /**
         * If message length >= this, always update the rolling summary (budget
         * trigger).
         */
        private int budgetTriggerChars = 12000;
        /** Update rolling summary every N chunk candidates (cost throttle). */
        private int summaryEveryNChunks = 3;
        /**
         * How many recent DB messages to scan when computing chunks since last RSUM.
         */
        private int summaryScanLimit = 64;
        /** Max characters passed into the distillation input. */
        private int distillMaxInputChars = 12000;
        /** Distillation timeout (ms). */
        private long distillTimeoutMs = 4000L;
        /** Max chars to store in rolling summary body. */
        private int rollingSummaryMaxChars = 1800;
        /** Whether to prepend rolling summary to getFormattedRecentHistory output. */
        private boolean prependRollingSummaryInHistory = true;
        /** Max chars to inject into history for prompt. */
        private int historyPrependMaxChars = 2200;
        /** Whether to strip chunk envelope header before persisting user message. */
        private boolean stripChunkEnvelopeOnPersist = false;
        /**
         * Whether to create summary immediately for the first chunk in a new session.
         */
        private boolean createSummaryOnFirstChunk = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getUserMinChars() {
            return userMinChars;
        }

        public void setUserMinChars(int userMinChars) {
            this.userMinChars = userMinChars;
        }

        public int getChunkMaxChars() {
            return chunkMaxChars;
        }

        public void setChunkMaxChars(int chunkMaxChars) {
            this.chunkMaxChars = chunkMaxChars;
        }

        public int getBudgetTriggerChars() {
            return budgetTriggerChars;
        }

        public void setBudgetTriggerChars(int budgetTriggerChars) {
            this.budgetTriggerChars = budgetTriggerChars;
        }

        public int getSummaryEveryNChunks() {
            return summaryEveryNChunks;
        }

        public void setSummaryEveryNChunks(int summaryEveryNChunks) {
            this.summaryEveryNChunks = summaryEveryNChunks;
        }

        public int getSummaryScanLimit() {
            return summaryScanLimit;
        }

        public void setSummaryScanLimit(int summaryScanLimit) {
            this.summaryScanLimit = summaryScanLimit;
        }

        public int getDistillMaxInputChars() {
            return distillMaxInputChars;
        }

        public void setDistillMaxInputChars(int distillMaxInputChars) {
            this.distillMaxInputChars = distillMaxInputChars;
        }

        public long getDistillTimeoutMs() {
            return distillTimeoutMs;
        }

        public void setDistillTimeoutMs(long distillTimeoutMs) {
            this.distillTimeoutMs = distillTimeoutMs;
        }

        public int getRollingSummaryMaxChars() {
            return rollingSummaryMaxChars;
        }

        public void setRollingSummaryMaxChars(int rollingSummaryMaxChars) {
            this.rollingSummaryMaxChars = rollingSummaryMaxChars;
        }

        public boolean isPrependRollingSummaryInHistory() {
            return prependRollingSummaryInHistory;
        }

        public void setPrependRollingSummaryInHistory(boolean prependRollingSummaryInHistory) {
            this.prependRollingSummaryInHistory = prependRollingSummaryInHistory;
        }

        public int getHistoryPrependMaxChars() {
            return historyPrependMaxChars;
        }

        public void setHistoryPrependMaxChars(int historyPrependMaxChars) {
            this.historyPrependMaxChars = historyPrependMaxChars;
        }

        public boolean isStripChunkEnvelopeOnPersist() {
            return stripChunkEnvelopeOnPersist;
        }

        public void setStripChunkEnvelopeOnPersist(boolean stripChunkEnvelopeOnPersist) {
            this.stripChunkEnvelopeOnPersist = stripChunkEnvelopeOnPersist;
        }

        public boolean isCreateSummaryOnFirstChunk() {
            return createSummaryOnFirstChunk;
        }

        public void setCreateSummaryOnFirstChunk(boolean createSummaryOnFirstChunk) {
            this.createSummaryOnFirstChunk = createSummaryOnFirstChunk;
        }
    }

    public static class DegradedStorageProps {
        private boolean enabled = true;
        private String path = "./data/degraded-memory.jsonl";
        /** TTL hint for external/periodic cleanup. */
        private long ttlSeconds = 604800L;

        // MERGE_HOOK:PROJ_AGENT::DEGRADED_OUTBOX_RETENTION_PROPS_V1
        /**
         * Storage format for degraded outbox.
         *
         * <ul>
         * <li><code>auto</code> (default): infer from {@link #path}</li>
         * <li><code>jsonl</code>: legacy single JSONL file</li>
         * <li><code>dir</code>: directory spool (1 file per item; recommended)</li>
         * </ul>
         */
        private String format = "auto";

        /**
         * Hard cap for pending artifacts (dir: files, jsonl: lines).
         *
         * <p>
         * 0 or negative disables.
         * </p>
         */
        private int maxFiles = 20000;

        /**
         * Hard cap for on-disk usage.
         *
         * <p>
         * 0 or negative disables.
         * </p>
         */
        private long maxBytes = 256L * 1024L * 1024L;

        /**
         * If an item has been claimed/inflight longer than this, it will be recovered
         * back to
         * pending on a sweep.
         */
        private long inflightStaleSeconds = 3600L;

        // MERGE_HOOK:PROJ_AGENT::DEGRADED_OUTBOX_QUARANTINE_PROPS_V1
        /**
         * If enabled, items that exceed {@link #maxAttempts} will be quarantined
         * instead of being
         * re-queued back into pending.
         */
        private boolean quarantineEnabled = true;

        /**
         * Maximum number of failed attempts (nacks) before an item is quarantined.
         *
         * <p>
         * 0 or negative disables the quarantine behavior.
         * </p>
         */
        private int maxAttempts = 10;

        /**
         * Quarantine location hint:
         *
         * <ul>
         * <li>dir format: quarantine sub-directory name under the outbox directory</li>
         * <li>jsonl format: appended as a suffix to the jsonl file name</li>
         * </ul>
         */
        private String quarantineDirName = "quarantine";

        /** Micrometer metrics settings for outbox diagnostics. */
        private MetricsProps metrics = new MetricsProps();

        /**
         * Whether to enforce TTL/max limits on each write (in addition to drain-time).
         */
        private boolean enforceOnWrite = true;

        /** Optional scheduled drain/consume loop (disabled by default). */
        private DrainProps drain = new DrainProps();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public int getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(int maxFiles) {
            this.maxFiles = maxFiles;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public long getInflightStaleSeconds() {
            return inflightStaleSeconds;
        }

        public void setInflightStaleSeconds(long inflightStaleSeconds) {
            this.inflightStaleSeconds = inflightStaleSeconds;
        }

        public boolean isQuarantineEnabled() {
            return quarantineEnabled;
        }

        public void setQuarantineEnabled(boolean quarantineEnabled) {
            this.quarantineEnabled = quarantineEnabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public String getQuarantineDirName() {
            return quarantineDirName;
        }

        public void setQuarantineDirName(String quarantineDirName) {
            this.quarantineDirName = quarantineDirName;
        }

        public MetricsProps getMetrics() {
            return metrics;
        }

        public void setMetrics(MetricsProps metrics) {
            this.metrics = (metrics == null) ? new MetricsProps() : metrics;
        }

        public boolean isEnforceOnWrite() {
            return enforceOnWrite;
        }

        public void setEnforceOnWrite(boolean enforceOnWrite) {
            this.enforceOnWrite = enforceOnWrite;
        }

        public DrainProps getDrain() {
            return drain;
        }

        public void setDrain(DrainProps drain) {
            this.drain = (drain == null) ? new DrainProps() : drain;
        }

        /** Controls micrometer outbox metrics. */
        public static class MetricsProps {
            private boolean enabled = true;
            private long refreshMs = 5000L;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public long getRefreshMs() {
                return refreshMs;
            }

            public void setRefreshMs(long refreshMs) {
                this.refreshMs = refreshMs;
            }
        }

        /** Controls the "drain 소비 플로우" for degraded-storage. */
        public static class DrainProps {
            /** Enable scheduled drainer bean. Default: off (manual opt-in). */
            private boolean enabled = false;
            private int batchSize = 50;
            private long initialDelayMs = 60_000L;
            private long fixedDelayMs = 300_000L;
            private boolean requireEvidence = true;
            private int minSnippetLen = 80;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getBatchSize() {
                return batchSize;
            }

            public void setBatchSize(int batchSize) {
                this.batchSize = batchSize;
            }

            public long getInitialDelayMs() {
                return initialDelayMs;
            }

            public void setInitialDelayMs(long initialDelayMs) {
                this.initialDelayMs = initialDelayMs;
            }

            public long getFixedDelayMs() {
                return fixedDelayMs;
            }

            public void setFixedDelayMs(long fixedDelayMs) {
                this.fixedDelayMs = fixedDelayMs;
            }

            public boolean isRequireEvidence() {
                return requireEvidence;
            }

            public void setRequireEvidence(boolean requireEvidence) {
                this.requireEvidence = requireEvidence;
            }

            public int getMinSnippetLen() {
                return minSnippetLen;
            }

            public void setMinSnippetLen(int minSnippetLen) {
                this.minSnippetLen = minSnippetLen;
            }
        }
    }
}
