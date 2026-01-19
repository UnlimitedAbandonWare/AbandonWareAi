package com.abandonware.ai.agent.job;

import org.springframework.stereotype.Service;
import java.util.Map;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.job.DurableJobService
 * Role: service
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.job.DurableJobService
role: service
*/
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