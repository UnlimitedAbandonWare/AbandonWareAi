package com.example.lms.service.rag.graph;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrainStateDuplicateChunkCharacterizationTest {
    @AfterEach
    void clearSyntheticTrace() { TraceStore.clear(); }

    @Test
    void identicalReplayRetainsOneChunkWhileMentionsAndRelationsCountBothDeliveries() {
        BrainStateService service = localService();
        KgChunk chunk = chunk("fixture-session-a", "GENERAL", "Alpha", "Beta");
        service.recordChunks(List.of(chunk));
        BrainSnapshot before = service.getBrainSnapshot("fixture-session-a");
        service.recordChunks(List.of(chunk));
        BrainSnapshot after = service.getBrainSnapshot("fixture-session-a");

        assertEquals(1, before.totalChunks());
        assertEquals(1, after.totalChunks());
        assertEquals(2, before.totalEntities());
        assertEquals(2, after.totalEntities());
        assertEquals(2, before.sparseNodes().size());
        assertTrue(after.sparseNodes().isEmpty());
        assertEquals(1, before.topPortMappings().get(0).count());
        assertEquals(2, after.topPortMappings().get(0).count());
        assertTrue(service.listEntityNodes("GENERAL", 10).stream().allMatch(e -> e.mentionCount() == 2));
        assertFalse(after.toString().contains("fixture source text"));
    }

    @Test
    void suppliedIdReplacementMovesChunkMetadataWhileOldSessionAccumulatorsRemain() {
        BrainStateService service = localService();
        service.recordChunks(List.of(chunk("fixture-session-a", "GENERAL", "Alpha", "Beta")));
        service.recordChunks(List.of(chunk("fixture-session-b", "FINANCE", "Gamma", "Delta")));

        BrainSnapshot priorSession = service.getBrainSnapshot("fixture-session-a");
        BrainSnapshot currentSession = service.getBrainSnapshot("fixture-session-b");

        assertEquals(0, priorSession.totalChunks());
        assertEquals(2, priorSession.totalEntities());
        assertEquals(2, priorSession.sparseNodes().size());
        assertEquals(1, priorSession.topPortMappings().size());
        assertEquals(1, currentSession.totalChunks());
        assertEquals(2, currentSession.totalEntities());
        assertEquals(2, service.listEntityNodes("GENERAL", 10).size());
        assertEquals(2, service.listEntityNodes("FINANCE", 10).size());
    }

    private static KgChunk chunk(String session, String domain, String first, String second) {
        return new KgChunk("fixture-reused-id", session, "fixture source text",
                List.of(new KgChunk.KgEntity(first, "ENTITY", domain, 0.8),
                        new KgChunk.KgEntity(second, "ENTITY", domain, 0.7)),
                List.of(new KgChunk.KgRelation(first, second, "CO_MENTIONED_WITH", 0.6)),
                domain, 0.8, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static BrainStateService localService() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> provider = mock(ObjectProvider.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.disabledReason()).thenReturn("disabled");
        return new BrainStateService(new BrainStateProperties(), writer, provider);
    }
}
