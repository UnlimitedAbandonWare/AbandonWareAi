package com.abandonware.ai.agent.tool.response;

import java.util.HashMap;
import java.util.Map;



/**
 * Standard output wrapper for tool invocations.  Tools build up a response
 * by putting arbitrary key/value pairs into the data map.  The response
 * class is intentionally simple to avoid imposing any particular data
 * structure on tool authors; the orchestrator is responsible for mapping
 * these results back into the agent context.
 */
public final class ToolResponse {
    private final Map<String, Object> data = new HashMap<>();

    private ToolResponse() {
    }

    /** Creates a new empty successful response. */
    public static ToolResponse ok() {
        return new ToolResponse();
    }

    /**
     * Adds a key/value pair to the response and returns this instance for
     * fluent chaining.
     */
    public ToolResponse put(String key, Object value) {
        data.put(key, value);
        return this;
    }

    /** Returns the underlying response map. */
    public Map<String, Object> data() {
        return data;
    }
}