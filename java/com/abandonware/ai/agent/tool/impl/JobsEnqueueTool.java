package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.job.DurableJobService;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.springframework.stereotype.Component;
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
        String flow = (String) input.get("flow");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) input.get("payload");
        String requestId = (String) input.get("requestId");
        String sessionId = (String) input.get("sessionId");
        String jobId = durableJobService.enqueue(flow, payload, requestId, sessionId);
        return ToolResponse.ok().put("jobId", jobId);
    }
}