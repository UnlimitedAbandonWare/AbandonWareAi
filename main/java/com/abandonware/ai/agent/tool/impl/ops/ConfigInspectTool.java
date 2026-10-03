package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

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
            "agent.tools.policy.enabled",
            "lms.debug.events.enabled",
            "lms.debug.events.max-size",
            "trace.snapshot.enabled",
            "trace.snapshot.max-size"
    );
    private static final Map<String, String> TYPED_KEYS = Map.of(
            "agent.tools.api.enabled", "boolean",
            "agent.tools.policy.enabled", "boolean",
            "lms.debug.events.enabled", "boolean",
            "lms.debug.events.max-size", "integer",
            "trace.snapshot.enabled", "boolean",
            "trace.snapshot.max-size", "integer");

    private final Environment environment;
    private final ObjectProvider<TraceSnapshotStore> snapshots;

    public ConfigInspectTool(Environment environment) {
        this(environment, null);
    }

    public ConfigInspectTool(Environment environment, ObjectProvider<TraceSnapshotStore> snapshots) {
        this.environment = environment;
        this.snapshots = snapshots;
    }

    @Override
    public String id() {
        return "config.inspect";
    }

    @Override
    public String description() {
        return "Inspect configuration presence or explicitly allowlisted typed effective values.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        String mode = input.get("mode") == null ? "presence" : String.valueOf(input.get("mode")).trim();
        if (!"presence".equals(mode) && !"effective".equals(mode))
            throw ToolInvocationException.badRequest("invalid_mode");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = environment == null ? "" : environment.getProperty(key, "");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configured", !ConfigValueGuards.isMissing(value));
            row.put("placeholderOrMissing", ConfigValueGuards.isMissing(value));
            row.put("secretLike", key.toLowerCase().contains("token") || key.toLowerCase().contains("secret"));
            if ("effective".equals(mode) && TYPED_KEYS.containsKey(key)) {
                addEffective(key, value, row);
            }
            out.put(key, row);
        }
        return ToolResponse.ok().put("mode", mode).put("config", out);
    }

    private void addEffective(String key, String raw, Map<String, Object> row) {
        row.put("key", key);
        row.put("consumer", consumer(key));
        row.put("consumerActive", "not_observed");
        row.put("effectiveSourceKind", sourceKind(key));
        if (key.startsWith("trace.snapshot.") && snapshots != null) {
            TraceSnapshotStore store = snapshots.getIfAvailable();
            if (store != null) {
                Map<String, Object> stats = store.retentionStats();
                Object actual = key.endsWith(".enabled") ? stats.get("captureEnabled") : stats.get("capacity");
                if (actual instanceof Boolean || actual instanceof Number) {
                    row.put("consumerActive", true);
                    row.put("effectiveValue", actual);
                    row.put("valueStatus", "verified_consumer");
                    return;
                }
            }
        }
        if (ConfigValueGuards.isMissing(raw)) {
            row.put("valueStatus", "not_observed");
            return;
        }
        if ("boolean".equals(TYPED_KEYS.get(key))) {
            if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                row.put("effectiveValue", Boolean.parseBoolean(raw));
                row.put("valueStatus", "inferred_from_configuration");
            } else row.put("valueStatus", "invalid_configuration");
            return;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed <= 0) throw new NumberFormatException("nonpositive");
            row.put("effectiveValue", parsed);
            row.put("valueStatus", "inferred_from_configuration");
        } catch (NumberFormatException ex) {
            row.put("valueStatus", "invalid_configuration");
        }
    }

    private String sourceKind(String key) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) return "not_observed";
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!source.containsProperty(key)) continue;
            String name = source.getName().toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
            if (name.contains("commandline")) return "command_line";
            if (name.contains("systemproperties")) return "system_properties";
            if (name.contains("systemenvironment")) return "environment";
            if (name.contains("application") || name.contains("configresource")) return "application_config";
            return "property_source";
        }
        return "not_observed";
    }

    private static String consumer(String key) {
        if (key.startsWith("trace.snapshot.")) return "TraceSnapshotStore";
        if (key.startsWith("lms.debug.events.")) return "DebugEventStore";
        if ("agent.tools.api.enabled".equals(key)) return "InternalAgentToolController";
        return "ToolPolicyEnforcer";
    }
}
