package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.KakaoPlacesClient;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.tool.impl.PlacesSearchTool
 * Role: config
 * Dependencies: com.abandonware.ai.agent.integrations.KakaoPlacesClient, com.abandonware.ai.agent.tool.AgentTool, com.abandonware.ai.agent.tool.ToolScope, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.impl.PlacesSearchTool
role: config
*/
public class PlacesSearchTool implements AgentTool {
    private final KakaoPlacesClient client;

    public PlacesSearchTool(KakaoPlacesClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return "places.search";
    }

    @Override
    public String description() {
        return "Search for nearby places via Kakao Local Search.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request.input();
        String query = (String) input.get("query");
        Double x = input.containsKey("x") ? ((Number) input.get("x")).doubleValue() : null;
        Double y = input.containsKey("y") ? ((Number) input.get("y")).doubleValue() : null;
        Integer radius = input.containsKey("radius") ? ((Number) input.get("radius")).intValue() : null;
        List<Map<String, Object>> results = client.search(query, x, y, radius);
        return ToolResponse.ok().put("results", results);
    }
}