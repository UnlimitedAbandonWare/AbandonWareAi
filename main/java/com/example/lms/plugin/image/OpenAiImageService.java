package com.example.lms.plugin.image;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.image.ImageMetaHolder;
import com.example.lms.plugin.image.storage.FileSystemImageStorage;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "openai.image", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class OpenAiImageService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OpenAiImageProperties properties;
    private final com.example.lms.image.GroundedImagePromptBuilder groundedImagePromptBuilder;

    @Qualifier("openaiWebClient")
    private final WebClient openaiWebClient;

    private final ObjectProvider<FileSystemImageStorage> imageStorageProvider;

    public List<String> generateImages(String prompt, int count, String size) {
        String apiKey = configuredApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            traceProviderDisabled("missing_openai_api_key");
            log.warn("[AWX][image][openai] provider=OpenAI enabled=false disabledReason=missing_openai_api_key");
            return List.of();
        }

        String grounded = groundedPrompt(prompt);
        String defaultSize = (size == null || size.isBlank()) ? "1024x1024" : size;
        String finalSize = metaOrDefault("image.size", defaultSize);
        String model = metaOrDefault("image.model", "gpt-image-1");

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("prompt", grounded);
            payload.put("size", finalSize);
            addOptionalImageMeta(payload, "quality", "image.quality");
            addOptionalImageMeta(payload, "background", "image.background");

            ExchangeStrategies strategies = ExchangeStrategies.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                    .build();
            WebClient wc = openaiWebClient.mutate().exchangeStrategies(strategies).build();

            String body = wc.post()
                    .uri((properties.endpoint() == null || properties.endpoint().isBlank()) ? "/v1/images" : properties.endpoint())
                    .header(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", apiKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchangeToMono(resp -> resp.bodyToMono(String.class).flatMap(bodyStr -> {
                        int status = resp.statusCode().value();
                        if (resp.statusCode().isError()) {
                            recordProviderBodyError("HTTP_" + status, bodyStr, requestId(resp.headers().asHttpHeaders()));
                            return Mono.error(new IllegalStateException("HTTP " + status));
                        }
                        String contentType = resp.headers().contentType().map(MediaType::toString).orElse("");
                        if (!contentType.contains("application/json")) {
                            putMeta("image.error", "NON_JSON_200:" + contentType);
                            return Mono.error(new IllegalStateException("Non-JSON 200 from provider: " + contentType));
                        }
                        if (bodyStr != null && bodyStr.contains("\"error\"")) {
                            recordProviderBodyError("ERROR_BODY_200", bodyStr, requestId(resp.headers().asHttpHeaders()));
                            return Mono.error(new IllegalStateException("Provider error with 200"));
                        }
                        String rid = requestId(resp.headers().asHttpHeaders());
                        if (rid != null) {
                            log.debug("openai.images ridHash={}", SafeRedactor.hashValue(rid));
                        }
                        return Mono.just(bodyStr);
                    }))
                    .timeout(Duration.ofSeconds(240))
                    .onErrorResume(DataBufferLimitException.class,
                            e -> Mono.error(new IllegalStateException("Image JSON too large (increase maxInMemorySize)")))
                    .block();

            if (body == null || body.isBlank()) {
                log.warn("OpenAI image API returned empty body");
                putMeta("image.error", "EMPTY_BODY_OR_CANCELLED");
                return List.of();
            }

            List<String> results = parseResults(body);
            if (results.isEmpty()) {
                putMeta("image.error", "EMPTY_DATA");
                return List.of();
            }

            log.info("img.gen req modelHash={} modelLength={} promptHash={}",
                    SafeRedactor.hashValue(model), model == null ? 0 : model.length(), SafeRedactor.hashValue(grounded));
            return results;
        } catch (WebClientResponseException ex) {
            log.debug("[AWX][image][openai] response exception caught errorType={}",
                    ex.getClass().getSimpleName());
            String body = null;
            try {
                body = ex.getResponseBodyAsString();
            } catch (Exception bodyError) {
                traceTelemetrySkipped("response_body", bodyError);
            }
            String rid = requestId(ex.getHeaders());
            int status = ex.getStatusCode().value();
            log.warn("OpenAI image API failed: status={} reason={} ridHash={} bodyHash={} bodyLength={}",
                    status,
                    ex.getStatusText(),
                    SafeRedactor.hashValue(rid),
                    SafeRedactor.hashValue(body),
                    body == null ? 0 : body.length());
            recordProviderBodyError("OPENAI_" + status, body, rid);
        } catch (Exception ex) {
            log.warn("Error calling OpenAI image API: type={}", ex.getClass().getSimpleName());
            putMeta("image.error", "UNEXPECTED_ERROR: " + ex.getClass().getSimpleName());
        }
        return List.of();
    }

    private String groundedPrompt(String prompt) {
        String grounded = prompt == null ? "" : prompt;
        try {
            if (groundedImagePromptBuilder != null) {
                grounded = groundedImagePromptBuilder.build(grounded, null);
            }
        } catch (Exception ex) {
            log.debug("generateImages: grounding prompt failed errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
        }
        String metaPrompt = ImageMetaHolder.get("image.prompt");
        return (metaPrompt == null || metaPrompt.isBlank()) ? grounded : metaPrompt;
    }

    private static String metaOrDefault(String key, String fallback) {
        try {
            return ImageMetaHolder.getOrDefault(key, fallback);
        } catch (Exception ex) {
            traceTelemetrySkipped("meta_or_default", ex);
            return fallback;
        }
    }

    private static void addOptionalImageMeta(Map<String, Object> payload, String payloadKey, String metaKey) {
        try {
            String value = ImageMetaHolder.get(metaKey);
            if (value != null && !value.isBlank()) {
                payload.put(payloadKey, value);
            }
        } catch (Exception metaError) {
            traceTelemetrySkipped("meta_optional", metaError);
        }
    }

    private List<String> parseResults(String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode dataNode = root.path("data");
        if (!dataNode.isArray()) {
            return List.of();
        }

        List<String> results = new ArrayList<>();
        for (JsonNode node : dataNode) {
            if (node.hasNonNull("url")) {
                String url = node.get("url").asText();
                if (url != null && !url.isBlank()) {
                    results.add(url);
                    continue;
                }
            }
            if (node.hasNonNull("b64_json")) {
                String b64 = node.get("b64_json").asText();
                if (b64 != null && !b64.isBlank()) {
                    FileSystemImageStorage storage = imageStorageProvider.getIfAvailable();
                    if (storage == null) {
                        putMeta("image.error", "INLINE_BASE64_STORAGE_UNAVAILABLE");
                        continue;
                    }
                    results.add(storage.saveBase64Png(b64, "openai-image").publicUrl());
                }
            }
        }
        return results;
    }

    private String configuredApiKey() {
        String configured = properties.apiKey();
        if (configured != null && !configured.isBlank()) {
            return usableKey(configured);
        }
        String key = usableKey(System.getenv("OPENAI_API_KEY"));
        if (key == null) {
            key = usableKey(System.getProperty("OPENAI_API_KEY"));
        }
        return key;
    }

    private static String usableKey(String key) {
        return ConfigValueGuards.isMissing(key) ? null : key.trim();
    }

    private static String requestId(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        try {
            return headers.getFirst("x-request-id");
        } catch (Exception ex) {
            traceTelemetrySkipped("request_id_header", ex);
            return null;
        }
    }

    private static void recordProviderBodyError(String code, String body, String rid) {
        putMeta("image.error", code);
        putMeta("image.error.bodyHash", SafeRedactor.hashValue(body));
        putMeta("image.error.bodyLength", String.valueOf(body == null ? 0 : body.length()));
        if (rid != null && !rid.isBlank()) {
            putMeta("image.error.ridHash", SafeRedactor.hashValue(rid));
        }
    }

    private static void putMeta(String key, String value) {
        try {
            ImageMetaHolder.put(key, value);
        } catch (Exception metaError) {
            traceTelemetrySkipped("meta_put", metaError);
        }
    }

    private static void traceProviderDisabled(String reason) {
        try {
            TraceStore.put("image.openai.providerDisabled", true);
            TraceStore.put("image.openai.disabledReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (RuntimeException traceFailure) {
            log.debug("[AWX][image][openai] provider-disabled trace failed errorType={}", errorType(traceFailure));
        }
    }

    public boolean isConfigured() {
        String key = configuredApiKey();
        return key != null && !key.isBlank();
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }

    private static void traceTelemetrySkipped(String stage, Throwable error) {
        log.debug("[AWX][image][openai] telemetry skipped stage={} errorType={}",
                stage, errorType(error));
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
