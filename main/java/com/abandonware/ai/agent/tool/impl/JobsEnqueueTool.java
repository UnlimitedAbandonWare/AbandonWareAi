package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.job.DurableJobService;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;




/**
 * Enqueues a long running job.  Requires the {@code internal.enqueue}
 * scope.  The shim delegates to {@link DurableJobService} and returns the
 * generated job identifier.
 */
@Component
@RequiresScopes({ToolScope.INTERNAL_ENQUEUE})
public class JobsEnqueueTool implements AgentTool {
    private final DurableJobService durableJobService;

    public JobsEnqueueTool(DurableJobService durableJobService) {
        this.durableJobService = durableJobService;
    }

    @Override
    public String id() {
        return "jobs.enqueue";
    }

    @Override
    public String description() {
        return "Register a long running internal job.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request.input();
        String flow = stringInput(input.get("flow"));
        Map<String, Object> payload = payloadInput(input.get("payload"));
        String requestId = stringInput(input.get("requestId"));
        String sessionId = stringInput(input.get("sessionId"));
        String jobId = durableJobService.enqueue(flow, payload, requestId, sessionId);
        return ToolResponse.ok().put("jobId", jobId);
    }

    private static String stringInput(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Map<String, Object> payloadInput(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                payload.put(key, entry.getValue());
            }
        }
        return payload;
    }
}
