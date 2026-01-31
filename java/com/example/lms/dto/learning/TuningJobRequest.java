package com.example.lms.dto.learning;

import java.util.Objects;



/**
 * Represents a request to start a tuning job on Vertex AI.
 */
public record TuningJobRequest(
        String datasetUri,
        String model,
        String suffix,
        int epochs
) {
    public TuningJobRequest {
        Objects.requireNonNull(datasetUri, "datasetUri must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(suffix, "suffix must not be null");
    }
}