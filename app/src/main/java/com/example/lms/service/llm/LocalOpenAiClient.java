package com.example.lms.service.llm;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.List;

/**
 * Simple wrapper around Spring WebClient to call OpenAI-compatible chat
 * endpoints.  This client is used by {@link LlmRouterService} to dispatch
 * requests to local vLLM servers.  The responses are returned as raw
 * strings (either SSE chunks or a single JSON block) and parsed at a
 * higher layer.
 */
@Component
public class LocalOpenAiClient {
    private final WebClient.Builder http;
    public LocalOpenAiClient(WebClient.Builder http) {
        this.http = http;
    }

    /**
     * Submit a chat completion request in streaming mode.  The method
     * constructs a payload according to the OpenAI Chat Completion API
     * specification and returns a {@link Flux} of raw lines.
     *
     * @param baseUrl the base URL of the vLLM server (without "/chat/completions")
     * @param model   the model name to use
     * @param prompt  the user prompt
     * @return a Flux emitting raw JSON lines
     */
    public Flux<String> stream(String baseUrl, String model, String prompt) {
        Map<String,Object> req = Map.of(
            "model", model,
            "stream", true,
            "temperature", 0.2,
            "messages", List.of(Map.of("role","user","content",prompt))
        );
        return http.baseUrl(baseUrl).build()
            .post().uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .retrieve()
            .bodyToFlux(String.class);
    }

    /**
     * Submit a chat completion request in non-streaming mode.  A single
     * response JSON string is returned.
     *
     * @param baseUrl the base URL of the vLLM server
     * @param model   the model name to use
     * @param prompt  the user prompt
     * @return a Mono of the response JSON
     */
    public Mono<String> nonStream(String baseUrl, String model, String prompt) {
        Map<String,Object> req = Map.of(
            "model", model,
            "stream", false,
            "temperature", 0.2,
            "messages", List.of(Map.of("role","user","content",prompt))
        );
        return http.baseUrl(baseUrl).build()
            .post().uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .retrieve()
            .bodyToMono(String.class);
    }


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}