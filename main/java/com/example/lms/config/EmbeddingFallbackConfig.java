package com.example.lms.config;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;

import java.net.URI;
import java.time.Duration;

/**
 * OpenAI embedding fallback.
 *
 * <p>
 * Designed to be available even when {@code llm.provider != openai} so that
 * local
 * embedding providers (e.g. Ollama) can fail over without breaking the vector
 * pipeline.
 * </p>
 */
@Configuration
public class EmbeddingFallbackConfig {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.llm.ModelRuntimeHealthTracker apiFailureHealthTracker;

    private static final Logger log = LoggerFactory.getLogger(EmbeddingFallbackConfig.class);

    @Bean("backupEmbeddingModel")
    @ConditionalOnMissingBean(name = "backupEmbeddingModel")
    @ConditionalOnProperty(name = "embedding.fallback.enabled", havingValue = "true", matchIfMissing = true)
    @Conditional(EmbeddingFallbackKeyPresentCondition.class)
    public EmbeddingModel backupEmbeddingModel(
            ObjectProvider<DebugEventStore> debugEvents,
            @Value("${embedding.fallback.api-key:${openai.api.key:${OPENAI_API_KEY:__MISSING__}}}") String apiKey,
            @Value("${embedding.fallback.base-url:${openai.api.url:${openai.base-url:https://api.openai.com}}}") String baseUrl,
            @Value("${embedding.fallback.model:text-embedding-3-small}") String model,
            @Value("${embedding.fallback.dimensions:${embedding.dimensions:1536}}") int dimensions,
            @Value("${embedding.fallback.timeout-seconds:${embedding.timeout-seconds:30}}") long timeoutSeconds) {
        String key = (apiKey == null) ? "" : apiKey.trim();
        if (ConfigValueGuards.isMissing(key)) {
            // Should not happen because of the condition. Avoid registering a null bean.
            throw new IllegalStateException("OpenAI embedding fallback requested, but api key is missing");
        }

        String m = (model == null || model.isBlank()) ? "text-embedding-3-small" : model.trim();
        String bu = normalizeBaseUrl(baseUrl);

        var b = OpenAiEmbeddingModel.builder()
                .httpClientBuilder(apiFailureHealthTracker == null
                        ? dev.langchain4j.http.client.HttpClientBuilderLoader.loadHttpClientBuilder()
                        : apiFailureHealthTracker.observedHttpClientBuilder("fallback"))
                .apiKey(key)
                .modelName(m);

        if (bu != null) {
            b.baseUrl(bu);
        }
        if (timeoutSeconds > 0) {
            b.timeout(Duration.ofSeconds(timeoutSeconds));
        }
        if (dimensions > 0) {
            b.dimensions(dimensions);
        }

        log.info("[EMBED_FAILOVER] OpenAI fallback embedding enabled. modelHash={} modelLength={} dimensions={} baseUrlHost={} baseUrlHash={}",
                SafeRedactor.hashValue(m), m.length(), dimensions, safeHost(bu), SafeRedactor.hashValue(bu));

        DebugEventStore store = debugEvents.getIfAvailable();
        if (store != null) {
            store.emit(
                    DebugProbeType.MODEL_GUARD,
                    DebugEventLevel.INFO,
                    "model_guard.embedding_fallback.enabled",
                    "Embedding fallback model registered",
                    "EmbeddingFallbackConfig.backupEmbeddingModel",
                    java.util.Map.of(
                            "modelHash", SafeRedactor.hashValue(m),
                            "modelLength", m.length(),
                            "dimensions", dimensions,
                            "baseUrlHost", safeHost(bu),
                            "baseUrlHash", SafeRedactor.hashValue(bu)),
                    null
            );
        }

        return b.build();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String b = baseUrl.trim();
        // LangChain4j expects baseUrl like https://api.openai.com/v1
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (!b.endsWith("/v1")) {
            b = b + "/v1";
        }
        return b;
    }

    private static String safeHost(String value) {
        if (value == null || value.isBlank()) {
            return "(default)";
        }
        try {
            String host = URI.create(value.trim()).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (Exception ignored) {
            TraceStore.put("embeddingFallback.safeHost.suppressed", true);
            TraceStore.put("embeddingFallback.safeHost.suppressed.errorType", "invalid_url");
            return "invalid";
        }
    }
}
