package com.example.lms.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidualIdentifierLogRedactionTest {

    @Test
    void chatSseAndRagLogsLabelHashedIdentifiersAsHashes() throws Exception {
        String chatApi = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);
        String rag = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/LangChainRAGService.java"),
                StandardCharsets.UTF_8);

        assertFalse(chatApi.contains("SSE stream error (sessionId={})"));
        assertTrue(chatApi.contains("SSE stream error (sessionHash={}):"));
        assertFalse(rag.contains("sid={}, err={}"));
        assertTrue(rag.contains("metadata filter failed failureReason={} errorType={} sidHash={}"));
    }

    @Test
    void naverHttpErrorLogsHashOnlyRequestAndSessionIds() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/NaverSearchService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("rid={} sid={} bodyHash={}"));
        assertTrue(source.contains("ridHash={} sidHash={} bodyHash={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(requestId)"));
        assertTrue(source.contains("SafeRedactor.hashValue(sessionId)"));
    }

    @Test
    void vectorKnowledgeAndHybridLogsUseHashOnlySids() throws Exception {
        String sidAdvisor = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/SidRotationAdvisor.java"),
                StandardCharsets.UTF_8);
        String embedding = Files.readString(
                Path.of("main/java/com/example/lms/service/EmbeddingStoreManager.java"),
                StandardCharsets.UTF_8);
        String knowledge = Files.readString(
                Path.of("main/java/com/example/lms/service/knowledge/DefaultKnowledgeBaseService.java"),
                StandardCharsets.UTF_8);
        String hybrid = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/HybridRetriever.java"),
                StandardCharsets.UTF_8);
        String workflow = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        assertFalse(sidAdvisor.contains("rotate recommended sid={}"));
        assertTrue(sidAdvisor.contains("rotate recommended sidHash={}"));
        assertFalse(embedding.contains("sid={} indexed={}"));
        assertFalse(embedding.contains("sid={} active={} indexed={}"));
        assertTrue(embedding.contains("sidHash={} indexed={}"));
        assertTrue(embedding.contains("sidHash={} activeHash={} indexed={}"));
        assertFalse(knowledge.contains("sid={} targetSid={} indexed={}"));
        assertTrue(knowledge.contains("sidHash={} targetSidHash={} indexed={}"));
        assertFalse(hybrid.contains("(sid={}, queryHash12={}, queryLength={})"));
        assertTrue(hybrid.contains("(sidHash={}, queryHash12={}, queryLength={})"));
        assertFalse(workflow.contains("session={}"));
        assertTrue(workflow.contains("sessionHash={}"));
        assertFalse(hybrid.contains("bean '{}' not found for backend='{}'"));
        assertTrue(hybrid.contains("beanHash={} beanLength={} not found for backendHash={} backendLength={}"));
    }
}
