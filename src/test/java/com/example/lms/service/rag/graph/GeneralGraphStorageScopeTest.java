package com.example.lms.service.rag.graph;

import com.example.lms.domain.ChatSession;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphProperties;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneralGraphStorageScopeTest {
    private GeneralGraphScope scope(String owner, long id, long epoch) {
        var session = new ChatSession("synthetic", owner, "ANON");
        session.setId(id);
        return GeneralGraphScope.authorize(session, null, owner).orElseThrow().withPolicy(epoch, true);
    }
    private KgChunk chunk(GeneralGraphScope scope, long sourceId, long revision) {
        return new KgChunk("chunk-" + scope.indexNamespace() + "-" + sourceId + "-" + revision,
                Long.toString(scope.sessionId()), "Spark was considered but not purchased",
                List.of(new KgChunk.KgEntity("Spark", "THING", "GENERAL", .8)),
                List.of(), "GENERAL", .8, Instant.now(), "USER", "BRAIN_STATE", "CONVERSATION",
                "brain_state", scope, "chat-message:" + sourceId, revision);
    }
    @Test void namespaceSeparatesOwnerSessionAndConsentEpoch() {
        var a = scope("a", 7, 1);
        assertNotEquals(a.indexNamespace(), scope("b", 7, 1).indexNamespace());
        assertNotEquals(a.indexNamespace(), scope("a", 8, 1).indexNamespace());
        assertNotEquals(a.indexNamespace(), scope("a", 7, 2).indexNamespace());
        assertEquals(64, a.indexNamespace().length());
    }
    @Test void writerUsesSourceBoundIdentitiesWithoutRawTextOrOwner() {
        var scope = scope("synthetic-owner", 7, 1);
        var chunk = chunk(scope, 11, 2);
        var writer = new Neo4jKgChunkWriter(new Neo4jKnowledgeGraphProperties(), new BrainStateProperties());
        var parameters = writer.chunkParameters(chunk);
        assertEquals(scope.indexNamespace(), parameters.get("scopeKey"));
        assertEquals("chat-message:11", parameters.get("sourceId"));
        assertEquals(2L, parameters.get("sourceRevision"));
        assertFalse(parameters.toString().contains(chunk.sourceText()));
        assertFalse(parameters.toString().contains("synthetic-owner"));
        assertTrue(Neo4jKgChunkWriter.entityUpsertCypher().contains("scopeKey: $scopeKey"));
        assertTrue(Neo4jKgChunkWriter.relationUpsertCypher().contains("sourceRevision: $sourceRevision"));
        var relation = new KgChunk.KgRelation("Spark", "Option", "CO_MENTIONED_WITH", .8);
        var relationParameters = writer.relationParameters(chunk, relation);
        assertEquals(scope.indexNamespace(), relationParameters.get("scopeKey"));
        assertEquals("chat-message:11", relationParameters.get("sourceId"));
        assertEquals(2L, relationParameters.get("sourceRevision"));
    }
    @Test void fallbackCannotCrossSessionOwnerOrRevocationAndDoesNotPromoteCoMentions() {
        var properties = new BrainStateProperties();
        properties.setEnabled(true);
        var brain = new BrainStateService(properties, mock(Neo4jKgChunkWriter.class), null);
        var own = scope("a", 7, 1);
        brain.recordChunks(List.of(chunk(own, 11, 1), chunk(scope("b", 8, 1), 12, 1),
                chunk(scope("a", 9, 1), 13, 1), chunk(scope("a", 7, 2), 14, 1)));
        assertEquals(List.of(new KgChunk.SourceRef("chat-message:11", 1)), brain.privateSources(own, "Spark", 8));
        assertTrue(brain.privateSources(null, "Spark", 8).isEmpty());
        assertTrue(brain.listEntityNodes("GENERAL", 10).isEmpty());
        brain.recordChunks(List.of(chunk(own, 11, 1)));
        assertEquals(1, brain.privateSources(own, "the second option", 8).size());
    }
    @Test void laterCorrectionWithoutEntityNamesRemainsAheadOfTheOldMention() {
        var properties = new BrainStateProperties();
        properties.setEnabled(true);
        var brain = new BrainStateService(properties, mock(Neo4jKgChunkWriter.class), null);
        var own = scope("a", 7, 1);
        var correction = new KgChunk("correction", "7", "That condition is cancelled.",
                List.of(), List.of(), "GENERAL", .8, Instant.now(), "USER", "BRAIN_STATE",
                "CONVERSATION", "brain_state", own, "chat-message:12", 1);
        brain.recordChunks(List.of(chunk(own, 11, 1), correction));
        assertEquals(List.of(new KgChunk.SourceRef("chat-message:12", 1),
                new KgChunk.SourceRef("chat-message:11", 1)), brain.privateSources(own, "Spark", 8));
        brain.recordChunks(List.of(correction));
        assertEquals(2, brain.privateSources(own, "the second option", 8).size());
    }

    @Test void legacyManualLabelDoesNotAuthorizePublicRows() {
        var legacy = new KgChunk("legacy", "7", "synthetic", List.of(), List.of(), "GENERAL", .8, Instant.now());
        assertEquals("UNSCOPED", legacy.indexScopeKey());
        var manual = new KgChunk("manual", "global", "synthetic", List.of(), List.of(), "GENERAL", .8,
                Instant.now(), "MANUAL_GRAPHDB", "GRAPHDB_MANUAL_LEARNING", "graphdb_manual_learning",
                "graphdb_manual_learning");
        assertEquals("PUBLIC", manual.indexScopeKey());
    }
}
