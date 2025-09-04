package com.example.lms.plugin.image;

import com.example.lms.plugin.jobs.ImageJob;
import com.example.lms.plugin.jobs.ImageJobRepository;
import com.example.lms.plugin.jobs.ImageJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider; // ⭐ import 추가
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.lms.plugin.image.PolicyBlockException;

// JSON parsing for error bodies
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * REST controller exposing the image generation plugin.  This controller
 * defines a single POST endpoint that accepts a prompt and returns
 * generated image URLs.  By isolating the plugin under its own URL
 * namespace it avoids clashing with existing controllers in the
 * application.  The controller does not depend on any existing
 * gptapi or image components and delegates all work to
 * {@link OpenAiImageService}.
 */
@RestController
@RequestMapping("/api/image-plugin")
@RequiredArgsConstructor
public class ImageGenerationPluginController {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationPluginController.class);

    private static final ObjectMapper ERR_OM = new ObjectMapper();

    private final OpenAiImageService imageService;
    private final ImageJobService jobService;
    private final ImageJobRepository jobRepo;

    // [변경] GeminiImageService를 선택적으로 주입받도록 ObjectProvider 사용
    private final ObjectProvider<GeminiImageService> geminiImageService;

    /**
     * Generate images from the provided prompt.  Returns HTTP 200 with
     * a list of image URLs on success, 400 for invalid input and 500
     * when an error occurs.  Validation annotations ensure that a
     * missing or blank prompt is handled automatically by Spring.
     *
     * @param request the image generation request containing a prompt
     * @return a response containing generated image URLs
     */
    @PostMapping("/generate")
    public ResponseEntity<ImageGenerationPluginResponse> generateImage(
            @RequestBody @Valid ImageGenerationPluginRequest request,
            @RequestHeader(value = "X-Request-ID", required = false) String xReqId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        try {
            // If no API key is configured return a 400 with a reason code
            if (!imageService.isConfigured()) {
                return ResponseEntity.badRequest().body(ImageGenerationPluginResponse.error("NO_API_KEY"));
            }
            // Log a deterministic hash of the prompt for observability without leaking content
            try {
                log.info("image.generate promptHash={}", String.valueOf(
                        Objects.requireNonNullElse(request.getPrompt(), "").hashCode()));
            } catch (Exception ignore) {
                // ignore hash/logging issues
            }
            // Retrieve the prompt context associated with the session ID.  This
            // may be null when the session has not yet created a context.
            var ctx = com.example.lms.prompt.SessionPromptContextStore.get(sessionId);
            List<String> urls = imageService.generateImages(
                    request.getPrompt(),
                    request.getCount(),
                    request.getSize(),
                    ctx
            );
            // When no images are returned consider it a failure with a generic reason.
            if (urls == null || urls.isEmpty()) {
                String reqId = (xReqId == null || xReqId.isBlank()) ? "?" : xReqId;
                return ResponseEntity.ok(ImageGenerationPluginResponse.error(
                        "NO_IMAGE: 200 OK but empty/malformed body (x-request-id=" + reqId + ")"));
            }
            // Successful response
            return ResponseEntity.ok(ImageGenerationPluginResponse.ok(urls));
        } catch (DataBufferLimitException ex) {
            // The response payload exceeded the configured in‑memory buffer limit
            return ResponseEntity.ok(ImageGenerationPluginResponse.error("RESPONSE_TOO_LARGE"));
        } catch (PolicyBlockException pbe) {
            // Pre-flight policy guard blocked the prompt (copyrighted or disallowed terms)
            String msg = safePreview(pbe.getMessage(), 512);
            return ResponseEntity.ok(ImageGenerationPluginResponse.error("POLICY_BLOCK: " + msg));
        } catch (WebClientResponseException ex) {
            // Map OpenAI HTTP errors to stable reason codes.  The status code is
            // inspected directly without parsing the error body.  Include a
            // truncated preview and x‑request‑id when returning UNEXPECTED_ERROR.
            int sc = ex.getRawStatusCode();
            String reqId = (ex.getHeaders() != null && ex.getHeaders().getFirst("x-request-id") != null
                    && !ex.getHeaders().getFirst("x-request-id").isBlank())
                    ? ex.getHeaders().getFirst("x-request-id") : "?";
            String preview = safePreview(ex.getResponseBodyAsString(), 256);
            // Log for observability
            log.warn("openai.image.error status={} x-request-id={} preview={}", sc, reqId, maskSecrets(preview));
            if (sc == 401) {
                return ResponseEntity.ok(ImageGenerationPluginResponse.error("NO_API_KEY"));
            } else if (sc == 403) {
                return ResponseEntity.ok(ImageGenerationPluginResponse.error("AUTH_INVALID"));
            } else if (sc == 429) {
                return ResponseEntity.ok(ImageGenerationPluginResponse.error("RATE_LIMIT"));
            } else if (sc == 400) {
                // Inspect the error body for model and parameter errors to improve client feedback
                String body = ex.getResponseBodyAsString();
                String reason = "INVALID_PARAM";
                try {
                    JsonNode root = ERR_OM.readTree(body);
                    String code = root.path("error").path("code").asText("");
                    String msg  = root.path("error").path("message").asText("");
                    if (code.contains("invalid_model") || msg.toLowerCase().contains("model")) {
                        reason = "INVALID_MODEL";
                    }
                } catch (Exception ignore) {
                    // ignore parse failures; fallback to INVALID_PARAM
                }
                return ResponseEntity.ok(ImageGenerationPluginResponse.error(reason));
            }
            return ResponseEntity.ok(ImageGenerationPluginResponse.error("UNEXPECTED_ERROR: " + preview + " (x-request-id=" + reqId + ")"));
        } catch (Exception ex) {
            // Catch any unexpected exceptions to avoid leaking details
            return ResponseEntity.ok(ImageGenerationPluginResponse.error("UNEXPECTED_ERROR"));
        } finally {
            // Clear any image metadata written by the service
            try { com.example.lms.image.ImageMetaHolder.clear(); } catch (Exception ignore) {}
        }
    }

    // --- Gemini 전용 엔드포인트: 텍스트 → 이미지 생성 (수정됨) ---
    @PostMapping("/gemini/generate")
    public ResponseEntity<ImageGenerationPluginResponse> geminiGenerate(
            @RequestBody @Valid ImageGenerationPluginRequest req) {
        // ObjectProvider를 통해 서비스 빈을 안전하게 가져옴
        GeminiImageService gemini = geminiImageService.getIfAvailable();

        // 서비스가 없거나(null) 설정이 올바르지 않으면 에러 응답
        if (gemini == null || !gemini.isConfigured()) {
            return ResponseEntity.badRequest().body(ImageGenerationPluginResponse.error("NO_API_KEY"));
        }
        var urls = gemini.generate(req.getPrompt(), req.getCount(), req.getSize());
        String reason = (urls == null || urls.isEmpty()) ? "NO_IMAGE" : "OK";
        return ResponseEntity.ok(ImageGenerationPluginResponse.success(urls, reason));
    }

    // --- Gemini 전용 엔드포인트: 이미지 편집 (수정됨) ---
    @PostMapping("/gemini/edit")
    public ResponseEntity<ImageGenerationPluginResponse> geminiEdit(
            @RequestBody @Valid GeminiImageEditRequest req) {
        // ObjectProvider를 통해 서비스 빈을 안전하게 가져옴
        GeminiImageService gemini = geminiImageService.getIfAvailable();

        // 서비스가 없거나(null) 설정이 올바르지 않으면 에러 응답
        if (gemini == null || !gemini.isConfigured()) {
            return ResponseEntity.badRequest().body(ImageGenerationPluginResponse.error("NO_API_KEY"));
        }
        var urls = gemini.edit(req.getPrompt(), req.getImageBase64(), req.getMimeType());
        String reason = (urls == null || urls.isEmpty()) ? "NO_IMAGE" : "OK";
        return ResponseEntity.ok(ImageGenerationPluginResponse.success(urls, reason));
    }

    /**
     * Enqueue an asynchronous image generation request.  When the API key
     * is missing a 400 with a NO_API_KEY reason is returned.  The
     * optional {@code X-Session-Id} header associates the job with a
     * client session to support history queries.  The response includes
     * both the job metadata and an ETA estimate.
     */
    @PostMapping("/jobs")
    public ResponseEntity<ImageJobResponse> enqueue(
            @RequestBody @Valid ImageGenerationPluginRequest req,
            @RequestHeader(value = "X-Session-Id", required = false) String sid) {
        if (!imageService.isConfigured()) {
            return ResponseEntity.badRequest().body(ImageJobResponse.error("NO_API_KEY"));
        }
        var job = jobService.enqueue(req.getPrompt(), "gpt-image-1", req.getSize(), sid);
        var eta = jobService.estimate(job.getId());
        return ResponseEntity.ok(ImageJobResponse.of(job, eta));
    }

    /**
     * Retrieve the current status of an asynchronous image generation job.
     * When the job has completed the ETA is zero and no expected ready
     * timestamp is returned.  A 404 is returned when the job ID is not
     * found.
     */
    @GetMapping("/jobs/{id}")
    public ResponseEntity<ImageJobResponse> status(@PathVariable String id) {
        var job = jobRepo.findById(id).orElse(null);
        if (job == null) {
            return ResponseEntity.status(404).body(ImageJobResponse.error("NOT_FOUND"));
        }
        var eta = (job.getStatus() == ImageJob.Status.SUCCEEDED ||
                job.getStatus() == ImageJob.Status.FAILED)
                ? new ImageJobService.Eta(0L, null)
                : jobService.estimate(id);
        return ResponseEntity.ok(ImageJobResponse.of(job, eta));
    }

    /**
     * Return up to 20 most recent jobs associated with the provided
     * session ID.  The session ID must be supplied as a query parameter.
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<ImageJobResponse>> history(@RequestParam String sessionId) {
        var list = jobRepo.findTop20BySessionIdOrderByCreatedAtDesc(sessionId).stream()
                .map(j -> ImageJobResponse.of(j,
                        new ImageJobService.Eta(0L, null)))
                .toList();
        return ResponseEntity.ok(list);
    }

    /**
     * Helper to get text value or null from a JsonNode.
     */
    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText(null);
    }

    /**
     * Null-safe string (returns empty string when null).
     */
    private static String nullSafe(String s) { return s == null ? "" : s; }

    /**
     * Clamp a string to a maximum length and append ellipsis when truncated.
     */
    private static String safePreview(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    /**
     * Mask API keys or bearer tokens that may appear in logs.
     */
    private static String maskSecrets(String s) {
        if (s == null) return null;
        return s.replaceAll("(?i)sk-[a-z0-9-_]{16,}", "sk-***");
    }
}