package com.example.lms.jobs;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * In‑memory job service supporting asynchronous task execution and
 * lightweight progress tracking.  Jobs are represented by a simple
 * record containing metadata and optional results.  The service
 * executes tasks on a cached thread pool and exposes basic CRUD
 * operations for polling.  This implementation does not persist jobs
 * across restarts and is therefore intended for short‑lived tasks only.
 */
@Service
public class JobService {

    /** Concurrent job storage keyed by job identifier. */
    private final ConcurrentMap<String, Job> store = new ConcurrentHashMap<>();

    /** Executor used to run asynchronous tasks. */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Create and store a new job in the PENDING state.  No processing is
     * performed until {@link #executeAsync(String, java.util.concurrent.Callable, Consumer)} is
     * invoked.  The payload is stored as metadata on the job for later
     * inspection.
     *
     * @param flow      the flow or queue name (e.g. n8n flow name)
     * @param payload   arbitrary payload associated with the job
     * @param requestId optional request correlation identifier
     * @param sessionId optional session identifier
     * @return the generated job identifier
     */
    public String enqueue(String flow, Object payload, String requestId, String sessionId) {
        String jobId = UUID.randomUUID().toString();
        Job job = new Job(jobId, flow, requestId, sessionId, JobStatus.PENDING, payload, null, null);
        store.put(jobId, job);
        return jobId;
    }

    /**
     * Execute a task associated with the given job asynchronously.  The job
     * is marked IN_PROGRESS prior to execution.  Upon completion the job
     * status is updated to SUCCEEDED or FAILED and the result is stored.
     * Optionally a completion callback can be supplied; it will be
     * invoked on the calling thread after the job finishes.  Any
     * exceptions thrown by the task are captured and stored on the job.
     *
     * @param jobId     the job identifier returned by {@link #enqueue}
     * @param task      a supplier producing the result for the job
     * @param onFinish  callback invoked with the result when the task completes
     * @param <T>       type of the result
     */
    public <T> void executeAsync(String jobId, java.util.concurrent.Callable<T> task, Consumer<T> onFinish) {
        Job job = store.get(jobId);
        if (job == null) return;
        // Update status to IN_PROGRESS
        job.setStatus(JobStatus.IN_PROGRESS);
        executor.submit(() -> {
            try {
                T result = task.call();
                job.setStatus(JobStatus.SUCCEEDED);
                job.setResult(result);
                if (onFinish != null) {
                    try { onFinish.accept(result); } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                job.setStatus(JobStatus.FAILED);
                job.setError(e.toString());
            }
        });
    }

    /**
     * Retrieve a job by identifier.
     *
     * @param jobId the identifier to lookup
     * @return the job instance or null when missing
     */
    public Job get(String jobId) {
        return store.get(jobId);
    }

    /**
     * Remove a job from the store.  Intended to free memory after clients
     * have retrieved the results.
     *
     * @param jobId the job identifier
     */
    public void remove(String jobId) {
        store.remove(jobId);
    }

    /**
     * Create an SSE emitter for the given job.  This shim emitter
     * immediately completes and does not currently stream events.  It
     * exists purely to satisfy the API contract; a fuller
     * implementation could hook into job lifecycle events and push
     * progress updates to the emitter.
     *
     * @param jobId the job identifier
     * @return a completed SseEmitter
     */
    public SseEmitter emitter(String jobId) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("complete").data("done"));
            emitter.complete();
        } catch (Exception ignore) {
            emitter.completeWithError(ignore);
        }
        return emitter;
    }

    /**
     * Job status enumeration capturing the high level lifecycle states.
     */
    public enum JobStatus { PENDING, IN_PROGRESS, SUCCEEDED, FAILED }

    /**
     * Mutable record representing the state of a job.  The result and
     * error fields may be populated asynchronously as the task
     * progresses.  Consumers should treat this record as a snapshot at
     * the time of retrieval.
     *
     * @param id         unique job identifier
     * @param flow       flow or queue name
     * @param requestId  correlation identifier
     * @param sessionId  session identifier
     * @param status     current status
     * @param payload    arbitrary payload metadata
     * @param result     optional result
     * @param error      optional error details
     */
    public static class Job {
        private final String id;
        private final String flow;
        private final String requestId;
        private final String sessionId;
        private volatile JobStatus status;
        private final Object payload;
        private volatile Object result;
        private volatile String error;

        public Job(String id, String flow, String requestId, String sessionId,
                   JobStatus status, Object payload, Object result, String error) {
            this.id = id;
            this.flow = flow;
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.status = status;
            this.payload = payload;
            this.result = result;
            this.error = error;
        }

        public String getId() { return id; }
        public String getFlow() { return flow; }
        public String getRequestId() { return requestId; }
        public String getSessionId() { return sessionId; }
        public JobStatus getStatus() { return status; }
        public void setStatus(JobStatus status) { this.status = status; }
        public Object getPayload() { return payload; }
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}