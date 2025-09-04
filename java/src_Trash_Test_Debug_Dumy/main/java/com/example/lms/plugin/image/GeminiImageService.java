package com.example.lms.plugin.image;

import com.example.lms.image.ImageMetaHolder;
import com.example.lms.plugin.storage.FileSystemImageStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils; // ⭐ import 추가
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gemini 이미지 API와 통합하는 서비스입니다. 이 서비스는 OpenAiImageService의 동작을
 * 미러링하지만 Gemini API를 대상으로 하며 이미지를 Base64로 인코딩하여 반환합니다.
 * API 키와 엔드포인트는 GeminiImageProperties를 통해 제공됩니다.
 * 실패 시 서비스는 빈 목록을 반환하고 경고를 기록합니다.
 *
 * 이 서비스는 'gemini.image.enabled=true'일 때만 활성화됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "gemini.image", name = "enabled", havingValue = "true")
public class GeminiImageService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GeminiImageProperties props;
    private final FileSystemImageStorage storage;
    @Qualifier("geminiWebClient")
    private final WebClient gemini;
    private final com.example.lms.image.GroundedImagePromptBuilder groundedImagePromptBuilder;

    /**
     * 서비스가 동작하는 데 필요한 필수 프로퍼티(endpoint, apiKey)가
     * 올바르게 설정되었는지 확인합니다.
     * @return 설정이 올바르면 true, 아니면 false
     */
    public boolean isConfigured() {
        // 'enabled=true'로 빈이 생성된 이후에도, 세부 설정값이 유효한지 검사
        return props != null
                && StringUtils.hasText(props.getEndpoint())
                && StringUtils.hasText(props.getApiKey());
    }

    /**
     * Gemini API를 호출할 때 사용할 모델 이름을 결정합니다.
     * 프로퍼티에 모델이 지정되지 않은 경우 적절한 기본값이 반환됩니다.
     */
    private String model() {
        return (props.getModel() == null || props.getModel().isBlank())
                ? "gemini-2.5-flash-image-preview"
                : props.getModel();
    }


    public List<String> generate(String prompt, int count, String sizeHint) {
        if (!isConfigured() || prompt == null || prompt.isBlank()) {
            log.warn("GeminiImageService is not configured or prompt is empty. Skipping generation.");
            return List.of();
        }
        String grounded = prompt;
        try {
            if (groundedImagePromptBuilder != null) {
                grounded = groundedImagePromptBuilder.build(prompt, null);
            }
            if (sizeHint != null && !sizeHint.isBlank()) {
                grounded += " (" + sizeHint + ")";
            } else {
                grounded += " (Square image)";
            }
            String metaPrompt = ImageMetaHolder.get("image.prompt");
            if (metaPrompt != null && !metaPrompt.isBlank()) {
                grounded = metaPrompt;
            }
        } catch (Exception ignore) {
            // grounding 실패 시 원본 프롬프트로 진행
        }
        int n = Math.max(1, Math.min(4, count));
        List<String> urls = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", grounded))
                    ))
            );
            String path = "/v1beta/models/" + model() + ":generateContent";
            try {
                String b64 = gemini.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .flatMap(this::firstInlineDataBase64)
                        .toFuture()
                        .get();
                if (b64 != null && !b64.isBlank()) {
                    var stored = storage.saveBase64Png(b64, "gemini_" + Math.abs(grounded.hashCode()));
                    urls.add(stored.publicUrl());
                }
            } catch (Exception e) {
                log.warn("Gemini image.generate failed", e);
            }
        }
        return urls;
    }


    public List<String> edit(String prompt, String srcB64, String mime) {
        if (!isConfigured() || srcB64 == null || srcB64.isBlank()) {
            log.warn("GeminiImageService is not configured or source image is empty. Skipping edit.");
            return List.of();
        }
        String grounded = prompt == null ? "" : prompt;
        String mm = (mime == null || mime.isBlank()) ? "image/png" : mime;
        var parts = List.of(
                Map.of("text", grounded),
                Map.of("inline_data", Map.of(
                        "mime_type", mm,
                        "data", srcB64
                ))
        );
        Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", parts)));
        String path = "/v1beta/models/" + model() + ":generateContent";
        try {
            String b64 = gemini.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .flatMap(this::firstInlineDataBase64)
                    .toFuture()
                    .get();
            if (b64 != null && !b64.isBlank()) {
                var stored = storage.saveBase64Png(b64, "gemini_edit_" + Math.abs(grounded.hashCode()));
                return List.of(stored.publicUrl());
            }
        } catch (Exception e) {
            log.warn("Gemini image.edit failed", e);
        }
        return List.of();
    }

    /**
     * 원시 JSON 응답을 파싱하고 'inline_data.data' 필드에 포함된 첫 번째 Base64 문자열을 반환합니다.
     */
    private Mono<String> firstInlineDataBase64(String raw) {
        try {
            JsonNode root = MAPPER.readTree(raw);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            for (JsonNode p : parts) {
                JsonNode id = p.path("inline_data").path("data");
                if (!id.isMissingNode() && !id.asText("").isBlank()) {
                    return Mono.just(id.asText());
                }
            }
        } catch (Exception ignore) {
            // 파싱 오류 무시
        }
        return Mono.empty();
    }
}