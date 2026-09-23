package com.example.lms.jobs;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Async job API. Production uses the durable SQL owner; memory is an explicit development option.
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

    /** A restartable handler consumes the persisted request, never a serialized closure. */
    @FunctionalInterface
    interface JobHandler {
        String execute(String requestJson) throws Exception;
        default boolean needsCompletion(String requestJson) throws Exception { return false; }
        default boolean completed(String taskId, String requestJson, String resultJson) throws Exception { return true; }
    }

    default void registerHandler(String type, JobHandler handler) { }
    default boolean runsPersistedJobs() { return false; }
    default java.util.Optional<JobSnapshot> find(String taskId, String ownerHash) { return java.util.Optional.empty(); }
    default java.util.Optional<String> result(String taskId, String ownerHash) { return java.util.Optional.empty(); }
    default boolean cancel(String taskId, String ownerHash) { return false; }
    /** Durable request identity, independent of Redis TTL. Unsupported stores must fail closed. */
    default Admission enqueueOnce(String type,Object payload,Map<String,Object> metadata,String correlationId,String key,String fingerprint){throw new UnsupportedOperationException("durable_idempotency_required");}
    default java.util.Optional<Admission> findAdmission(String type,String owner,String key,String fingerprint){throw new UnsupportedOperationException("durable_idempotency_required");}
    record Admission(String taskId,boolean replayed,String state){}
    final class IdempotencyConflict extends RuntimeException {public IdempotencyConflict(){super("idempotency_conflict");}}

    record JobSnapshot(String taskId, String state, long createdAt, Long completedAt,
                       Long expiresAt, String resultRef, String errorCode) { }
}
