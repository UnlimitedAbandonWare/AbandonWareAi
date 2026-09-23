package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.GenericReverseGeocodingClient;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;




/**
 * Converts geographic coordinates into a human readable address using the
 * Channel reverse geocoding API.  Requires the {@code geo.read} scope.
 */
@Component
@RequiresScopes({ToolScope.GEO_READ})
public class GeoReverseTool implements AgentTool {
    private final GenericReverseGeocodingClient client;

    public GeoReverseTool(GenericReverseGeocodingClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return "geo.reverse";
    }

    @Override
    public String description() {
        return "Reverse geocode coordinates into an address.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        Double x = number(input.get("x"));
        Double y = number(input.get("y"));
        TraceStore.put("tool.geo.reverse.coordinatesProvided", x != null && y != null);
        TraceStore.put("tool.geo.reverse.coordinateHash", GenericReverseGeocodingClient.coordinateHash(x, y));
        if (x == null || y == null) {
            TraceStore.put("tool.geo.reverse.status", "SKIPPED");
            TraceStore.put("tool.geo.reverse.skipped.reason", "MISSING_COORDINATES");
            return ToolResponse.ok()
                    .put("available", true)
                    .put("address", Map.of())
                    .put("skippedReason", "MISSING_COORDINATES");
        }
        try {
            Map<String, Object> address = client.lookup(x, y);
            Map<String, Object> safeAddress = address == null ? Map.of() : new LinkedHashMap<>(address);
            TraceStore.put("tool.geo.reverse.status", "OK");
            TraceStore.put("tool.geo.reverse.returned", !safeAddress.isEmpty());
            TraceStore.put("tool.geo.reverse.skipped.reason", null);
            return ToolResponse.ok().put("address", safeAddress);
        } catch (RuntimeException ex) {
            TraceStore.put("tool.geo.reverse.status", "FAIL_SOFT");
            TraceStore.put("tool.geo.reverse.skipped.reason", "CLIENT_EXCEPTION");
            TraceStore.put("tool.geo.reverse.failReason",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            TraceStore.put("tool.geo.reverse.failMsgHash", SafeRedactor.hashValue(ex.getMessage()));
            return ToolResponse.ok()
                    .put("address", Map.of())
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
            String raw = value == null ? null : String.valueOf(value);
            TraceStore.put("tool.geo.reverse.suppressed", true);
            TraceStore.put("tool.geo.reverse.suppressed.stage", "coordinate_parse");
            TraceStore.put("tool.geo.reverse.suppressed.errorType", "invalid_number");
            TraceStore.put("tool.geo.reverse.suppressed.valueHash", SafeRedactor.hashValue(raw));
            TraceStore.put("tool.geo.reverse.suppressed.valueLength", raw == null ? 0 : raw.length());
            return null;
        }
    }
}
