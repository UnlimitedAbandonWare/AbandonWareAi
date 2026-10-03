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
    /** Implementations with a type policy reject admission before performing work. */
    default boolean isTypeDisabled(String type) { return false; }
    default java.util.Optional<JobSnapshot> find(String taskId, String ownerHash) { return java.util.Optional.empty(); }
    default java.util.Optional<String> result(String taskId, String ownerHash) { return java.util.Optional.empty(); }
    default boolean cancel(String taskId, String ownerHash) { return false; }
    /** Durable request identity, independent of Redis TTL. Unsupported stores must fail closed. */
    default Admission enqueueOnce(String type,Object payload,Map<String,Object> metadata,String correlationId,String key,String fingerprint){throw new UnsupportedOperationException("durable_idempotency_required");}
    default java.util.Optional<Admission> findAdmission(String type,String owner,String key,String fingerprint){throw new UnsupportedOperationException("durable_idempotency_required");}
    record Admission(String taskId,boolean replayed,String state){}
    String UNDERSTANDING_TYPE = "understanding_summary_v1";
    /** Internal derived work: compute has no transaction; commit owns its short source transaction. */
    interface DerivedJobHandler {
        default boolean recover(DerivedClaim claim, String payload) throws Exception { return false; }
        String compute(String payload) throws Exception;
        void commit(DerivedClaim claim, String payload, String prepared) throws Exception;
    }
    record DerivedClaim(String taskId, String token) {
        @Override public String toString() { return "DerivedClaim[redacted]"; }
    }
    record DerivedIdentity(String ownerHash, long sessionId, String originalRunId, String effectKey) { }
    final class DerivedRejected extends RuntimeException {
        public DerivedRejected() { super("derived_source_or_policy_invalidated"); }
    }
    default void registerDerivedHandler(String type, DerivedJobHandler handler) { throw new UnsupportedOperationException("derived_jobs_required"); }
    default boolean derivedReady(String type) { return false; }
    default Admission enqueueDerivedOnce(Object payload, DerivedIdentity identity, String key, String fingerprint) { throw new UnsupportedOperationException("derived_jobs_required"); }
    default void requireDerivedLease(DerivedClaim claim) { throw new UnsupportedOperationException("derived_jobs_required"); }
    default void completeDerived(DerivedClaim claim) { throw new UnsupportedOperationException("derived_jobs_required"); }
    default boolean cancelDerivedRun(String owner, long sessionId, String originalRunId) { return false; }
    final class IdempotencyConflict extends RuntimeException {public IdempotencyConflict(){super("idempotency_conflict");}}

    record JobSnapshot(String taskId, String state, long createdAt, Long completedAt,
                       Long expiresAt, String resultRef, String errorCode) { }
}
