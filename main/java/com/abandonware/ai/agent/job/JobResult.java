package com.abandonware.ai.agent.job;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;



/**
 * Encapsulates the result of a job execution.  The result is a simple
 * key/value map; concrete processors may put whatever structured data they
 * produce into this map.  Jobs that fail may produce no result and leave
 * the map empty.
 */
public final class JobResult {
    private final Map<String, Object> data;

    public JobResult(Map<String, Object> data) {
        if (data == null) {
            this.data = Collections.emptyMap();
        } else {
            this.data = Collections.unmodifiableMap(new HashMap<>(data));
        }
    }

    public Map<String, Object> data() {
        return data;
    }
}