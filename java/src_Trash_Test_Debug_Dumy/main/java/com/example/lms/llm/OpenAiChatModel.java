// src/main/java/com/example/lms/llm/OpenAiChatModel.java
package com.example.lms.llm;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Simple chat model implementation that invokes the OpenAI chat completions
 * endpoint using a reactive {@link WebClient}.  This implementation
 * avoids a dependency on external OpenAI client libraries and allows for
 * customised timeout and buffer settings via the injected {@code openaiWebClient}.
 *
 * The API key and base URL are resolved from configuration properties or
 * environment variables.  When no API key is provided the service will
 * return an empty string for all completions.
 *
 * This version adds optional Azure OpenAI support when both
 * azure.openai.endpoint and azure.openai.deployment-name are configured.
 * When configured the model name is set to the deployment name and the
 * request will be sent to the Azure endpoint with the appropriate API version.
 */
@Service
@RequiredArgsConstructor
public class OpenAiChatModel implements ChatModel {

    @Value("${openai.api.key}")
    private String apiKey;

    // Base URL for OpenAI. Default is blank to allow fallback to the public API.
    @Value("${openai.base-url:}")
    private String baseUrl;

    // Azure OpenAI optional endpoint (e.g. https://example.openai.azure.com)
    @Value("${azure.openai.endpoint:}")
    private String azureEndpoint;

    // Azure deployment name for the chat model.
    @Value("${azure.openai.deployment-name:}")
    private String azureDeploymentName;

    // Default model for non-Azure mode (5-series mini model)
    @Value("${openai.chat.model:gpt-5-mini}")
    private String defaultModel;

    /**
     * Dedicated OpenAI WebClient.  Qualifier ensures the correct bean is
     * injected from {@link com.example.lms.config.WebClientConfig}.
     */
    private final @Qualifier("openaiWebClient") WebClient openaiWebClient;

    @Override
    public String generate(String prompt) {
        return generate(prompt, 0.7, 1024);
    }

    @Override
    public String generate(String prompt, double temperature, int maxTokens) {
        try {
            if (apiKey == null || apiKey.isBlank()) {
                return "";
            }
            boolean isAzure = azureEndpoint != null && !azureEndpoint.isBlank();
            Map<String, Object> payload = Map.of(
                    "model", isAzure ? azureDeploymentName : defaultModel,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", temperature,
                    "max_completion_tokens", maxTokens
            );
            String requestUrl = isAzure
                    ? String.format("%s/openai/deployments/%s/chat/completions?api-version=2024-02-15-preview",
                    azureEndpoint, azureDeploymentName)
                    : ((baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com" : baseUrl) + "/v1/chat/completions";
            WebClient.RequestBodySpec reqSpec = openaiWebClient.post().uri(requestUrl);
            if (isAzure) {
                reqSpec = reqSpec.header("api-key", apiKey);
            } else {
                reqSpec = reqSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }
            String body = reqSpec
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (body == null || body.isBlank()) {
                return "";
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return "";
            }
            return choices.get(0).path("message").path("content").asText("");
        } catch (Exception e) {
            // Fail-soft: return empty on any exception.
            return "";
        }
    }
}
