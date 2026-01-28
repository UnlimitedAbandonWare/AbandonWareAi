
package com.abandonware.ai.agent.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal OpenAI-Compatible Chat Completions client using Java 11 HttpClient.
 * No WebFlux dependency required.
 */
@Component
public class LocalOpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(LocalOpenAiClient.class);
    private final HttpClient http;

    public LocalOpenAiClient() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String chatCompletionBlocking(String baseUrl, String apiKey, String modelId, String prompt, double temperature, long timeoutMs) throws Exception {
        String url = normalizeBaseUrl(baseUrl) + "/chat/completions";
        Map<String, Object> req = new HashMap<>();
        req.put("model", modelId);
        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        req.put("messages", new Object[]{ userMsg });
        req.put("temperature", temperature);

        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(req);

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(2000L, timeoutMs)))
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey.trim());
        }
        HttpRequest request = b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
            return resp.body();
        }
        throw new IllegalStateException("HTTP " + code + ": " + resp.body());
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "http://localhost:11434/v1";
        String b = baseUrl.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length()-1);
        if (b.endsWith("/v1")) return b;
        if (b.endsWith("/v1/")) return b.substring(0, b.length()-1);
        return b; // assume full path provided
    }
}
