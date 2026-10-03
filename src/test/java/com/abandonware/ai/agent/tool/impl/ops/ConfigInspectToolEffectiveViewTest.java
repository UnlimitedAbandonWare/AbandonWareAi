package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.request.ToolRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigInspectToolEffectiveViewTest {
    @Test
    void effectiveModeUsesEnvironmentPrecedenceForAllowlistedTypedValuesOnly() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-command-line", Map.of(
                "agent.tools.api.enabled", "false",
                "trace.snapshot.max-size", "240",
                "domain.allowlist.admin-token", "synthetic_marker_123")));
        environment.getPropertySources().addAfter("test-command-line", new MapPropertySource("test-application", Map.of(
                "agent.tools.api.enabled", "true",
                "trace.snapshot.max-size", "100")));
        ConfigInspectTool tool = new ConfigInspectTool(environment);

        Map<?, ?> presence = config(tool, Map.of());
        assertFalse(((Map<?, ?>) presence.get("agent.tools.api.enabled")).containsKey("effectiveValue"));

        Map<?, ?> effective = config(tool, Map.of("mode", "effective"));
        assertTrue(effective.containsKey("trace.snapshot.max-size"));
        assertEquals(false, ((Map<?, ?>) effective.get("agent.tools.api.enabled")).get("effectiveValue"));
        assertEquals(240, ((Map<?, ?>) effective.get("trace.snapshot.max-size")).get("effectiveValue"));
        assertFalse(((Map<?, ?>) effective.get("domain.allowlist.admin-token")).containsKey("effectiveValue"));
        assertFalse(effective.toString().contains("synthetic_marker_123"));
    }

    @Test
    void invalidTypedValueIsNotPresentedAsEffective() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-command-line",
                Map.of("trace.snapshot.max-size", "many")));
        Map<?, ?> row = (Map<?, ?>) config(new ConfigInspectTool(environment),
                Map.of("mode", "effective")).get("trace.snapshot.max-size");
        assertEquals("invalid_configuration", row.get("valueStatus"));
        assertFalse(row.containsKey("effectiveValue"));
    }

    private static Map<?, ?> config(ConfigInspectTool tool, Map<String, Object> input) {
        return (Map<?, ?>) tool.execute(new ToolRequest(input, null)).data().get("config");
    }
}
