package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalHandlerRedactionContractTest {

    @Test
    void retrievalHandlerTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();
        try {
            RetrievalHandlerTraceSuppressions.trace(
                    "fusionScore.parse",
                    new NumberFormatException("ownerToken=raw-secret"));

            assertEquals("invalid_number",
                    TraceStore.get("retrieval.handler.suppressed.fusionScore.parse.errorType"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("NumberFormatException"), trace);
            assertFalse(trace.contains("ownerToken=raw-secret"), trace);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void retrievalHandlerTraceSuppressionsIncludeSafeAggregateStageAndErrorType() {
        TraceStore.clear();
        try {
            String rawStage = "fusionScore.parse " + com.example.lms.test.SecretFixtures.openAiKey();
            RetrievalHandlerTraceSuppressions.trace(
                    rawStage,
                    new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

            Object safeStage = TraceStore.get("retrieval.handler.suppressed.stage");
            assertTrue(String.valueOf(safeStage).startsWith("hash:"));
            assertEquals(Boolean.TRUE, TraceStore.get("retrieval.handler.suppressed." + safeStage));
            assertEquals("silent-failure", TraceStore.get("retrieval.handler.suppressed.errorType"));
            assertEquals("silent-failure",
                    TraceStore.get("retrieval.handler.suppressed." + safeStage + ".errorType"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void handlerLogsDoNotWriteRawSubjectAnchorsOrErrorMessages() throws Exception {
        String pairing = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/PairingGuardHandler.java"),
                StandardCharsets.UTF_8);
        String abstractHandler = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/AbstractRetrievalHandler.java"),
                StandardCharsets.UTF_8);

        assertFalse(pairing.contains("subject='{}'"));
        assertFalse(pairing.contains("anchored={}"));
        assertTrue(pairing.contains("domainHash={} domainLength={} subjectHash={} subjectLength={} anchoredCount={}"));
        assertTrue(pairing.contains("SafeRedactor.hashValue(subject)"));

        assertFalse(abstractHandler.contains("Error: {}\",\r\n                    handler, t.getMessage())")
                || abstractHandler.contains("Error: {}\",\n                    handler, t.getMessage())"));
        assertFalse(abstractHandler.contains("ev.put(\"errMsg\","));
        assertTrue(abstractHandler.contains("Handler handlerHash={} handlerLength={} failed"));
        assertTrue(abstractHandler.contains("SafeRedactor.hashValue(handler)"));
        assertFalse(abstractHandler.contains("SafeRedactor.safeMessage(t.getMessage(), 180)"));
        assertTrue(abstractHandler.contains("Error: errorHash={} errorLength={}"));
        assertTrue(abstractHandler.contains(
                "SafeRedactor.hashValue(t.getMessage()), t.getMessage() == null ? 0 : t.getMessage().length()"));
        assertTrue(abstractHandler.contains("ev.put(\"errMsgHash\", SafeRedactor.hashValue(msg));"));
        assertTrue(abstractHandler.contains("ev.put(\"errMsgLength\", msg.length());"));
    }

    @Test
    void retrievalChainErrorBreadcrumbsUseStableOperationalLabels() throws Exception {
        String abstractHandler = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/AbstractRetrievalHandler.java"),
                StandardCharsets.UTF_8);
        String ambiguityGuard = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/AmbiguityGuardHandler.java"),
                StandardCharsets.UTF_8);

        assertFalse(abstractHandler.contains("ev.put(\"err\", err.getClass().getSimpleName());"),
                "retrieval chain event should not expose Java exception class names");
        assertFalse(abstractHandler.contains(
                        "TraceStore.put(\"retrieval.chain.last.err\", err.getClass().getSimpleName())"),
                "retrieval chain last error should not expose Java exception class names");
        assertTrue(abstractHandler.contains("ev.put(\"err\", \"retrieval_handler_failed\");"),
                "retrieval chain event should use a stable operational error label");
        assertTrue(abstractHandler.contains(
                        "TraceStore.put(\"retrieval.chain.last.err\", \"retrieval_handler_failed\")"),
                "retrieval chain last error should use a stable operational error label");
        assertTrue(abstractHandler.contains("log.debug(\"[RetrievalChain] fail-soft stage={} err={}"));
        assertTrue(ambiguityGuard.contains("log.debug(\"[AmbiguityGuardHandler] fail-soft stage={} err={}"));
        assertFalse(ambiguityGuard.contains("catch (Exception ignore) {"));
    }

    @Test
    void smallHandlerFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String entity = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/EntityDisambiguationHandler.java"), StandardCharsets.UTF_8);
        String repair = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/EvidenceRepairHandler.java"), StandardCharsets.UTF_8);
        String fusion = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/FusionScoreDiagnostics.java"), StandardCharsets.UTF_8);
        String kalloc = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/KAllocArtPlateBridge.java"), StandardCharsets.UTF_8);

        assertTrue(entity.contains("traceSuppressed(log, \"EntityDisambiguationHandler\", \"confidence.parse\""));
        assertTrue(repair.contains("traceSuppressed(log, \"EvidenceRepairHandler\", \"repair.trace\""));
        assertTrue(fusion.contains("RetrievalHandlerTraceSuppressions.trace(\"fusionScore.parse\""));
        assertTrue(kalloc.contains("RetrievalHandlerTraceSuppressions.trace(\"kalloc.artplate.metaDouble\""));
    }

    @Test
    void suppressedBreadcrumbHelperUsesOperationalFailureLabels() throws Exception {
        String helper = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/RetrievalHandlerTraceSuppressions.java"), StandardCharsets.UTF_8);

        assertFalse(helper.contains("TraceStore.put(\"retrieval.handler.suppressed.\" + safeStage + \".errorType\", failure.getClass().getSimpleName())"));
        assertFalse(helper.contains("String errorType = failure == null ? \"unknown\" : failure.getClass().getSimpleName();"));
        assertTrue(helper.contains("return \"invalid_number\";"));
        assertTrue(helper.contains("return \"silent-failure\";"));
    }

    @Test
    void fixedChainRoutingAndWebHandlersLeaveSuppressedStageBreadcrumbs() throws Exception {
        String kgFixed = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/KnowledgeGraphRetrievalHandler.java"), StandardCharsets.UTF_8);
        String memory = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/MemoryHandler.java"), StandardCharsets.UTF_8);
        String gate = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/OrchestrationGate.java"), StandardCharsets.UTF_8);
        String precondition = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/PreconditionCheckHandler.java"), StandardCharsets.UTF_8);
        String route = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/QueryRouteHandler.java"), StandardCharsets.UTF_8);
        String cost = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/SearchCostGuardHandler.java"), StandardCharsets.UTF_8);
        String web = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/WebSearchHandler.java"), StandardCharsets.UTF_8);

        assertTrue(kgFixed.contains("RetrievalHandlerTraceSuppressions.trace(\"kg.fixedChain.trace\""));
        assertTrue(memory.contains("traceSuppressed(log, \"MemoryHandler\", \"memory.rehydrate.trace\""));
        assertTrue(gate.contains("retrieval.handler.suppressed.orchestration.cooldown"));
        assertTrue(gate.contains("RetrievalHandlerTraceSuppressions.trace(\"orchestration.cooldown\""));
        assertTrue(gate.contains("retrieval.handler.suppressed.orchestration.metadata"));
        assertTrue(gate.contains("RetrievalHandlerTraceSuppressions.trace(\"orchestration.metadata\""));
        assertTrue(gate.contains("retrieval.handler.suppressed.orchestration.sessionId"));
        assertTrue(gate.contains("RetrievalHandlerTraceSuppressions.trace(\"orchestration.sessionId\""));
        assertTrue(precondition.contains("traceSuppressed(log, \"PreconditionCheckHandler\", \"metadata.bool\""));
        assertTrue(precondition.contains("traceSuppressed(log, \"PreconditionCheckHandler\", \"metadata.string\""));
        assertTrue(route.contains("retrieval.handler.suppressed.route.metadataRead"));
        assertTrue(route.contains("RetrievalHandlerTraceSuppressions.trace(\"route.metadataRead\""));
        assertTrue(route.contains("retrieval.handler.suppressed.route.metadataWrite"));
        assertTrue(route.contains("RetrievalHandlerTraceSuppressions.trace(\"route.metadataWrite\""));
        assertTrue(route.contains("retrieval.handler.suppressed.route.decision"));
        assertTrue(route.contains("RetrievalHandlerTraceSuppressions.trace(\"route.decision\""));
        assertTrue(route.contains(": \"route-failure\""));
        assertTrue(cost.contains("retrieval.handler.suppressed.costGuard.estimate"));
        assertTrue(cost.contains("RetrievalHandlerTraceSuppressions.trace(\"costGuard.estimate\""));
        assertTrue(cost.contains("retrieval.handler.suppressed.costGuard.relief"));
        assertTrue(cost.contains("RetrievalHandlerTraceSuppressions.trace(\"costGuard.relief\""));
        assertTrue(cost.contains(": \"cost-guard-failure\""));
        assertTrue(web.contains("traceSuppressed(log, \"WebSearchHandler\", \"selectedTerms.summary\""));
        assertTrue(web.contains("traceSuppressed(log, \"WebSearchHandler\", \"effectiveQuery.trace\""));
        assertTrue(web.contains("retrieval.handler.suppressed.web.appliedTokens.read"));
        assertTrue(web.contains("RetrievalHandlerTraceSuppressions.trace(\"web.appliedTokens.read\""));
        assertTrue(web.contains("retrieval.handler.suppressed.web.appliedTokens.write"));
        assertTrue(web.contains("RetrievalHandlerTraceSuppressions.trace(\"web.appliedTokens.write\""));
        assertTrue(web.contains("retrieval.handler.suppressed.web.ruleTrace"));
        assertTrue(web.contains("RetrievalHandlerTraceSuppressions.trace(\"web.ruleTrace\""));
    }
}
