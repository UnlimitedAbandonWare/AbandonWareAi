package com.example.lms.graphdb;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/graph")
public class GraphDbManualLearningController {

    private final GraphDbManualLearningService learningService;

    public GraphDbManualLearningController(GraphDbManualLearningService learningService) {
        this.learningService = learningService;
    }

    @PostMapping(value = "/learn-text", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GraphDbManualLearningService.LearnReport> learnText(
            @RequestBody(required = false) LearnTextRequest request) {
        LearnTextRequest safe = request == null ? new LearnTextRequest(null, null, null, null) : request;
        GraphDbManualLearningService.LearnReport report = learningService.learnText(
                safe.sessionId(),
                safe.text(),
                safe.domain(),
                safe.dryRun());
        return response(report);
    }

    @PostMapping(value = "/learn-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GraphDbManualLearningService.LearnReport> learnFile(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "dryRun", required = false) Boolean dryRun) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(learningService.learnFile(sessionId, null, null, null, domain, dryRun));
        }
        try {
            GraphDbManualLearningService.LearnReport report = learningService.learnFile(
                    sessionId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes(),
                    domain,
                    dryRun);
            return response(report);
        } catch (Exception ex) {
            traceSuppressed("graphDb.controller.fileRead", ex);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(learningService.fileReadFailed(
                            sessionId,
                            file.getOriginalFilename(),
                            file.getContentType(),
                            dryRun,
                            ex));
        }
    }

    @GetMapping(value = "/learn-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GraphDbManualLearningService.LearnReport> status() {
        return ResponseEntity.ok(learningService.status());
    }

    @GetMapping(value = "/learn-evidence", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GraphDbManualLearningService.LearnReport> evidence(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return response(learningService.learnEvidence(domain, limit));
    }

    @GetMapping(value = "/learn-summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GraphDbManualLearningService.LearnReport> summary(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return response(learningService.learnSummary(domain, limit));
    }

    @GetMapping(value = "/learn-snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GraphDbManualLearningService.LearnReport> snapshot(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return response(learningService.learnSnapshot(domain, limit));
    }

    private static ResponseEntity<GraphDbManualLearningService.LearnReport> response(
            GraphDbManualLearningService.LearnReport report) {
        if (report == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if ("missing_text".equals(report.disabledReason())
                || "missing_file".equals(report.disabledReason())
                || "unsupported_or_empty_file".equals(report.disabledReason())
                || "file_read_failed".equals(report.disabledReason())
                || "file_extract_failed".equals(report.disabledReason())) {
            return ResponseEntity.badRequest().body(report);
        }
        if (!report.enabled() || "disabled".equals(report.status())) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(report);
        }
        return ResponseEntity.ok(report);
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(ignored);
        TraceStore.put("graphdb.controller.suppressed.stage", safeStage);
        TraceStore.put("graphdb.controller.suppressed.errorType", errorType);
        TraceStore.put("graphdb.controller.suppressed." + safeStage, true);
        TraceStore.put("graphdb.controller.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String errorType(Throwable ignored) {
        return SafeRedactor.traceLabelOrFallback(
                ignored == null ? null : ignored.getClass().getSimpleName(),
                "unknown");
    }

    public record LearnTextRequest(String sessionId, String text, String domain, Boolean dryRun) {
    }
}
