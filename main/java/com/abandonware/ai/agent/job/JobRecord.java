package com.abandonware.ai.agent.job;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;



/**
 * Persistent record of a job instance.  The record tracks the request
 * details, current state, result (if completed) and timestamps.  In
 * combination with the job queue, the job record allows retry policies and
 * dead letter queues to be implemented.  This simplified implementation
 * stores everything in memory and is not intended for production use.
 */
public final class JobRecord {
    private final JobId id;
    private final JobRequest request;
    private JobState state;
    private JobResult result;
    private final Instant enqueuedAt;
    private Instant completedAt;

    public JobRecord(JobId id, JobRequest request) {
        this.id = id == null ? new JobId() : id;
        this.request = request == null ? new JobRequest("default", Map.of(), null, null) : request;
        this.state = JobState.PENDING;
        this.enqueuedAt = Instant.now();
    }

    public JobId id() {
        return id;
    }

    public JobRequest request() {
        return request;
    }

    public synchronized JobState state() {
        return state;
    }

    public synchronized void setState(JobState state) {
        JobState safeState = state == null ? JobState.PENDING : state;
        this.state = safeState;
        if (safeState == JobState.SUCCEEDED || safeState == JobState.FAILED || safeState == JobState.DLQ) {
            this.completedAt = Instant.now();
        }
    }

    public synchronized Optional<JobResult> result() {
        return Optional.ofNullable(result);
    }

    public synchronized void setResult(JobResult result) {
        this.result = result;
    }

    public Instant enqueuedAt() {
        return enqueuedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }
}
