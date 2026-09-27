package com.abandonware.ai.agent.orchestrator.subagent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One immutable unit of work owned by a subagent execution. */
public record SubagentTask(
        String requestId,
        int ordinal,
        String taskId,
        String role,
        String prompt,
        Map<String, Object> context) {

    public SubagentTask {
        requestId = Objects.requireNonNullElse(requestId, "request-unknown");
        ordinal = Math.max(0, ordinal);
        taskId = Objects.requireNonNullElse(taskId, "task-unknown");
        role = Objects.requireNonNullElse(role, "analysis");
        prompt = Objects.requireNonNullElse(prompt, "");
        context = context == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(context));
    }
}
