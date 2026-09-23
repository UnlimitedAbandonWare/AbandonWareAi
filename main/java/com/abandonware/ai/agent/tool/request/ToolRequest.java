package com.abandonware.ai.agent.tool.request;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;



/**
 * Request envelope passed to every tool invocation.  The request contains
 * structured input parameters and a {@link ToolContext} describing the
 * environment in which the tool is being executed.  The input map must
 * satisfy the JSON schema for the tool as specified in the tool manifest.
 */
public final class ToolRequest {
    private final Map<String, Object> input;
    private final ToolContext context;

    public ToolRequest(Map<String, Object> input, ToolContext context) {
        if (input == null) {
            this.input = Collections.emptyMap();
        } else {
            this.input = Collections.unmodifiableMap(new HashMap<>(input));
        }
        this.context = context;
    }

    /** Returns the tool input parameters. */
    public Map<String, Object> input() {
        return input;
    }

    /** Returns the context associated with this request. */
    public ToolContext context() {
        return context;
    }
}