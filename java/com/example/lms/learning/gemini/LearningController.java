package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.TuningJobRequest;
import com.example.lms.dto.learning.TuningJobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;



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
    private final GeminiTuningService tuningService;

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody LearningEvent event) {
        KnowledgeDelta delta = curationService.ingest(event);
        // Return a simple summary indicating how many items were produced
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("applied", true);
        resp.put("triples", delta.triples().size());
        resp.put("rules", delta.rules().size());
        resp.put("memories", delta.memories().size());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/batch/build")
    public ResponseEntity<?> buildDataset(@RequestParam(name = "sinceHours", defaultValue = "24") int sinceHours) {
        String uri = batchService.buildDataset(sinceHours);
        return ResponseEntity.ok(java.util.Map.of("datasetUri", uri));
    }

    @PostMapping("/batch/run")
    public ResponseEntity<?> runBatch(@RequestParam String datasetUri, @RequestParam String jobName) {
        String jobId = batchService.runBatch(datasetUri, jobName);
        return ResponseEntity.ok(java.util.Map.of("jobId", jobId));
    }

    @PostMapping("/tune")
    public ResponseEntity<?> startTuning(@RequestBody TuningJobRequest request) {
        String jobId = tuningService.startTuningJob(request);
        return ResponseEntity.ok(java.util.Map.of("jobId", jobId));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> getJobStatus(@PathVariable("id") String id) {
        TuningJobStatus status = tuningService.getTuningJobStatus(id);
        return ResponseEntity.ok(status);
    }
}