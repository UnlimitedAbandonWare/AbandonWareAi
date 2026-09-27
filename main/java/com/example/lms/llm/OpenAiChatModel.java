// src/main/java/com/example/lms/llm/OpenAiChatModel.java
package com.example.lms.llm;

import com.example.lms.config.ConfigValueGuards;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ChatModel implementation backed by an OpenAI-compatible /v1/chat/completions
 * endpoint. This can talk to either the real OpenAI API or a local gateway
 * such as Ollama or vLLM, depending on configuration.
 *
 *  - llm.base-url            : preferred when present (e.g. http://localhost:11434/v1)
 *  - openai.api.url/base-url : used as a fallback
 *  - openai.chat.model       : used if no knowledge-curation/llm.chat-model is set
 */
@Service
@RequiredArgsConstructor
public class OpenAiChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatModel.class);
    private static final LlmGatewayFailureClassifier FAILURE_CLASSIFIER = new LlmGatewayFailureClassifier();

    // Resolve API key in the following order:
    //  1) openai.api.key
    //  2) OPENAI_API_KEY env
    //  3) llm.api-key (for local OpenAI-compatible gateways)
    @Value("${openai.api.key:${OPENAI_API_KEY:${llm.api-key:}}}")
    private String apiKey;

    // Base URL resolution order:
    //  1) llm.base-url
    //  2) openai.api.url (used by application-local-llm.yml)
    //  3) openai.base-url
    //  4) default https://api.openai.com
    @Value("${llm.base-url:${openai.api.url:${openai.base-url:https://api.openai.com}}}")
    private String baseUrl;

    // Model resolution order:
    //  1) knowledge-curation.model-id (if set, for background agents)
    //  2) llm.chat-model
    //  3) openai.chat.model
    //  4) default gemma4:26b (local/open-source style id)
    @Value("${knowledge-curation.model-id:${llm.chat-model:${openai.chat.model:gemma4:26b}}}")
    private String defaultModel;

    // Optional toggle to force local mode even if baseUrl points at api.openai.com
    @Value("${openai.local-enabled:false}")
    private boolean localEnabled;

    // Optional dedicated local endpoint (e.g. http://localhost:11434)
    @Value("${openai.local-base-url:}")
    private String localBaseUrl;

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

    @Value("${llm.gateway.throw-on-empty-failure:false}")
    private boolean throwOnEmptyFailure;

    /** Optional dispatch-time endpoint resolver (quarantine/failover); absent in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelRuntimeHealthTracker modelRuntimeHealthTracker;

    /**
     * Dedicated WebClient configured with timeouts and buffer limits.
     */
    private final @Qualifier("openaiWebClient") WebClient openaiWebClient;

    @PostConstruct
    void normaliseAndValidate() {
        String resolved = selectBaseUrl(baseUrl, localBaseUrl, localEnabled);
        this.baseUrl = normaliseBaseUrl(resolved);
        this.defaultModel = (defaultModel == null ? "" : defaultModel.trim());

        String url = this.baseUrl.toLowerCase(Locale.ROOT);
        String model = this.defaultModel.toLowerCase(Locale.ROOT);

        // Guard: obviously-local model ids should not be sent to api.openai.com
        if (url.contains("api.openai.com")
                && (model.contains("gemma") || model.contains("qwen") || model.contains("llama"))) {
            String msg = "Invalid ChatModel configuration: baseUrl=" + this.baseUrl +
                    ", model=" + this.defaultModel +
                    " (looks like a local LLM id but endpoint is api.openai.com). " +
                    "Set llm.base-url / openai.api.url to your local gateway " +
                    "or change chat.model to a real OpenAI model id (e.g. gpt-4o-mini).";
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        if (ModelCapabilities.isLocalChatModelId(this.defaultModel)) {
            LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                    this.baseUrl,
                    allowPrivateRemote,
                    allowedHosts,
                    requireAuthForRemote,
                    apiKey,
                    ownerToken);
        }
    }

    private static String selectBaseUrl(String configuredBaseUrl,
                                        String configuredLocalBaseUrl,
                                        boolean localEnabled) {
        String candidate = configuredBaseUrl;
        if (localEnabled) {
            if (configuredLocalBaseUrl != null && !configuredLocalBaseUrl.isBlank()) {
                candidate = configuredLocalBaseUrl;
            } else if (candidate == null || candidate.isBlank()) {
                candidate = "http://localhost:11434";
            }
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = "https://api.openai.com";
        }
        return candidate;
    }

    private static String normaliseBaseUrl(String raw) {
        if (raw == null) {
            return "https://api.openai.com";
        }
        String url = raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        }
        return url;
    }

    @Override
    public String generate(String llmGenerationPrompt) {
        return generate(llmGenerationPrompt, 0.7, 1024);
    }

    @Override
    public String generate(String llmGenerationPrompt, double temperature, int maxTokens) {
        try {
            if (llmGenerationPrompt == null || llmGenerationPrompt.isBlank()) {
                return "";
            }
            String normalizedApiKey = usableApiKey(apiKey);
            if (isPublicOpenAiEndpoint(baseUrl) && normalizedApiKey == null) {
                TraceStore.put("llm.gateway.openai.providerDisabled", true);
                TraceStore.put("llm.gateway.openai.disabledReason", "missing_openai_api_key");
                return "";
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", defaultModel);
            payload.put("messages", List.of(Map.of("role", "user", "content", llmGenerationPrompt)));
            if (!omitsTemperature(baseUrl, defaultModel)) {
                payload.put("temperature", temperature);
            }
            String tokenKey = OpenAiTokenParamCompat.tokenParamKey(defaultModel, baseUrl);
            if (tokenKey != null && !tokenKey.isBlank() && !"none".equalsIgnoreCase(tokenKey)) {
                payload.put(tokenKey, maxTokens);
            }

            java.util.function.Function<Map<String, Object>, String> invoke = (pl) -> {
                WebClient.RequestBodySpec spec = openaiWebClient.post()
                        .uri(modelRuntimeHealthTracker == null
                                ? baseUrl + "/v1/chat/completions"
                                : modelRuntimeHealthTracker.resolveServiceEndpoint(baseUrl + "/v1/chat/completions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON);

                if (normalizedApiKey != null) {
                    spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedApiKey);
                }
                if (ModelCapabilities.isLocalChatModelId(defaultModel)
                        && LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, allowedHosts)) {
                    Map<String, String> headers = LocalLlmGatewaySecurity.ownerTokenHeaders(ownerTokenHeader, ownerToken);
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        spec = spec.header(header.getKey(), header.getValue());
                    }
                }

                return spec
                        .bodyValue(pl)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            };

            String body;
            try {
                body = invoke.apply(payload);
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                if (OpenAiTokenParamCompat.isUnsupportedMaxTokens(wcre)) {
                    TraceStore.put("llm.gateway.openai.tokenParamRetry", true);
                    TraceStore.put("llm.gateway.openai.tokenParamRetry.errorType",
                            SafeRedactor.traceLabelOrFallback(wcre.getClass().getSimpleName(), "unknown"));
                    OpenAiTokenParamCompat.replaceTokenParamForRetry(payload, maxTokens);
                    body = invoke.apply(payload);
                } else {
                    throw wcre;
                }
            }

            if (body == null || body.isBlank()) {
                return "";
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return "";
            }
            return choices.get(0).path("message").path("content").asText("");
        } catch (Exception e) {
            LlmFailureClass failureClass = FAILURE_CLASSIFIER.classifyEmptyAfterException(e);
            TraceStore.put("llm.gateway.openai.emptyFailureClass", failureClass.name());
            TraceStore.put("llm.gateway.openai.emptyAfterException", true);
            log.debug("[OpenAiChatModel] completion failed failureClass={} errorType={}",
                    failureClass.name(),
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"));
            if (throwOnEmptyFailure) {
                throw new IllegalStateException("llm.gateway.empty_failure=" + failureClass.name(), e);
            }
            return "";
        }
    }

    private static boolean omitsTemperature(String endpoint, String model) {
        if (!List.of("gpt-5", "gpt-5-mini", "gpt-5-nano").contains(model)) return false;
        try {
            return "api.openai.com".equalsIgnoreCase(java.net.URI.create(endpoint).getHost());
        } catch (IllegalArgumentException | NullPointerException invalidEndpoint) {
            return false; // Preserve the existing request failure path.
        }
    }

    private static String usableApiKey(String value) {
        return ConfigValueGuards.isMissing(value) ? null : value.trim();
    }

    private static boolean isPublicOpenAiEndpoint(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains("api.openai.com");
    }
}
