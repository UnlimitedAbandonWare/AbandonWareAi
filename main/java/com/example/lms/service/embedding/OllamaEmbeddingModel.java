package com.example.lms.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.example.lms.llm.spec.ModelSpecSnapshot;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OllamaEmbeddingModel
 *
 * <p>
 * Local embedding provider wrapper with:
 * <ul>
 * <li>Port failover (11435 ↔ 11434) on connection refused</li>
 * <li>Optional explicit local fallback URL: embedding.base-url-fallback</li>
 * <li>Fast-fail (breaker-lite) to route to a backup embedding model when local
 * is unhealthy</li>
 * <li>Optional health preflight to avoid expensive failures</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OllamaEmbeddingModel implements EmbeddingModel, MatryoshkaAware {

    /** Scoped private memory uses the configured Ollama space with no backup provider/health warmup. */
    public Embedding embedPrivate(String text) {
        if (!isOllamaProvider() || text == null || text.isBlank()) throw new IllegalArgumentException("private_embedding_disabled");
        com.example.lms.service.chat.ChatRunExecutionContext.capRequestWait(800);
        JsonNode response = postEmbedWithFallback(text, dimensions > 0 ? dimensions : null, null, timeoutSec);
        float[] raw = parseFloatArray(response.path("embeddings").path(0));
        if (raw.length == 0) throw new IllegalStateException("private_embedding_empty");
        return Embedding.from(normalizeEmbedding(raw, "private"));
    }

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingModel.class);

    /**
     * Static fallback for older callers. Runtime paths should use
     * {@link #indexDimensions()} so the contract follows embedding.dimensions.
     */
    private static final int DEFAULT_INDEX_DIM = 1536;
    private static final String DEFAULT_NORMALIZATION_MODE = "SLICE_TO_CONFIGURED_DIM";

    @Autowired(required = false)
    @Qualifier("backupEmbeddingModel")
    private EmbeddingModel backupModel;

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    @Autowired(required = false)
    private ModelSpecRegistry modelSpecRegistry;

    @Autowired(required = false)
    private com.example.lms.config.LocalLlmProcessManager localLlmProcessManager;

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${embedding.provider:${embeddings.provider:ollama}}")
    private String provider;
    @Value("${embeddings.provider:}")
    private String legacyProvider;

    @Value("${embedding.base-url:http://localhost:11434/api/embed}")
    private String apiUrl;

    /**
     * Optional explicit local fallback URL (e.g. http://localhost:11434/api/embed).
     */
    @Value("${embedding.base-url-fallback:}")
    private String fallbackApiUrl;

    @Value("${embedding.model:qwen3-embedding:4b}")
    private String model;

    @Value("${llm.api-key:${LLM_API_KEY:sk-local}}")
    private String llmApiKey;

    @Value("${llm.owner-token:${LLM_OWNER_TOKEN:}}")
    private String ownerToken;

    @Value("${llm.owner-token-header:${LLM_OWNER_TOKEN_HEADER:X-Owner-Token}}")
    private String ownerTokenHeader;

    @Value("${llm.provider-guard.allow-private-remote:${LLM_PROVIDER_GUARD_ALLOW_PRIVATE_REMOTE:false}}")
    private boolean allowPrivateRemote;

    @Value("${llm.provider-guard.allowed-hosts:${LLM_PROVIDER_GUARD_ALLOWED_HOSTS:}}")
    private String allowedHosts;

    @Value("${llm.provider-guard.require-auth-for-remote:${LLM_PROVIDER_GUARD_REQUIRE_AUTH_FOR_REMOTE:true}}")
    private boolean requireAuthForRemote;

    /**
     * Some local embedding servers (e.g. Ollama /api/embed) may warn on unknown options.
     * Enabled by default to take advantage of model-side dimensionality control
     * when supported. If the server rejects the option (e.g. "invalid option provided option=dimensions"),
     * the client will auto-suppress and retry without it.
     */
    @Value("${embedding.ollama.options.dimensions.enabled:true}")
    private boolean dimensionsOptionEnabled;

    @Value("${embedding.timeout-seconds:30}")
    private int timeoutSec;

    /** Target dimensions (used for matryoshka slicing). */
    @Value("${embedding.dimensions:1536}")
    private int dimensions;

    /**
     * Expected raw provider dimension for diagnostics. qwen3-embedding:4b returns
     * 2560 natively, while the repo vector contract remains embedding.dimensions.
     */
    @Value("${embedding.provider-raw-dimensions:0}")
    private int providerRawDimensions;

    @Value("${embedding.normalization-mode:SLICE_TO_CONFIGURED_DIM}")
    private String normalizationMode;

    /** WARN_ONLY | STRICT (best-effort). */
    @Value("${embedding.dimension-guard-mode:WARN_ONLY}")
    private String dimensionGuardMode;

    @Value("${embedding.allow-zero-pad:false}")
    private boolean allowZeroPad;

    @Value("${embedding.log-dimension-mismatch:true}")
    private boolean logDimensionMismatch;

    @Value("${embedding.port-fallback.enabled:false}")
    private boolean portFallbackEnabled;

    @Value("${embedding.cross-gpu-fallback.enabled:false}")
    private boolean crossGpuFallbackEnabled;

    private final AtomicBoolean fallbackConfigLogged = new AtomicBoolean(false);

    /** Ollama keep-alive string (passed as-is). */
    @Value("${embedding.ollama.keep-alive:}")
    private String ollamaKeepAlive;

    // ─────────────────────────────────────────────────────────────────────
    // Fast-fail configuration
    // ─────────────────────────────────────────────────────────────────────

    @Value("${embedding.fast-fail.enabled:true}")
    private boolean fastFailEnabled;

    @Value("${embedding.fast-fail.fail-threshold:1}")
    private int fastFailThreshold;

    /** Preferred config knob. If 0, legacy ms knob (if any) is used. */
    @Value("${embedding.fast-fail.cooldown-seconds:0}")
    private long fastFailCooldownSeconds;

    /**
     * Legacy knob (milliseconds). If non-zero and cooldown-seconds is 0, used as
     * base.
     */
    @Value("${embedding.fast-fail.cooldown-ms:0}")
    private long fastFailCooldownMsLegacy;

    /** fixed | exponential */
    @Value("${embedding.fast-fail.cooldown.strategy:fixed}")
    private String fastFailCooldownStrategy;

    @Value("${embedding.fast-fail.cooldown-max-seconds:1800}")
    private long fastFailCooldownMaxSeconds;

    @Value("${embedding.fast-fail.cooldown-backoff-factor:2.0}")
    private double fastFailCooldownBackoffFactor;

    @Value("${embedding.fast-fail.cooldown-jitter-ratio:0.0}")
    private double fastFailCooldownJitterRatio;

    /** Manual override: skip local even if it might be healthy. */
    @Value("${embedding.fast-fail.force-open:false}")
    private boolean fastFailForceOpen;

    @Value("${embedding.fast-fail.force-open-seconds:0}")
    private long fastFailForceOpenSeconds;

    // ─────────────────────────────────────────────────────────────────────
    // Health preflight configuration
    // ─────────────────────────────────────────────────────────────────────

    @Value("${embedding.fast-fail.health.enabled:true}")
    private boolean fastFailHealthEnabled;

    @Value("${embedding.fast-fail.health.concurrent-guard:true}")
    private boolean fastFailHealthConcurrentGuard;

    /** version | tags | tags_ps | embed_probe */
    @Value("${embedding.fast-fail.health.mode:version}")
    private String fastFailHealthMode;

    /** Optional explicit health URL (base or full). */
    @Value("${embedding.fast-fail.health.url:}")
    private String fastFailHealthUrl;

    @Value("${embedding.fast-fail.health.timeout-ms:750}")
    private long fastFailHealthTimeoutMs;

    @Value("${embedding.fast-fail.health.ok-ttl-seconds:30}")
    private long fastFailHealthOkTtlSeconds;

    @Value("${embedding.fast-fail.health.tags.max-model-size-bytes:0}")
    private long fastFailHealthTagsMaxModelSizeBytes;

    @Value("${embedding.fast-fail.health.ps.max-vram-bytes:0}")
    private long fastFailHealthPsMaxVramBytes;

    @Value("${embedding.fast-fail.health.embed-probe.timeout-ms:1500}")
    private long fastFailHealthEmbedProbeTimeoutMs;

    @Value("${embedding.fast-fail.health.embed-probe.input:ping}")
    private String fastFailHealthEmbedProbeInput;

    @Value("${embedding.fast-fail.health.embed-probe.keep-alive:0}")
    private String fastFailHealthEmbedProbeKeepAlive;

    // ─────────────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────────────

    private final AtomicBoolean dimensionWarned = new AtomicBoolean(false);
    private final AtomicInteger lastProviderActualDim = new AtomicInteger(0);
    private final AtomicInteger lastTargetDim = new AtomicInteger(0);

    /**
     * Some Ollama-like servers ignore or reject "options.dimensions".
     * Once detected, we suppress sending the option to avoid noisy warnings
     * and repeated HTTP failures.
     */
    private final AtomicBoolean dimensionsOptionSuppressed = new AtomicBoolean(false);

    private final AtomicInteger failureStreak = new AtomicInteger(0);
    private final AtomicInteger tripCount = new AtomicInteger(0);
    private final AtomicLong skipUntilMs = new AtomicLong(0L);
    private final AtomicLong forceOpenUntilMs = new AtomicLong(0L);

    private final AtomicLong healthOkUntilMs = new AtomicLong(0L);
    private final AtomicLong healthLastOkAtMs = new AtomicLong(0L);
    private final AtomicLong healthLastFailAtMs = new AtomicLong(0L);
    private final AtomicReference<String> healthLastError = new AtomicReference<>(null);
    private final AtomicBoolean healthInFlight = new AtomicBoolean(false);

    private final AtomicReference<String> lastLocalError = new AtomicReference<>(null);

    // ─────────────────────────────────────────────────────────────────────
    // Public helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the embedding dimensions used by the indexing subsystem (static
     * version for external callers).
     */
    public static int indexDimensionsStatic() {
        return DEFAULT_INDEX_DIM;
    }

    /**
     * Returns the embedding dimensions used by the indexing subsystem.
     * Implements MatryoshkaAware interface.
     */
    @Override
    public int indexDimensions() {
        return configuredIndexDimensions();
    }

    /**
     * Runtime snapshot used by diagnostics.
     */
    public Map<String, Object> diagnosticsSnapshot() {
        long now = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("available", true);
        out.put("provider", provider);
        out.put("model", model);
        out.put("apiUrl", safeUrl(apiUrl));
        out.put("fallbackApiUrl", safeUrl(fallbackApiUrl));
        out.put("endpointHost", LocalLlmGatewaySecurity.endpointHost(apiUrl));
        out.put("hasOwnerToken", LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken));
        out.put("dimensions", dimensions);
        out.put("rawProviderDim", rawProviderDimSnapshot());
        out.put("targetDim", configuredIndexDimensions());
        out.put("normalizationMode", effectiveNormalizationMode());
        out.put("dimensionsOptionEnabled", dimensionsOptionEnabled);
        out.put("dimensionsOptionSuppressed", dimensionsOptionSuppressed.get());
        out.put("dimensionsOptionEffective", dimensionsOptionEffectiveSnapshot());
        out.put("timeoutSec", timeoutSec);
        out.put("portFallbackEnabled", portFallbackEnabled);
        out.put("crossGpuFallbackEnabled", crossGpuFallbackEnabled);

        out.put("backupAvailable", backupModel != null);
        out.put("backupCompatible", backupEmbeddingSpaceCompatible());

        // fast-fail
        out.put("fastFailEnabled", fastFailEnabled);
        out.put("fastFailThreshold", fastFailThreshold);
        out.put("skipUntilMs", skipUntilMs.get());
        out.put("skipRemainingMs", Math.max(0L, skipUntilMs.get() - now));
        out.put("failureStreak", failureStreak.get());
        out.put("tripCount", tripCount.get());
        out.put("cooldownSeconds", baseCooldownSeconds());
        out.put("cooldownStrategy", fastFailCooldownStrategy);
        out.put("cooldownMaxSeconds", fastFailCooldownMaxSeconds);
        out.put("cooldownBackoffFactor", fastFailCooldownBackoffFactor);
        out.put("cooldownJitterRatio", fastFailCooldownJitterRatio);
        out.put("forceOpen", fastFailForceOpen);
        out.put("forceOpenUntilMs", forceOpenUntilMs.get());
        out.put("forceOpenRemainingMs", Math.max(0L, forceOpenUntilMs.get() - now));

        // health
        out.put("healthEnabled", fastFailHealthEnabled);
        out.put("healthMode", fastFailHealthMode);
        out.put("healthUrl", safeUrl(resolveHealthUrl("/api/version")));
        out.put("healthOkUntilMs", healthOkUntilMs.get());
        out.put("healthOkRemainingMs", Math.max(0L, healthOkUntilMs.get() - now));
        out.put("healthLastOkAtMs", healthLastOkAtMs.get());
        out.put("healthLastFailAtMs", healthLastFailAtMs.get());
        out.put("healthLastError", healthLastError.get());

        out.put("lastLocalError", lastLocalError.get());

        return out;
    }

    /**
     * Clears fast-fail and health state (diagnostics endpoint).
     */
    public void resetFastFail() {
        failureStreak.set(0);
        tripCount.set(0);
        skipUntilMs.set(0L);
        forceOpenUntilMs.set(0L);
        healthOkUntilMs.set(0L);
        healthLastOkAtMs.set(0L);
        healthLastFailAtMs.set(0L);
        healthLastError.set(null);
        lastLocalError.set(null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // EmbeddingModel implementation
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public Response<Embedding> embed(String text) {
        if (nullSafe(text).isBlank()) {
            rejectBlankEmbeddingInput("single");
        }
        return embed(TextSegment.from(text));
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        if (!isOllamaProvider()) {
            // Not used when provider is not ollama.
            return Response.from(null, null);
        }

        String input = (textSegment == null ? "" : nullSafe(textSegment.text()));
        if (input.isBlank()) {
            rejectBlankEmbeddingInput("single");
        }

        // Fast-fail skip
        if (shouldSkipLocalNow()) {
            return Response.from(Embedding.from(callBackupVector(input, "fastfail")), null);
        }

        // Optional health preflight
        if (!ensureLocalHealthy("single")) {
            return Response.from(Embedding.from(callBackupVector(input, "health")), null);
        }

        float[] vec = callOllamaVector(input);
        return Response.from(Embedding.from(vec), null);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        if (!isOllamaProvider()) {
            return Response.from(List.of(), null);
        }

        if (textSegments == null || textSegments.isEmpty()) {
            return Response.from(List.of(), null);
        }

        // Fast-fail skip
        if (shouldSkipLocalNow()) {
            return Response.from(callBackupBatch(textSegments, "fastfail"), null);
        }

        // Optional health preflight
        if (!ensureLocalHealthy("batch")) {
            return Response.from(callBackupBatch(textSegments, "health"), null);
        }

        // Normal path: try batch -> if mismatch, fallback to per-item
        List<String> texts = new ArrayList<>(textSegments.size());
        for (TextSegment ts : textSegments) {
            texts.add(ts == null ? "" : nullSafe(ts.text()));
        }

        List<float[]> batchVectors = callOllamaBatchVectors(texts);
        if (batchVectors != null && batchVectors.size() == texts.size()) {
            List<Embedding> out = new ArrayList<>(batchVectors.size());
            for (float[] v : batchVectors) {
                out.add(Embedding.from(v));
            }
            return Response.from(out, null);
        }

        // Batch failed or returned inconsistent size: fallback per item (each call can
        // failover to backup)
        log.warn("[OllamaEmbeddingModel] embedAll batch mismatch (got={}, expected={}) -> falling back per item",
                batchVectors == null ? -1 : batchVectors.size(), texts.size());

        List<Embedding> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(Embedding.from(callOllamaVector(t)));
        }
        return Response.from(out, null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Core embedding calls
    // ─────────────────────────────────────────────────────────────────────

    private List<float[]> callOllamaBatchVectors(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        try {
            Integer targetDim = (dimensions > 0 ? dimensions : null);
            JsonNode root = postEmbedWithFallback(texts, targetDim, null, timeoutSec);
            JsonNode embeddings = root.path("embeddings");
            if (!embeddings.isArray()) {
                recordLocalFailure("batch.parse", new IllegalStateException("missing embeddings[]"));
                return null;
            }

            List<float[]> out = new ArrayList<>(texts.size());
            int lastRawDim = 0;
            for (JsonNode arr : embeddings) {
                float[] raw = parseFloatArray(arr);
                lastRawDim = raw.length;
                float[] norm = normalizeEmbedding(raw, "batch");
                out.add(norm);
            }

            // Treat empty as failure (forces retry/failover).
            if (out.isEmpty()) {
                throw new IllegalStateException("empty embeddings from Ollama");
            }

            recordLocalSuccess();
            publishEmbeddingSpec(lastRawDim);
            return out;
        } catch (Exception e) {
            log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "batch");
            recordLocalFailure("batch", e);
            return null;
        }
    }

    private float[] callOllamaVector(String text) {
        String input = nullSafe(text);
        if (input.isBlank()) {
            rejectBlankEmbeddingInput("single");
        }

        try {
            Integer targetDim = (dimensions > 0 ? dimensions : null);
            JsonNode root = postEmbedWithFallback(input, targetDim, null, timeoutSec);
            JsonNode embeddingArray = root.path("embeddings").path(0);
            float[] raw = parseFloatArray(embeddingArray);

            // Treat empty as failure.
            if (raw.length == 0) {
                throw new IllegalStateException("empty embedding from Ollama");
            }

            float[] norm = normalizeEmbedding(raw, "single");
            recordLocalSuccess();
            publishEmbeddingSpec(raw.length);
            return norm;
        } catch (Exception e) {
            log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "single");
            recordLocalFailure("single", e);
            return callBackupVector(input, "fallback");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Backup embedding
    // ─────────────────────────────────────────────────────────────────────

    private void publishEmbeddingSpec(int rawDim) {
        if (modelSpecRegistry == null || rawDim <= 0) {
            return;
        }
        try {
            modelSpecRegistry.publish(ModelSpecSnapshot.of(
                    provider,
                    model,
                    endpointHost(apiUrl),
                    null,
                    rawDim,
                    List.of("embedding"),
                    Map.of("source", "ollama_embed_probe", "targetDim", dimensions)));
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("ollama.embedProbeDebugEvent", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "ollama.embedProbeDebugEvent"); }
    }

    private static void rejectBlankEmbeddingInput(String stage) {
        try {
            com.example.lms.search.TraceStore.inc("embed.blank_input.rejected");
            com.example.lms.search.TraceStore.put("embed.blank_input.stage",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("blankInput.trace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "blankInput.trace"); }
        throw new IllegalArgumentException("Embedding input is blank");
    }

    private static String endpointHost(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(rawUrl.trim()).getHost();
            return host == null ? null : host;
        } catch (Exception ignore) {
            log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "endpointHost");
            return null;
        }
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private boolean backupEmbeddingSpaceCompatible() {
        // Only a known local configuration can attest model and preprocessing identity.
        // A generic/OpenAI backup with the same vector length is not the same embedding space.
        if (!(backupModel instanceof OllamaEmbeddingModel localBackup)
                || localBackup == this || localBackup.backupModel != null) return false;
        return isOllamaProvider() && localBackup.isOllamaProvider()
                && model != null && !model.isBlank()
                && Objects.equals(model.trim(), localBackup.model == null ? null : localBackup.model.trim())
                && configuredIndexDimensions() == localBackup.configuredIndexDimensions()
                && Objects.equals(effectiveNormalizationMode(), localBackup.effectiveNormalizationMode())
                && allowZeroPad == localBackup.allowZeroPad;
    }

    private void requireCompatibleBackupEmbeddingSpace() {
        if (!backupEmbeddingSpaceCompatible()) {
            com.example.lms.search.TraceStore.put("embed.failover.blockedReason", "embedding_space_unverified");
            throw new IllegalStateException("Embedding fallback blocked: embedding_space_unverified");
        }
    }

    private float[] callBackupVector(String text, String stage) {
        if (backupModel != null) requireCompatibleBackupEmbeddingSpace();
        if (backupModel == null) {
	        log.warn("[OllamaEmbeddingModel] backupEmbeddingModel not available; failing embedding (stage={})", stage);
	        if (debugEventStore != null) {
	            debugEventStore.emit(
	                    DebugProbeType.EMBEDDING,
	                    DebugEventLevel.WARN,
	                    "embedding.failover.no_backup." + stage,
	                    "Embedding failover requested but backup model is missing",
	                    "OllamaEmbeddingModel.callBackupVector",
	                    java.util.Map.of(
	                            "stage", stage,
	                            "provider", provider,
	                            "model", model,
	                            "lastLocalError", String.valueOf(lastLocalError.get()),
	                            "textLen", text == null ? 0 : text.length()
	                    ),
	                    null
	            );
	        }
	        throw new IllegalStateException("Embedding failover requested but backup model is missing"
                    + " (stage=" + stage + ", lastLocalError=" + lastLocalError.get() + ")");
        }

	    if (debugEventStore != null) {
	        DebugEventLevel lvl = "fallback".equalsIgnoreCase(stage) ? DebugEventLevel.WARN : DebugEventLevel.INFO;
	        String backupCls = backupModel.getClass().getName();
	        debugEventStore.emit(
	                DebugProbeType.EMBEDDING,
	                lvl,
	                "embedding.failover.used." + stage + "." + backupModel.getClass().getSimpleName(),
	                "Embedding failover: using backup model",
	                "OllamaEmbeddingModel.callBackupVector",
	                java.util.Map.of(
	                        "stage", stage,
	                        "provider", provider,
	                        "model", model,
	                        "backupModelClass", backupCls,
	                        "lastLocalError", String.valueOf(lastLocalError.get()),
	                        "textLen", text == null ? 0 : text.length()
	                ),
	                null
	        );
	        if (backupCls.toLowerCase(java.util.Locale.ROOT).contains("openai")) {
	            debugEventStore.emit(
	                    DebugProbeType.MODEL_GUARD,
	                    lvl,
	                    "model_guard.embedding.failover.openai." + stage,
	                    "Embedding failover selected an OpenAI-backed model",
	                    "OllamaEmbeddingModel.callBackupVector",
	                    java.util.Map.of(
	                            "stage", stage,
	                            "backupModelClass", backupCls
	                    ),
	                    null
	            );
	        }
	    }

        try {
            com.example.lms.search.TraceStore.putIfAbsent("embed.failover.used", true);
            com.example.lms.search.TraceStore.putIfAbsent("embed.failover.stage", stage);
            com.example.lms.search.TraceStore.put("embed.failover.used.cur", true);
            com.example.lms.search.TraceStore.put("embed.failover.stage.cur", stage);
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("backupVector.failoverTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "backupVector.failoverTrace"); }
	try {
            float[] vec = backupModel.embed(text).content().vector();
            if (vec == null) {
                return new float[0];
            }
            return normalizeEmbedding(vec, "backup-" + stage);
        } catch (Exception e) {
            log.warn("[OllamaEmbeddingModel] backup embedding failed (stage={}): {}", stage, shortErr(e));
	            if (debugEventStore != null) {
	                debugEventStore.emit(
	                        DebugProbeType.EMBEDDING,
	                        DebugEventLevel.WARN,
	                        "embedding.failover.backup_failed." + stage + "." + e.getClass().getSimpleName(),
	                        "Backup embedding failed",
	                        "OllamaEmbeddingModel.callBackupVector",
	                        java.util.Map.of(
	                                "stage", stage,
	                                "backupModelClass", backupModel.getClass().getName()
	                        ),
	                        e
	                );
	            }
            throw new IllegalStateException("Backup embedding failed"
                    + " (stage=" + stage + ", lastLocalError=" + lastLocalError.get() + ")", e);
        }
    }

    private List<Embedding> callBackupBatch(List<TextSegment> segments, String stage) {
        if (backupModel != null) requireCompatibleBackupEmbeddingSpace();
        if (backupModel == null) {
	        log.warn("[OllamaEmbeddingModel] backupEmbeddingModel not available; failing batch embedding (stage={})", stage);
	        if (debugEventStore != null) {
	            debugEventStore.emit(
	                    DebugProbeType.EMBEDDING,
	                    DebugEventLevel.WARN,
	                    "embedding.failover.no_backup.batch." + stage,
	                    "Batch embedding failover requested but backup model is missing",
	                    "OllamaEmbeddingModel.callBackupBatch",
	                    java.util.Map.of(
	                            "stage", stage,
	                            "provider", provider,
	                            "model", model,
	                            "segments", segments == null ? 0 : segments.size(),
	                            "lastLocalError", String.valueOf(lastLocalError.get())
	                    ),
	                    null
	            );
	        }
	        throw new IllegalStateException("Batch embedding failover requested but backup model is missing"
                    + " (stage=" + stage + ", lastLocalError=" + lastLocalError.get() + ")");
        }

	    if (debugEventStore != null) {
	        DebugEventLevel lvl = "fallback".equalsIgnoreCase(stage) ? DebugEventLevel.WARN : DebugEventLevel.INFO;
	        String backupCls = backupModel.getClass().getName();
	        debugEventStore.emit(
	                DebugProbeType.EMBEDDING,
	                lvl,
	                "embedding.failover.used.batch." + stage + "." + backupModel.getClass().getSimpleName(),
	                "Embedding failover: using backup model (batch)",
	                "OllamaEmbeddingModel.callBackupBatch",
	                java.util.Map.of(
	                        "stage", stage,
	                        "provider", provider,
	                        "model", model,
	                        "backupModelClass", backupCls,
	                        "segments", segments == null ? 0 : segments.size(),
	                        "lastLocalError", String.valueOf(lastLocalError.get())
	                ),
	                null
	        );
	        if (backupCls.toLowerCase(java.util.Locale.ROOT).contains("openai")) {
	            debugEventStore.emit(
	                    DebugProbeType.MODEL_GUARD,
	                    lvl,
	                    "model_guard.embedding.failover.openai.batch." + stage,
	                    "Embedding failover selected an OpenAI-backed model (batch)",
	                    "OllamaEmbeddingModel.callBackupBatch",
	                    java.util.Map.of(
	                            "stage", stage,
	                            "backupModelClass", backupCls
	                    ),
	                    null
	            );
	        }
	    }

        try {
            com.example.lms.search.TraceStore.putIfAbsent("embed.failover.used", true);
            com.example.lms.search.TraceStore.putIfAbsent("embed.failover.stage", stage);
            com.example.lms.search.TraceStore.put("embed.failover.used.cur", true);
            com.example.lms.search.TraceStore.put("embed.failover.stage.cur", stage);
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("backupBatch.failoverTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "backupBatch.failoverTrace"); }
try {
            Response<List<Embedding>> r = backupModel.embedAll(segments);
            List<Embedding> list = (r == null ? null : r.content());
            if (list == null) {
                return List.of();
            }
            List<Embedding> out = new ArrayList<>(list.size());
            for (Embedding e : list) {
                float[] v = (e == null ? null : e.vector());
                out.add(Embedding.from(normalizeEmbedding(v == null ? new float[0] : v, "backup-" + stage)));
            }
            return out;
        } catch (Exception e) {
            log.warn("[OllamaEmbeddingModel] backup embedAll failed (stage={}): {}", stage, shortErr(e));
	            if (debugEventStore != null) {
	                debugEventStore.emit(
	                        DebugProbeType.EMBEDDING,
	                        DebugEventLevel.ERROR,
	                        "embedding.failover.error.backup.batch." + stage + "." + e.getClass().getSimpleName(),
	                        "Embedding failover backup embedAll failed (batch)",
	                        "OllamaEmbeddingModel.callBackupBatch",
	                        java.util.Map.of(
	                                "stage", stage,
	                                "backupModelClass", backupModel == null ? "<null>" : backupModel.getClass().getName(),
	                                "segments", segments == null ? 0 : segments.size()
	                        ),
	                        e
	                );
	            }
            throw new IllegalStateException("Backup batch embedding failed"
                    + " (stage=" + stage + ", lastLocalError=" + lastLocalError.get() + ")", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fast-fail + health
    // ─────────────────────────────────────────────────────────────────────

    private boolean isOllamaProvider() {
        return "ollama".equals(com.example.lms.vector.EmbeddingFingerprint.resolveProvider(provider, legacyProvider));
    }

    private boolean backupAvailable() {
        return backupModel != null;
    }

    private boolean shouldSkipLocalNow() {
        if (!fastFailEnabled || !backupAvailable()) {
            return false;
        }

        long now = System.currentTimeMillis();

        if (fastFailForceOpen) {
            long until = forceOpenUntilMs.get();
            if (until <= 0L) {
                long secs = fastFailForceOpenSeconds;
                if (secs <= 0L) {
                    // Treat as indefinite while the flag is enabled.
                    forceOpenUntilMs.set(Long.MAX_VALUE);
                } else {
                    forceOpenUntilMs.set(now + secs * 1000L);
                }
            }
            // Also reflect in skip window for visibility.
            long target = forceOpenUntilMs.get();
            skipUntilMs.updateAndGet(prev -> Math.max(prev, target));
            return now < forceOpenUntilMs.get();
        }

        return now < skipUntilMs.get();
    }

    private void recordLocalSuccess() {
        failureStreak.set(0);
        tripCount.set(0);
        skipUntilMs.set(0L);
        // Leave healthOkUntilMs as-is; success is stronger signal than a health check.
        lastLocalError.set(null);
        try {
            com.example.lms.search.TraceStore.inc("embed.fastfail.local_ok");
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("fastFail.localOkTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "fastFail.localOkTrace"); }
    }

    private void recordLocalFailure(String stage, Throwable e) {
        lastLocalError.set(shortErr(e));
        // Invalidate health cache on local failure.
        healthOkUntilMs.set(0L);

        if (!fastFailEnabled || !backupAvailable()) {
            return;
        }

        int streak = failureStreak.incrementAndGet();
        try {
            com.example.lms.search.TraceStore.inc("embed.fastfail.local_fail");
            if (stage != null && !stage.isBlank()) {
                com.example.lms.search.TraceStore.inc("embed.fastfail.local_fail." + stage);
            }
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("fastFail.localFailTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "fastFail.localFailTrace"); }

        if (streak < Math.max(1, fastFailThreshold)) {
            return;
        }

        int nextTrip = tripCount.incrementAndGet();
        long cooldownSec = computeCooldownSeconds(nextTrip);
        long until = System.currentTimeMillis() + cooldownSec * 1000L;
        skipUntilMs.updateAndGet(prev -> Math.max(prev, until));

        try {
            com.example.lms.search.TraceStore.put("embed.fastfail.skip_until_ms", skipUntilMs.get());
            com.example.lms.search.TraceStore.inc("embed.fastfail.tripped");
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("fastFail.trippedTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "fastFail.trippedTrace"); }

        log.warn("[OllamaEmbeddingModel] fast-fail tripped (trip={}, streak={}, cooldownSec={}, stage={}, api={})",
                nextTrip, streak, cooldownSec, stage, safeUrl(apiUrl));
    }

    private long baseCooldownSeconds() {
        if (fastFailCooldownSeconds > 0L) {
            return fastFailCooldownSeconds;
        }
        if (fastFailCooldownMsLegacy > 0L) {
            return Math.max(1L, fastFailCooldownMsLegacy / 1000L);
        }
        return 300L;
    }

    private long computeCooldownSeconds(int trip) {
        long base = baseCooldownSeconds();
        String strategy = nullSafe(fastFailCooldownStrategy).trim().toLowerCase();
        double seconds = base;

        if ("exponential".equals(strategy)) {
            double factor = (fastFailCooldownBackoffFactor <= 1.0) ? 2.0 : fastFailCooldownBackoffFactor;
            seconds = base * Math.pow(factor, Math.max(0, trip - 1));
        }

        if (fastFailCooldownMaxSeconds > 0L) {
            seconds = Math.min(seconds, fastFailCooldownMaxSeconds);
        }

        double jitter = Math.max(0.0, fastFailCooldownJitterRatio);
        if (jitter > 0.0) {
            double delta = seconds * jitter;
            seconds = seconds + ThreadLocalRandom.current().nextDouble(-delta, delta);
        }

        return Math.max(1L, (long) Math.ceil(seconds));
    }

    private boolean ensureLocalHealthy(String stage) {
        if (!fastFailEnabled || !fastFailHealthEnabled) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (healthOkUntilMs.get() > now) {
            try {
                com.example.lms.search.TraceStore.inc("embed.fastfail.health.cache_hit");
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("health.cacheHitTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "health.cacheHitTrace"); }
            return true;
        }

        // If another thread is performing health-check, do a brief wait.
        if (fastFailHealthConcurrentGuard && !healthInFlight.compareAndSet(false, true)) {
            long deadline = now + 200L;
            while (System.currentTimeMillis() < deadline) {
                if (healthOkUntilMs.get() > System.currentTimeMillis()) {
                    return true;
                }
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ie) {
                    log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "health.concurrentWait");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                com.example.lms.search.TraceStore.inc("embed.fastfail.health.concurrent_skip");
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("health.concurrentSkipTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "health.concurrentSkipTrace"); }
            if (allowLocalProbeWhenNoBackupAfterConcurrentHealth()) {
                return true;
            }
            return false;
        }

        boolean locked = healthInFlight.compareAndSet(false, true);
        if (!locked) {
            // Should be rare; conservatively skip.
            return allowLocalProbeWhenNoBackupAfterConcurrentHealth();
        }

        try {
            runHealthCheckOrThrow();

            long okUntil = System.currentTimeMillis() + Math.max(1L, fastFailHealthOkTtlSeconds) * 1000L;
            healthOkUntilMs.set(okUntil);
            healthLastOkAtMs.set(System.currentTimeMillis());
            healthLastError.set(null);

            try {
                com.example.lms.search.TraceStore.inc("embed.fastfail.health.ok");
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("health.okTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "health.okTrace"); }

            return true;
        } catch (Exception e) {
            healthLastFailAtMs.set(System.currentTimeMillis());
            healthLastError.set(shortErr(e));

            try {
                com.example.lms.search.TraceStore.inc("embed.fastfail.health.fail");
            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("health.failTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "health.failTrace"); }

            recordLocalFailure("health." + nullSafe(stage), e);
            return false;
        } finally {
            healthInFlight.set(false);
        }
    }

    private boolean allowLocalProbeWhenNoBackupAfterConcurrentHealth() {
        if (backupAvailable()) {
            return false;
        }
        try {
            com.example.lms.search.TraceStore.inc("embed.fastfail.health.concurrent_local_probe");
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("health.concurrentLocalProbeTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "health.concurrentLocalProbeTrace"); }
        return true;
    }

    private void runHealthCheckOrThrow() {
        String mode = nullSafe(fastFailHealthMode).trim().toLowerCase();
        if (mode.isBlank()) {
            mode = "version";
        }

        switch (mode) {
            case "tags" -> checkTags();
            case "tags_ps" -> checkTagsAndPs();
            case "embed_probe" -> checkEmbedProbe();
            case "version" -> checkVersion();
            default -> checkVersion();
        }
    }

    private void checkVersion() {
        JsonNode root = getJsonWithFallback(resolveHealthUrl("/api/version"), fastFailHealthTimeoutMs);
        String v = root.path("version").asText("");
        if (v.isBlank()) {
            throw new IllegalStateException("/api/version missing version field");
        }
    }

    private void checkTags() {
        JsonNode root = getJsonWithFallback(resolveHealthUrl("/api/tags"), fastFailHealthTimeoutMs);
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            throw new IllegalStateException("/api/tags missing models[]");
        }

        JsonNode found = null;
        for (JsonNode m : models) {
            String name = m.path("name").asText(null);
            String mf = m.path("model").asText(null);
            if (matchesOllamaModel(name) || matchesOllamaModel(mf)) {
                found = m;
                break;
            }
        }
        if (found == null) {
            throw new IllegalStateException("model not found in /api/tags modelHash=" + hashOrEmpty(model) + " modelLength=" + (model == null ? 0 : model.length()));
        }

        if (fastFailHealthTagsMaxModelSizeBytes > 0L) {
            long size = found.path("size").asLong(0L);
            if (size > fastFailHealthTagsMaxModelSizeBytes) {
                throw new IllegalStateException("model too large (size=" + size + ")");
            }
        }
    }

    private void checkTagsAndPs() {
        JsonNode tags = getJsonWithFallback(resolveHealthUrl("/api/tags"), fastFailHealthTimeoutMs);
        JsonNode models = tags.path("models");
        if (!models.isArray()) {
            throw new IllegalStateException("/api/tags missing models[]");
        }

        String digest = null;
        long size = 0L;
        boolean found = false;
        for (JsonNode m : models) {
            String name = m.path("name").asText(null);
            String mf = m.path("model").asText(null);
            if (matchesOllamaModel(name) || matchesOllamaModel(mf)) {
                digest = m.path("digest").asText(null);
                size = m.path("size").asLong(0L);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalStateException("model not found in /api/tags modelHash=" + hashOrEmpty(model) + " modelLength=" + (model == null ? 0 : model.length()));
        }

        if (fastFailHealthTagsMaxModelSizeBytes > 0L && size > fastFailHealthTagsMaxModelSizeBytes) {
            throw new IllegalStateException("model too large (size=" + size + ")");
        }

        JsonNode ps = getJsonWithFallback(resolveHealthUrl("/api/ps"), fastFailHealthTimeoutMs);
        JsonNode running = ps.path("models");
        if (!running.isArray()) {
            // If ps is unsupported, treat as OK (tags already verified).
            return;
        }

        JsonNode run = null;
        for (JsonNode m : running) {
            String name = m.path("name").asText(null);
            String mf = m.path("model").asText(null);
            String dg = m.path("digest").asText(null);
            if (matchesOllamaModel(name) || matchesOllamaModel(mf) || (digest != null && digest.equals(dg))) {
                run = m;
                break;
            }
        }

        if (run == null) {
            // Model isn't currently loaded; that's fine.
            return;
        }

        if (fastFailHealthPsMaxVramBytes > 0L) {
            long vram = 0L;
            if (run.has("size_vram")) {
                vram = run.path("size_vram").asLong(0L);
            } else if (run.has("sizeVram")) {
                vram = run.path("sizeVram").asLong(0L);
            }

            if (vram > fastFailHealthPsMaxVramBytes) {
                throw new IllegalStateException("vram too high (size_vram=" + vram + ")");
            }
        }
    }

    private void checkEmbedProbe() {
        // Small embed probe with separate timeout.
        String probeInput = (fastFailHealthEmbedProbeInput == null || fastFailHealthEmbedProbeInput.isBlank())
                ? "ping"
                : fastFailHealthEmbedProbeInput;

        try {
            Integer probeDim = (dimensions > 0 ? Math.min(dimensions, 256) : null);
            JsonNode root = postEmbedWithFallback(probeInput, probeDim, fastFailHealthEmbedProbeKeepAlive,
                    fastFailHealthEmbedProbeTimeoutMs);
            float[] vec = parseFloatArray(root.path("embeddings").path(0));
            if (vec.length == 0) {
                throw new IllegalStateException("embed_probe empty embedding");
            }
        } catch (Exception e) {
            log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "embedProbe");
            throw new IllegalStateException("embed_probe failed: " + shortErr(e), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HTTP utilities
    // ─────────────────────────────────────────────────────────────────────

    private JsonNode postEmbedWithFallback(String input, Integer targetDim, String keepAliveOverride,
            long timeoutSecondsOrMs) {
        Map<String, Object> body = buildEmbedBody(input, targetDim, keepAliveOverride);
        return postJsonWithFallback(body, timeoutSecondsOrMs);
    }

    private JsonNode postEmbedWithFallback(List<String> inputs, Integer targetDim, String keepAliveOverride,
            long timeoutSecondsOrMs) {
        Map<String, Object> body = buildEmbedBody(inputs, targetDim, keepAliveOverride);
        return postJsonWithFallback(body, timeoutSecondsOrMs);
    }

    /** Cache identity follows the endpoint selected at dispatch; it contains no raw URL or credentials. */
    public String cacheIdentity() {
        String endpoint = localLlmProcessManager == null ? apiUrl : localLlmProcessManager.resolveServiceUrl(apiUrl);
        Object execution = localLlmProcessManager != null && localLlmProcessManager.managesEndpoint(apiUrl)
                ? List.of(localLlmProcessManager.diagnostics().get("pid"),
                        localLlmProcessManager.diagnostics().get("serverStartedAtEpochMs")) : "unmanaged";
        return hashOrEmpty(endpoint + "|" + model + "|" + dimensions + "|" + effectiveNormalizationMode()
                + "|" + providerRawDimensions + "|" + dimensionGuardMode + "|" + allowZeroPad + "|" + execution);
    }

    private Map<String, Object> buildEmbedBody(Object input, Integer targetDim, String keepAliveOverride) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);

        // Ollama /api/embed supports top-level dimensions. Send it only when
        // explicitly enabled and the server hasn't rejected it.
        // hasn't shown it doesn't support the option.
        if (targetDim != null && targetDim > 0 && dimensionsOptionEnabled && !dimensionsOptionSuppressed.get()) {
            body.put("dimensions", targetDim);
        }

        String ka = (keepAliveOverride != null && !keepAliveOverride.isBlank())
                ? keepAliveOverride
                : ollamaKeepAlive;
        if (ka != null && !ka.isBlank()) {
            body.put("keep_alive", ka);
        }

        return body;
    }

    private JsonNode postJsonWithFallback(Map<String, Object> body, long timeoutSecondsOrMs) {
        List<String> candidates = buildCandidateUrls(apiUrl, fallbackApiUrl);
        Exception last = null;

        for (int i = 0; i < candidates.size(); i++) {
            String url = candidates.get(i);
            try (var cancellation = com.example.lms.service.chat.ChatRunExecutionContext.interruptibleCall("ollama_embedding")) {
                try {
                    com.example.lms.search.TraceStore.inc("embed.ollama.post.attempt");
                } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("ollama.postAttemptTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "ollama.postAttemptTrace"); }

                long timeoutMs = normalizeTimeoutMs(timeoutSecondsOrMs);
                WebClient.RequestBodySpec request = applyGatewayHeaders(webClient.post().uri(url), url);
                String json = request
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .onErrorResume(t -> Mono.error(new RuntimeException(t)))
                        .block();

                try {
                    com.example.lms.search.TraceStore.inc("embed.ollama.post.ok");
                } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("ollama.postOkTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "ollama.postOkTrace"); }

                return mapper.readTree(json);
            } catch (Exception e) {
                com.example.lms.service.chat.ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
                last = e;

                // [AUTO-HEAL] Some local embedding servers reject "dimensions"
                // with messages like: "invalid option provided option=dimensions".
                // If detected, suppress the option and retry once without it.
                if (hasDimensionsOption(body) && looksLikeDimensionsOptionUnsupported(e)) {
                    try (var cancellation = com.example.lms.service.chat.ChatRunExecutionContext.interruptibleCall("ollama_embedding")) {
                        Integer dim = extractDimensionsOption(body);

                        if (dimensionsOptionSuppressed.compareAndSet(false, true)) {
                            log.warn(
                                    "[OllamaEmbeddingModel] 'dimensions' unsupported by embed endpoint; suppressing and retrying without it (dim={}, modelHash={}, urlHost={}, urlHash={})",
                                    dim, hashOrEmpty(model), endpointHost(url), hashOrEmpty(url));
                            try {
                                com.example.lms.search.TraceStore.put("embed.ollama.dimensions_option.suppressed", true);
                                if (dim != null) {
                                    com.example.lms.search.TraceStore.put("embed.ollama.dimensions_option.suppressed.dim", dim);
                                }
                            } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("dimensionsOption.suppressedTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "dimensionsOption.suppressedTrace"); }

                            if (debugEventStore != null) {
                                try {
                                    java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
                                    meta.put("modelHash", hashOrEmpty(model));
                                    meta.put("urlHost", endpointHost(url));
                                    meta.put("urlHash", hashOrEmpty(url));
                                    if (dim != null) {
                                        meta.put("dim", dim);
                                    }
                                    meta.put("message", shortErr(e));

                                    debugEventStore.emit(
                                            DebugProbeType.EMBEDDING,
                                            DebugEventLevel.WARN,
                                            "embedding.ollama.dimensions_option.unsupported",
                                            "Embedding endpoint rejected dimensions; suppressing and retrying without it",
                                            "OllamaEmbeddingModel.postJsonWithFallback",
                                            meta,
                                            null);
                                } catch (Throwable ignore) {
                                    EmbeddingTraceSuppressions.trace("dimensionsOption.debugEvent", ignore);
                                    log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "dimensionsOption.debugEvent");
                                }
                            }
                        }

                        try {
                            com.example.lms.search.TraceStore.inc("embed.ollama.post.retry_no_dimensions");
                        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("ollama.retryNoDimensionsTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "ollama.retryNoDimensionsTrace"); }

                        Map<String, Object> stripped = stripDimensionsOption(body);
                        WebClient.RequestBodySpec retryRequest = applyGatewayHeaders(webClient.post().uri(url), url);
                        String retryJson = retryRequest
                                .bodyValue(stripped)
                                .retrieve()
                                .bodyToMono(String.class)
                                .timeout(Duration.ofMillis(normalizeTimeoutMs(timeoutSecondsOrMs)))
                                .onErrorResume(t -> Mono.error(new RuntimeException(t)))
                                .block();

                        try {
                            com.example.lms.search.TraceStore.inc("embed.ollama.post.retry_no_dimensions.ok");
                        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("ollama.retryNoDimensionsOkTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "ollama.retryNoDimensionsOkTrace"); }

                        return mapper.readTree(retryJson);
                    } catch (Exception retryEx) {
                        com.example.lms.service.chat.ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
                        log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "ollama.retryNoDimensions");
                        // Retry also failed; fall through to normal failure handling.
                        last = retryEx;
                    }
                }

                Exception ex = last;
                boolean maybeConnRefused = looksLikeConnectionRefused(ex);

                try {
                    com.example.lms.search.TraceStore.inc("embed.ollama.post.fail");
                    if (maybeConnRefused) {
                        com.example.lms.search.TraceStore.inc("embed.ollama.post.fail.conn_refused");
                    }
                } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("ollama.postFailTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "ollama.postFailTrace"); }

                // Only retry on next candidate if it is likely a connectivity/endpoint issue.
                if (i < candidates.size() - 1) {
                    if (maybeConnRefused) {
                        log.warn("[OllamaEmbeddingModel] POST failed (conn refused) -> retry on alternate urlHost={} urlHash={}",
                                endpointHost(url), hashOrEmpty(url));
                        continue;
                    }
                    // For non-connectivity errors, still allow fallback URL to run (best-effort)
                    if (!Objects.equals(url, apiUrl) && !Objects.equals(url, fallbackApiUrl)) {
                        continue;
                    }
                    // Otherwise, break early.
                }
            }
        }

        if (last instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(last);
    }

    // ─────────────────────────────────────────────────────────────────────
    // options.dimensions auto-heal helpers
    // ─────────────────────────────────────────────────────────────────────

    WebClient.RequestBodySpec applyGatewayHeaders(WebClient.RequestBodySpec request, String url) {
        assertGatewayAllowedForUrl(url);
        gatewayHeadersForUrl(url).forEach(request::header);
        return request;
    }

    WebClient.RequestHeadersSpec<?> applyGatewayHeaders(WebClient.RequestHeadersSpec<?> request, String url) {
        assertGatewayAllowedForUrl(url);
        gatewayHeadersForUrl(url).forEach(request::header);
        return request;
    }

    void assertGatewayAllowedForUrl(String url) {
        LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                url,
                allowPrivateRemote,
                allowedHosts,
                requireAuthForRemote,
                llmApiKey,
                ownerToken);
    }

    Map<String, String> gatewayHeadersForUrl(String url) {
        if (!LocalLlmGatewaySecurity.shouldAttachOwnerToken(url, allowedHosts)) {
            return Map.of();
        }
        return LocalLlmGatewaySecurity.ownerTokenHeaders(ownerTokenHeader, ownerToken);
    }

    private static boolean hasDimensionsOption(Map<String, Object> body) {
        if (body == null) {
            return false;
        }
        if (body.containsKey("dimensions")) {
            return true;
        }
        Object opts = body.get("options");
        if (!(opts instanceof Map)) {
            return false;
        }
        @SuppressWarnings("rawtypes")
        Map m = (Map) opts;
        return m.containsKey("dimensions");
    }

    private static Integer extractDimensionsOption(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        try {
            Object direct = body.get("dimensions");
            if (direct instanceof Number n) {
                return n.intValue();
            }
            if (direct != null) {
                String s = String.valueOf(direct).trim();
                if (!s.isBlank()) {
                    return Integer.parseInt(s);
                }
            }
            Object opts = body.get("options");
            if (!(opts instanceof Map)) {
                return null;
            }
            @SuppressWarnings("rawtypes")
            Map m = (Map) opts;
            Object v = m.get("dimensions");
            if (v == null) {
                return null;
            }
            if (v instanceof Number n) {
                return n.intValue();
            }
            String s = String.valueOf(v).trim();
            if (s.isBlank()) {
                return null;
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException ignore) {
            log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "dimensionsOption.parse");
            return null;
        }
    }

    private static Map<String, Object> stripDimensionsOption(Map<String, Object> body) {
        if (body == null) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.remove("dimensions");
        Object opts = copy.get("options");
        if (opts instanceof Map) {
            @SuppressWarnings("rawtypes")
            Map m = (Map) opts;
            Map<String, Object> opts2 = new LinkedHashMap<>();
            for (Object k0 : m.keySet()) {
                if (k0 == null) {
                    continue;
                }
                String k = String.valueOf(k0);
                if ("dimensions".equals(k)) {
                    continue;
                }
                opts2.put(k, m.get(k0));
            }
            if (opts2.isEmpty()) {
                copy.remove("options");
            } else {
                copy.put("options", opts2);
            }
        }

        return copy;
    }

    private static boolean looksLikeDimensionsOptionUnsupported(Throwable t) {
        String flat = flattenForInspection(t);
        if (flat == null || flat.isBlank()) {
            return false;
        }
        String m = flat.toLowerCase(java.util.Locale.ROOT);
        if (!m.contains("dimensions")) {
            return false;
        }

        // Typical Ollama-like errors
        if (m.contains("invalid option") || m.contains("unknown option")) {
            return true;
        }
        if (m.contains("unrecognized") && m.contains("option")) {
            return true;
        }
        if (m.contains("unsupported") && m.contains("option")) {
            return true;
        }
        return m.contains("invalid") && m.contains("option");
    }

    private static String flattenForInspection(Throwable t) {
        if (t == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        Throwable cur = t;
        while (cur != null && depth++ < 6) {
            try {
                String msg = cur.getMessage();
                if (msg != null && !msg.isBlank()) {
                    sb.append(SafeRedactor.safeMessage(msg, 800)).append(" | ");
                }
            } catch (Throwable ignore) { EmbeddingTraceSuppressions.trace("flatten.messageTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "flatten.messageTrace"); }

            if (cur instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                try {
                    String body = ((org.springframework.web.reactive.function.client.WebClientResponseException) cur)
                            .getResponseBodyAsString();
                    if (body != null && !body.isBlank()) {
                        String bodyHash = SafeRedactor.hashValue(body);
                        sb.append("bodyHash=").append(bodyHash == null ? "" : bodyHash)
                                .append(" bodyLength=").append(body.length());
                        if (body.toLowerCase(java.util.Locale.ROOT).contains("dimensions")) {
                            sb.append(" bodyMentionsDimensions=true");
                        }
                        sb.append(" | ");
                    }
                } catch (Throwable ignore) { EmbeddingTraceSuppressions.trace("flatten.bodyTrace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "flatten.bodyTrace"); }
            }

            cur = cur.getCause();
        }
        return sb.toString();
    }

    private JsonNode getJsonWithFallback(String url, long timeoutMs) {
        String secondary = null;
        if (fallbackApiUrl != null && !fallbackApiUrl.isBlank() && url != null) {
            int idx = url.indexOf("/api/");
            if (idx >= 0) {
                String path = url.substring(idx);
                secondary = deriveUrl(fallbackApiUrl, path);
            }
        }

        List<String> candidates = buildCandidateUrls(url, secondary);
        Exception last = null;

        for (int i = 0; i < candidates.size(); i++) {
            String u = candidates.get(i);
            try {
                WebClient.RequestHeadersSpec<?> request = applyGatewayHeaders(webClient.get().uri(u), u);
                String json = request
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofMillis(Math.max(1L, timeoutMs)))
                        .onErrorResume(t -> Mono.error(new RuntimeException(t)))
                        .block();
                return mapper.readTree(json);
            } catch (Exception e) {
                log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "getJsonWithFallback");
                last = e;
                if (i < candidates.size() - 1 && looksLikeConnectionRefused(e)) {
                    continue;
                }
            }
        }

        if (last instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(last);
    }

    List<String> buildCandidateUrls(String primary, String secondary) {
        if (localLlmProcessManager != null) {
            primary = localLlmProcessManager.resolveServiceUrl(primary);
            secondary = localLlmProcessManager.resolveServiceUrl(secondary);
        }
        List<String> out = new ArrayList<>();
        addUrl(out, primary);

        boolean fallbackAllowed = portFallbackEnabled || crossGpuFallbackEnabled;
        logFallbackConfigOnce(primary, secondary, fallbackAllowed);

        if (fallbackAllowed) {
            String alt = alternativePortUrl(primary);
            if (alt != null && !alt.equals(primary)) {
                addUrl(out, alt);
            }
        }

        if (fallbackAllowed && secondary != null && !secondary.isBlank()) {
            addUrl(out, secondary);
            if (fallbackAllowed) {
                String alt2 = alternativePortUrl(secondary);
                if (alt2 != null && !alt2.equals(secondary)) {
                    addUrl(out, alt2);
                }
            }
        }

        // Also consider health-url override as a primary for GET health, if applicable.
        return out;
    }

    private void logFallbackConfigOnce(String primary, String secondary, boolean fallbackAllowed) {
        if (!fallbackConfigLogged.compareAndSet(false, true)) {
            return;
        }
        String fallbackState = fallbackAllowed ? safeUrl(secondary) : "disabled";
        log.info("[AWX2AF2][gpu][embedding] primary={} fallback={} portFallbackEnabled={} crossGpuFallbackEnabled={}",
                safeUrl(primary), fallbackState, portFallbackEnabled, crossGpuFallbackEnabled);
    }

    private void addUrl(List<String> out, String url) {
        if (url == null)
            return;
        String u = url.trim();
        if (u.isEmpty())
            return;
        if (!out.contains(u)) {
            out.add(u);
        }
    }

    private long normalizeTimeoutMs(long timeoutSecondsOrMs) {
        // Heuristic: if value is > 1000, assume ms. else seconds.
        long configured = timeoutSecondsOrMs > 1000L ? timeoutSecondsOrMs : Math.max(1L, timeoutSecondsOrMs) * 1000L;
        return com.example.lms.service.chat.ChatRunExecutionContext.capRequestWait(configured);
    }

    private String resolveHealthUrl(String path) {
        String p = (path == null || path.isBlank()) ? "/api/version" : path;

        // If an explicit health url is provided, use it (as base or full endpoint).
        if (fastFailHealthUrl != null && !fastFailHealthUrl.isBlank()) {
            String hu = fastFailHealthUrl.trim();
            if (hu.contains("/api/")) {
                return hu;
            }
            if (hu.endsWith("/")) {
                hu = hu.substring(0, hu.length() - 1);
            }
            return hu + p;
        }

        return deriveUrl(apiUrl, p);
    }

    private static String deriveUrl(String base, String path) {
        if (base == null || base.isBlank()) {
            return base;
        }
        String u = base.trim();

        if (u.contains("/api/embed")) {
            return u.replace("/api/embed", path);
        }

        int idx = u.indexOf("/api/");
        if (idx >= 0) {
            String host = u.substring(0, idx);
            return host + path;
        }

        if (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u + path;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Vector parsing / normalization
    // ─────────────────────────────────────────────────────────────────────

    private float[] normalizeEmbedding(float[] raw, String tag) {
        if (raw == null) {
            raw = new float[0];
        }

        int actual = raw.length;
        if (actual <= 0) {
            recordDimensionTrace(0, Math.max(0, dimensions), tag, "empty_vector_rejected", false);
            throw new IllegalStateException("Embedding provider returned an empty vector");
        }

        int target = resolveTargetDim(actual, dimensions, tag);
        lastProviderActualDim.set(actual);
        lastTargetDim.set(target);

        if (actual == target) {
            recordDimensionTrace(actual, target, tag, "pass_through", false);
            return raw;
        }

        if (actual > target) {
            recordDimensionTrace(actual, target, tag, "slice_to_configured_dim", true);
            return sliceVector(raw, target);
        }

        // Local warn-only compatibility path. Strict/non-local under-dim cases are rejected in resolveTargetDim.
        recordDimensionTrace(actual, target, tag,
                allowZeroPad ? "pad_to_configured_dim_allowed" : "pad_to_configured_dim_warn_only", false);
        float[] out = new float[target];
        System.arraycopy(raw, 0, out, 0, actual);
        return out;
    }

    private int resolveTargetDim(int actualDim, int configuredDim, String tag) {
        if (configuredDim <= 0) {
            return actualDim;
        }

        if (actualDim != 0 && actualDim != configuredDim && isStrictDimensionGuard()) {
            String kind = actualDim < configuredDim ? "underflow" : "mismatch";
            throw new IllegalStateException("Embedding dimension " + kind + ": expected " + configuredDim + ", got "
                    + actualDim + " (" + tag + ")");
        }
        if (actualDim > 0 && actualDim < configuredDim && !allowZeroPad && !isLocalEmbeddingProvider()) {
            throw new IllegalStateException("Embedding dimension underflow: expected " + configuredDim + ", got "
                    + actualDim + " (" + tag + ", provider=" + safeProviderName() + ")");
        }

        // WARN_ONLY: warn once
        if (logDimensionMismatch && actualDim != 0 && actualDim != configuredDim
                && dimensionWarned.compareAndSet(false, true)) {
            log.warn("[OllamaEmbeddingModel] dimension mismatch (configured={}, actual={}, tagHash={}, tagLength={}, modelHash={})",
                    configuredDim, actualDim, hashOrEmpty(tag), lengthOf(tag), hashOrEmpty(model));
	            if (debugEventStore != null) {
	                debugEventStore.emit(
	                        DebugProbeType.EMBEDDING,
	                        DebugEventLevel.WARN,
	                        "embedding.dimension_mismatch." + hashOrEmpty(model),
	                        "Embedding dimension mismatch (warn-only)",
	                        "OllamaEmbeddingModel.resolveTargetDim",
	                        java.util.Map.of(
	                                "configuredDim", configuredDim,
	                                "actualDim", actualDim,
	                                "tagHash", hashOrEmpty(tag),
	                                "tagLength", lengthOf(tag),
	                                "modelHash", hashOrEmpty(model),
	                                "guardMode", dimensionGuardMode
	                        ),
	                        null
	                );
	            }
        }

        // Matryoshka: normalize to the configured index dimension.
        // - actual > configured: prefix truncation (handled by normalizeEmbedding)
        // - actual < configured: zero-padding (handled by normalizeEmbedding)
        return configuredDim;
    }

    private boolean isStrictDimensionGuard() {
        return "STRICT".equalsIgnoreCase(nullSafe(dimensionGuardMode));
    }

    private boolean isLocalEmbeddingProvider() {
        String p = safeProviderName();
        return p.isBlank() || p.contains("ollama") || p.contains("local");
    }

    private String safeProviderName() {
        return nullSafe(provider).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private int configuredIndexDimensions() {
        return dimensions > 0 ? dimensions : DEFAULT_INDEX_DIM;
    }

    private Object rawProviderDimSnapshot() {
        int last = lastProviderActualDim.get();
        if (last > 0) {
            return last;
        }
        return providerRawDimensions > 0 ? providerRawDimensions : null;
    }

    private String effectiveNormalizationMode() {
        String m = nullSafe(normalizationMode).trim();
        return m.isEmpty() ? DEFAULT_NORMALIZATION_MODE : m;
    }

    private Object dimensionsOptionEffectiveSnapshot() {
        if (!dimensionsOptionEnabled || dimensionsOptionSuppressed.get()) {
            return Boolean.FALSE;
        }
        return "unknown";
    }

    private void recordDimensionTrace(int actual, int target, String tag, String strategy, boolean sliced) {
        try {
            com.example.lms.search.TraceStore.put("embed.providerActualDim", actual);
            com.example.lms.search.TraceStore.put("embed.actualDim", actual);
            com.example.lms.search.TraceStore.put("embed.sourceDim", actual);
            com.example.lms.search.TraceStore.put("embed.targetDim", target);
            com.example.lms.search.TraceStore.put("embed.sliceMethod", sliceMethod(actual, target, strategy, sliced));
            com.example.lms.search.TraceStore.put("embed.normalizeApplied", actual != target);
            com.example.lms.search.TraceStore.put("embed.sliceReason", sliceReason(actual, target, strategy, sliced));
            com.example.lms.search.TraceStore.put("embed.matryoshka.sliced", sliced);
            com.example.lms.search.TraceStore.put("embed.matryoshka.rawDim", actual);
            com.example.lms.search.TraceStore.put("embed.matryoshka.targetDim", target);
            com.example.lms.search.TraceStore.put("embedding.rawDimension", actual);
            com.example.lms.search.TraceStore.put("embedding.slicedDimension", Math.max(0, target));
            com.example.lms.search.TraceStore.put("embedding.matryoshkaSliced", sliced);
            com.example.lms.search.TraceStore.put("embedding.sliceSkipReason",
                    embeddingSliceSkipReason(actual, strategy, sliced));
            if (sliced && actual > 0 && target > 0) {
                long reductionPct = Math.round((1.0d - ((double) target / (double) actual)) * 100.0d);
                com.example.lms.search.TraceStore.put("embed.matryoshka.dimensionReduction", reductionPct + "pct");
                com.example.lms.search.TraceStore.put("embed.matryoshka.slice.actual", actual);
                com.example.lms.search.TraceStore.put("embed.matryoshka.slice.target", target);
                com.example.lms.search.TraceStore.put("embed.matryoshka.slice.reductionRatio",
                        Math.max(0.0d, Math.min(1.0d, 1.0d - ((double) target / (double) actual))));
                com.example.lms.search.TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsRatio",
                        round4((double) target / (double) actual));
                com.example.lms.search.TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsSpeedup",
                        round4((double) actual / (double) target));
            }
            com.example.lms.search.TraceStore.put("embed.matryoshka.strategy", strategy);
            com.example.lms.search.TraceStore.put("embed.matryoshka.tagHash", hashOrEmpty(tag));
            com.example.lms.search.TraceStore.put("embed.matryoshka.tagLength", lengthOf(tag));
        } catch (Exception ignore) { EmbeddingTraceSuppressions.trace("matryoshka.trace", ignore); log.debug("[OllamaEmbeddingModel] fail-soft stage={}", "matryoshka.trace"); }
    }

    private static String embeddingSliceSkipReason(int actual, String strategy, boolean sliced) {
        if (sliced) {
            return "";
        }
        String safe = nullSafe(strategy).toLowerCase(java.util.Locale.ROOT);
        if (actual <= 0 || safe.contains("empty")) {
            return "empty_vector_rejected";
        }
        return "";
    }

    private static String sliceMethod(int actual, int target, String strategy, boolean sliced) {
        if (sliced && actual > target) {
            return "MRL_PREFIX";
        }
        if (actual == target) {
            return "NONE";
        }
        String safe = nullSafe(strategy).toLowerCase(java.util.Locale.ROOT);
        if (safe.contains("pad")) {
            return "ZERO_PAD";
        }
        return "DIMENSION_NORMALIZE";
    }

    private static String sliceReason(int actual, int target, String strategy, boolean sliced) {
        if (sliced && actual > target) {
            return "MRL";
        }
        if (actual == target) {
            return "NONE";
        }
        String safe = nullSafe(strategy).toLowerCase(java.util.Locale.ROOT);
        if (safe.contains("pad")) {
            return "ZERO_PAD";
        }
        return "DIMENSION_MISMATCH";
    }

    private float[] parseFloatArray(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return new float[0];
        }
        int n = arr.size();
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) arr.get(i).asDouble(0.0);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Misc helpers
    // ─────────────────────────────────────────────────────────────────────

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private boolean matchesOllamaModel(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String want = nullSafe(model).trim();
        if (want.isBlank()) {
            return false;
        }
        String have = name.trim();

        if (have.equalsIgnoreCase(want)) {
            return true;
        }

        // tolerate tags like "qwen3-embedding:latest"
        int c1 = have.indexOf(':');
        if (c1 > 0 && have.substring(0, c1).equalsIgnoreCase(want)) {
            return true;
        }
        int c2 = want.indexOf(':');
        if (c2 > 0 && want.substring(0, c2).equalsIgnoreCase(have)) {
            return true;
        }

        return false;
    }

    private static boolean looksLikeConnectionRefused(Throwable e) {
        if (e == null)
            return false;
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            String m = cur.getMessage();
            if (m != null) {
                String lm = m.toLowerCase();
                if (lm.contains("connection refused") || lm.contains("connectexception")
                        || lm.contains("connectionrefused")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String alternativePortUrl(String url) {
        if (url == null)
            return null;
        // Very small heuristic: swap :11434 and :11435.
        if (url.contains(":11434")) {
            return url.replace(":11434", ":11435");
        }
        if (url.contains(":11435")) {
            return url.replace(":11435", ":11434");
        }
        return null;
    }

    private static String safeUrl(String url) {
        if (url == null)
            return null;
        String u = url;
        // Strip query params which may include secrets.
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        return u;
    }

    private static String shortErr(Throwable e) {
        if (e == null) {
            return null;
        }
        String msg = String.valueOf(e);
        return "errorType=" + e.getClass().getSimpleName()
                + " errorHash=" + SafeRedactor.hashValue(msg)
                + " errorLength=" + msg.length();
    }
}
