package com.example.lms.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
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

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingModel.class);

    /** Default "index" dimensions used by the training pipeline. */
    private static final int INDEX_DIM = 1024;

    @Autowired(required = false)
    @Qualifier("backupEmbeddingModel")
    private EmbeddingModel backupModel;

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${embedding.provider:ollama}")
    private String provider;

    @Value("${embedding.base-url:http://localhost:11435/api/embed}")
    private String apiUrl;

    /**
     * Optional explicit local fallback URL (e.g. http://localhost:11434/api/embed).
     */
    @Value("${embedding.base-url-fallback:}")
    private String fallbackApiUrl;

    @Value("${embedding.model:qwen3-embedding}")
    private String model;

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

    /** WARN_ONLY | STRICT (best-effort). */
    @Value("${embedding.dimension-guard-mode:WARN_ONLY}")
    private String dimensionGuardMode;

    @Value("${embedding.log-dimension-mismatch:true}")
    private boolean logDimensionMismatch;

    @Value("${embedding.port-fallback.enabled:true}")
    private boolean portFallbackEnabled;

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
        return INDEX_DIM;
    }

    /**
     * Returns the embedding dimensions used by the indexing subsystem.
     * Implements MatryoshkaAware interface.
     */
    @Override
    public int indexDimensions() {
        return INDEX_DIM;
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
        out.put("dimensions", dimensions);
        out.put("timeoutSec", timeoutSec);
        out.put("portFallbackEnabled", portFallbackEnabled);

        out.put("backupAvailable", backupModel != null);

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
            return Response.from(Embedding.from(new float[0]), null);
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
            for (JsonNode arr : embeddings) {
                float[] raw = parseFloatArray(arr);
                float[] norm = normalizeEmbedding(raw, "batch");
                out.add(norm);
            }

            // Treat empty as failure (forces retry/failover).
            if (out.isEmpty()) {
                throw new IllegalStateException("empty embeddings from Ollama");
            }

            recordLocalSuccess();
            return out;
        } catch (Exception e) {
            recordLocalFailure("batch", e);
            return null;
        }
    }

    private float[] callOllamaVector(String text) {
        String input = nullSafe(text);
        if (input.isBlank()) {
            return new float[0];
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
            return norm;
        } catch (Exception e) {
            recordLocalFailure("single", e);
            return callBackupVector(input, "fallback");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Backup embedding
    // ─────────────────────────────────────────────────────────────────────

    private float[] callBackupVector(String text, String stage) {
        if (backupModel == null) {
	        log.warn("[OllamaEmbeddingModel] backupEmbeddingModel not available; returning zero vector (stage={})", stage);
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
	        return new float[0];
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
        } catch (Exception ignore) {
        }
	try {
            float[] vec = backupModel.embed(text).content().vector();
            if (vec == null) {
                return new float[0];
            }
            return normalizeEmbedding(vec, "backup-" + stage);
        } catch (Exception e) {
            log.warn("[OllamaEmbeddingModel] backup embedding failed (stage={}): {}", stage, e.toString());
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
            return new float[0];
        }
    }

    private List<Embedding> callBackupBatch(List<TextSegment> segments, String stage) {
        if (backupModel == null) {
	        log.warn("[OllamaEmbeddingModel] backupEmbeddingModel not available; returning empty embeddings (stage={})", stage);
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
            // Produce empties; upstream (VectorStoreService) can enforce non-empty when
            // desired.
            List<Embedding> out = new ArrayList<>(segments == null ? 0 : segments.size());
            if (segments != null) {
                for (int i = 0; i < segments.size(); i++) {
                    out.add(Embedding.from(new float[0]));
                }
            }
	        return out;
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
        } catch (Exception ignore) {
        }
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
            log.warn("[OllamaEmbeddingModel] backup embedAll failed (stage={}): {}", stage, e.toString());
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
            // Return empties
            List<Embedding> out = new ArrayList<>(segments == null ? 0 : segments.size());
            if (segments != null) {
                for (int i = 0; i < segments.size(); i++) {
                    out.add(Embedding.from(new float[0]));
                }
            }
            return out;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fast-fail + health
    // ─────────────────────────────────────────────────────────────────────

    private boolean isOllamaProvider() {
        return provider == null || provider.isBlank() || "ollama".equalsIgnoreCase(provider);
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
        } catch (Exception ignore) {
        }
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
        } catch (Exception ignore) {
        }

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
        } catch (Exception ignore) {
        }

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
        // Health preflight is only useful if we have a backup target.
        if (!fastFailEnabled || !backupAvailable() || !fastFailHealthEnabled) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (healthOkUntilMs.get() > now) {
            try {
                com.example.lms.search.TraceStore.inc("embed.fastfail.health.cache_hit");
            } catch (Exception ignore) {
            }
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
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                com.example.lms.search.TraceStore.inc("embed.fastfail.health.concurrent_skip");
            } catch (Exception ignore) {
            }
            return false;
        }

        boolean locked = healthInFlight.compareAndSet(false, true);
        if (!locked) {
            // Should be rare; conservatively skip.
            return false;
        }

        try {
            runHealthCheckOrThrow();

            long okUntil = System.currentTimeMillis() + Math.max(1L, fastFailHealthOkTtlSeconds) * 1000L;
            healthOkUntilMs.set(okUntil);
            healthLastOkAtMs.set(System.currentTimeMillis());
            healthLastError.set(null);

            try {
                com.example.lms.search.TraceStore.inc("embed.fastfail.health.ok");
            } catch (Exception ignore) {
            }

            return true;
        } catch (Exception e) {
            healthLastFailAtMs.set(System.currentTimeMillis());
            healthLastError.set(shortErr(e));

            try {
                com.example.lms.search.TraceStore.inc("embed.fastfail.health.fail");
            } catch (Exception ignore) {
            }

            recordLocalFailure("health." + nullSafe(stage), e);
            return false;
        } finally {
            healthInFlight.set(false);
        }
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
            throw new IllegalStateException("model not found in /api/tags: " + model);
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
            throw new IllegalStateException("model not found in /api/tags: " + model);
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

    private Map<String, Object> buildEmbedBody(Object input, Integer targetDim, String keepAliveOverride) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);

        // Only send options.dimensions when explicitly enabled *and* the server
        // hasn't shown it doesn't support the option.
        if (targetDim != null && targetDim > 0 && dimensionsOptionEnabled && !dimensionsOptionSuppressed.get()) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("dimensions", targetDim);
            body.put("options", options);
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
            try {
                try {
                    com.example.lms.search.TraceStore.inc("embed.ollama.post.attempt");
                } catch (Exception ignore) {
                }

                long timeoutMs = normalizeTimeoutMs(timeoutSecondsOrMs);
                String json = webClient
                        .post()
                        .uri(url)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .onErrorResume(t -> Mono.error(new RuntimeException(t)))
                        .block();

                try {
                    com.example.lms.search.TraceStore.inc("embed.ollama.post.ok");
                } catch (Exception ignore) {
                }

                return mapper.readTree(json);
            } catch (Exception e) {
                last = e;

                // [AUTO-HEAL] Some local embedding servers reject "options.dimensions"
                // with messages like: "invalid option provided option=dimensions".
                // If detected, suppress the option and retry once without it.
                if (hasDimensionsOption(body) && looksLikeDimensionsOptionUnsupported(e)) {
                    try {
                        Integer dim = extractDimensionsOption(body);

                        if (dimensionsOptionSuppressed.compareAndSet(false, true)) {
                            log.warn(
                                    "[OllamaEmbeddingModel] 'options.dimensions' unsupported by embed endpoint; suppressing and retrying without it (dim={}, model={}, url={})",
                                    dim, model, safeUrl(url));
                            try {
                                com.example.lms.search.TraceStore.put("embed.ollama.dimensions_option.suppressed", true);
                                if (dim != null) {
                                    com.example.lms.search.TraceStore.put("embed.ollama.dimensions_option.suppressed.dim", dim);
                                }
                            } catch (Exception ignore) {
                            }

                            if (debugEventStore != null) {
                                try {
                                    java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
                                    meta.put("model", model);
                                    meta.put("url", safeUrl(url));
                                    if (dim != null) {
                                        meta.put("dim", dim);
                                    }
                                    meta.put("message", shortErr(e));

                                    debugEventStore.emit(
                                            DebugProbeType.EMBEDDING,
                                            DebugEventLevel.WARN,
                                            "embedding.ollama.dimensions_option.unsupported",
                                            "Embedding endpoint rejected options.dimensions; suppressing and retrying without it",
                                            "OllamaEmbeddingModel.postJsonWithFallback",
                                            meta,
                                            null);
                                } catch (Throwable ignore) {
                                    // best-effort
                                }
                            }
                        }

                        try {
                            com.example.lms.search.TraceStore.inc("embed.ollama.post.retry_no_dimensions");
                        } catch (Exception ignore) {
                        }

                        Map<String, Object> stripped = stripDimensionsOption(body);
                        String retryJson = webClient
                                .post()
                                .uri(url)
                                .bodyValue(stripped)
                                .retrieve()
                                .bodyToMono(String.class)
                                .timeout(Duration.ofMillis(normalizeTimeoutMs(timeoutSecondsOrMs)))
                                .onErrorResume(t -> Mono.error(new RuntimeException(t)))
                                .block();

                        try {
                            com.example.lms.search.TraceStore.inc("embed.ollama.post.retry_no_dimensions.ok");
                        } catch (Exception ignore) {
                        }

                        return mapper.readTree(retryJson);
                    } catch (Exception retryEx) {
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
                } catch (Exception ignore) {
                }

                // Only retry on next candidate if it is likely a connectivity/endpoint issue.
                if (i < candidates.size() - 1) {
                    if (maybeConnRefused) {
                        log.warn("[OllamaEmbeddingModel] POST failed (conn refused) -> retry on alternate url: {}",
                                safeUrl(url));
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

    private static boolean hasDimensionsOption(Map<String, Object> body) {
        if (body == null) {
            return false;
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
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Map<String, Object> stripDimensionsOption(Map<String, Object> body) {
        if (body == null) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> copy = new LinkedHashMap<>(body);
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
                    sb.append(msg).append(" | ");
                }
            } catch (Throwable ignore) {
            }

            if (cur instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                try {
                    String body = ((org.springframework.web.reactive.function.client.WebClientResponseException) cur)
                            .getResponseBodyAsString();
                    if (body != null && !body.isBlank()) {
                        sb.append(body).append(" | ");
                    }
                } catch (Throwable ignore) {
                }
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
                String json = webClient
                        .get()
                        .uri(u)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofMillis(Math.max(1L, timeoutMs)))
                        .onErrorResume(t -> Mono.error(new RuntimeException(t)))
                        .block();
                return mapper.readTree(json);
            } catch (Exception e) {
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

    private List<String> buildCandidateUrls(String primary, String secondary) {
        List<String> out = new ArrayList<>();
        addUrl(out, primary);

        if (portFallbackEnabled) {
            String alt = alternativePortUrl(primary);
            if (alt != null && !alt.equals(primary)) {
                addUrl(out, alt);
            }
        }

        if (secondary != null && !secondary.isBlank()) {
            addUrl(out, secondary);
            if (portFallbackEnabled) {
                String alt2 = alternativePortUrl(secondary);
                if (alt2 != null && !alt2.equals(secondary)) {
                    addUrl(out, alt2);
                }
            }
        }

        // Also consider health-url override as a primary for GET health, if applicable.
        return out;
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
        if (timeoutSecondsOrMs > 1000L) {
            return timeoutSecondsOrMs;
        }
        return Math.max(1L, timeoutSecondsOrMs) * 1000L;
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
        int target = resolveTargetDim(actual, dimensions, tag);

        if (actual == target) {
            return raw;
        }

        if (actual > target) {
            return sliceVector(raw, target);
        }

        // Pad
        float[] out = new float[target];
        System.arraycopy(raw, 0, out, 0, actual);
        return out;
    }

    private int resolveTargetDim(int actualDim, int configuredDim, String tag) {
        if (configuredDim <= 0) {
            return actualDim;
        }

        // STRICT: fail if mismatch
        if ("STRICT".equalsIgnoreCase(nullSafe(dimensionGuardMode)) && actualDim != 0 && actualDim != configuredDim) {
            throw new IllegalStateException("Embedding dimension mismatch: expected " + configuredDim + ", got "
                    + actualDim + " (" + tag + ")");
        }

        // WARN_ONLY: warn once
        if (logDimensionMismatch && actualDim != 0 && actualDim != configuredDim
                && dimensionWarned.compareAndSet(false, true)) {
            log.warn("[OllamaEmbeddingModel] dimension mismatch (configured={}, actual={}, tag={}, model={})",
                    configuredDim, actualDim, tag, model);
	            if (debugEventStore != null) {
	                debugEventStore.emit(
	                        DebugProbeType.EMBEDDING,
	                        DebugEventLevel.WARN,
	                        "embedding.dimension_mismatch." + model,
	                        "Embedding dimension mismatch (warn-only)",
	                        "OllamaEmbeddingModel.resolveTargetDim",
	                        java.util.Map.of(
	                                "configuredDim", configuredDim,
	                                "actualDim", actualDim,
	                                "tag", tag,
	                                "model", model,
	                                "guardMode", dimensionGuardMode
	                        ),
	                        null
	                );
	            }
        }

        // Matryoshka: normalize to the configured index dimension.
        // - actual > configured: prefix truncation (handled by normalizeEmbedding)
        // - actual < configured: zero-padding (handled by normalizeEmbedding)
        // - actual == 0: treat as empty and pad to configured (handled by normalizeEmbedding)
        return configuredDim;
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
        String msg = e.toString();
        if (msg == null) {
            msg = e.getClass().getName();
        }
        msg = msg.replaceAll("\n", " ").replaceAll("\r", " ");
        if (msg.length() > 320) {
            msg = msg.substring(0, 320);
        }
        return msg;
    }
}
