package com.abandonware.ai.agent.integrations.service.rag.fallback;


import java.util.*;
/**
 * FallbackRetrieveTool: returns empty results on external failure.
 */
public class FallbackRetrieveTool {
    public List<String> retrieveOrEmpty(String query){
        return new ArrayList<>();
    }
}