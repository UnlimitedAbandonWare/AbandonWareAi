package com.example.lms.api;

import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/local-llm")
public class LocalLlmDiagnosticsController {

    private final LocalLlmSmokeHistoryDiagnosticsService smokeHistoryDiagnosticsService;

    public LocalLlmDiagnosticsController(LocalLlmSmokeHistoryDiagnosticsService smokeHistoryDiagnosticsService) {
        this.smokeHistoryDiagnosticsService = smokeHistoryDiagnosticsService;
    }

    @GetMapping(value = "/smoke-history", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> smokeHistory(
            @RequestParam(value = "limit", required = false, defaultValue = "12") int limit) {
        return smokeHistoryDiagnosticsService.snapshot(limit);
    }
}
