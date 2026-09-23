package com.example.lms.api;

import com.example.lms.debug.ai.DebugAiMetricSnapshot;
import com.example.lms.debug.ai.DebugAiMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/debug/ai")
@RequiredArgsConstructor
public class DebugAiMetricsController {

    private final DebugAiMetricsService service;

    @GetMapping(value = "/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    public DebugAiMetricSnapshot snapshot(
            @RequestParam(name = "limit", defaultValue = "80") int limit,
            @RequestParam(name = "windowMs", defaultValue = "60000") long windowMs) {
        return service.snapshot(limit, windowMs);
    }

    @GetMapping(value = "/compact", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> compact(
            @RequestParam(name = "limit", defaultValue = "30") int limit,
            @RequestParam(name = "windowMs", defaultValue = "60000") long windowMs) {
        return service.compactSnapshot(limit, windowMs);
    }

    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<DebugAiMetricSnapshot> history(
            @RequestParam(name = "maxEntries", defaultValue = "12") int maxEntries) {
        return service.snapshotHistory(maxEntries);
    }
}
