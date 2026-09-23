package com.example.lms.api;

import com.example.lms.learning.ops.RagLearningOpsDashboardService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/rag/learning-ops")
public class RagLearningOpsDiagnosticsController {

    private final RagLearningOpsDashboardService service;

    public RagLearningOpsDiagnosticsController(RagLearningOpsDashboardService service) {
        this.service = service;
    }

    @GetMapping(value = {"", "/overview"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> overview(@RequestParam(defaultValue = "50") int limit) {
        return service.overview(limit);
    }

    @GetMapping(value = "/samples", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> samples(@RequestParam(defaultValue = "100") int limit) {
        return service.samples(limit);
    }

    @GetMapping(value = "/failures", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> failures(@RequestParam(defaultValue = "100") int limit) {
        return service.failures(limit);
    }

    @GetMapping(value = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> metrics() {
        return service.metrics();
    }

    @GetMapping(value = "/metrics/prometheus")
    public ResponseEntity<String> prometheus() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8"))
                .body(service.prometheus());
    }
}
