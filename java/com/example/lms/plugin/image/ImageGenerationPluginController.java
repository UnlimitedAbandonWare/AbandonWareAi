package com.example.lms.plugin.image;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;
import java.util.Objects;




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
@ConditionalOnProperty(prefix = "openai.image", name = "enabled", havingValue = "true")
@RequestMapping("/api/image-plugin")
@RequiredArgsConstructor
public class ImageGenerationPluginController {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationPluginController.class);

    private final OpenAiImageService imageService;
    private final com.example.lms.plugin.image.jobs.ImageJobService jobService;
    private final com.example.lms.plugin.image.jobs.ImageJobRepository jobRepo;

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
            @RequestBody @Valid ImageGenerationPluginRequest request) {
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
            List<String> urls = imageService.generateImages(
                    request.getPrompt(),
                    request.getCount(),
                    request.getSize()
            );
            String reason = (urls == null || urls.isEmpty())
                    ? java.util.Objects.requireNonNullElse(
                            com.example.lms.image.ImageMetaHolder.get("image.error"),
                            "EMPTY_BODY_OR_CANCELLED"
                    )
                    : null;
            return ResponseEntity.ok(new ImageGenerationPluginResponse(urls, reason, null, null));
        } catch (Exception ex) {
            // Catch any unexpected exceptions to avoid leaking details
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ImageGenerationPluginResponse.error("UNEXPECTED_ERROR"));
        } finally {
            // reason을 읽은 뒤 항상 메타데이터를 정리한다.
            try { com.example.lms.image.ImageMetaHolder.clear(); } catch (Exception ignore) {}
        }
    }

    /**
     * Enqueue an asynchronous image generation request.  When the API key
     * is missing a 400 with a NO_API_KEY reason is returned.  The
     * optional {@code X-Session-Id} header associates the job with a
     * client session to support history queries.  The response includes
     * both the job metadata and an ETA estimate.
     */
    @PostMapping("/jobs")
    public ResponseEntity<com.example.lms.plugin.image.ImageJobResponse> enqueue(
            @RequestBody @Valid ImageGenerationPluginRequest req,
            @RequestHeader(value = "X-Session-Id", required = false) String sid) {
        if (!imageService.isConfigured()) {
            return ResponseEntity.badRequest().body(com.example.lms.plugin.image.ImageJobResponse.error("NO_API_KEY"));
        }
        var job = jobService.enqueue(req.getPrompt(), "gpt-image-1", req.getSize(), sid);
        var eta = jobService.estimate(job.getId());
        return ResponseEntity.ok(com.example.lms.plugin.image.ImageJobResponse.of(job, eta));
    }

    /**
     * Retrieve the current status of an asynchronous image generation job.
     * When the job has completed the ETA is zero and no expected ready
     * timestamp is returned.  A 404 is returned when the job ID is not
     * found.
     */
    @GetMapping("/jobs/{id}")
    public ResponseEntity<com.example.lms.plugin.image.ImageJobResponse> status(@PathVariable String id) {
        var job = jobRepo.findById(id).orElse(null);
        if (job == null) {
            return ResponseEntity.status(404).body(com.example.lms.plugin.image.ImageJobResponse.error("NOT_FOUND"));
        }
        var eta = (job.getStatus() == com.example.lms.plugin.image.jobs.ImageJob.Status.SUCCEEDED ||
                job.getStatus() == com.example.lms.plugin.image.jobs.ImageJob.Status.FAILED)
                ? new com.example.lms.plugin.image.jobs.ImageJobService.Eta(0L, null)
                : jobService.estimate(id);
        return ResponseEntity.ok(com.example.lms.plugin.image.ImageJobResponse.of(job, eta));
    }

    /**
     * Return up to 20 most recent jobs associated with the provided
     * session ID.  The session ID must be supplied as a query parameter.
     */
    @GetMapping("/jobs")
    public ResponseEntity<java.util.List<com.example.lms.plugin.image.ImageJobResponse>> history(@RequestParam String sessionId) {
        var list = jobRepo.findTop20BySessionIdOrderByCreatedAtDesc(sessionId).stream()
                .map(j -> com.example.lms.plugin.image.ImageJobResponse.of(j,
                        new com.example.lms.plugin.image.jobs.ImageJobService.Eta(0L, null)))
                .toList();
        return ResponseEntity.ok(list);
    }
}