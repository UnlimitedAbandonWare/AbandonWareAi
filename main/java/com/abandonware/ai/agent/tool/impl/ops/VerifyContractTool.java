package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;

import java.util.Map;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class VerifyContractTool implements AgentTool {
    private final ToolManifestCatalog catalog;
    private final ToolRegistry registry;

    public VerifyContractTool(ToolManifestCatalog catalog, ToolRegistry registry) {
        this.catalog = catalog;
        this.registry = registry;
    }

    @Override
    public String id() {
        return "verify.contract";
    }

    @Override
    public String description() {
        return "Validate the AgentTool registry against the canonical manifest.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> report = catalog.writeValidationReport(registry);
        return ToolResponse.ok()
                .put("ok", report.getOrDefault("ok", false))
                .put("issueCount", report.getOrDefault("issueCount", 0))
                .put("warningCount", report.getOrDefault("warningCount", 0))
                .put("resourcePathHash", report.getOrDefault("resourcePathHash", ""))
                .put("resourcePathLength", report.getOrDefault("resourcePathLength", 0))
                .put("reportPathId", report.getOrDefault("reportPathId", ""))
                .put("reportPathHash", report.getOrDefault("reportPathHash", ""))
                .put("reportPathLength", report.getOrDefault("reportPathLength", 0))
                .put("sha256", report.getOrDefault("sha256", ""));
    }
}
