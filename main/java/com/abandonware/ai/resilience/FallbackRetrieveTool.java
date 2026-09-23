package com.abandonware.ai.resilience;

import java.util.*;

public class FallbackRetrieveTool {
    public List<String> retrieveOrEmpty(boolean backendOk, String query) {
        if (!backendOk) return Collections.emptyList();
        // otherwise delegate to actual retriever
        return List.of(query);
    }
}