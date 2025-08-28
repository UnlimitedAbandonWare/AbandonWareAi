package com.example.lms.plugin.image;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import com.example.lms.image.ImageMetaHolder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.core.io.buffer.DataBufferLimitException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Service responsible for invoking the OpenAI image generation API.  This
 * implementation uses Spring's {@link WebClient} to perform a blocking
 * HTTP POST request against the configured endpoint.  The service
 * constructs a minimal JSON payload containing the prompt, requested
 * image count and size, and extracts the resulting image URLs from the
 * response.  On failure, the service logs a warning and returns an
 * empty list.
 *
 * <p>The service does not depend on any existing GPT image components in
 * the repository; it is fully self‑contained.  The API key is read
 * from {@link OpenAiImageProperties} or, when that is undefined, from
 * the {@code OPENAI_API_KEY} environment variable.</p>
 */
@Service
@RequiredArgsConstructor
public class OpenAiImageService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageService.class);

    /**
     * Shared ObjectMapper instance configured to ignore unknown properties during
     * deserialisation.  The OpenAI image API response may include fields such
     * as {@code created} that are not defined in our DTO; failing to ignore
     * these unknown properties would result in an UnrecognizedPropertyException.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OpenAiImageProperties properties;
    /**
     * Builder used to ground image prompts using contextual information.
     * When present the raw prompt will be passed through the builder
     * before being sent to the OpenAI API.  This dependency is optional
     * and will be null when the bean is not available in the context.
     */
    private final com.example.lms.image.GroundedImagePromptBuilder groundedImagePromptBuilder;

    /**
     * Dedicated WebClient for OpenAI calls.  Inject the bean named
     * {@code openaiWebClient} defined in {@link com.example.lms.config.WebClientConfig}.
     * This client is configured with long timeouts and a large buffer to
     * accommodate image generation responses.  Using a qualifier ensures the
     * global WebClient is unaffected.
     */
    @Qualifier("openaiWebClient")
    private final WebClient openaiWebClient;

    /**
     * Generate one or more images using the OpenAI image API.  When the
     * provided API key is missing or blank the method returns an empty
     * list.  Any exceptions during the HTTP call are caught and logged
     * but not propagated.
     *
     * @param prompt the textual description of the desired image
     * @param count  number of images to generate
     * @param size   requested image size (e.g. 512x512)
     * @return a list of URLs pointing to the generated images or an
     *         empty list on failure
     */
    public List<String> generateImages(String prompt, int count, String size) {
        // Determine the API key from configuration or environment
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            // Avoid direct environment access; fall back to system properties instead
            apiKey = System.getProperty("OPENAI_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI API key is not configured. Unable to generate images.");
            return java.util.Collections.emptyList();
        }
        // Ground the prompt if the builder is available.  When no builder is
        // configured the original prompt is used as-is.  A null prompt
        // results in an empty string to avoid NPE downstream.
        String raw = prompt == null ? "" : prompt;
        String grounded = raw;
        try {
            if (groundedImagePromptBuilder != null) {
                grounded = groundedImagePromptBuilder.build(raw, null);
            }
        } catch (Exception e) {
            // Log and continue with the raw prompt; grounding is best-effort
            log.debug("generateImages: grounding prompt failed", e);
        }
        // Override grounded prompt, model and size from chain metadata when
        // available.  The RAG chain writes image.* keys into
        // ImageMetaHolder via DefaultChainContext.putMeta().  Use these
        // annotations to influence the downstream API call.  When no
        // metadata is present the existing grounded prompt and defaults
        // apply.
        try {
            String metaPrompt = ImageMetaHolder.get("image.prompt");
            if (metaPrompt != null && !metaPrompt.isBlank()) {
                grounded = metaPrompt;
            }
        } catch (Exception ignore) {
            // ignore metadata lookup failures
        }
        // Choose defaults for size and model.  When size is blank use 1024x1024.
        String defaultSize = (size == null || size.isBlank()) ? "1024x1024" : size;
        String finalSize;
        String model;
        try {
            finalSize = ImageMetaHolder.getOrDefault("image.size", defaultSize);
            model = ImageMetaHolder.getOrDefault("image.model", "gpt-image-1");
        } catch (Exception ex) {
            // If metadata retrieval fails fall back to defaults
            finalSize = defaultSize;
            model = "gpt-image-1";
        }
        try {
            // Build payload using a mutable map to allow optional parameters.
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("model", model);
            payload.put("prompt", grounded);
            payload.put("n", Math.max(1, count));
            payload.put("size", finalSize);
            // response_format은 더 이상 허용되지 않으므로 제거한다.
            // OpenAI는 URL 또는 b64_json을 자동으로 선택해 응답하므로
            // 해당 파라미터를 명시하지 않는다. 응답에서 각각 처리한다.
            // Execute the HTTP call using the dedicated OpenAI WebClient.  Apply a read
            // timeout to ensure we wait for the entire body and map large payload errors
            // to a descriptive exception when the buffer limit is exceeded.
            // Build the Authorization header using String.format to avoid direct concatenation
            String bearerHeader = String.format("Bearer %s", apiKey);
            String body = openaiWebClient.post()
                    .uri(properties.endpoint())
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    // allow up to 240 seconds for the body to be delivered
                    .timeout(java.time.Duration.ofSeconds(240))
                    .onErrorResume(DataBufferLimitException.class,
                            e -> Mono.error(new IllegalStateException("Image JSON too large (increase maxInMemorySize)")))
                    .block();
            if (body == null || body.isBlank()) {
                log.warn("OpenAI image API returned empty body (possibly receiver cancelled)");
                try { ImageMetaHolder.put("image.error", "EMPTY_BODY_OR_CANCELLED"); } catch (Exception ignore) {}
                return java.util.Collections.emptyList();
            }
            // Parse the JSON response using the shared mapper.  The OpenAI image API
            // returns a top‑level object with a "data" array and additional
            // fields such as "created".  By deserialising into a JsonNode we
            // gracefully ignore unknown fields and avoid UnrecognizedPropertyException.
            JsonNode root = MAPPER.readTree(body);
            java.util.List<String> results = new java.util.ArrayList<>();
            JsonNode dataNode = root.path("data");
            if (dataNode.isArray()) {
                for (JsonNode n : dataNode) {
                    // Prefer a URL when present
                    if (n.hasNonNull("url")) {
                        String url = n.get("url").asText();
                        if (url != null && !url.isBlank()) {
                            results.add(url);
                            continue;
                        }
                    }
                    // Fall back to b64_json encoded PNGs.  Convert these into
                    // data URI strings so the front‑end can display them directly.
                    if (n.hasNonNull("b64_json")) {
                        String b64 = n.get("b64_json").asText();
                        if (b64 != null && !b64.isBlank()) {
                            // Use String.format to avoid direct string concatenation when building the data URI
                            results.add(String.format("data:image/png;base64,%s", b64));
                        }
                    }
                }
            }
            if (!results.isEmpty()) {
                try {
                    log.info("img.gen req model={} promptHash={}", model, grounded.hashCode());
                } catch (Exception ignore) {
                    // ignore any issues computing hash or logging
                }
                return results;
            }
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            String body = null;
            try { body = ex.getResponseBodyAsString(); } catch (Exception ignore) {}
            log.warn("OpenAI image API failed: status={} reason={} body={}",
                    ex.getStatusCode().value(), ex.getStatusText(),
                    body != null ? body.substring(0, Math.min(500, body.length())) : "(no-body)");
            // 프런트에 넘길 간단한 사유 저장
            try {
                com.example.lms.image.ImageMetaHolder.put(
                        "image.error",
                        "OPENAI_" + ex.getStatusCode().value() +
                                (body != null ? (": " + body.replaceAll("\\s+"," ").trim()) : "")
                );
            } catch (Exception ignore) {}
        } catch (Exception ex) {
            log.warn("Error calling OpenAI image API: {}", ex.toString());
            try {
                com.example.lms.image.ImageMetaHolder.put("image.error", "UNEXPECTED_ERROR: " + ex.getClass().getSimpleName());
            } catch (Exception ignore) {}
        } finally {
            // 메타데이터는 컨트롤러에서 reason을 읽은 후에 정리한다.
        }
        return java.util.Collections.emptyList();
    }

    /**
     * Internal representation of the OpenAI image API response.  Only
     * fields relevant to the plugin are captured here.  This nested
     * static class is intentionally kept private to avoid exposing it
     * outside the service and to prevent reuse in other parts of the
     * application.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private static class OpenAiImageResponse {
        private List<ImageData> data;

        public List<ImageData> getData() {
            return data;
        }

        public void setData(List<ImageData> data) {
            this.data = data;
        }

        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        private static class ImageData {
            private String url;
            private String b64_json;

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public String getB64_json() {
                return b64_json;
            }

            public void setB64_json(String b64_json) {
                this.b64_json = b64_json;
            }
        }
    }

    /**
     * Determine whether an API key is configured either via
     * {@link OpenAiImageProperties#apiKey()} or the {@code OPENAI_API_KEY}
     * environment variable.  This helper allows the controller to
     * short‑circuit requests when no credentials are available.
     *
     * @return true if an API key is present, false otherwise
     */
    public boolean isConfigured() {
        String k = properties.apiKey();
        if (k == null || k.isBlank()) {
            // Avoid direct environment access; fall back to system properties instead
            k = System.getProperty("OPENAI_API_KEY");
        }
        return k != null && !k.isBlank();
    }
}