package com.abandonware.ai.agent.job;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;



/**
 * Representation of a job request.  A job request encapsulates a flow name
 * (indicating which orchestrated process should execute the job), an
 * arbitrary payload, and identifiers for tracing the request back to the
 * original caller.  Jobs are always enqueued through the
 * {@link com.abandonware.ai.agent.job.JobQueue} and processed asynchronously.
 */
public final class JobRequest {
    private final String flow;
    private final Map<String, Object> payload;
    private final String requestId;
    private final String sessionId;

    public JobRequest(String flow, Map<String, Object> payload, String requestId, String sessionId) {
        this.flow = flow;
        if (payload == null) {
            this.payload = Collections.emptyMap();
        } else {
            this.payload = Collections.unmodifiableMap(new HashMap<>(payload));
        }
        this.requestId = requestId;
        this.sessionId = sessionId;
    }

    public String flow() {
        return flow;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public String requestId() {
        return requestId;
    }

    public String sessionId() {
        return sessionId;
    }
}