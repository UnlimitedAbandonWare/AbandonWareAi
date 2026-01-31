package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.HybridRetriever;
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
 * Module: com.abandonware.ai.agent.tool.impl.RagRetrieveTool
 * Role: config
 * Dependencies: com.abandonware.ai.agent.integrations.HybridRetriever, com.abandonware.ai.agent.tool.AgentTool, com.abandonware.ai.agent.tool.ToolScope, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.impl.RagRetrieveTool
role: config
*/
public class RagRetrieveTool implements AgentTool {
    private final HybridRetriever retriever;

    public RagRetrieveTool(HybridRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public String id() {
        return "rag.retrieve";
    }

    @Override
    public String description() {
        return "Retrieve evidence snippets from internal RAG sources.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request.input();
        String query = (String) input.get("query");
        Integer topK = input.containsKey("topK") ? ((Number) input.get("topK")).intValue() : null;
        String domain = (String) input.get("domain");
        Long sessionId = null; if (input.containsKey("sessionId")) { try { sessionId = Long.valueOf(String.valueOf(input.get("sessionId"))); } catch(Exception ignore){} }
List<Map<String, Object>> results = retriever.retrieve(query, topK, domain);
        return ToolResponse.ok().put("results", results);
    }
}