package com.abandonware.ai.agent.tool;

import com.abandonware.ai.agent.orchestrator.support.SchemaRegistry;
import org.junit.jupiter.api.Test;



import static org.assertj.core.api.Assertions.*;

/**
 * Snapshot test to ensure every registered tool has a schema file.
 */
public class AllToolsSchemaPresenceTest {

    @Test
    void allTools_haveSchemas() {
        var registry = new ToolRegistry();
        var schema = new SchemaRegistry();
        registry.listRegisteredTools().forEach(toolId ->
            assertThat(schema.schemaFor(toolId))
                .as("missing schema for " + toolId)
                .isNotNull()
        );
    }
}