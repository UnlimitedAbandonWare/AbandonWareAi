package com.example.lms.config;

import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.time.Duration;

/** Bounded Focus variant of the existing OpenAI embedding fallback; general RAG beans stay unchanged. */
@Configuration
public class FocusMemoryEmbeddingConfig {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.example.lms.llm.ModelRuntimeHealthTracker apiFailureHealthTracker;

    /** Focus uses the existing fallback credentials/endpoint, with its own deadline and vector space. */
    public interface FocusCloudEmbedding {
        String fingerprint();
        int dimensions();
        dev.langchain4j.data.embedding.Embedding embed(String text);
    }

    @Bean
    @ConditionalOnMissingBean(FocusCloudEmbedding.class)
    @ConditionalOnProperty(name = "focus.memory.embedding.cloud-enabled", havingValue = "true", matchIfMissing = false)
    @Conditional(EmbeddingFallbackKeyPresentCondition.class)
    public FocusCloudEmbedding focusCloudEmbedding(
            @Value("${embedding.fallback.api-key:${openai.api.key:${OPENAI_API_KEY:}}}") String apiKey,
            @Value("${embedding.fallback.base-url:${openai.api.url:${openai.base-url:https://api.openai.com}}}") String baseUrl,
            @Value("${embedding.fallback.model:text-embedding-3-small}") String model,
            @Value("${embedding.fallback.dimensions:${embedding.dimensions:1536}}") int dimensions) {
        if (ConfigValueGuards.isMissing(apiKey) || dimensions < 1)
            throw new IllegalStateException("focus_embedding_config_invalid");
        final String endpoint = normalizeBaseUrl(baseUrl);
        final String identity = "openai|" + model + "|" + dimensions + "|" + SafeRedactor.hashValue(endpoint);
        return new FocusCloudEmbedding() {
            public String fingerprint() { return identity; }
            public int dimensions() { return dimensions; }
            public dev.langchain4j.data.embedding.Embedding embed(String text) {
                long wait = com.example.lms.service.chat.ChatRunExecutionContext.capRequestWait(3000);
                var builder = OpenAiEmbeddingModel.builder()
                        .httpClientBuilder(apiFailureHealthTracker == null
                                ? dev.langchain4j.http.client.HttpClientBuilderLoader.loadHttpClientBuilder()
                                : apiFailureHealthTracker.observedHttpClientBuilder("fallback"))
                        .apiKey(apiKey.trim()).modelName(model).dimensions(dimensions)
                        .timeout(Duration.ofMillis(wait)).maxRetries(0).logRequests(false).logResponses(false);
                if (endpoint != null) builder.baseUrl(endpoint);
                try (var ignored = com.example.lms.service.chat.ChatRunExecutionContext.interruptibleCall("focus_embedding")) {
                    var result = builder.build().embed(text).content();
                    com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
                    return result;
                }
            }
            @Override public String toString() { return "FocusCloudEmbedding[redacted]"; }
        };
    }
    private static String normalizeBaseUrl(String baseUrl) {
        if(baseUrl==null||baseUrl.isBlank())return null;
        String value=baseUrl.trim().replaceAll("/+$","");
        return value.endsWith("/v1")?value:value+"/v1";
    }
}
