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

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;

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
     * Inject the OpenAI API key from configuration.  This allows the
     * service to reapply the bearer token at request time when necessary.
     * The empty default ensures that a missing property does not break
     * bean construction but the fail‑fast check in WebClientConfig will
     * prevent misconfigured deployments.
     */
    @Value("${openai.api.key:}")
    private String openAiApiKey;

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
        // Delegate to generateImages with no contextual PromptContext.
        return generateImages(prompt, count, size, null);
    }

    /**
     * Generate one or more images using the OpenAI image API.  When the
     * provided API key is missing or blank the method returns an empty
     * list.  This overloaded method accepts a PromptContext which is
     * forwarded to the GroundedImagePromptBuilder when present.
     *
     * @param prompt the textual description of the desired image
     * @param count  number of images to generate
     * @param size   requested image size (e.g. 512x512)
     * @param ctx    optional PromptContext for grounding the prompt
     * @return a list of URLs pointing to the generated images or an empty list on failure
     */
    public List<String> generateImages(String prompt, int count, String size, com.example.lms.prompt.PromptContext ctx) {
        // Determine the API key from configuration or environment.  Prefer the
        // value defined in properties; fall back to system property and
        // environment variable in order.  If no key is found return an
        // empty list to signal a configuration error.
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("openai.api.key");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENAI_API_KEY");
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
                grounded = groundedImagePromptBuilder.build(raw, ctx);
            }
        } catch (Exception e) {
            // Log and continue with the raw prompt; grounding is best‑effort
            log.debug("generateImages: grounding prompt failed", e);
        }
        // Override grounded prompt, model and size from chain metadata when
        // available.  The RAG chain writes image.* keys into ImageMetaHolder via
        // DefaultChainContext.putMeta().  Use these annotations to influence
        // the downstream API call.  When no metadata is present the existing
        // grounded prompt and defaults apply.
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
        // Determine default model: prefer value from properties; fallback to "dall-e-3"
        String modelDefault = (properties.model() != null && !properties.model().isBlank())
                ? properties.model()
                : "dall-e-3";
        String model;
        try {
            finalSize = ImageMetaHolder.getOrDefault("image.size", defaultSize);
            model = ImageMetaHolder.getOrDefault("image.model", modelDefault);
        } catch (Exception ex) {
            // If metadata retrieval fails fall back to defaults
            finalSize = defaultSize;
            model = modelDefault;
        }
        // --- Prompt hygiene & safety pre-check ---------------------------------
        String clean = sanitizePrompt(grounded);
        // Optional: block obviously copyrighted character direct mentions
        if (containsBlockedProperNoun(clean)) {
            throw new PolicyBlockException("Request references a copyrighted character explicitly. Remove proper nouns and describe attributes instead.");
        }
        // Clamp prompt length to 800 chars
        if (clean.length() > 800) clean = clean.substring(0, 800);
        // Clamp the number of images between 1 and 4
        int numImages = Math.min(4, Math.max(1, count));

        // Build payload using a mutable map to allow optional parameters.
        // (선택) Always specify response_format to avoid the OpenAI default of b64_json which
        // returns a large base64 encoded payload.  We default to 'url' to
        // receive image URLs.  If additional formats are needed in future
        // they can be configured via configuration properties.
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("model", model);
        payload.put("prompt", clean);
        // use the safe numImages variable to avoid local variable shadowing
        payload.put("n", numImages);
        payload.put("size", finalSize);
        // payload.put("response_format", "url"); // (선택) URL만 필요 시
        // Note: do not specify response_format here.  The OpenAI API
        // may reject unknown parameters, and omitting response_format
        // allows the server to choose its default while still supporting
        // both URL and b64_json returns.  If future versions require
        // response_format again, this can be configured via properties.
        // Execute the HTTP call using the dedicated OpenAI WebClient.  Always consume the body
        // and report any client/server error statuses via onStatus.  A doOnCancel handler logs
        // subscription cancellations (e.g. receiver cancelled) before body consumption.
        reactor.core.publisher.Mono<String> bodyMono = openaiWebClient.post()
                .uri(properties.endpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                // Reapply the bearer token if another component removed it from the request.
                .headers(h -> {
                    if (openAiApiKey != null && !openAiApiKey.isBlank()
                            && !h.containsKey(HttpHeaders.AUTHORIZATION)) {
                        h.setBearerAuth(openAiApiKey);
                    }
                })
                .bodyValue(payload)
                .retrieve()
                // Propagate HTTP error statuses via the default WebClient exception type.  This
                // preserves the status code and response body for the controller to map.
                .onStatus(org.springframework.http.HttpStatusCode::isError, resp -> resp.createException())
                .bodyToMono(String.class)
                .doOnCancel(() -> log.error("openai.image.cancelled before body consumption"));
        String body = bodyMono.block(java.time.Duration.ofSeconds(240));
        if (body == null || body.isBlank()) {
            log.warn("openai.image.parse.empty status=200 x-request-id={} bodyLen={} preview={}",
                    "N/A", -1, "null");
            return java.util.Collections.emptyList();
        }
        // Parse the JSON response using the shared mapper.  The OpenAI image API
// returns a top-level object with a "data" array and additional fields.
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 잘못된 JSON이면 사용자에게 빈 결과를 돌려주고 로그만 남깁니다.
            // (과도한 로그 폭주 방지 차원에서 본문은 길이 제한)
            String preview = body.length() > 512 ? body.substring(0, 512) + "...(truncated)" : body;
            log.warn("OpenAI image API returned non-JSON or malformed body: {}", preview, e);
            return java.util.Collections.emptyList();
        }
        java.util.List<String> results = new java.util.ArrayList<>();
        JsonNode dataNode = root.path("data");
        if (dataNode.isArray()) {
            for (JsonNode item : dataNode) {
                // Prefer a URL when present
                if (item.hasNonNull("url")) {
                    String u = item.get("url").asText();
                    if (u != null && !u.isBlank()) {
                        results.add(u);
                        continue;
                    }
                }
                // Fall back to b64_json encoded PNGs.  Convert these into data URI
                // strings so the front‑end can display them directly.
                if (item.hasNonNull("b64_json")) {
                    String b64 = item.get("b64_json").asText();
                    if (b64 != null && !b64.isBlank()) {
                        results.add(String.format("data:image/png;base64,%s", b64));
                    }
                }
            }
        }
        if (!results.isEmpty()) {
            try {
                log.info("img.gen req model={} promptHash={}", model, clean.hashCode());
            } catch (Exception ignore) {
                // ignore any issues computing hash or logging
            }
        } else {
            String preview = body.substring(0, Math.min(1024, body.length()));
            String reqId = "N/A";
            log.warn("openai.image.parse.empty status=200 x-request-id={} bodyLen={} preview={}",
                    reqId, body.length(), preview);
            return java.util.Collections.emptyList();
        }
        return results;
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
            k = System.getProperty("openai.api.key");
        }
        if (k == null || k.isBlank()) {
            k = System.getenv("OPENAI_API_KEY");
        }
        return k != null && !k.isBlank();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Sanitize noisy prompts: convert <EOL>, strip system/template lines and /imagine prefix.
     */
    private static String sanitizePrompt(String in) {
        if (in == null) return "";
        String s = in.replace("\r", "");
        // Convert <EOL> tokens (case-insensitive) to real newlines
        s = s.replaceAll("(?i)<EOL>", "\n");
        // Remove leading "/imagine " command if present
        s = s.replaceFirst("(?s)^(?i)/imagine\\s+", "");
        // Drop lines that start with template/system noise
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("You are ")) continue;           // e.g. You are an image generation model.
            if (t.startsWith("System:")) continue;            // System: ...
            if (t.startsWith("###") || t.startsWith("##") || t.startsWith("#")) continue; // markdown headers
            sb.append(line).append('\n');
        }
        String out = sb.toString().trim();
        return out;
    }

    /**
     * Very small allowlist-based guard to catch direct copyrighted character mentions.
     * This is intentionally simple and can be expanded via configuration if needed.
     */
    private static boolean containsBlockedProperNoun(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        String[] banned = new String[]{
                "작안의 샤나", "샤나 ", "shakugan no shana", "shana ",
                "명탐정 코난", "코난 ", "detective conan",
                "미키 마우스", "mickey mouse",
                "원피스", "one piece",
                "나루토", "naruto",
                "드래곤볼", "dragon ball",
                "포켓몬", "pokemon"
        };
        for (String k : banned) {
            if (lower.contains(k.trim())) return true;
        }
        return false;
    }
}