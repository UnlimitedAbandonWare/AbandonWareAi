package com.example.lms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.time.Duration;

/**
 * OpenAI (or OpenAI-compatible local) Retrofit wiring.
 *
 * <p>Note: Embedding fallback is configured separately in {@link EmbeddingFallbackConfig}
 * so that embeddings can fall back to OpenAI even when {@code llm.provider != openai}.</p>
 */
@Configuration
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai", matchIfMissing = false)
public class OpenAiConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenAiConfig.class);

    // Resolve the API key for OpenAI. Prefer `openai.api.key` and fall back
    // to OPENAI_API_KEY env var. Do not fall back to other vendor keys.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String openAiKey;

    @Value("${local-llm.enabled:false}")
    private boolean localEnabled;

    @Value("${local-llm.base-url:}")
    private String localBaseUrl;

    // Base URL for OpenAI public API.
    @Value("${openai.base-url:https://api.openai.com/}")
    private String openAiBaseUrl;

    @Bean
    @Primary
    public Object openAiService() {
        // 1) Local (OpenAI-compatible) endpoint.
        if (localEnabled && localBaseUrl != null && !localBaseUrl.isBlank()) {
            String base = ensureTrailingSlash(localBaseUrl.trim());
            // Local servers may ignore the token; if missing, use a placeholder.
            String token = (openAiKey == null || openAiKey.isBlank()) ? "ollama" : openAiKey;

            OkHttpClient client = makeClient(token, Duration.ofSeconds(60));
            ObjectMapper mapper = makeMapper();

            return new Retrofit.Builder()
                    .baseUrl(base)
                    .client(client)
                    .addConverterFactory(JacksonConverterFactory.create(mapper))
                    .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                    .build();
        }

        // 2) Public OpenAI endpoint: key is required.
        if (openAiKey == null || openAiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing");
        }
        if (!openAiKey.startsWith("sk-")) {
            throw new IllegalStateException("OpenAI API key must start with 'sk-'");
        }

        OkHttpClient client = makeClient(openAiKey, Duration.ofSeconds(60));
        ObjectMapper mapper = makeMapper();

        return new Retrofit.Builder()
                .baseUrl(ensureTrailingSlash(openAiBaseUrl))
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create(mapper))
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .build();
    }

    private static String ensureTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    /** Configures token header injection and timeouts without relying on external OpenAI client libs. */
    private static OkHttpClient makeClient(String token, Duration timeout) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .callTimeout(timeout);
        if (token != null && !token.isBlank()) {
            b.addInterceptor(chain -> {
                Request org = chain.request();
                Request req = org.newBuilder()
                        .header("Authorization", "Bearer " + token)
                        .build();
                return chain.proceed(req);
            });
        }
        return b.build();
    }

    /** Customize when needed. */
    private static ObjectMapper makeMapper() {
        return new ObjectMapper();
    }
}
