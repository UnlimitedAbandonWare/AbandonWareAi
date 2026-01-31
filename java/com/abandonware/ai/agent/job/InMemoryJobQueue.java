package com.abandonware.ai.agent.job;

import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;



/**
 * Simple in-memory implementation of {@link JobQueue}.  Jobs are grouped
 * by flow name and stored in concurrent queues.  A separate lookup map
 * allows acknowledgement and state transitions by job identifier.  This
 * implementation is intended for unit testing and demonstrations only; it
 * does not support persistence across restarts or distribution across
 * multiple processes.
 */
public class InMemoryJobQueue implements JobQueue {
    private final Map<String, Queue<JobRecord>> queues = new ConcurrentHashMap<>();
    private final Map<String, JobRecord> records = new ConcurrentHashMap<>();

    @Override
    public String enqueue(JobRequest request) {
        JobRecord record = new JobRecord(new JobId(), request);
        records.put(record.id().value(), record);
        queues.computeIfAbsent(request.flow(), f -> new ConcurrentLinkedQueue<>()).offer(record);
        return record.id().value();
    }

    @Override
    public Optional<JobRecord> dequeue(String flow, long blockMillis) {
        Queue<JobRecord> q = queues.computeIfAbsent(flow, f -> new ConcurrentLinkedQueue<>());
        long deadline = java.time.Instant.now().toEpochMilli() + blockMillis;
        while (true) {
            JobRecord rec = q.poll();
            if (rec != null) {
                synchronized (rec) {
                    rec.setState(JobState.RUNNING);
                }
                return Optional.of(rec);
            }
            if (blockMillis <= 0 || java.time.Instant.now().toEpochMilli() >= deadline) {
                return Optional.empty();
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    @Override
    public void ackSuccess(String jobId, JobResult result) {
        JobRecord rec = records.get(jobId);
        if (rec != null) {
            synchronized (rec) {
                rec.setResult(result);
                rec.setState(JobState.SUCCEEDED);
            }
        }
    }

    @Override
    public void ackFailure(String jobId, String reason, boolean toDlq) {
        JobRecord rec = records.get(jobId);
        if (rec != null) {
            synchronized (rec) {
                rec.setState(toDlq ? JobState.DLQ : JobState.FAILED);
            }
        }
    }
}