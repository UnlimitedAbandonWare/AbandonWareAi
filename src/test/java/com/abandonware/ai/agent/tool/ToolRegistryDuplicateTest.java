package com.abandonware.ai.agent.tool;

import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ToolRegistryDuplicateTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void duplicateIdsAreRecordedWithoutOverwritingFirstTool() {
        AgentTool first = new StubTool("ops.snapshot", "first");
        AgentTool second = new StubTool("ops.snapshot", "second");
        ToolRegistry registry = new ToolRegistry();

        registry.register(first);
        registry.register(second);

        assertSame(first, registry.get("ops.snapshot").orElseThrow());
        assertEquals(1, registry.duplicateToolIdCounts().get("ops.snapshot"));
        assertEquals(1, registry.all().size());
        assertEquals("ops.snapshot", TraceStore.get("toolRegistry.duplicate.id"));
        assertEquals("StubTool", TraceStore.get("toolRegistry.duplicate.rejected"));
        assertEquals(1L, TraceStore.get("toolRegistry.duplicate.count"));
        assertEquals("StubTool", TraceStore.get("toolRegistry.registered.ops.snapshot"));
    }

    private record StubTool(String id, String description) implements AgentTool {
        @Override
        public ToolResponse execute(ToolRequest request) {
            return ToolResponse.ok();
        }
    }
}
