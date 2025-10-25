package com.example.lms.api;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;

import java.util.UUID;

// Minimal Spring-like controller (annotations omitted to avoid hard deps)
// Frameworks can add @RestController/@PostMapping as needed without breaking this code.
public class RagOrchestratorController {

    private final UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();

    // Pseudo endpoint method
    public QueryResponse query(QueryRequest req) {
        if (req == null) {
            req = new QueryRequest();
            req.query = "hello world";
        }
        return orchestrator.query(req);
    }

    // Probe variant with safer defaults
    public QueryResponse probe(String q) {
        QueryRequest req = new QueryRequest();
        req.query = q;
        req.enableSelfAsk = true;
        req.whitelistOnly = false;
        return orchestrator.query(req);
    }
}