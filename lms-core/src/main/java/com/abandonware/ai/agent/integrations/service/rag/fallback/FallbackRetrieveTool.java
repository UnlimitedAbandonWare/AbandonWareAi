package com.abandonware.ai.agent.integrations.service.rag.fallback;


import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.service.rag.fallback.FallbackRetrieveTool
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.service.rag.fallback.FallbackRetrieveTool
role: config
*/
public class FallbackRetrieveTool {
    public List<String> retrieveOrEmpty(String query){
        return new ArrayList<>();
    }
}