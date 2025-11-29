package com.abandonware.ai.agent.job;

import org.springframework.stereotype.Service;
import java.util.Map;



/**
 * Facade for enqueuing long running jobs.  Delegates to a {@link JobQueue}
 * and produces job identifiers which can be used to track progress via
 * polling or callbacks.  In a full implementation this service would also
 * handle scheduling, retries and deduplication policies.
 */
@Service
public class DurableJobService {
    private final JobQueue queue;

    public DurableJobService(JobQueue queue) {
        this.queue = queue;
    }

    /**
     * Enqueues a job for the given flow with the supplied payload and
     * identifiers.  Returns the generated job ID.
     */
    public String enqueue(String flow, Map<String, Object> payload, String requestId, String sessionId) {
        JobRequest req = new JobRequest(flow, payload, requestId, sessionId);
        return queue.enqueue(req);
    }
}