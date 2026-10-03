package com.example.lms.service;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the call-site boundary so an empty/failed approved tool result cannot buy a new web pass. */
class ChatWorkflowAgentWebBudgetContractTest {
    @Test void toolOwnershipPrecedesRetrievalAndGatesNeedleProbe() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        int ownership = source.indexOf("approvedToolWebEvidence = externalCtxProvider instanceof WebEvidenceSupplier");
        int call = source.indexOf("supplier.evidence(q0)");
        assertTrue(ownership >= 0 && ownership < call,
                "skipping or failing the supplier must still retain tool-owned request boundaries");
        int probe = source.indexOf("needleProbeEngine.maybeProbe(");
        int guard = source.lastIndexOf("if (approvedToolWebEvidence == null && useWeb", probe);
        assertTrue(guard >= 0 && guard < probe,
                "Needle Probe must not issue unbudgeted web calls after a tool-owned result");
    }
}
