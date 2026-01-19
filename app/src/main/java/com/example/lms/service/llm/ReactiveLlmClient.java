package com.example.lms.service.llm;

import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

/**
 * Reactive interface for local LLM backends.  This API mirrors the
 * OpenAI Chat Completion contract but returns a {@link Mono} to support
 * asynchronous/non-blocking use from WebFlux controllers and services.
 *
 * <p>Implementations should call OpenAI‑compatible endpoints (such
 * as vLLM) using {@link org.springframework.web.reactive.function.client.WebClient}
 * or similar.  The enclosing application can bind multiple backends via
 * {@code llm.backends} in {@code application.yml}.  A scheduler
 * determines which backend to use based on request size or load.</p>
 */
public interface ReactiveLlmClient {
    /**
     * Container for a chat request.  The messages must follow the
     * OpenAI Chat Completion API format (role/content pairs).  The
     * {@code maxTokens} and {@code approxInputTokens} fields allow
     * schedulers to make routing decisions based on the expected size
     * of the response.
     */
    record LlmRequest(List<Map<String, String>> messages,
                      int maxTokens,
                      int approxInputTokens,
                      Map<String, Object> extra) {
    }

    /**
     * Descriptor for a local backend.  Implementations should treat
     * instances of this interface as immutable metadata describing
     * the base URL, model name and tensor parallel size of the server.
     */
    interface Backend {
        String id();
        String baseUrl();
        String model();
        /**
         * The tensor parallel size for this backend.  When greater
         * than 1 the backend expects to run across multiple GPUs.
         * Implementations may ignore this hint if not applicable.
         */
        int tensorParallelSize();
    }

    /**
     * Execute a chat completion call against the given backend.
     *
     * @param request the chat request
     * @param backend the backend to call
     * @return a Mono emitting the assistant message content
     */
    Mono<String> chat(LlmRequest request, Backend backend);
}