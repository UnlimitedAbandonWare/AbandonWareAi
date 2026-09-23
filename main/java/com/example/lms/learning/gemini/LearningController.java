package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.TuningJobRequest;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.trace.SafeRedactor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;



/**
 * REST controller exposing endpoints for Gemini-based learning ingestion,
 * batch processing and optional Vertex tuning. Endpoints are deliberately
 * simple and return shim objects; a real implementation should
 * produce more descriptive responses based on the curation and tuning outcomes.
 */
@RestController
@RequestMapping("/api/learning/gemini")
@RequiredArgsConstructor
public class LearningController {

    private final GeminiCurationService curationService;
    private final GeminiBatchService batchService;
    private final AdminTokenGuardInterceptor adminTokenGuardInterceptor;

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody LearningEvent event, HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdmin(request);
        if (denied != null) return denied;
        if (event == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "disabledReason", "missing_learning_event"));
        }
        GeminiCurationService.CurationResult result = curationService.ingestWithResult(event);
        java.util.Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("applied", result.applied());
        String disabledReason = result.disabledReason();
        if (!disabledReason.isBlank()) {
            resp.put("disabledReason", safeReason(disabledReason));
        }
        var delta = result.delta();
        resp.put("triples", delta.triples().size());
        resp.put("rules", delta.rules().size());
        resp.put("memories", delta.memories().size());
        if (!result.applied()) {
            HttpStatus status = "invalid_event".equals(disabledReason)
                    ? HttpStatus.BAD_REQUEST
                    : HttpStatus.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(status).body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/batch/build")
    public ResponseEntity<?> buildDataset(@RequestParam(name = "sinceHours", defaultValue = "24") int sinceHours,
                                          HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(batchService.buildDataset(sinceHours));
    }

    @PostMapping("/batch/run")
    public ResponseEntity<?> runBatch(@RequestParam String datasetUri, @RequestParam String jobName,
                                      HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(batchService.runBatch(datasetUri, jobName));
    }

    @PostMapping("/tune")
    public ResponseEntity<?> startTuning(@RequestBody TuningJobRequest tuningRequest, HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdmin(request);
        if (denied != null) return denied;
        if (tuningRequest == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "disabledReason", "missing_tuning_request"));
        }
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(tuningUnavailable("start_not_implemented", null));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> getJobStatus(@PathVariable("id") String id, HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(tuningUnavailable("status_not_implemented", id));
    }

    private ResponseEntity<?> requireAdmin(HttpServletRequest request) {
        if (adminTokenGuardInterceptor != null && adminTokenGuardInterceptor.isPresentedTokenAuthorized(request)) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "ADMIN_REQUIRED"));
    }

    private Map<String, Object> tuningUnavailable(String disabledReason, String jobId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("error", "tuning_job_unavailable");
        resp.put("disabledReason", disabledReason);
        if (jobId != null && !jobId.isBlank()) {
            resp.put("jobIdHash", SafeRedactor.hashValue(jobId));
            resp.put("jobIdLength", jobId.length());
        }
        return resp;
    }

    private static String safeReason(String value) {
        return SafeRedactor.traceLabelOrFallback(value, "");
    }
}
