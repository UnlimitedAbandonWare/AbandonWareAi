package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.KakaoReverseGeocodingClient;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.springframework.stereotype.Component;
import java.util.Map;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.tool.impl.GeoReverseTool
 * Role: config
 * Dependencies: com.abandonware.ai.agent.integrations.KakaoReverseGeocodingClient, com.abandonware.ai.agent.tool.AgentTool, com.abandonware.ai.agent.tool.ToolScope, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.impl.GeoReverseTool
role: config
*/
public class GeoReverseTool implements AgentTool {
    private final KakaoReverseGeocodingClient client;

    public GeoReverseTool(KakaoReverseGeocodingClient client) {
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
        Map<String, Object> input = request.input();
        Double x = ((Number) input.get("x")).doubleValue();
        Double y = ((Number) input.get("y")).doubleValue();
        Map<String, Object> address = client.lookup(x, y);
        return ToolResponse.ok().put("address", address);
    }
}