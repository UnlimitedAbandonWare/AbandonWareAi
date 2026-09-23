package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.GenericPlacesClient;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacesSearchToolTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidCoordinateLeavesHashOnlySuppressionBreadcrumb() {
        PlacesSearchTool tool = new PlacesSearchTool(new StubPlacesClient());
        String raw = "private coordinate ownerToken=secret";

        tool.execute(new ToolRequest(
                Map.of("query", "coffee", "x", raw, "y", 37.5, "radius", 100),
                new ToolContext("ctx", null)));

        assertEquals(Boolean.TRUE, TraceStore.get("tool.places.search.suppressed"));
        assertEquals("coordinate_parse", TraceStore.get("tool.places.search.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("tool.places.search.suppressed.errorType"));
        assertTrue(String.valueOf(TraceStore.get("tool.places.search.suppressed.valueHash")).startsWith("hash:"));
        assertEquals(raw.length(), TraceStore.get("tool.places.search.suppressed.valueLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void invalidRadiusLeavesHashOnlySuppressionBreadcrumb() {
        PlacesSearchTool tool = new PlacesSearchTool(new StubPlacesClient());
        String raw = "private radius ownerToken=secret";

        tool.execute(new ToolRequest(
                Map.of("query", "coffee", "x", 127.0, "y", 37.5, "radius", raw),
                new ToolContext("ctx", null)));

        assertEquals(Boolean.TRUE, TraceStore.get("tool.places.search.suppressed"));
        assertEquals("radius_parse", TraceStore.get("tool.places.search.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("tool.places.search.suppressed.errorType"));
        assertTrue(String.valueOf(TraceStore.get("tool.places.search.suppressed.valueHash")).startsWith("hash:"));
        assertEquals(raw.length(), TraceStore.get("tool.places.search.suppressed.valueLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    private static final class StubPlacesClient extends GenericPlacesClient {
        @Override
        public List<Map<String, Object>> search(String query, Double x, Double y, Integer radius) {
            return List.of();
        }
    }
}
