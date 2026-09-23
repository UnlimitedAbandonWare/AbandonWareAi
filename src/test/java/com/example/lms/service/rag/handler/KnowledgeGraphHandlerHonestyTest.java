package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.rag.kg.KgTailPowerMeanScorer;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient.Neo4jKgEntry;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphProperties;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeGraphHandlerHonestyTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void disabledNeo4jAndJpaSuccessAreDistinctTraceStates() {
        KnowledgeBaseService kb = baseKnowledge();
        when(kb.getConfidenceScore("GENERAL", "Alpha")).thenReturn(Optional.of(0.8));
        when(kb.getLastAccessedAt("GENERAL", "Alpha"))
                .thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        when(kb.getAllRelationships("GENERAL", "Alpha"))
                .thenReturn(Map.of("RELATIONSHIP_ASSOCIATED_WITH", Set.of("Beta")));
        KnowledgeGraphHandler handler = newHandler(kb, new FakeNeo4j("disabled", List.of(), false));

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        assertEquals(1, out.size());
        assertEquals("disabled", TraceStore.get("retrieval.kg.neo4j.status"));
        assertEquals("success", TraceStore.get("retrieval.kg.jpa.status"));
        assertEquals("success", TraceStore.get("retrieval.dependency.kg.status"));
    }

    @Test
    void neo4jQueryFailureFallsBackToJpaAndRecordsFailure() {
        KnowledgeBaseService kb = baseKnowledge();
        when(kb.getConfidenceScore("GENERAL", "Alpha")).thenReturn(Optional.of(0.8));
        when(kb.getLastAccessedAt("GENERAL", "Alpha"))
                .thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        when(kb.getAllRelationships("GENERAL", "Alpha"))
                .thenReturn(Map.of("RELATIONSHIP_ASSOCIATED_WITH", Set.of("Beta")));
        KnowledgeGraphHandler handler = newHandler(kb, new FakeNeo4j(null, List.of(), true));

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        assertEquals(1, out.size());
        assertEquals("failed", TraceStore.get("retrieval.kg.neo4j.status"));
        assertEquals("silent-failure", TraceStore.get("retrieval.kg.neo4j.failureClass"));
        assertEquals("success", TraceStore.get("retrieval.kg.jpa.status"));
    }

    @Test
    void jpaEntityFailureIsNotReportedAsCleanEmpty() {
        KnowledgeBaseService kb = baseKnowledge();
        when(kb.getConfidenceScore("GENERAL", "Alpha")).thenReturn(Optional.of(0.8));
        when(kb.getLastAccessedAt("GENERAL", "Alpha"))
                .thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        when(kb.getAllRelationships("GENERAL", "Alpha"))
                .thenThrow(new RuntimeException("jpa relation lookup failed"));
        KnowledgeGraphHandler handler = newHandler(kb, new FakeNeo4j("disabled", List.of(), false));

        List<Content> out = handler.retrieve(new Query("Alpha relationship"));

        assertTrue(out.isEmpty());
        assertEquals("failed", TraceStore.get("retrieval.kg.jpa.status"));
        assertEquals(1L, TraceStore.getLong("retrieval.kg.jpa.failureCount"));
        assertEquals("jpa_failed", TraceStore.get("retrieval.dependency.kg.status"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.dependency.kg.fallbackUsed"));
    }

    @Test
    void topLevelKgFailureRecordsDependencyErrorTypeWithoutRawQuery() {
        String rawQuery = "private kg dependency failure query";
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.inferDomain(rawQuery)).thenThrow(new IllegalStateException("domain lookup failed"));
        KnowledgeGraphHandler handler = newHandler(kb, new FakeNeo4j("disabled", List.of(), false));

        List<Content> out = handler.retrieve(new Query(rawQuery));

        assertTrue(out.isEmpty());
        assertEquals("failed", TraceStore.get("retrieval.dependency.kg.status"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.dependency.kg.fallbackUsed"));
        assertEquals("IllegalStateException", TraceStore.get("retrieval.dependency.kg.errorType"));
        assertTrue(String.valueOf(TraceStore.get("retrieval.dependency.kg.queryHash12")).matches("[0-9a-f]{12}"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void topLevelKgCancellationRecordsCancelledFailureClassWithoutRawLeak() {
        String rawQuery = "private kg cancellation query";
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.inferDomain(rawQuery))
                .thenThrow(new java.util.concurrent.CancellationException("cancelled ownerToken abc123"));
        KnowledgeGraphHandler handler = newHandler(kb, new FakeNeo4j("disabled", List.of(), false));

        List<Content> out = handler.retrieve(new Query(rawQuery));

        assertTrue(out.isEmpty());
        assertEquals("failed", TraceStore.get("retrieval.dependency.kg.status"));
        assertEquals("cancelled", TraceStore.get("retrieval.dependency.kg.failureClass"));
        assertEquals("CancellationException", TraceStore.get("retrieval.dependency.kg.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken abc123"));
    }

    private static KnowledgeGraphHandler newHandler(KnowledgeBaseService kb, Neo4jKnowledgeGraphClient neo4j) {
        return new KnowledgeGraphHandler(kb, neo4j, new KgTailPowerMeanScorer(false, 2.0, 0.25));
    }

    private static KnowledgeBaseService baseKnowledge() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.inferDomain(anyString())).thenReturn("GENERAL");
        when(kb.findMentionedEntities("GENERAL", "Alpha relationship")).thenReturn(Set.of("Alpha"));
        return kb;
    }

    private static final class FakeNeo4j extends Neo4jKnowledgeGraphClient {
        private final String disabledReason;
        private final List<Neo4jKgEntry> rows;
        private final boolean throwOnLookup;

        private FakeNeo4j(String disabledReason, List<Neo4jKgEntry> rows, boolean throwOnLookup) {
            super(new Neo4jKnowledgeGraphProperties());
            this.disabledReason = disabledReason;
            this.rows = rows;
            this.throwOnLookup = throwOnLookup;
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
            if (throwOnLookup) {
                throw new RuntimeException("neo4j backend unavailable");
            }
            return rows;
        }
    }
}
