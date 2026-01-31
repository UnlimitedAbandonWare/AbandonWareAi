package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.WebSearchGateway;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;




/**
 * Performs a web search for recent information.  Requires the {@code web.get}
 * scope.  This shim returns an empty list.
 */
@Component
@RequiresScopes({ToolScope.WEB_GET})
public class WebSearchTool implements AgentTool {
    private final WebSearchGateway search;

    public WebSearchTool(WebSearchGateway search) { this.search = search; }

    @Override
    public String id() {
        return "web.search";
    }

    @Override
    public String description() {
        return "Perform a web search for up to topK recent results.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request.input();
        String query = (String) input.get("query");
        Integer topK = input.containsKey("topK") ? ((Number) input.get("topK")).intValue() : null;
        String lang = (String) input.get("lang");
        List<Map<String, Object>> results = search.searchAndRank(query, topK != null ? topK : 5, lang != null ? lang : "ko");
        return ToolResponse.ok().put("results", results);
    }
}