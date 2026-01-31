package com.example.lms.jobs;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Minimal async job API used by task endpoints.  Backed by an in-memory
 * implementation for local/dev builds.
 */
public interface JobService {
    /** Enqueue a simple payload; returns generated job id. */
    String enqueue(String payload);

    /**
     * Extended enqueue used by controllers. Arguments are intentionally
     * generic to avoid coupling. Implementations may persist the payload.
     */
    default String enqueue(String jobType, Object payload, Map<String, Object> metadata, String correlationId) {
        // Fallback: compose a synthetic payload and delegate.
        String composed = (jobType == null ? "job" : jobType)
                + "|" + (correlationId == null ? "" : correlationId);
        return enqueue(composed);
    }

    /** Execute work asynchronously associated with an existing job id. */
    <T> void executeAsync(String jobId, Supplier<T> work, Consumer<T> onSuccess);

    /** Lightweight status probe. */
    String status(String jobId);
}