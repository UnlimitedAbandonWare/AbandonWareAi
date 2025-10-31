package com.example.lms.learning.gemini;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;



/**
 * Service that orchestrates batch normalisation and ingestion of learning events.
 * The current implementation is a shim and returns empty results.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiBatchService {
    // In a complete implementation, dependencies such as the Gemini client,
    // storage repositories and configuration would be injected here.

    public String buildDataset(int sinceHours) {
        // Implementation shim: build a JSONL dataset from events within the past N hours.
        return "";
    }

    public String runBatch(String datasetUri, String jobName) {
        // Implementation shim: trigger a batch processing job on Gemini's Files/Batch API.
        return "";
    }
}