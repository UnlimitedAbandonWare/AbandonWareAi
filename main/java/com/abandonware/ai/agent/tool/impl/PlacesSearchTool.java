package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.GenericPlacesClient;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;




/**
 * Performs a local search using the Channel Local Search API.  Requires the
 * {@code places.read} scope.  The shim returns an empty list.
 */
@Component
@RequiresScopes({ToolScope.PLACES_READ})
public class PlacesSearchTool implements AgentTool {
    private final GenericPlacesClient client;

    public PlacesSearchTool(GenericPlacesClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return "places.search";
    }

    @Override
    public String description() {
        return "Search for nearby places via Channel Local Search.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        String query = input.get("query") == null ? "" : String.valueOf(input.get("query")).trim();
        Double x = number(input.get("x"));
        Double y = number(input.get("y"));
        Integer radius = integer(input.get("radius"));
        TraceStore.put("tool.places.search.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("tool.places.search.queryLength", query.length());
        TraceStore.put("tool.places.search.coordinatesProvided", x != null && y != null);
        TraceStore.put("tool.places.search.requestedRadius", radius == null ? 0 : Math.max(0, radius));
        if (query.isBlank()) {
            TraceStore.put("tool.places.search.status", "SKIPPED");
            TraceStore.put("tool.places.search.skipped.reason", "EMPTY_QUERY");
            TraceStore.put("tool.places.search.returnedCount", 0);
            return ToolResponse.ok()
                    .put("results", List.of())
                    .put("skippedReason", "EMPTY_QUERY");
        }
        try {
            List<Map<String, Object>> results = client.search(query, x, y, radius);
            List<Map<String, Object>> safeResults = results == null ? List.of() : results;
            TraceStore.put("tool.places.search.status", "OK");
            TraceStore.put("tool.places.search.returnedCount", safeResults.size());
            TraceStore.put("tool.places.search.zeroResults", safeResults.isEmpty());
            TraceStore.put("tool.places.search.skipped.reason", safeResults.isEmpty() ? "NO_PLACES_RESULTS" : null);
            return ToolResponse.ok().put("results", safeResults);
        } catch (RuntimeException ex) {
            TraceStore.put("tool.places.search.status", "FAIL_SOFT");
            TraceStore.put("tool.places.search.skipped.reason", "CLIENT_EXCEPTION");
            TraceStore.put("tool.places.search.failReason",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            TraceStore.put("tool.places.search.failMsgHash", SafeRedactor.hashValue(ex.getMessage()));
            TraceStore.put("tool.places.search.returnedCount", 0);
            return ToolResponse.ok()
                    .put("results", List.of())
                    .put("skippedReason", "CLIENT_EXCEPTION");
        }
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            traceParseSuppressed("coordinate_parse", value);
            return null;
        }
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            traceParseSuppressed("radius_parse", value);
            return null;
        }
    }

    private static void traceParseSuppressed(String stage, Object value) {
        String raw = value == null ? null : String.valueOf(value);
        TraceStore.put("tool.places.search.suppressed", true);
        TraceStore.put("tool.places.search.suppressed.stage", stage);
        TraceStore.put("tool.places.search.suppressed.errorType", "invalid_number");
        TraceStore.put("tool.places.search.suppressed.valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put("tool.places.search.suppressed.valueLength", raw == null ? 0 : raw.length());
    }
}
