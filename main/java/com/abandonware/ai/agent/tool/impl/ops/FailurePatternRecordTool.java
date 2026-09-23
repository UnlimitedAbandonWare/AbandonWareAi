package com.abandonware.ai.agent.tool.impl.ops;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;

import java.util.Map;

@RequiresScopes({ToolScope.INTERNAL_ENQUEUE})
public class FailurePatternRecordTool implements AgentTool {
    private final FailurePatternMemoryService service;

    public FailurePatternRecordTool(FailurePatternMemoryService service) {
        this.service = service;
    }

    @Override
    public String id() {
        return "failure.pattern.record";
    }

    @Override
    public String description() {
        return "Record sanitized failure-pattern and patch-judgment memory for later agent recall.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null ? Map.of() : request.input();
        return ToolResponse.ok().put("failurePatternRecord", service.record(input));
    }
}
