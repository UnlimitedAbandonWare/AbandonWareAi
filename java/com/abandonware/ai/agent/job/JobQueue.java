package com.abandonware.ai.agent.job;

import java.util.Optional;



/**
 * Contract for a durable job queue.  Implementations store job records and
 * support blocking consumption, acknowledgements and optional dead letter
 * routing.  A queue is partitioned by flow name so that different flows
 * can be processed independently.
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