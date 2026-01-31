package com.example.lms.service.llm;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

/**
 * A simple reactive client for calling OpenAI‑compatible vLLM servers.
 * This implementation uses Spring's {@link WebClient} to POST
 * chat completion requests and extract the assistant message from the
 * response.  It does not handle streaming; callers should prefer
 * {@link com.example.lms.service.llm.LocalOpenAiClient} when streaming
 * responses are required.  This client is primarily intended to
 * support the non‑streaming chat API used by the reactive LLM
 * interface.
 */
@Component
public class LocalVllmClient implements ReactiveLlmClient {
    private final WebClient.Builder http;

    public LocalVllmClient(WebClient.Builder http) {
        this.http = http;
    }

    @Override
    public Mono<String> chat(LlmRequest request, Backend backend) {
        // Construct the OpenAI chat payload.  We intentionally pass
        // through user/system messages exactly as supplied in the
        // request; the caller is responsible for including any system
        // prompts.  Temperature and other generation parameters can be
        // supplied via the extra map if needed in the future.
        Map<String,Object> body = Map.of(
            "model", backend.model(),
            "messages", request.messages(),
            "max_tokens", request.maxTokens()
        );
        return http.baseUrl(backend.baseUrl()).build()
            .post().uri("/chat/completions")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Map.class)
            .map(resp -> {
                Object choices = resp.get("choices");
                if (choices instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Map<?,?> m) {
                        Object message = m.get("message");
                        if (message instanceof Map<?,?> mm) {
                            Object content = mm.get("content");
                            return content == null ? "" : content.toString();
                        }
                    }
                }
                return "";
            });
    }
}