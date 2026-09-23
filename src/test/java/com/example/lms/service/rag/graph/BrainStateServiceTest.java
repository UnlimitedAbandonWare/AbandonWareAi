package com.example.lms.service.rag.graph;

import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrainStateServiceTest {

    @Test
    void traceLongRejectsNonFiniteNumbers() throws Exception {
        TraceStore.clear();
        Method traceLong = BrainStateService.class.getDeclaredMethod("traceLong", Map.class, String.class);
        traceLong.setAccessible(true);

        assertEquals(0L, traceLong.invoke(null, Map.of("count", Double.POSITIVE_INFINITY), "count"));
        assertEquals(0L, traceLong.invoke(null, Map.of("count", "ownerToken=raw-count"), "count"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.brainState.suppressed.traceLong"));
        assertEquals("invalid_number", TraceStore.get("retrieval.kg.brainState.suppressed.traceLong.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-count"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        TraceStore.clear();
    }

    @Test
    void snapshotListsCountsWithoutRawChunkText() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.disabledReason()).thenReturn("disabled");
        when(writer.status()).thenReturn(Map.of("enabled", false));
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        BrainStateService service = new BrainStateService(props, writer, provider);
        service.recordChunks(List.of(new KgChunk(
                "chunk-1",
                "42",
                "raw secret conversation text",
                List.of(
                        new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.8),
                        new KgChunk.KgEntity("Beta", "ENTITY", "GENERAL", 0.7)),
                List.of(new KgChunk.KgRelation("Alpha", "Beta", "CO_MENTIONED_WITH", 0.5)),
                "GENERAL",
                0.7,
                Instant.parse("2026-01-01T00:00:00Z"))));

        BrainSnapshot snapshot = service.getBrainSnapshot("42");

        assertEquals(1, snapshot.totalChunks());
        assertEquals(2, snapshot.totalEntities());
        assertEquals("disabled", snapshot.neo4jStatus());
        assertEquals("disabled", snapshot.disabledReason());
        assertEquals(1, snapshot.domainSummaries().size());
        assertEquals("GENERAL", snapshot.domainSummaries().get(0).domain());
        assertFalse(snapshot.toString().contains("raw secret conversation text"));
        assertEquals(1, snapshot.sourceSummaries().size());
        assertEquals("UNKNOWN", snapshot.sourceSummaries().get(0).sourceTag());
        assertEquals(1, snapshot.recentChanges().size());
        assertEquals(12, snapshot.recentChanges().get(0).chunkHash12().length());
        assertEquals(12, snapshot.recentChanges().get(0).sessionHash12().length());
        assertFalse(snapshot.recentChanges().toString().contains("raw secret conversation text"));
        assertEquals(false, snapshot.anchorMap().enabled());
        assertEquals("no_recent_query", snapshot.queryTime().status());
        assertEquals(1, snapshot.topPortMappings().size());
        assertEquals("semantic_out", snapshot.topPortMappings().get(0).sourcePort());
        assertEquals("semantic_in", snapshot.topPortMappings().get(0).targetPort());
        assertEquals(12, snapshot.topPortMappings().get(0).connectorHash12().length());
        assertFalse(snapshot.topPortMappings().toString().contains("Alpha"));
        assertEquals(2, service.listEntityNodes("GENERAL", 10).size());
    }

    @Test
    void snapshotListsSourcesRecentAnchorMapAndLatestQueryTimeWithoutRawText() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BrainStateService service = enabledAnchorMapService(props, writer, provider);
        service.recordChunks(List.of(
                new KgChunk(
                        "chunk-alpha",
                        "s1",
                        "Alpha links Beta with sensitive source wording",
                        List.of(
                                new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.9),
                                new KgChunk.KgEntity("Beta", "ENTITY", "GENERAL", 0.8)),
                        List.of(new KgChunk.KgRelation("Alpha", "Beta", "RELATIONSHIP_LINKS", 0.7)),
                        "GENERAL",
                        0.8,
                        Instant.now(),
                        "CHAT_HISTORY",
                        "conversation",
                        "chat",
                        "manual"),
                new KgChunk(
                        "chunk-finance",
                        "s1",
                        "Finance Hub links Market Route",
                        List.of(
                                new KgChunk.KgEntity("Finance Hub", "ENTITY", "FINANCE", 0.9),
                                new KgChunk.KgEntity("Market Route", "ENTITY", "FINANCE", 0.8)),
                        List.of(new KgChunk.KgRelation("Finance Hub", "Market Route", "RELATIONSHIP_LINKS", 0.7)),
                        "FINANCE",
                        0.8,
                        Instant.now(),
                        "UAW_THUMBNAIL",
                        "thumbnail",
                        "uaw",
                        "warmup")));

        InferenceResult inference = service.querySparseInferenceLocalOnly("What about Alpha?", "GENERAL");
        BrainSnapshot snapshot = service.getBrainSnapshot("s1", "GENERAL", 1);

        assertTrue(inference.enabled());
        assertEquals(1, snapshot.totalChunks());
        assertEquals(1, snapshot.sourceSummaries().size());
        assertEquals("CHAT_HISTORY", snapshot.sourceSummaries().get(0).sourceTag());
        assertEquals("manual", snapshot.sourceSummaries().get(0).lane());
        assertEquals(1, snapshot.recentChanges().size());
        assertEquals("GENERAL", snapshot.recentChanges().get(0).domain());
        assertEquals("CHAT_HISTORY", snapshot.recentChanges().get(0).sourceTag());
        assertEquals(12, snapshot.recentChanges().get(0).chunkHash12().length());
        assertTrue(snapshot.anchorMap().enabled());
        assertEquals("GENERAL", snapshot.anchorMap().domain());
        assertTrue(snapshot.anchorMap().topAnchors().stream()
                .allMatch(anchor -> anchor.entityHash12().matches("[0-9a-f]{12}")));
        assertEquals("success", snapshot.queryTime().status());
        assertEquals(12, snapshot.queryTime().queryHash12().length());
        assertTrue(snapshot.queryTime().matchedEntityCount() > 0L);
        assertFalse(snapshot.toString().contains("sensitive source wording"));
        assertFalse(snapshot.toString().contains("What about Alpha?"));
        assertFalse(snapshot.sourceSummaries().toString().contains("Alpha"));
        assertFalse(snapshot.recentChanges().toString().contains("Alpha"));
        assertTrue(snapshot.domainSummaries().stream().noneMatch(domain -> "FINANCE".equals(domain.domain())));
    }

    @Test
    void sparseInferenceUsesLocalGraphAndHashOnlyQuery() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BrainStateService service = new BrainStateService(props, writer, provider);
        service.recordChunks(List.of(new KgChunk(
                "chunk-1",
                "s1",
                "Alpha meets Beta",
                List.of(
                        new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.8),
                        new KgChunk.KgEntity("Beta", "ENTITY", "GENERAL", 0.8)),
                List.of(new KgChunk.KgRelation("Alpha", "Beta", "CO_MENTIONED_WITH", 0.5)),
                "GENERAL",
                0.8,
                Instant.now())));

        InferenceResult result = service.querySparseInference("What about Alpha?");

        assertTrue(result.enabled());
        assertEquals(12, result.queryHash().length());
        assertTrue(result.matchedEntities().contains("Alpha"));
        assertFalse(result.inferredRelations().isEmpty());
        assertFalse(result.toString().contains("What about Alpha?"));
    }

    @Test
    void sparseInferenceSelfCheckPreservesNeo4jReasonAndFailureClass() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        RagOrchestratorFacade facade = mock(RagOrchestratorFacade.class);
        UnifiedRagOrchestrator.QueryResponse response = new UnifiedRagOrchestrator.QueryResponse();
        response.debug.put("retrieval.kg.neo4j.status", "failed");
        response.debug.put("retrieval.kg.neo4j.disabledReason", "neo4j_query_failed");
        response.debug.put("retrieval.kg.neo4j.failureClass", "silent-failure");
        response.debug.put("rag.eval.kgAxis.neo4jDisabledReason", "neo4j_query_failed");
        response.debug.put("rag.eval.kgAxis.neo4jFailureClass", "silent-failure");
        when(facade.query(any(UnifiedRagOrchestrator.QueryRequest.class))).thenReturn(response);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facade);
        BrainStateService service = new BrainStateService(props, writer, provider);

        InferenceResult result = service.querySparseInference("opaque sparse query");

        assertEquals("failed", result.ragDebug().get("retrieval.kg.neo4j.status"));
        assertEquals("neo4j_query_failed", result.ragDebug().get("retrieval.kg.neo4j.disabledReason"));
        assertEquals("silent-failure", result.ragDebug().get("retrieval.kg.neo4j.failureClass"));
        assertEquals("neo4j_query_failed", result.ragDebug().get("rag.eval.kgAxis.neo4jDisabledReason"));
        assertEquals("silent-failure", result.ragDebug().get("rag.eval.kgAxis.neo4jFailureClass"));
        assertFalse(result.ragDebug().toString().contains("opaque sparse query"));
    }

    @Test
    void sparseInferenceSelfCheckNormalizesCancellationFailureClass() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        RagOrchestratorFacade facade = mock(RagOrchestratorFacade.class);
        when(facade.query(any(UnifiedRagOrchestrator.QueryRequest.class)))
                .thenThrow(new CancellationException("cancelled ownerToken fake-token"));
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facade);
        BrainStateService service = new BrainStateService(props, writer, provider);

        InferenceResult result = service.querySparseInference("secret sparse cancellation query");

        assertEquals(true, result.ragDebug().get("available"));
        assertEquals("cancelled", result.ragDebug().get("failureClass"));
        assertFalse(result.ragDebug().toString().contains("ownerToken"));
        assertFalse(result.ragDebug().toString().contains("secret sparse cancellation query"));
    }

    @Test
    void sparseInferenceFollowsAssociativeMemoryPathBeyondDirectNeighbor() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BrainStateService service = new BrainStateService(props, writer, provider);
        service.recordChunks(List.of(
                new KgChunk(
                        "chunk-1",
                        "s1",
                        "Alpha meets Beta",
                        List.of(
                                new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.9),
                                new KgChunk.KgEntity("Beta", "ENTITY", "GENERAL", 0.8)),
                        List.of(new KgChunk.KgRelation("Alpha", "Beta", "RELATIONSHIP_SEEDS", 0.8)),
                        "GENERAL",
                        0.8,
                        Instant.now()),
                new KgChunk(
                        "chunk-2",
                        "s1",
                        "Beta points to Gamma",
                        List.of(
                                new KgChunk.KgEntity("Beta", "ENTITY", "GENERAL", 0.8),
                                new KgChunk.KgEntity("Gamma", "ENTITY", "GENERAL", 0.7)),
                        List.of(new KgChunk.KgRelation("Beta", "Gamma", "RELATIONSHIP_POINTS_TO", 0.7)),
                        "GENERAL",
                        0.7,
                        Instant.now())));

        InferenceResult result = service.querySparseInference("What about Alpha?");

        assertTrue(result.inferredRelations().stream().anyMatch(path ->
                path.contains("Alpha --RELATIONSHIP_SEEDS--> Beta --RELATIONSHIP_POINTS_TO--> Gamma")));
        assertEquals("associative_path_inference", result.ragDebug().get("sparseNode.mode"));
        assertEquals(1, result.ragDebug().get("sparseNode.matchedEntityCount"));
        assertTrue(((Number) result.ragDebug().get("sparseNode.transitivePathCount")).longValue() > 0L);
        assertTrue(((Number) result.ragDebug().get("sparseNode.portMappingCount")).longValue() > 0L);
        assertTrue(((List<?>) result.ragDebug().get("sparseNode.portMappingHashes")).stream()
                .allMatch(hash -> String.valueOf(hash).length() == 12));
        assertFalse(result.toString().contains("What about Alpha?"));
    }

    @Test
    void queryTimeAnchorMapSeedsLandmarksWhenExactEntityNameIsMissing() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BrainStateService service = enabledAnchorMapService(props, writer, provider);
        service.recordChunks(List.of(
                new KgChunk(
                        "chunk-landmark-1",
                        "s1",
                        "Han River sits near Riverside Park",
                        List.of(
                                new KgChunk.KgEntity("Han River", "PLACE", "GENERAL", 0.9),
                                new KgChunk.KgEntity("Riverside Park", "PLACE", "GENERAL", 0.8)),
                        List.of(new KgChunk.KgRelation("Han River", "Riverside Park", "LANDMARK_NEAR", 0.8)),
                        "GENERAL",
                        0.8,
                        Instant.now()),
                new KgChunk(
                        "chunk-landmark-2",
                        "s1",
                        "Riverside Park connects to Cycling Route",
                        List.of(
                                new KgChunk.KgEntity("Riverside Park", "PLACE", "GENERAL", 0.8),
                                new KgChunk.KgEntity("Cycling Route", "PLACE", "GENERAL", 0.7)),
                        List.of(new KgChunk.KgRelation("Riverside Park", "Cycling Route", "CONNECTS_TO", 0.7)),
                        "GENERAL",
                        0.7,
                        Instant.now())));

        InferenceResult result = service.querySparseInferenceLocalOnly(
                "What was the bike place near the river called?",
                "GENERAL");

        assertTrue(result.enabled());
        assertTrue(result.inferredRelations().stream().anyMatch(path ->
                path.contains("Han River --LANDMARK_NEAR--> Riverside Park")));
        assertEquals(true, result.ragDebug().get("sparseNode.queryAnchorMap.applied"));
        assertEquals("cue_seeded_landmark_anchors", result.ragDebug().get("sparseNode.queryAnchorMap.reason"));
        assertEquals("GENERAL", result.ragDebug().get("sparseNode.domain"));
        assertEquals("GENERAL", result.ragDebug().get("sparseNode.queryAnchorMap.domain"));
        assertTrue(((Number) result.ragDebug().get("sparseNode.queryAnchorMap.seedCount")).longValue() > 0L);
        assertTrue(((List<?>) result.ragDebug().get("sparseNode.queryAnchorMap.seedHashes")).stream()
                .allMatch(hash -> String.valueOf(hash).matches("[0-9a-f]{12}")));
        assertTrue(((Number) result.ragDebug().get("sparseNode.queryAnchorMapSeedCount")).longValue() > 0L);
        assertTrue(((List<?>) result.ragDebug().get("sparseNode.queryAnchorMapSeedHashes")).stream()
                .allMatch(hash -> String.valueOf(hash).matches("[0-9a-f]{12}")));
        assertFalse(result.ragDebug().toString().contains("Han River"));
        assertFalse(result.ragDebug().toString().contains("Riverside Park"));
        assertFalse(result.toString().contains("What was the bike place"));
    }

    @Test
    void queryTimeAnchorMapIsScopedToRequestedDomain() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BrainStateService service = enabledAnchorMapService(props, writer, provider);
        service.recordChunks(List.of(
                new KgChunk(
                        "chunk-general-landmark",
                        "s1",
                        "Han River sits near Riverside Park",
                        List.of(
                                new KgChunk.KgEntity("Han River", "PLACE", "GENERAL", 0.9),
                                new KgChunk.KgEntity("Riverside Park", "PLACE", "GENERAL", 0.8)),
                        List.of(new KgChunk.KgRelation("Han River", "Riverside Park", "LANDMARK_NEAR", 0.8)),
                        "GENERAL",
                        0.8,
                        Instant.now()),
                new KgChunk(
                        "chunk-finance-landmark",
                        "s1",
                        "Finance Hub sits near Market Route",
                        List.of(
                                new KgChunk.KgEntity("Finance Hub", "PLACE", "FINANCE", 0.99),
                                new KgChunk.KgEntity("Market Route", "ROUTE", "FINANCE", 0.99)),
                        List.of(new KgChunk.KgRelation("Finance Hub", "Market Route", "LANDMARK_NEAR", 0.95)),
                        "FINANCE",
                        0.95,
                        Instant.now())));

        InferenceResult result = service.querySparseInferenceLocalOnly(
                "What was the bike place near the river called?",
                "GENERAL");

        assertTrue(result.enabled());
        assertEquals("GENERAL", result.ragDebug().get("sparseNode.domain"));
        assertTrue(result.inferredRelations().stream().anyMatch(path ->
                path.contains("Han River --LANDMARK_NEAR--> Riverside Park")));
        assertTrue(result.inferredRelations().stream().noneMatch(path -> path.contains("Finance Hub")));
        assertEquals(true, result.ragDebug().get("sparseNode.queryAnchorMap.applied"));
        assertTrue(((List<?>) result.ragDebug().get("sparseNode.queryAnchorMap.seedHashes")).stream()
                .allMatch(hash -> String.valueOf(hash).matches("[0-9a-f]{12}")));
        assertTrue(((List<?>) result.ragDebug().get("sparseNode.queryAnchorMapSeedHashes")).stream()
                .allMatch(hash -> String.valueOf(hash).matches("[0-9a-f]{12}")));
        assertFalse(result.ragDebug().toString().contains("Finance Hub"));
        assertFalse(result.toString().contains("What was the bike place"));
    }

    @Test
    void queryTimeAnchorMapDefaultsDisabledUntilExplicitlyEnabled() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BrainStateService service = new BrainStateService(props, writer, provider);
        service.recordChunks(List.of(new KgChunk(
                "chunk-default-disabled",
                "s1",
                "Han River sits near Riverside Park",
                List.of(
                        new KgChunk.KgEntity("Han River", "PLACE", "GENERAL", 0.9),
                        new KgChunk.KgEntity("Riverside Park", "PLACE", "GENERAL", 0.8)),
                List.of(new KgChunk.KgRelation("Han River", "Riverside Park", "LANDMARK_NEAR", 0.8)),
                "GENERAL",
                0.8,
                Instant.now())));

        InferenceResult result = service.querySparseInferenceLocalOnly("bike place near the river", "GENERAL");

        assertTrue(result.enabled());
        assertTrue(result.matchedEntities().isEmpty());
        assertTrue(result.inferredRelations().isEmpty());
        assertEquals(false, result.ragDebug().get("sparseNode.queryAnchorMap.enabled"));
        assertEquals(false, result.ragDebug().get("sparseNode.queryAnchorMap.applied"));
        assertEquals("route_disabled", result.ragDebug().get("sparseNode.queryAnchorMap.reason"));
        assertEquals(0L, ((Number) result.ragDebug().get("sparseNode.queryAnchorMap.seedCount")).longValue());
    }

    @Test
    void sparseInferenceWithNoMatchedSeedFailsClosed() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BrainStateService service = enabledAnchorMapService(props, writer, provider);
        service.recordChunks(List.of(new KgChunk(
                "chunk-unrelated",
                "s1",
                "Alpha links Beta",
                List.of(
                        new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.8),
                        new KgChunk.KgEntity("Beta", "ENTITY", "GENERAL", 0.8)),
                List.of(new KgChunk.KgRelation("Alpha", "Beta", "RELATIONSHIP_LINKS", 0.7)),
                "GENERAL",
                0.8,
                Instant.now())));

        InferenceResult result = service.querySparseInferenceLocalOnly("unrelated topic", "GENERAL");

        assertTrue(result.enabled());
        assertTrue(result.matchedEntities().isEmpty());
        assertTrue(result.inferredRelations().isEmpty());
        assertEquals("no_graph_path", result.disabledReason());
        assertEquals(false, result.ragDebug().get("sparseNode.queryAnchorMap.applied"));
        assertEquals("no_query_anchor_cue", result.ragDebug().get("sparseNode.queryAnchorMap.reason"));
        assertEquals(0L, ((Number) result.ragDebug().get("sparseNode.portMappingCount")).longValue());
        assertTrue(((List<?>) result.ragDebug().get("sparseNode.portMappingHashes")).isEmpty());
    }

    @Test
    void queryTimeAnchorMapDoesNotSeedFromCueOnlyWithoutEvidence() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BrainStateService service = enabledAnchorMapService(props, writer, provider);
        service.recordChunks(List.of(new KgChunk(
                "chunk-landmark-cue-only",
                "s1",
                "Han River sits near Riverside Park",
                List.of(
                        new KgChunk.KgEntity("Han River", "PLACE", "GENERAL", 0.9),
                        new KgChunk.KgEntity("Riverside Park", "PLACE", "GENERAL", 0.8)),
                List.of(new KgChunk.KgRelation("Han River", "Riverside Park", "LANDMARK_NEAR", 0.8)),
                "GENERAL",
                0.8,
                Instant.now())));

        InferenceResult result = service.querySparseInferenceLocalOnly("Where was that place called?", "GENERAL");

        assertTrue(result.enabled());
        assertTrue(result.matchedEntities().isEmpty());
        assertTrue(result.inferredRelations().isEmpty());
        assertEquals(false, result.ragDebug().get("sparseNode.queryAnchorMap.applied"));
        assertEquals("insufficient_anchor_evidence", result.ragDebug().get("sparseNode.queryAnchorMap.reason"));
        assertEquals(0L, ((Number) result.ragDebug().get("sparseNode.queryAnchorMap.seedCount")).longValue());
    }

    @Test
    void queryTimeAnchorMapRequiresDomainWhenNoDomainRequested() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BrainStateService service = enabledAnchorMapService(props, writer, provider);
        service.recordChunks(List.of(
                new KgChunk(
                        "chunk-general-river",
                        "s1",
                        "Han River sits near Riverside Park",
                        List.of(
                                new KgChunk.KgEntity("Han River", "PLACE", "GENERAL", 0.9),
                                new KgChunk.KgEntity("Riverside Park", "PLACE", "GENERAL", 0.8)),
                        List.of(new KgChunk.KgRelation("Han River", "Riverside Park", "LANDMARK_NEAR", 0.8)),
                        "GENERAL",
                        0.8,
                        Instant.now()),
                new KgChunk(
                        "chunk-finance-river",
                        "s1",
                        "River Market sits near Market Route",
                        List.of(
                                new KgChunk.KgEntity("River Market", "PLACE", "FINANCE", 0.9),
                                new KgChunk.KgEntity("Market Route", "ROUTE", "FINANCE", 0.8)),
                        List.of(new KgChunk.KgRelation("River Market", "Market Route", "LANDMARK_NEAR", 0.8)),
                        "FINANCE",
                        0.8,
                        Instant.now())));

        InferenceResult result = service.querySparseInferenceLocalOnly("bike place near the river");

        assertTrue(result.enabled());
        assertTrue(result.matchedEntities().isEmpty());
        assertTrue(result.inferredRelations().isEmpty());
        assertEquals("ALL", result.ragDebug().get("sparseNode.domain"));
        assertEquals(false, result.ragDebug().get("sparseNode.queryAnchorMap.applied"));
        assertEquals("domain_required", result.ragDebug().get("sparseNode.queryAnchorMap.reason"));
        assertTrue(((List<?>) result.ragDebug().get("sparseNode.queryAnchorMap.seedHashes")).isEmpty());
    }

    @Test
    void localOnlySparseInferenceSkipsBoundedRagProvider() {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        BrainStateService service = new BrainStateService(props, writer, provider);
        service.recordChunks(List.of(new KgChunk(
                "chunk-local-only",
                "s1",
                "Alpha links Beta",
                List.of(
                        new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.8),
                        new KgChunk.KgEntity("Beta", "ENTITY", "GENERAL", 0.8)),
                List.of(new KgChunk.KgRelation("Alpha", "Beta", "RELATIONSHIP_LINKS", 0.7)),
                "GENERAL",
                0.8,
                Instant.now())));

        InferenceResult result = service.querySparseInferenceLocalOnly("What about Alpha?");

        assertTrue(result.enabled());
        assertEquals("local-brain-state-local-only-v1", result.mode());
        assertEquals(false, result.ragDebug().get("available"));
        assertEquals(true, result.ragDebug().get("sparseNode.localOnly"));
        assertEquals("kg_handler_fallback", result.ragDebug().get("sparseNode.caller"));
        assertFalse(result.inferredRelations().isEmpty());
        assertFalse(result.toString().contains("What about Alpha?"));
        verify(provider, never()).getIfAvailable();
    }

    @Test
    void snapshotQueryTimeFallsBackToKgAxisSparseNodeAliases() {
        TraceStore.clear();
        try {
            TraceStore.put("rag.eval.kgAxis.sparseNodeStatus", "no_graph_path");
            TraceStore.put("rag.eval.kgAxis.sparseNodeDisabledReason", "no_graph_path");
            TraceStore.put("rag.eval.kgAxis.sparseNodeFailureClass", "sparse_node_starvation");
            TraceStore.put("rag.eval.kgAxis.sparseNodePortMappingCount", 2);
            TraceStore.put("rag.eval.kgAxis.sparseNodeQueryAnchorMapApplied", true);
            TraceStore.put("rag.eval.kgAxis.sparseNodeQueryAnchorMapSeedCount", 2);
            TraceStore.put("rag.eval.kgAxis.sparseNodeQueryAnchorMapSeedHashes",
                    List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb"));
            TraceStore.put("rag.eval.kgAxis.sparseNodeQueryAnchorMapReason", "cue_seeded_landmark_anchors");
            TraceStore.put("rag.eval.kgAxis.neo4jDisabledReason", "neo4j_query_failed");
            TraceStore.put("rag.eval.kgAxis.neo4jFailureClass", "silent-failure");
            BrainStateProperties props = new BrainStateProperties();
            Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            BrainStateService service = new BrainStateService(props, writer, provider);

            BrainSnapshot snapshot = service.getBrainSnapshot("", "", 20);

            assertEquals("no_graph_path", snapshot.queryTime().status());
            assertEquals(true, snapshot.queryTime().fallbackUsed());
            assertEquals(true, snapshot.queryTime().queryAnchorMapApplied());
            assertEquals(2, snapshot.queryTime().queryAnchorMapSeedCount());
            assertEquals(List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb"),
                    snapshot.queryTime().queryAnchorMapSeedHashes());
            assertEquals("cue_seeded_landmark_anchors", snapshot.queryTime().queryAnchorMapReason());
            assertEquals(2, snapshot.queryTime().portMappingCount());
            assertEquals("no_graph_path", snapshot.queryTime().disabledReason());
            assertEquals("sparse_node_starvation", snapshot.queryTime().failureClass());
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void snapshotQueryTimeTraceReasonsUseTraceLabels() {
        TraceStore.clear();
        try {
            String rawReason = "private student query reason api_key=test-token-brainstate";
            TraceStore.put("rag.eval.kgAxis.sparseNodeStatus", "no_graph_path");
            TraceStore.put("rag.eval.kgAxis.sparseNodeQueryAnchorMapApplied", true);
            TraceStore.put("rag.eval.kgAxis.sparseNodeQueryAnchorMapReason", rawReason);
            TraceStore.put("rag.eval.kgAxis.sparseNodeDisabledReason", rawReason);
            TraceStore.put("rag.eval.kgAxis.sparseNodeFailureClass", rawReason);
            BrainStateProperties props = new BrainStateProperties();
            Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            BrainStateService service = new BrainStateService(props, writer, provider);

            BrainSnapshot snapshot = service.getBrainSnapshot("", "", 20);
            String rendered = snapshot.queryTime().toString();

            assertTrue(snapshot.queryTime().queryAnchorMapReason().startsWith("hash:"),
                    snapshot.queryTime().queryAnchorMapReason());
            assertTrue(snapshot.queryTime().disabledReason().startsWith("hash:"),
                    snapshot.queryTime().disabledReason());
            assertTrue(snapshot.queryTime().failureClass().startsWith("hash:"),
                    snapshot.queryTime().failureClass());
            assertFalse(rendered.contains(rawReason), rendered);
            assertFalse(rendered.contains("student"), rendered);
            assertFalse(rendered.contains("api_key"), rendered);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void brainStateFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/graph/BrainStateService.java"));

        assertBrainStateStage(source, "recordAnchorFrequency");
        assertBrainStateStage(source, "langgraph.status");
        assertBrainStateStage(source, "anchorSlice");
        assertBrainStateStage(source, "anchorMap.summary");
        assertBrainStateStage(source, "traceLong");
        assertTrue(source.contains("log.debug(\"[BrainStateService] fail-soft stage={} err={}"));
        assertFalse(source.contains("catch (Exception ignore) {"));
        assertFalse(source.contains("catch (NumberFormatException ignored) {"));
        assertTrue(source.contains("catch (NumberFormatException ex) {"));
    }

    private static BrainStateService enabledAnchorMapService(
            BrainStateProperties props,
            Neo4jKgChunkWriter writer,
            ObjectProvider<RagOrchestratorFacade> provider) {
        AnchorFrequencyIndex anchorFrequencyIndex = new AnchorFrequencyIndex(true);
        return new BrainStateService(
                props,
                writer,
                provider,
                new SparseNodeInferenceService(),
                anchorFrequencyIndex,
                new QueryTimeAnchorMap(anchorFrequencyIndex, true, 5));
    }

    private static void assertBrainStateStage(String source, String stage) {
        assertTrue(source.contains("traceSuppressed(\"" + stage + "\""),
                "BrainStateService fail-soft path needs stage breadcrumb: " + stage);
    }
}
