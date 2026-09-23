package com.example.lms.plugin.image.debug;

import com.example.lms.plugin.image.OpenAiImageService;
import com.example.lms.plugin.image.jobs.ImageJob;
import com.example.lms.plugin.image.jobs.ImageJobOwnerKey;
import com.example.lms.plugin.image.jobs.ImageJobRepository;
import com.example.lms.plugin.image.jobs.ImageJobService;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.web.ClientOwnerKeyResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/image")
@RequiredArgsConstructor
public class ImageJobDebugController {
    private static final Logger log = LoggerFactory.getLogger(ImageJobDebugController.class);

    private final ImageJobRepository jobRepo;
    private final ObjectProvider<ImageJobService> jobService;
    private final ImageJobDebugLedger ledger;
    private final Environment env;
    private final ObjectProvider<OpenAiImageService> imageService;
    private final ClientOwnerKeyResolver ownerKeyResolver;
    private final AdminTokenGuardInterceptor adminTokenGuardInterceptor;

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> out = configSnapshot(false);
        out.put("nextAction", configNextAction(out));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/jobs/{id}/debug")
    public ResponseEntity<Map<String, Object>> debug(@PathVariable String id,
                                                     HttpServletRequest request) {
        ImageJob job = jobRepo.findById(id).orElse(null);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
        }
        if (!canAccess(job, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "SESSION_MISMATCH"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", jobSnapshot(job));
        out.put("eta", etaSnapshot(id));
        out.put("config", configSnapshot(true));
        out.put("ledger", ledger.snapshot(id));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/jobs/{id}/attempts/score")
    public ResponseEntity<Map<String, Object>> scoreAttempt(@PathVariable String id,
                                                            @RequestBody AttemptScoreRequest request,
                                                            HttpServletRequest servletRequest) {
        ImageJob job = jobRepo.findById(id).orElse(null);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
        }
        if (!canAccess(job, servletRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "SESSION_MISMATCH"));
        }
        AttemptScoreRequest safe = request == null ? new AttemptScoreRequest(null, null, null, null, null, null) : request;
        Map<String, Object> result = ledger.recordAttemptScore(
                id,
                safe.attemptId(),
                safe.changeKey(),
                finite(safe.beforeScore()),
                finite(safe.afterScore()),
                finite(safe.regressionPenalty()),
                safe.note());
        return ResponseEntity.ok(result);
    }

    public record AttemptScoreRequest(
            String attemptId,
            String changeKey,
            Double beforeScore,
            Double afterScore,
            Double regressionPenalty,
            String note
    ) {
    }

    private Map<String, Object> jobSnapshot(ImageJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idHash", SafeRedactor.hashValue(job.getId()));
        out.put("idLength", job.getId() == null ? 0 : job.getId().length());
        out.put("status", job.getStatus() == null ? "UNKNOWN" : job.getStatus().name());
        out.put("progress", job.getProgress());
        out.put("reason", SafeRedactor.traceLabelOrFallback(job.getReason(), "unknown"));
        out.put("createdAt", job.getCreatedAt());
        out.put("startedAt", job.getStartedAt());
        out.put("completedAt", job.getCompletedAt());
        out.put("durationMs", job.getDurationMs());
        out.put("publicUrlPresent", job.getPublicUrl() != null && !job.getPublicUrl().isBlank());
        out.put("manifestUrlPresent", job.getManifestUrl() != null && !job.getManifestUrl().isBlank());
        out.put("artifactRefPresent", job.getArtifactHash() != null && !job.getArtifactHash().isBlank());
        out.put("ownerKeyHashPresent", job.getOwnerKeyHash() != null && !job.getOwnerKeyHash().isBlank());
        out.put("bytes", job.getBytes());
        out.put("contentType", SafeRedactor.traceLabelOrFallback(job.getContentType(), "unknown"));
        return out;
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
            return ownerKeyResolver != null && ImageJobOwnerKey.matches(ownerHash, ownerKeyResolver.ownerKey());
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

    private Map<String, Object> etaSnapshot(String id) {
        ImageJobService service = jobService.getIfAvailable();
        if (service == null) {
            return Map.of("serviceAvailable", false);
        }
        ImageJobService.Eta eta = service.estimate(id);
        return Map.of(
                "serviceAvailable", true,
                "etaSeconds", eta == null ? 0L : eta.etaSeconds(),
                "expectedReadyAt", eta == null ? "" : String.valueOf(eta.expectedReadyAt()));
    }

    private Map<String, Object> configSnapshot(boolean includeCredentialPresence) {
        OpenAiImageService service = imageService.getIfAvailable();
        boolean imageEnabled = booleanProperty("openai.image.enabled", false);
        boolean syncEnabled = booleanProperty("openai.image.sync.enabled", false);
        boolean serviceAvailable = service != null;
        Boolean apiKeyConfigured = includeCredentialPresence && serviceAvailable ? service.isConfigured() : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openai.image.enabled", imageEnabled);
        out.put("openai.image.sync.enabled", syncEnabled);
        out.put("imageServiceAvailable", serviceAvailable);
        out.put("image.jobs.relay-delay-ms", longProperty("image.jobs.relay-delay-ms", 300_000L));
        out.put("image.jobs.debug.enabled", booleanProperty("image.jobs.debug.enabled", true));
        out.put("image.jobs.debug.session-ttl-ms", longProperty("image.jobs.debug.session-ttl-ms", 32_400_000L));
        out.put("image.jobs.debug.expected-ui-poll-ms", longProperty("image.jobs.debug.expected-ui-poll-ms", 120_000L));
        if (includeCredentialPresence) {
            out.put("apiKeyConfigured", Boolean.TRUE.equals(apiKeyConfigured));
        }
        out.put("disabledReason", disabledReason(imageEnabled, serviceAvailable, apiKeyConfigured));
        return out;
    }

    private static String disabledReason(boolean imageEnabled, boolean serviceAvailable, Boolean apiKeyConfigured) {
        if (!imageEnabled) {
            return "image plugin disabled";
        }
        if (!serviceAvailable) {
            return "image service unavailable";
        }
        if (apiKeyConfigured != null && !apiKeyConfigured) {
            return "missing_openai_image_key";
        }
        return null;
    }

    private static String configNextAction(Map<String, Object> config) {
        if (Boolean.FALSE.equals(config.get("openai.image.enabled"))) {
            return "image plugin disabled";
        }
        if (Boolean.FALSE.equals(config.get("imageServiceAvailable"))) {
            return "image service unavailable";
        }
        if (config.containsKey("apiKeyConfigured") && Boolean.FALSE.equals(config.get("apiKeyConfigured"))) {
            return "api key not configured";
        }
        if (Boolean.FALSE.equals(config.get("openai.image.sync.enabled"))) {
            return "async image job path enabled; sync image endpoint disabled";
        }
        return "image config looks enabled; inspect provider/storage job signals";
    }

    private boolean booleanProperty(String key, boolean fallback) {
        String value = env.getProperty(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private long longProperty(String key, long fallback) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ex) {
            log.debug("[AWX][image-debug] numeric config fallback key={} errorType={}",
                    SafeRedactor.traceLabelOrFallback(key, "unknown"),
                    ex.getClass().getSimpleName());
            return fallback;
        }
    }

    private double finite(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return 0.0d;
        }
        return value;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\u0000', ' ').trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
