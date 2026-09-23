package com.abandonware.ai.agent.tool.impl.ops;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;

import java.util.Map;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class FailurePatternRecallTool implements AgentTool {
    private final FailurePatternMemoryService service;

    public FailurePatternRecallTool(FailurePatternMemoryService service) {
        this.service = service;
    }

    @Override
    public String id() {
        return "failure.pattern.recall";
    }

    @Override
    public String description() {
        return "Recall matching failure-pattern memory rows and next patch actions by low-cardinality labels.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null ? Map.of() : request.input();
        return ToolResponse.ok().put("failurePatternRecall", service.recall(input));
    }
}
