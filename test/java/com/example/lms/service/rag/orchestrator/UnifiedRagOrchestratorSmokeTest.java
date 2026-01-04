package com.example.lms.service.rag.orchestrator;

import org.junit.Test;
import static org.junit.Assert.*;

public class UnifiedRagOrchestratorSmokeTest {

    @Test
    public void pipeline_runs_with_defaults() {
        UnifiedRagOrchestrator orch = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest req = new UnifiedRagOrchestrator.QueryRequest();
        req.query = "RAG orchestration";
        UnifiedRagOrchestrator.QueryResponse resp = orch.query(req);
        assertNotNull(resp);
        assertTrue(resp.results.size() > 0);
    }
}