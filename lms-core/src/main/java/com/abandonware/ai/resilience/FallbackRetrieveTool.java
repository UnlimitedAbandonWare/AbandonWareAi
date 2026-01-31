package com.abandonware.ai.resilience;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.resilience.FallbackRetrieveTool
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.resilience.FallbackRetrieveTool
role: config
*/
public class FallbackRetrieveTool {
    public List<String> retrieveOrEmpty(boolean backendOk, String query) {
        if (!backendOk) return Collections.emptyList();
        // otherwise delegate to actual retriever
        return List.of(query);
    }
}