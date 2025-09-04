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
 */
@Service
@RequiredArgsConstructor
public class OpenAiChatModel implements ChatModel {

    // Resolve API key for OpenAI. Prefer the `openai.api.key` property and fall
    // back only to OPENAI_API_KEY.  Do not include other vendor keys (e.g. GROQ)
    // in the fallback chain to avoid invalid authentication.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String apiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String baseUrl;

    // Default to gpt-5-mini for chat completions to enforce 5‑series models
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
            Map<String, Object> payload = Map.of(
                    "model", defaultModel,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", temperature,
                    "max_tokens", maxTokens
            );
            String body = openaiWebClient.post()
                    .uri(baseUrl + "/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
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
            return "";
        }
    }
}