package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.config.ConfigValueGuards;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class ConfigInspectTool implements AgentTool {
    private static final List<String> KEYS = List.of(
            "domain.allowlist.admin-token",
            "llm.owner-token",
            "probe.admin-token",
            "runtime.config.guard.enabled",
            "runtime.config.guard.strict",
            "lms.debug.mask-secrets",
            "management.endpoints.web.exposure.include",
            "agent.tools.api.enabled",
            "agent.tools.policy.enabled"
    );

    private final Environment environment;

    public ConfigInspectTool(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String id() {
        return "config.inspect";
    }

    @Override
    public String description() {
        return "Inspect selected runtime configuration presence and risk flags without values.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = environment == null ? "" : environment.getProperty(key, "");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configured", !ConfigValueGuards.isMissing(value));
            row.put("placeholderOrMissing", ConfigValueGuards.isMissing(value));
            row.put("secretLike", key.toLowerCase().contains("token") || key.toLowerCase().contains("secret"));
            out.put(key, row);
        }
        return ToolResponse.ok().put("config", out);
    }
}
