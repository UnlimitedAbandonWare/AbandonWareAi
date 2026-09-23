package com.example.lms.config;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OllamaNativeChatModel;
import com.example.lms.llm.OpenAiCompatBaseUrl;

import com.example.lms.guard.KeyResolver;
import com.example.lms.guard.ModelGuard;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreakerProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * LLM configuration.
 *
 * <p>Important:
 * - Keep internal retries low (ideally 0 for fast/utility models) so the orchestration
 *   layer (timeouts/circuit breakers) can make consistent decisions.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(NightmareBreakerProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);
    private static final String LLM_PRIMARY_PROVIDER_KEY = "llm.primary.provider";
    private static final String LLM_PRIMARY_PORT_KEY = "llm.primary.port";
    private static final String LLM_PRIMARY_SUPPRESSED_STAGE_KEY = "llm.primary.suppressed.stage";
    private static final String LLM_PRIMARY_SUPPRESSED_ERROR_TYPE_KEY = "llm.primary.suppressed.errorType";
    private static final String LLM_FAST_PROVIDER_KEY = "llm.fast.provider";
    private static final String LLM_FAST_PORT_KEY = "llm.fast.port";
    private static final String LLM_FAST_SUPPRESSED_STAGE_KEY = "llm.fast.suppressed.stage";
    private static final String LLM_FAST_SUPPRESSED_ERROR_TYPE_KEY = "llm.fast.suppressed.errorType";
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

    @Value("${llm.ollama-native.think-false.enabled:true}")
    private boolean ollamaNativeThinkFalseEnabled = true;

    @Value("${llm.ollama-native.num-gpu:${LLM_OLLAMA_NATIVE_NUM_GPU:}}")
    private String ollamaNativeNumGpu;

    @Autowired(required = false)
    private ai.abandonware.nova.config.LlmRouterProperties llmRouterProperties;

    @Bean(name = {"chatModel","redChatModel"})
    @Primary
    public ChatModel chatModel(
            @Value("${llm.base-url}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.chat-model}") String model,
            @Value("${llm.chat.temperature:0.3}") double temperature,
            @Value("${llm.timeout-seconds:12}") long timeoutSeconds,
            @Value("${llm.max-tokens:${LLM_MAX_TOKENS:512}}") Integer maxTokens,
            // NOTE: keep internal retries fail-fast by default (0).
            // Outer orchestrators / caller-level retry should own the policy to avoid stacked timeouts.
            @Value("${llm.max-retries:0}") int maxRetries,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker
    ) {
        ChatModel routeFailure = routeDisabledChatModel("chatModel", model, baseUrl);
        if (routeFailure != null) return routeFailure;
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
            return disabledChatModel("chatModel", model, modelRuntimeHealthTracker);
        }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
        traceLlmEndpoint(
                LLM_PRIMARY_PROVIDER_KEY,
                LLM_PRIMARY_PORT_KEY,
                LLM_PRIMARY_SUPPRESSED_STAGE_KEY,
                LLM_PRIMARY_SUPPRESSED_ERROR_TYPE_KEY,
                sanitizedBaseUrl);
        assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);
        if (shouldUseOllamaNativeThinkFalse(model, sanitizedBaseUrl)) {
            TraceStore.put("llm.ollamaNative.route", true);
            TraceStore.put("llm.ollamaNative.route.bean", "chatModel");
            return new OllamaNativeChatModel(
                    sanitizedBaseUrl,
                    model,
                    Duration.ofSeconds(timeoutSeconds),
                    maxTokens,
                    ModelCapabilities.sanitizeTemperature(model, temperature),
                    ollamaNativeNumGpu(),
                    modelRuntimeHealthTracker);
        }

        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(modelRuntimeHealthTracker.observedHttpClientBuilder("primary"))
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        applyGatewayHeaders(builder, sanitizedBaseUrl, model);

        if (maxTokens != null && maxTokens > 0) {
            String tokenParam = OpenAiTokenParamCompat.tokenParamKey(model, sanitizedBaseUrl);
            if ("max_tokens".equals(tokenParam)) {
                builder.maxTokens(maxTokens);
            } else if ("max_completion_tokens".equals(tokenParam)) {
                builder.maxCompletionTokens(maxTokens);
            }
        }

        builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    public ChatModel chatModel(
            String baseUrl,
            KeyResolver keyResolver,
            String model,
            double temperature,
            long timeoutSeconds,
            int maxRetries
    ) {
        return chatModel(baseUrl, keyResolver, model, temperature, timeoutSeconds,
                null, maxRetries, new ModelRuntimeHealthTracker());
    }

    @Bean(name = "miniModel")
    public ChatModel miniModel(
            @Value("${llm.base-url}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.mini.model:${llm.chat-model}}") String model,
            @Value("${llm.mini.temperature:0.2}") double temperature,
            @Value("${llm.mini.timeout-seconds:12}") long timeoutSeconds,
            // NOTE: keep internal retries fail-fast by default (0).
            @Value("${llm.mini.max-retries:0}") int maxRetries,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker
    ) {
        ChatModel routeFailure = routeDisabledChatModel("miniModel", model, baseUrl);
        if (routeFailure != null) return routeFailure;
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
            return disabledChatModel("miniModel", model, modelRuntimeHealthTracker);
        }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
        assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);

        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(modelRuntimeHealthTracker.observedHttpClientBuilder("primary"))
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        applyGatewayHeaders(builder, sanitizedBaseUrl, model);

        builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    /**
     * Fast model for utility tasks (QueryTransformer, disambiguation, etc.).
     *
     * <p>Hard rule: avoid internal retries for the fast model. Upper orchestration already
     * applies time budgets / fallbacks, and internal retries tend to create zombie work
     * under cancellation/timeouts.</p>
     */
    @Bean(name = {"fastChatModel","greenChatModel"})
    public ChatModel fastChatModel(
            @Value("${llm.fast.base-url:${llm.base-url}}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.fast.model:${llm.chat-model}}") String model,
            @Value("${llm.fast.temperature:0.0}") double temperature,
            @Value("${llm.fast.timeout-seconds:5}") long timeoutSeconds,
            @Value("${llm.fast.max-retries:0}") int maxRetries,
            @Value("${llm.fast.max-tokens:256}") Integer maxTokens,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker
    ) {
        ChatModel routeFailure = routeDisabledChatModel("fastChatModel", model, baseUrl);
        if (routeFailure != null) return routeFailure;
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
            return disabledChatModel("fastChatModel", model, modelRuntimeHealthTracker);
        }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
        traceLlmEndpoint(
                LLM_FAST_PROVIDER_KEY,
                LLM_FAST_PORT_KEY,
                LLM_FAST_SUPPRESSED_STAGE_KEY,
                LLM_FAST_SUPPRESSED_ERROR_TYPE_KEY,
                sanitizedBaseUrl);
        assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);
        if (shouldUseOllamaNativeThinkFalse(model, sanitizedBaseUrl)) {
            TraceStore.put("llm.ollamaNative.route", true);
            TraceStore.put("llm.ollamaNative.route.bean", "fastChatModel");
            return new OllamaNativeChatModel(
                    sanitizedBaseUrl,
                    model,
                    Duration.ofSeconds(timeoutSeconds),
                    maxTokens,
                    ModelCapabilities.sanitizeTemperature(model, temperature),
                    ollamaNativeNumGpu(),
                    modelRuntimeHealthTracker);
        }

        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(modelRuntimeHealthTracker.observedHttpClientBuilder("primary"))
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        applyGatewayHeaders(builder, sanitizedBaseUrl, model);
        if (maxTokens != null) {
            if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
                builder.maxTokens(maxTokens);
            } else {
                log.info("[OpenAI-Compat] modelHash={} modelLength={} rejects max_tokens; skipping",
                        SafeRedactor.hashValue(model), lengthOf(model));
            }
        }

        builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    public ChatModel miniModel(
            String baseUrl,
            KeyResolver keyResolver,
            String model,
            double temperature,
            long timeoutSeconds,
            int maxRetries) {
        return miniModel(baseUrl, keyResolver, model, temperature, timeoutSeconds, maxRetries,
                new ModelRuntimeHealthTracker());
    }

    public ChatModel fastChatModel(
            String baseUrl,
            KeyResolver keyResolver,
            String model,
            double temperature,
            long timeoutSeconds,
            int maxRetries,
            int maxTokens
    ) {
        return fastChatModel(baseUrl, keyResolver, model, temperature, timeoutSeconds,
                maxRetries, Integer.valueOf(maxTokens), new ModelRuntimeHealthTracker());
    }

@Bean(name = "exploreChatModel")
public ChatModel exploreChatModel(
        @Value("${llm.explore.base-url:${llm.fast.base-url:${llm.base-url}}}") String baseUrl,
        KeyResolver keyResolver,
        @Value("${llm.explore.model:${llm.chat-model}}") String model,
        @Value("${llm.explore.temperature:0.85}") double temperature,
        @Value("${llm.explore.timeout-seconds:6}") long timeoutSeconds,
        @Value("${llm.explore.max-retries:0}") int maxRetries,
        @Value("${llm.explore.max-tokens:512}") Integer maxTokens,
        ModelRuntimeHealthTracker modelRuntimeHealthTracker
) {
    ChatModel routeFailure = routeDisabledChatModel("exploreChatModel", model, baseUrl);
    if (routeFailure != null) return routeFailure;
    String apiKey = keyResolver.resolveLocalApiKeyStrict();
    if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
        return disabledChatModel("exploreChatModel", model, modelRuntimeHealthTracker);
    }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

    String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
    assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);

    var builder = OpenAiChatModel.builder()
            .httpClientBuilder(modelRuntimeHealthTracker.observedHttpClientBuilder("primary"))
            .baseUrl(sanitizedBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
            .timeout(Duration.ofSeconds(timeoutSeconds));
    applyGatewayHeaders(builder, sanitizedBaseUrl, model);
    if (maxTokens != null) {
            if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
            builder.maxTokens(maxTokens);
        } else {
            log.info("[OpenAI-Compat] modelHash={} modelLength={} rejects max_tokens; skipping",
                    SafeRedactor.hashValue(model), lengthOf(model));
        }
    }

    builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

    // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
    builder.modelName(model);
    return builder.build();
}

public ChatModel exploreChatModel(
        String baseUrl,
        KeyResolver keyResolver,
        String model,
        double temperature,
        long timeoutSeconds,
        int maxRetries,
        Integer maxTokens) {
    return exploreChatModel(baseUrl, keyResolver, model, temperature, timeoutSeconds,
            maxRetries, maxTokens, new ModelRuntimeHealthTracker());
}

@Bean(name = "judgeChatModel")
public ChatModel judgeChatModel(
        @Value("${llm.judge.base-url:${llm.high.base-url:${llm.base-url}}}") String baseUrl,
        KeyResolver keyResolver,
        @Value("${llm.judge.model:${llm.high.model:${llm.chat-model}}}") String model,
        @Value("${llm.judge.timeout-seconds:6}") long timeoutSeconds,
        @Value("${llm.judge.max-retries:0}") int maxRetries,
        @Value("${llm.judge.max-tokens:512}") Integer maxTokens,
        ModelRuntimeHealthTracker modelRuntimeHealthTracker
) {
    ChatModel routeFailure = routeDisabledChatModel("judgeChatModel", model, baseUrl);
    if (routeFailure != null) return routeFailure;
    String apiKey = keyResolver.resolveLocalApiKeyStrict();
    if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
        return disabledChatModel("judgeChatModel", model, modelRuntimeHealthTracker);
    }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

    String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
    assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);

    // Deterministic judge: keep temperature at 0 (or the model's fixed default for rigid sampling models).
    var builder = OpenAiChatModel.builder()
            .httpClientBuilder(modelRuntimeHealthTracker.observedHttpClientBuilder("primary"))
            .baseUrl(sanitizedBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .temperature(ModelCapabilities.sanitizeTemperature(model, 0.0d))
            .timeout(Duration.ofSeconds(timeoutSeconds));
    applyGatewayHeaders(builder, sanitizedBaseUrl, model);
    if (maxTokens != null) {
        if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
            builder.maxTokens(maxTokens);
        } else {
            log.info("[OpenAI-Compat] modelHash={} modelLength={} rejects max_tokens; skipping",
                    SafeRedactor.hashValue(model), lengthOf(model));
        }
    }

    builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

    // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
    builder.modelName(model);
    return builder.build();
}

public ChatModel judgeChatModel(
        String baseUrl,
        KeyResolver keyResolver,
        String model,
        long timeoutSeconds,
        int maxRetries,
        Integer maxTokens) {
    return judgeChatModel(baseUrl, keyResolver, model, timeoutSeconds, maxRetries, maxTokens,
            new ModelRuntimeHealthTracker());
}

    @Bean(name = "highModel")
    public ChatModel highModel(
            @Value("${llm.high.base-url:${llm.base-url}}") String baseUrl,
            KeyResolver keyResolver,
            @Value("${llm.high.model:${llm.chat-model}}") String model,
            @Value("${llm.high.temperature:0.3}") double temperature,
            @Value("${llm.high.timeout-seconds:30}") long timeoutSeconds,
            // NOTE: keep internal retries fail-fast by default (0).
            @Value("${llm.high.max-retries:0}") int maxRetries,
            @Value("${llm.high.max-tokens:1024}") Integer maxTokens,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker
    ) {
        ChatModel routeFailure = routeDisabledChatModel("highModel", model, baseUrl);
        if (routeFailure != null) return routeFailure;
        String apiKey = keyResolver.resolveLocalApiKeyStrict();
        if (ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey)) {
            return disabledChatModel("highModel", model, modelRuntimeHealthTracker);
        }
        ModelGuard.assertConfigured("openai-compatible", apiKey, model);

        String sanitizedBaseUrl = OpenAiCompatBaseUrl.sanitize(baseUrl);
        assertGatewayAllowed(model, sanitizedBaseUrl, apiKey);

        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(modelRuntimeHealthTracker.observedHttpClientBuilder("primary"))
                .baseUrl(sanitizedBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(ModelCapabilities.sanitizeTemperature(model, temperature))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        applyGatewayHeaders(builder, sanitizedBaseUrl, model);
        if (maxTokens != null) {
            if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(model, sanitizedBaseUrl)) {
                builder.maxTokens(maxTokens);
            } else {
                log.info("[OpenAI-Compat] modelHash={} modelLength={} rejects max_tokens; skipping",
                        SafeRedactor.hashValue(model), lengthOf(model));
            }
        }

        builder.maxRetries(Integer.valueOf(Math.max(0, maxRetries)));

        // Safety: ensure modelName survives builder mutations (maxTokens/maxRetries/etc)
        builder.modelName(model);
        return builder.build();
    }

    public ChatModel highModel(
            String baseUrl,
            KeyResolver keyResolver,
            String model,
            double temperature,
            long timeoutSeconds,
            int maxRetries,
            Integer maxTokens) {
        return highModel(baseUrl, keyResolver, model, temperature, timeoutSeconds,
                maxRetries, maxTokens, new ModelRuntimeHealthTracker());
    }

    /**
     * Lightweight LLM-specific circuit breaker used by utility LLM calls
     * (QueryTransformer, query analysis, etc.).
     */
    @Bean
    public NightmareBreaker nightmareBreaker(NightmareBreakerProperties props) {
        return new NightmareBreaker(props);
    }

    private void assertGatewayAllowed(String model, String baseUrl, String apiKey) {
        if (!ModelCapabilities.isLocalChatModelId(model)) {
            return;
        }
        LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                baseUrl,
                allowPrivateRemote,
                allowedHosts,
                requireAuthForRemote,
                apiKey,
                ownerToken);
    }

    private void applyGatewayHeaders(OpenAiChatModel.OpenAiChatModelBuilder builder, String baseUrl, String model) {
        if (builder == null
                || !ModelCapabilities.isLocalChatModelId(model)
                || !LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, allowedHosts)) {
            return;
        }
        Map<String, String> headers = LocalLlmGatewaySecurity.ownerTokenHeaders(ownerTokenHeader, ownerToken);
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }
    }

    private ChatModel routeDisabledChatModel(String beanName, String model, String baseUrl) {
        var failure = LocalLlmGatewaySecurity.routePolicyFailure(llmRouterProperties, model, model, baseUrl);
        return failure == null ? null : ExpectedFailureChatModel.forRouteFailure(beanName, failure);
    }

    private ChatModel disabledChatModel(String beanName, String model) {
        return disabledChatModel(beanName, model, null);
    }

    private ChatModel disabledChatModel(
            String beanName,
            String model,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker) {
        log.warn("[AWX][runtime-config] status=warning property=llm.api-key reason=provider_disabled_missing_key bean={} modelHash={} modelLength={}",
                beanName, SafeRedactor.hashValue(model), lengthOf(model));
        ModelRuntimeHealthTracker.ExpectedFailureAttemptEvidence attemptEvidence = modelRuntimeHealthTracker == null
                ? null
                : modelRuntimeHealthTracker.expectedFailureAttemptEvidence(
                        "primary",
                        modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                                "spring_bean", model, null, "unknown"),
                        Map.of("disabled", true));
        return new ExpectedFailureChatModel(
                "LLM provider is disabled because api key is not configured.",
                beanName,
                attemptEvidence);
    }

    private static void traceLlmEndpoint(
            String providerKey,
            String portKey,
            String suppressedStageKey,
            String suppressedErrorTypeKey,
            String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            TraceStore.put(providerKey,
                    LocalLlmGatewaySecurity.isKnownExternalProviderBaseUrl(baseUrl) ? "remote" : "local");
            TraceStore.put(portKey, uri.getPort());
        } catch (RuntimeException ex) {
            TraceStore.put(providerKey, "unknown");
            TraceStore.put(portKey, -1);
            TraceStore.put(suppressedStageKey, "parseEndpoint");
            TraceStore.put(suppressedErrorTypeKey, "invalid_url");
        }
    }

    private static int lengthOf(String model) {
        return model == null ? 0 : model.length();
    }

    private boolean shouldUseOllamaNativeThinkFalse(String model, String baseUrl) {
        return OllamaNativeChatModel.supportsThinkFalseRoute(
                ollamaNativeThinkFalseEnabled,
                model,
                baseUrl);
    }

    private Integer ollamaNativeNumGpu() {
        if (ollamaNativeNumGpu == null || ollamaNativeNumGpu.isBlank()) {
            return null;
        }
        String value = ollamaNativeNumGpu.trim();
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ex) {
            log.warn("[AWX][local-llm] invalid llm.ollama-native.num-gpu valueHash={} valueLength={}",
                    SafeRedactor.hashValue(value), value.length());
            return null;
        }
    }

    /**
     * Backward compatible alias.
     */
    @Bean(name = "localChatModel")
    public ChatModel localChatModel(@Qualifier("miniModel") ChatModel delegate) {
        return delegate;
    }
}
