package com.abandonware.ai.agent.orchestrator;

import java.util.Map;

public class Orchestrator {
    public Orchestrator() {}

    public Map<String, Object> run(String flowId, Map<String, Object> input) {
        // No-op orchestrator stub for build-only
        return input;
    }

    public java.util.Map<String, Object> execute(String flowId, java.util.Map<String, Object> input, com.abandonware.ai.agent.tool.request.ToolContext ctx) {
        return input;
    }

}