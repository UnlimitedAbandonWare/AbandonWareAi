package com.abandonware.ai.agent.tool;

import com.abandonware.ai.agent.integrations.GenericReverseGeocodingClient;
import com.abandonware.ai.agent.tool.impl.GeoReverseTool;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeoReverseToolLocationSafetyTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void noProviderShimDoesNotEchoRawCoordinates() {
        GeoReverseTool tool = new GeoReverseTool(new GenericReverseGeocodingClient());

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("x", 127.02758, "y", 37.49794),
                new ToolContext("session-1", null)));

        String rendered = response.data().toString();
        assertThat(rendered)
                .doesNotContain("127.02758")
                .doesNotContain("37.49794");
        assertThat(rendered)
                .contains("coordinateHash")
                .contains("coordinatesProvided=true")
                .contains("provider=outbox/noop");
    }

    @Test
    void invalidCoordinateLeavesHashOnlySuppressionBreadcrumb() {
        GeoReverseTool tool = new GeoReverseTool(new GenericReverseGeocodingClient());
        String raw = "private geo ownerToken=secret";

        tool.execute(new ToolRequest(
                Map.of("x", raw, "y", 37.49794),
                new ToolContext("session-1", null)));

        assertThat(TraceStore.get("tool.geo.reverse.suppressed")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("tool.geo.reverse.suppressed.stage")).isEqualTo("coordinate_parse");
        assertThat(TraceStore.get("tool.geo.reverse.suppressed.errorType")).isEqualTo("invalid_number");
        assertThat(String.valueOf(TraceStore.get("tool.geo.reverse.suppressed.valueHash"))).startsWith("hash:");
        assertThat(TraceStore.get("tool.geo.reverse.suppressed.valueLength")).isEqualTo(raw.length());
        assertThat(String.valueOf(TraceStore.getAll()))
                .doesNotContain(raw)
                .doesNotContain("ownerToken");
    }
}
