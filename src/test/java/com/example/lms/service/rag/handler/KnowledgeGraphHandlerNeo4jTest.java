package com.example.lms.service.rag.handler;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.graph.BrainStateService;
import com.example.lms.service.rag.graph.InferenceResult;
import com.example.lms.service.rag.graph.SparseNodeInferenceService;
import com.example.lms.service.rag.kg.KgTailPowerMeanScorer;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient.Neo4jKgEntry;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphProperties;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphHandlerNeo4jTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void realClientFailSoftFailureRemainsFailedAfterJpaFallback() {
        var session = mock(org.neo4j.driver.Session.class);
        when(session.<List<Neo4jKgEntry>>executeRead(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("SYNTHETIC_PRIVATE_ERROR"));
        var client = realClient(session);
        var kb = baseKnowledge();
        when(kb.getAllRelationships("GENERAL", "Alpha"))
                .thenReturn(Map.of("RELATIONSHIP_ASSOCIATED_WITH", Set.of("Beta")));
        var handler = new KnowledgeGraphHandler(kb, client, new KgTailPowerMeanScorer(false, 2.0, 0.25));
        TraceStore.put("fixture.unrelated", "preserve");

        var out = handler.retrieve(new Query("Alpha relationship"));

        assertEquals(1, out.size());
        assertEquals("jpa", out.get(0).textSegment().metadata().getString("kg_provider"));
        assertEquals("failed", TraceStore.get("retrieval.kg.neo4j.status"));
        assertEquals(true, TraceStore.get("retrieval.kg.neo4j.failed"));
        assertEquals("neo4j_query_failed", TraceStore.get("retrieval.kg.neo4j.disabledReason"));
        assertEquals("success", TraceStore.get("retrieval.dependency.kg.status"));
        assertEquals("preserve", TraceStore.get("fixture.unrelated"));
        assertFalse(TraceStore.getAll().toString().contains("SYNTHETIC_PRIVATE_ERROR"));
        assertFalse(TraceStore.getAll().toString().contains("Alpha relationship"));
        verify(session).close();
    }

    @Test
    void realClientFailureDoesNotContaminateFollowingEmptyAndSuccessAttempts() {
        var session = mock(org.neo4j.driver.Session.class);
        var row = new Neo4jKgEntry("Alpha", "Concept", 0.9, Instant.now(),
                Map.of("RELATIONSHIP_ASSOCIATED_WITH", Set.of("Gamma")), List.of("fixture"));
        when(session.<List<Neo4jKgEntry>>executeRead(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("synthetic"))
                .thenReturn(List.of()).thenReturn(List.of(row));
        var handler = new KnowledgeGraphHandler(baseKnowledge(), realClient(session), new KgTailPowerMeanScorer(false, 2.0, 0.25));
        handler.retrieve(new Query("Alpha relationship"));
        handler.retrieve(new Query("Alpha relationship"));
        assertEquals("empty", TraceStore.get("retrieval.kg.neo4j.status"));
        assertEquals(false, TraceStore.get("retrieval.kg.neo4j.failed"));
        assertEquals(null, TraceStore.get("retrieval.kg.neo4j.failureClass"));
        assertEquals(null, TraceStore.get("retrieval.kg.neo4j.fallback"));
        assertEquals(1, handler.retrieve(new Query("Alpha relationship")).size());
        assertEquals("success", TraceStore.get("retrieval.kg.neo4j.status"));
        assertEquals(false, TraceStore.get("retrieval.kg.neo4j.failed"));
    }

    @Test
    void disabledHandlerAndDirectClientEmptyAttemptClearOnlyPreviousLookupFailure() {
        var session = mock(org.neo4j.driver.Session.class);
        when(session.<List<Neo4jKgEntry>>executeRead(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("synthetic")).thenReturn(List.of());
        var client = realClient(session);
        client.lookup("GENERAL", Set.of("Alpha"), 8);
        assertEquals(true, TraceStore.get("retrieval.kg.neo4j.failed"));
        client.lookup("GENERAL", Set.of("Alpha"), 8);
        assertEquals(false, TraceStore.get("retrieval.kg.neo4j.failed"));
        TraceStore.put("retrieval.kg.neo4j.failed", true);
        TraceStore.put("retrieval.kg.neo4j.failureClass", "synthetic");
        TraceStore.put("fixture.unrelated", "preserve");
        var handler = new KnowledgeGraphHandler(baseKnowledge(), new FakeNeo4j("disabled", List.of()), new KgTailPowerMeanScorer(false, 2.0, 0.25));
        handler.retrieve(new Query("Alpha relationship"));
        assertEquals("disabled", TraceStore.get("retrieval.kg.neo4j.status"));
        assertEquals(false, TraceStore.get("retrieval.kg.neo4j.failed"));
        assertEquals(null, TraceStore.get("retrieval.kg.neo4j.failureClass"));
        assertEquals("preserve", TraceStore.get("fixture.unrelated"));
    }

    private static Neo4jKnowledgeGraphClient realClient(org.neo4j.driver.Session session) {
        var properties = new Neo4jKnowledgeGraphProperties();
        properties.setEnabled(true);properties.setUri("bolt://127.0.0.1:7687");
        properties.setUser("fixture");properties.setPassword("loopback-contract-only");properties.setDatabase("");
        var driver = mock(org.neo4j.driver.Driver.class);when(driver.session()).thenReturn(session);
        var client = new Neo4jKnowledgeGraphClient(properties);ReflectionTestUtils.setField(client, "driver", driver);
        return client;
    }

    @Test
    void neo4jDisabledReasonDiagnosticsUseTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"retrieval.kg.neo4j.disabledReason\", disabledReason);"));
        assertFalse(source.contains("event.put(\"disabledReason\", disabledReason == null ? \"\" : disabledReason);"));
        assertFalse(source.contains("returnedCount, disabledReason, tookMs);"));
        assertFalse(source.contains("String safeDisabledReason = SafeRedactor.safeMessage(disabledReason, 120);"));
        assertTrue(source.contains(
                "String safeDisabledReason = SafeRedactor.traceLabelOrFallback(disabledReason, \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"retrieval.kg.neo4j.disabledReason\", safeDisabledReason);"));
        assertTrue(source.contains("event.put(\"disabledReason\", safeDisabledReason);"));
    }

    @Test
    void brainStateReasonDiagnosticsUseTraceLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"retrieval.kg.brainState.reason\", blankToDefault(reason, \"\"));"));
        assertFalse(source.contains("TraceStore.put(\"retrieval.kg.brainState.disabledReason\", blankToDefault(disabledReason, \"\"));"));
        assertFalse(source.contains("event.put(\"reason\", blankToDefault(reason, \"\"));"));
        assertFalse(source.contains("event.put(\"disabledReason\", blankToDefault(disabledReason, \"\"));"));
        assertFalse(source.contains(
                "String safeBrainStateReason = SafeRedactor.safeMessage(blankToDefault(reason, \"\"), 120);"));
        assertFalse(source.contains(
                "String safeBrainStateDisabledReason = SafeRedactor.safeMessage(blankToDefault(disabledReason, \"\"), 120);"));
        assertTrue(source.contains(
                "String safeBrainStateReason = SafeRedactor.traceLabelOrFallback(blankToDefault(reason, \"\"), \"unknown\");"));
        assertTrue(source.contains(
                "String safeBrainStateDisabledReason = SafeRedactor.traceLabelOrFallback(blankToDefault(disabledReason, \"\"), \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"retrieval.kg.brainState.reason\", safeBrainStateReason);"));
        assertTrue(source.contains("TraceStore.put(\"retrieval.kg.brainState.disabledReason\", safeBrainStateDisabledReason);"));
        assertTrue(source.contains("event.put(\"reason\", safeBrainStateReason);"));
        assertTrue(source.contains("event.put(\"disabledReason\", safeBrainStateDisabledReason);"));
    }

    @Test
    void kgScoreWeightsParseFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[KnowledgeGraphHandler] invalid kg.score.weights hash={} length={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void numericMetadataParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(source, "private static long traceLong");
        assertParserCatchNarrowed(source, "private static int metaInt");
    }

    @Test
    void numericMetadataParsersDropNonFiniteNumbers() {
        Map<String, Object> trace = Map.of("kgPoolCount", Double.POSITIVE_INFINITY);
        assertEquals(0L, (Long) ReflectionTestUtils.invokeMethod(
                KnowledgeGraphHandler.class, "traceLong", trace, "kgPoolCount"));
        assertEquals(6, (Integer) ReflectionTestUtils.invokeMethod(
                KnowledgeGraphHandler.class, "metaInt",
                Map.of("kgLimit", Double.NaN), "kgLimit", 6));
    }

    @Test
    void failSoftKgDiagnosticsLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java"),
                StandardCharsets.UTF_8);

        for (String stage : List.of(
                "jpa.entity",
                "brainState.trace",
                "traceLong.parse",
                "kgLimit.metadata",
                "sparsePath.trace",
                "neo4j.trace",
                "dependency.trace",
                "jpa.trace",
                "jpa.entityTrace",
                "faultMask.trace",
                "debugEvent.trace",
                "metaInt.parse")) {
            assertTrue(source.contains("traceSuppressed(log, \"KnowledgeGraphHandler\", \"" + stage + "\""),
                    "KnowledgeGraphHandler fail-soft path needs stage breadcrumb: " + stage);
        }
    }

    @Test
    void fallsBackToJpaKgWhenNeo4jIsDisabled() {
        KnowledgeBaseService kb = baseKnowledge();
        when(kb.getConfidenceScore("GENERAL", "Alpha")).thenReturn(Optional.of(0.8));
        when(kb.getLastAccessedAt("GENERAL", "Alpha"))
                .thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        when(kb.getAllRelationships("GENERAL", "Alpha"))
                .thenReturn(Map.of("RELATIONSHIP_ASSOCIATED_WITH", Set.of("Beta")));

        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(
                kb,
                new FakeNeo4j("disabled", List.of()),
                new KgTailPowerMeanScorer(false, 2.0, 0.25));

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        assertEquals(1, out.size());
        Content content = out.get(0);
        assertTrue(content.textSegment().text().contains("Beta"));
        assertTrue(content.textSegment().text().contains("relationBreadcrumbs:"));
        assertEquals("jpa", content.textSegment().metadata().getString("kg_provider"));
        assertEquals("relation_thumbnail_v1",
                content.textSegment().metadata().getString("kg_relation_thumbnail_mode"));
        assertEquals("jpa_relationships",
                content.textSegment().metadata().getString("kg_relation_thumbnail_source"));
        assertTrue(content.textSegment().metadata().getString("kg_relation_breadcrumb").contains("Beta"));
        verify(kb, atLeastOnce()).getAllRelationships("GENERAL", "Alpha");
    }

    @Test
    void usesNeo4jRowsBeforeJpaKgWhenAvailable() {
        KnowledgeBaseService kb = baseKnowledge();
        Neo4jKgEntry row = new Neo4jKgEntry(
                "Alpha",
                "Concept",
                0.91,
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of("RELATIONSHIP_ASSOCIATED_WITH", Set.of("Gamma")),
                List.of("fixture"));

        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(
                kb,
                new FakeNeo4j(null, List.of(row)),
                new KgTailPowerMeanScorer(false, 2.0, 0.25));

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        assertEquals(1, out.size());
        Content content = out.get(0);
        assertTrue(content.textSegment().text().contains("Gamma"));
        assertTrue(content.textSegment().text().contains("relationBreadcrumbs:"));
        assertEquals("neo4j", content.textSegment().metadata().getString("kg_provider"));
        assertEquals("relation_thumbnail_v1",
                content.textSegment().metadata().getString("kg_relation_thumbnail_mode"));
        assertEquals("neo4j_relationships",
                content.textSegment().metadata().getString("kg_relation_thumbnail_source"));
        assertTrue(content.textSegment().metadata().getString("kg_relation_summary").contains("Alpha"));
        verify(kb, never()).getAllRelationships(anyString(), anyString());
    }

    @Test
    void recordsNormalizedNeo4jFailureDiagnosticsWhenLookupFails() {
        KnowledgeBaseService kb = baseKnowledge();
        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(
                kb,
                new ThrowingNeo4j(),
                new KgTailPowerMeanScorer(false, 2.0, 0.25));

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        assertTrue(out.isEmpty());
        assertEquals("failed", TraceStore.get("retrieval.kg.neo4j.status"));
        assertEquals("neo4j_query_failed", TraceStore.get("retrieval.kg.neo4j.disabledReason"));
        assertEquals("silent-failure", TraceStore.get("retrieval.kg.neo4j.failureClass"));
        assertTrue(String.valueOf(TraceStore.get("retrieval.kg.neo4j.queryHash12")).matches("[0-9a-f]{12}"));
        assertTrue(String.valueOf(TraceStore.get("retrieval.kg.neo4j.events"))
                .contains("neo4j_query_failed"));
    }

    @Test
    void jpaEntityFailureDiagnosticsUseStableLabelsWithoutExceptionClassNames() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.inferDomain("Alpha relationship")).thenReturn("GENERAL");
        when(kb.findMentionedEntities("GENERAL", "Alpha relationship")).thenReturn(Set.of("Alpha"));
        when(kb.getConfidenceScore("GENERAL", "Alpha"))
                .thenThrow(new IllegalStateException("ownerToken=secret"));

        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(
                kb,
                new FakeNeo4j("disabled", List.of()),
                new KgTailPowerMeanScorer(false, 2.0, 0.25));

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        assertTrue(out.isEmpty());
        assertEquals("silent-failure", TraceStore.get("retrieval.kg.jpa.lastFailureClass"));
        assertEquals("silent-failure", TraceStore.get("retrieval.kg.jpa.lastExceptionType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("Alpha relationship"), trace);
    }

    @Test
    void addsAssociativePathInferenceContentForJpaKg() {
        KnowledgeBaseService kb = baseKnowledge();
        when(kb.getConfidenceScore("GENERAL", "Alpha")).thenReturn(Optional.of(0.9));
        when(kb.getConfidenceScore("GENERAL", "Beta")).thenReturn(Optional.of(0.8));
        when(kb.getConfidenceScore("GENERAL", "Gamma")).thenReturn(Optional.of(0.7));
        when(kb.getLastAccessedAt("GENERAL", "Alpha"))
                .thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        when(kb.getAllRelationships("GENERAL", "Alpha"))
                .thenReturn(Map.of("RELATIONSHIP_SEEDS", Set.of("Beta")));
        when(kb.getAllRelationships("GENERAL", "Beta"))
                .thenReturn(Map.of("RELATIONSHIP_POINTS_TO", Set.of("Gamma")));

        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(
                kb,
                new FakeNeo4j("disabled", List.of()),
                new KgTailPowerMeanScorer(false, 2.0, 0.25));

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        Content path = out.stream()
                .filter(c -> "associative_path_inference".equals(
                        c.textSegment().metadata().getString("kg_mode")))
                .findFirst()
                .orElseThrow();
        assertTrue(path.textSegment().text()
                .contains("Alpha --RELATIONSHIP_SEEDS--> Beta --RELATIONSHIP_POINTS_TO--> Gamma"));
        assertTrue(path.textSegment().text().contains("relationBreadcrumbs:"));
        assertEquals(2, path.textSegment().metadata().toMap().get("kg_path_depth"));
        assertEquals("relation_thumbnail_v1",
                path.textSegment().metadata().getString("kg_relation_thumbnail_mode"));
        assertEquals("associative_path_inference",
                path.textSegment().metadata().getString("kg_relation_thumbnail_source"));
        assertEquals(path.textSegment().metadata().getString("kg_path_hash12"),
                path.textSegment().metadata().getString("kg_relation_thumbnail_hash"));
        assertTrue(path.textSegment().metadata().getString("kg_relation_breadcrumb").contains("Gamma"));
        assertEquals("success", TraceStore.get("retrieval.kg.sparsePath.status"));
        assertEquals(1, TraceStore.get("retrieval.kg.sparsePath.transitivePathCount"));
        assertTrue(String.valueOf(TraceStore.get("retrieval.kg.sparsePath.queryHash12")).matches("[0-9a-f]{12}"));
    }

    @Test
    void usesBrainStateLocalOnlyFallbackWhenKgMentionsAreEmpty() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.inferDomain("orphan query")).thenReturn("GENERAL");
        when(kb.findMentionedEntities("GENERAL", "orphan query")).thenReturn(Set.of());
        BrainStateService brainStateService = mock(BrainStateService.class);
        when(brainStateService.querySparseInferenceLocalOnly("orphan query", "GENERAL")).thenReturn(new InferenceResult(
                true,
                "123456789abc",
                "local-brain-state-local-only-v1",
                List.of("Alpha"),
                List.of("Alpha --RELATIONSHIP_LINKS--> Beta"),
                Map.of(
                        "sparseNode.localOnly", true,
                        "sparseNode.caller", "kg_handler_fallback",
                        "sparseNode.queryAnchorMap.applied", true,
                        "sparseNode.queryAnchorMap.reason", "cue_seeded_landmark_anchors",
                        "sparseNode.queryAnchorMapSeedCount", 2L,
                        "sparseNode.queryAnchorMapSeedHashes", List.of("111111111111", "222222222222")),
                "",
                Instant.now()));
        @SuppressWarnings("unchecked")
        ObjectProvider<BrainStateService> brainStateProvider = mock(ObjectProvider.class);
        when(brainStateProvider.getIfAvailable()).thenReturn(brainStateService);

        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(
                kb,
                new FakeNeo4j("disabled", List.of()),
                new KgTailPowerMeanScorer(false, 2.0, 0.25),
                new SparseNodeInferenceService(),
                brainStateProvider);

        List<Content> out = handler.retrieve(new Query("orphan query"));

        assertEquals(1, out.size());
        Content content = out.get(0);
        assertTrue(content.textSegment().text().contains("Alpha --RELATIONSHIP_LINKS--> Beta"));
        assertEquals("kg", content.textSegment().metadata().getString("retrieval_source"));
        assertEquals("brain-state", content.textSegment().metadata().getString("kg_provider"));
        assertEquals("brain_state_sparse_inference", content.textSegment().metadata().getString("kg_mode"));
        assertTrue(content.textSegment().text().contains("relationBreadcrumbs:"));
        assertEquals("relation_thumbnail_v1",
                content.textSegment().metadata().getString("kg_relation_thumbnail_mode"));
        assertEquals("brain_state_sparse_inference",
                content.textSegment().metadata().getString("kg_relation_thumbnail_source"));
        assertEquals(content.textSegment().metadata().getString("kg_relation_hash12"),
                content.textSegment().metadata().getString("kg_relation_thumbnail_hash"));
        assertTrue(content.textSegment().metadata().getString("kg_relation_breadcrumb").contains("Beta"));
        assertEquals("no_mentioned_entities", content.textSegment().metadata().getString("kg_fallback_reason"));
        assertEquals("true", content.textSegment().metadata().getString("kg_query_anchor_map"));
        assertEquals(2L, content.textSegment().metadata().toMap().get("kg_query_anchor_map_seed_count"));
        assertEquals("cue_seeded_landmark_anchors",
                content.textSegment().metadata().getString("kg_query_anchor_map_reason"));
        assertEquals("success", TraceStore.get("retrieval.kg.brainState.status"));
        assertEquals(true, TraceStore.get("retrieval.kg.brainState.fallbackUsed"));
        assertEquals(true, TraceStore.get("retrieval.kg.brainState.queryAnchorMap.applied"));
        assertEquals(2L, TraceStore.get("retrieval.kg.brainState.queryAnchorMap.seedCount"));
        assertEquals(List.of("111111111111", "222222222222"),
                TraceStore.get("retrieval.kg.brainState.queryAnchorMap.seedHashes"));
        assertEquals("cue_seeded_landmark_anchors",
                TraceStore.get("retrieval.kg.brainState.queryAnchorMap.reason"));
        assertTrue(String.valueOf(TraceStore.get("retrieval.kg.brainState.queryHash12")).matches("[0-9a-f]{12}"));
        assertFalse(String.valueOf(TraceStore.get("retrieval.kg.brainState.events")).contains("orphan query"));
        verify(brainStateService).querySparseInferenceLocalOnly("orphan query", "GENERAL");
        verify(brainStateService, never()).querySparseInferenceLocalOnly("orphan query");
        verify(brainStateService, never()).querySparseInference(anyString());
    }

    @Test
    void hashesFreeTextQueryAnchorMapTraceReasonAndFiltersInvalidHashes() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.inferDomain("route query")).thenReturn("GENERAL");
        when(kb.findMentionedEntities("GENERAL", "route query")).thenReturn(Set.of());
        BrainStateService brainStateService = mock(BrainStateService.class);
        when(brainStateService.querySparseInferenceLocalOnly("route query", "GENERAL")).thenReturn(new InferenceResult(
                true,
                "123456789abc",
                "local-brain-state-local-only-v1",
                List.of("Han River"),
                List.of("Han River --LANDMARK_NEAR--> Riverside Park"),
                Map.of(
                        "sparseNode.queryAnchorMap.applied", true,
                        "sparseNode.queryAnchorMap.reason", "cue seeded / raw",
                        "sparseNode.queryAnchorMap.seedCount", 3L,
                        "sparseNode.queryAnchorMap.seedHashes",
                        List.of("AAAAAAAAAAAA", "not-a-hash", "bbbbbbbbbbbb")),
                "",
                Instant.now()));
        @SuppressWarnings("unchecked")
        ObjectProvider<BrainStateService> brainStateProvider = mock(ObjectProvider.class);
        when(brainStateProvider.getIfAvailable()).thenReturn(brainStateService);

        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(
                kb,
                new FakeNeo4j("disabled", List.of()),
                new KgTailPowerMeanScorer(false, 2.0, 0.25),
                new SparseNodeInferenceService(),
                brainStateProvider);

        List<Content> out = handler.retrieve(new Query("route query"));

        assertEquals(1, out.size());
        Content content = out.get(0);
        assertEquals(2L, content.textSegment().metadata().toMap().get("kg_query_anchor_map_seed_count"));
        assertEquals("aaaaaaaaaaaa,bbbbbbbbbbbb",
                content.textSegment().metadata().getString("kg_query_anchor_map_seed_hashes"));
        String metadataReason = content.textSegment().metadata().getString("kg_query_anchor_map_reason");
        assertTrue(metadataReason.startsWith("hash:"), metadataReason);
        assertFalse(metadataReason.contains("cue_seeded"), metadataReason);
        assertEquals(2L, TraceStore.get("retrieval.kg.brainState.queryAnchorMap.seedCount"));
        assertEquals(List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb"),
                TraceStore.get("retrieval.kg.brainState.queryAnchorMap.seedHashes"));
        String traceReason = String.valueOf(TraceStore.get("retrieval.kg.brainState.queryAnchorMap.reason"));
        assertTrue(traceReason.startsWith("hash:"), traceReason);
        assertFalse(traceReason.contains("cue_seeded"), traceReason);
        assertFalse(String.valueOf(TraceStore.get("retrieval.kg.brainState.events")).contains("cue seeded / raw"));
    }

    @Test
    void augmentsExistingNeo4jKgWhenQueryAnchorMapApplied() {
        KnowledgeBaseService kb = baseKnowledge();
        Neo4jKgEntry row = new Neo4jKgEntry(
                "Alpha",
                "Concept",
                0.91,
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of("RELATIONSHIP_ASSOCIATED_WITH", Set.of("Gamma")),
                List.of("fixture"));
        BrainStateService brainStateService = mock(BrainStateService.class);
        when(brainStateService.querySparseInferenceLocalOnly("Alpha relationship", "GENERAL")).thenReturn(new InferenceResult(
                true,
                "123456789abc",
                "local-brain-state-local-only-v1",
                List.of("Han River"),
                List.of("Han River --LANDMARK_NEAR--> Riverside Park"),
                Map.of(
                        "sparseNode.queryAnchorMap.applied", true,
                        "sparseNode.queryAnchorMap.reason", "cue_seeded_landmark_anchors",
                        "sparseNode.queryAnchorMap.seedCount", 1L,
                        "sparseNode.queryAnchorMap.seedHashes", List.of("111111111111")),
                "",
                Instant.now()));
        @SuppressWarnings("unchecked")
        ObjectProvider<BrainStateService> brainStateProvider = mock(ObjectProvider.class);
        when(brainStateProvider.getIfAvailable()).thenReturn(brainStateService);

        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(
                kb,
                new FakeNeo4j(null, List.of(row)),
                new KgTailPowerMeanScorer(false, 2.0, 0.25),
                new SparseNodeInferenceService(),
                brainStateProvider);

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        assertEquals(2, out.size());
        assertTrue(out.stream().anyMatch(content ->
                "neo4j".equals(content.textSegment().metadata().getString("kg_provider"))));
        Content anchor = out.stream()
                .filter(content -> "brain-state".equals(content.textSegment().metadata().getString("kg_provider")))
                .findFirst()
                .orElseThrow();
        assertTrue(anchor.textSegment().text().contains("Han River --LANDMARK_NEAR--> Riverside Park"));
        assertEquals("query_anchor_map_augmentation",
                anchor.textSegment().metadata().getString("kg_fallback_reason"));
        assertEquals("success", TraceStore.get("retrieval.kg.brainState.status"));
        assertEquals("success_anchor_augmented", TraceStore.get("retrieval.dependency.kg.status"));
        assertEquals(true, TraceStore.get("retrieval.kg.brainState.queryAnchorMap.applied"));
        assertEquals(List.of("111111111111"),
                TraceStore.get("retrieval.kg.brainState.queryAnchorMap.seedHashes"));
        verify(brainStateService).querySparseInferenceLocalOnly("Alpha relationship", "GENERAL");
        verify(kb, never()).getAllRelationships(anyString(), anyString());
    }

    @Test
    void doesNotAugmentExistingKgWhenQueryAnchorMapDidNotApply() {
        KnowledgeBaseService kb = baseKnowledge();
        Neo4jKgEntry row = new Neo4jKgEntry(
                "Alpha",
                "Concept",
                0.91,
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of("RELATIONSHIP_ASSOCIATED_WITH", Set.of("Gamma")),
                List.of("fixture"));
        BrainStateService brainStateService = mock(BrainStateService.class);
        when(brainStateService.querySparseInferenceLocalOnly("Alpha relationship", "GENERAL")).thenReturn(new InferenceResult(
                true,
                "123456789abc",
                "local-brain-state-local-only-v1",
                List.of("Alpha"),
                List.of("Alpha --RELATIONSHIP_LINKS--> Beta"),
                Map.of(
                        "sparseNode.queryAnchorMap.applied", false,
                        "sparseNode.queryAnchorMap.reason", "exact_entity_match"),
                "",
                Instant.now()));
        @SuppressWarnings("unchecked")
        ObjectProvider<BrainStateService> brainStateProvider = mock(ObjectProvider.class);
        when(brainStateProvider.getIfAvailable()).thenReturn(brainStateService);

        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(
                kb,
                new FakeNeo4j(null, List.of(row)),
                new KgTailPowerMeanScorer(false, 2.0, 0.25),
                new SparseNodeInferenceService(),
                brainStateProvider);

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        assertEquals(1, out.size());
        assertEquals("neo4j", out.get(0).textSegment().metadata().getString("kg_provider"));
        assertEquals("not_applied", TraceStore.get("retrieval.kg.brainState.status"));
        assertEquals("success", TraceStore.get("retrieval.dependency.kg.status"));
        assertEquals(false, TraceStore.get("retrieval.kg.brainState.queryAnchorMap.applied"));
        verify(brainStateService).querySparseInferenceLocalOnly("Alpha relationship", "GENERAL");
        verify(kb, never()).getAllRelationships(anyString(), anyString());
    }

    @Test
    void legacyAbandonwareKgHandlerDoesNotMergeQueryTimeAnchorMapSlices() {
        com.abandonware.ai.service.rag.handler.KnowledgeGraphHandler handler =
                new com.abandonware.ai.service.rag.handler.KnowledgeGraphHandler(2, 1200, 5);

        List<com.abandonware.ai.service.rag.model.ContextSlice> out =
                handler.lookup("rag bike place near river", 5);

        assertFalse(out.stream().anyMatch(slice -> "kg-anchor-map".equals(slice.getSource())));
    }

    private static KnowledgeBaseService baseKnowledge() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.inferDomain(anyString())).thenReturn("GENERAL");
        when(kb.findMentionedEntities("GENERAL", "Alpha relationship")).thenReturn(Set.of("Alpha"));
        return kb;
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse > start, "parser call should be locatable: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be locatable: " + signature);
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
    }

    private static final class FakeNeo4j extends Neo4jKnowledgeGraphClient {
        private final String disabledReason;
        private final List<Neo4jKgEntry> rows;

        private FakeNeo4j(String disabledReason, List<Neo4jKgEntry> rows) {
            super(new Neo4jKnowledgeGraphProperties());
            this.disabledReason = disabledReason;
            this.rows = rows;
        }

        @Override
        public boolean isConfiguredEnabled() {
            return disabledReason == null;
        }

        @Override
        public boolean hasPassword() {
            return disabledReason == null;
        }

        @Override
        public String endpointHost() {
            return "localhost";
        }

        @Override
        public String disabledReason() {
            return disabledReason;
        }

        @Override
        public List<Neo4jKgEntry> lookup(String domain, Set<String> entities, int limit) {
            return rows;
        }
    }

    private static final class ThrowingNeo4j extends Neo4jKnowledgeGraphClient {

        private ThrowingNeo4j() {
            super(new Neo4jKnowledgeGraphProperties());
        }

        @Override
        public boolean isConfiguredEnabled() {
            return true;
        }

        @Override
        public boolean hasPassword() {
            return true;
        }

        @Override
        public String endpointHost() {
            return "localhost";
        }

        @Override
        public String disabledReason() {
            return null;
        }

        @Override
        public List<Neo4jKgEntry> lookup(String domain, Set<String> entities, int limit) {
            throw new IllegalStateException("fixture lookup failure");
        }
    }
}
