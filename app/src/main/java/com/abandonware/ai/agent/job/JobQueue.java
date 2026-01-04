package com.abandonware.ai.agent.job;

import java.util.Optional;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.job.JobQueue
 * Role: config
 * Feature Flags: sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.job.JobQueue
role: config
flags: [sse]
*/
public interface JobQueue {

    /**
     * Enqueues the given job request and returns the generated job ID.  The
     * request is persisted and can be retrieved later via {@link #dequeue}.
     */
    String enqueue(JobRequest request);

    /**
     * Dequeues the next pending job for the specified flow.  If no jobs
     * exist this method blocks for up to {@code blockMillis} milliseconds
     * waiting for a job to arrive.  If a job is returned its state is
     * immediately set to RUNNING.  Returns an empty optional if the timeout
     * expires.
     */
    Optional<JobRecord> dequeue(String flow, long blockMillis);

    /**
     * Acknowledges a successful job execution.  The job state transitions to
     * SUCCEEDED and the result is stored.
     */
    void ackSuccess(String jobId, JobResult result);

    /**
     * Acknowledges a failed job execution.  The job state transitions to
     * FAILED or DLQ based on the {@code toDlq} flag.
     */
    void ackFailure(String jobId, String reason, boolean toDlq);
}