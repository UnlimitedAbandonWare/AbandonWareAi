package com.example.lms.dto.learning;

import java.util.Objects;



/**
 * Represents the status of a tuning job.
 */
public record TuningJobStatus(
        String jobId,
        String state,
        String message
) {
    public TuningJobStatus {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}