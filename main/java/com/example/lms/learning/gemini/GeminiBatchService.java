package com.example.lms.learning.gemini;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;



/**
 * Service that orchestrates batch normalisation and ingestion of learning events.
 * The current implementation is a shim and returns empty results.
 */
@Service
@RequiredArgsConstructor
public class GeminiBatchService {
    private static final Logger log = LoggerFactory.getLogger(GeminiBatchService.class);
    // In a complete implementation, dependencies such as the Gemini client,
    // storage repositories and configuration would be injected here.

    public Map<String, Object> buildDataset(int sinceHours) {
        return disabled("not-implemented", Map.of("sinceHours", Math.max(0, sinceHours)));
    }

    public Map<String, Object> runBatch(String datasetUri, String jobName) {
        return disabled("not-implemented", Map.of(
                "hasDatasetUri", datasetUri != null && !datasetUri.isBlank(),
                "hasJobName", jobName != null && !jobName.isBlank()));
    }

    private static Map<String, Object> disabled(String reason, Map<String, Object> details) {
        return Map.of(
                "enabled", false,
                "disabledReason", reason,
                "details", details == null ? Map.of() : details);
    }
}
