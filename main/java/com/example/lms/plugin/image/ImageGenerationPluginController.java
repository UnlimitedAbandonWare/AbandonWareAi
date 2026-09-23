package com.example.lms.plugin.image;

import com.example.lms.plugin.image.jobs.ImageJob;
import com.example.lms.plugin.image.jobs.ImageJobOwnerKey;
import com.example.lms.plugin.image.jobs.ImageManifestWriter;
import com.example.lms.plugin.image.debug.ImageJobDebugAgent;
import com.example.lms.plugin.image.debug.ImageJobDebugLedger;
import com.example.lms.security.AdminTokenGuardInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;




/**
 * REST controller exposing the image generation plugin.  This controller
 * defines a single POST endpoint that accepts a prompt and returns
 * generated image URLs.  By isolating the plugin under its own URL
 * namespace it avoids clashing with existing controllers in the
 * application.  The controller delegates all work to
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
    private final com.example.lms.web.ClientOwnerKeyResolver ownerKeyResolver;
    private final AdminTokenGuardInterceptor adminTokenGuardInterceptor;
    private final ImageManifestWriter manifestWriter;

    @Autowired(required = false)
    private ImageJobDebugLedger debugLedger;

    @Value("${openai.image.sync.enabled:false}")
    private boolean syncGenerateEnabled;

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
        if (!syncGenerateEnabled) {
            recordDebug(null, ImageJobDebugAgent.CONFIG_SENTINEL,
                    "sync.generate.disabled", 0.90, 1.0, 0.0, "SYNC_GENERATE_DISABLED",
                    Map.of("syncGenerateEnabled", false));
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(ImageGenerationPluginResponse.error("SYNC_GENERATE_DISABLED"));
        }
        try {
            // If no API key is configured return a 400 with a reason code
            if (!imageService.isConfigured()) {
                recordDebug(null, ImageJobDebugAgent.CONFIG_SENTINEL,
                        "sync.generate.no_api_key", 0.95, 1.0, 0.0, "NO_API_KEY",
                        Map.of("apiKeyConfigured", false, "syncGenerateEnabled", true));
                return ResponseEntity.badRequest().body(ImageGenerationPluginResponse.error("NO_API_KEY"));
            }
            // Log a deterministic hash of the prompt for observability without leaking content
            try {
                log.info("image.generate promptHash={}", String.valueOf(
                        Objects.requireNonNullElse(request.getPrompt(), "").hashCode()));
            } catch (Exception ignore) {
                log.debug("image.generate prompt hash logging skipped errorType={}",
                        ignore.getClass().getSimpleName());
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
            log.debug("image.generate unexpected error skipped errorType={}",
                    ex.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ImageGenerationPluginResponse.error("UNEXPECTED_ERROR"));
        } finally {
            // reason을 읽은 뒤 항상 메타데이터를 정리한다.
            try {
                com.example.lms.image.ImageMetaHolder.clear();
            } catch (Exception ex) {
                log.debug("image.generate metadata cleanup skipped stage=meta_clear errorType={}",
                        ex.getClass().getSimpleName());
            }
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
            @RequestHeader(value = "X-Session-Id", required = false) String sid,
            HttpServletRequest request) {
        if (!imageService.isConfigured()) {
            recordDebug(null, ImageJobDebugAgent.CONFIG_SENTINEL,
                    "job.enqueue.rejected", 0.95, 1.0, 0.0, "NO_API_KEY",
                    Map.of("apiKeyConfigured", false));
            return ResponseEntity.badRequest().body(com.example.lms.plugin.image.ImageJobResponse.error("NO_API_KEY"));
        }
        String ownerKeyHash = ImageJobOwnerKey.hash(ownerKeyResolver.ownerKey());
        var job = jobService.enqueue(req.getPrompt(), "gpt-image-1", req.getSize(),
                effectiveSessionId(sid, request), ownerKeyHash);
        if (job == null || clean(job.getId()) == null) {
            recordDebug(null, ImageJobDebugAgent.VERDICT,
                    "job.enqueue.failed", 1.0, 1.0, 0.0, "IMAGE_JOB_ENQUEUE_FAILED",
                    Map.of("jobCreated", false));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(com.example.lms.plugin.image.ImageJobResponse.error("IMAGE_JOB_ENQUEUE_FAILED"));
        }
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
    public ResponseEntity<com.example.lms.plugin.image.ImageJobResponse> status(@PathVariable String id,
                                                                                HttpServletRequest request) {
        var job = jobRepo.findById(id).orElse(null);
        if (job == null) {
            return ResponseEntity.status(404).body(com.example.lms.plugin.image.ImageJobResponse.error("NOT_FOUND"));
        }
        if (!canAccess(job, request)) {
            recordDebug(job, ImageJobDebugAgent.ACCESS,
                    "status.access.denied", 0.95, 1.0, 0.0, "SESSION_MISMATCH",
                    Map.of("adminTokenPresented", adminTokenGuardInterceptor != null
                            && adminTokenGuardInterceptor.isPresentedTokenAuthorized(request)));
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(com.example.lms.plugin.image.ImageJobResponse.error("SESSION_MISMATCH"));
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
    public ResponseEntity<java.util.List<com.example.lms.plugin.image.ImageJobResponse>> history(@RequestParam String sessionId,
                                                                                                  HttpServletRequest request) {
        var jobs = jobRepo.findTop20BySessionIdOrderByCreatedAtDesc(sessionId);
        var visible = jobs.stream()
                .filter(j -> canAccess(j, request))
                .map(j -> com.example.lms.plugin.image.ImageJobResponse.of(j,
                        new com.example.lms.plugin.image.jobs.ImageJobService.Eta(0L, null)))
                .toList();
        if (!jobs.isEmpty() && visible.isEmpty()) {
            jobs.stream().findFirst().ifPresent(job -> recordDebug(job, ImageJobDebugAgent.ACCESS,
                    "history.access.denied", 0.95, 1.0, 0.0, "SESSION_MISMATCH",
                    Map.of("visibleCount", 0, "requestedSessionPresent", clean(sessionId) != null)));
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.List.of(com.example.lms.plugin.image.ImageJobResponse.error("SESSION_MISMATCH")));
        }
        return ResponseEntity.ok(visible);
    }

    @GetMapping("/jobs/{id}/manifest")
    public ResponseEntity<Map<String, Object>> manifest(@PathVariable String id, HttpServletRequest request) {
        var job = jobRepo.findById(id).orElse(null);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "NOT_FOUND"));
        }
        if (!canAccess(job, request)) {
            recordDebug(job, ImageJobDebugAgent.ACCESS,
                    "manifest.access.denied", 0.95, 1.0, 0.0, "SESSION_MISMATCH",
                    Map.of("manifest", true));
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "SESSION_MISMATCH"));
        }
        return ResponseEntity.ok(manifestWriter.read(job));
    }

    private boolean canAccess(ImageJob job, HttpServletRequest request) {
        if (job == null) {
            return false;
        }
        if (adminTokenGuardInterceptor != null && adminTokenGuardInterceptor.isPresentedTokenAuthorized(request)) {
            return true;
        }
        String ownerHash = clean(job.getOwnerKeyHash());
        if (ownerHash != null) {
            return ImageJobOwnerKey.matches(ownerHash, ownerKeyResolver.ownerKey());
        }
        return canAccessLegacySession(job.getSessionId(), request);
    }

    private boolean canAccessLegacySession(String ownerSessionId, HttpServletRequest request) {
        String owner = clean(ownerSessionId);
        if (owner == null) {
            return false;
        }
        HttpSession session = request == null ? null : request.getSession(false);
        return session != null && owner.equals(clean(session.getId()));
    }

    private static String effectiveSessionId(String headerSessionId, HttpServletRequest request) {
        String fromHeader = clean(headerSessionId);
        if (fromHeader != null) {
            return fromHeader;
        }
        HttpSession session = request == null ? null : request.getSession(true);
        String fromSession = session == null ? null : clean(session.getId());
        return fromSession == null ? "" : fromSession;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String s = value.replace('\u0000', ' ').trim();
        return s.isEmpty() ? null : s;
    }

    private void recordDebug(ImageJob job,
                             ImageJobDebugAgent agent,
                             String stage,
                             double severity,
                             double expected,
                             double observed,
                             String reason,
                             Map<String, Object> data) {
        ImageJobDebugLedger ledger = debugLedger;
        if (ledger == null) {
            return;
        }
        String jobId = job == null ? null : job.getId();
        if (clean(jobId) == null) {
            jobId = "controller:" + stage;
        }
        try {
            ledger.record(jobId, agent, stage, severity, expected, observed, reason, data);
        } catch (Exception ex) {
            log.debug("[AWX][image-debug] controller record skipped stage={} errorType={}",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                    ex.getClass().getSimpleName());
        }
    }
}
