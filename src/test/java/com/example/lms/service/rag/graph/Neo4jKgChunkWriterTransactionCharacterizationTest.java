package com.example.lms.service.rag.graph;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** AL-10 characterization of callback boundaries; this is not live DB atomicity proof. */
class Neo4jKgChunkWriterTransactionCharacterizationTest {
    enum Stage { NONE, CHUNK, ENTITY, RELATION }

    @BeforeEach
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void failedCallbackLeavesEarlierSuccessfulCallbacksVisible(Stage failureStage) throws Exception {
        RecordingSession fixture = new RecordingSession(failureStage);
        Neo4jKgChunkWriter writer = writer(fixture.session);

        Neo4jKgChunkWriter.WriteReport report = writer.writeChunks(List.of(syntheticChunk()));

        List<Stage> expectedAttempts = switch (failureStage) {
            case CHUNK -> List.of(Stage.CHUNK);
            case ENTITY -> List.of(Stage.CHUNK, Stage.ENTITY);
            default -> List.of(Stage.CHUNK, Stage.ENTITY, Stage.RELATION);
        };
        List<List<Stage>> expectedCompletedCallbacks = switch (failureStage) {
            case CHUNK -> List.of();
            case ENTITY -> List.of(List.of(Stage.CHUNK));
            case RELATION -> List.of(List.of(Stage.CHUNK), List.of(Stage.ENTITY));
            case NONE -> List.of(List.of(Stage.CHUNK), List.of(Stage.ENTITY), List.of(Stage.RELATION));
        };
        assertEquals(expectedAttempts, fixture.attempts);
        // The fake publishes only after a callback returns normally. It discards that
        // callback's pending operations on failure, without discarding earlier ones.
        assertEquals(expectedCompletedCallbacks, fixture.completedCallbacks);
        assertEquals(failureStage == Stage.CHUNK ? 0 : 1, report.chunkCount());
        assertEquals(failureStage == Stage.NONE || failureStage == Stage.RELATION ? 1 : 0,
                report.entityCount());
        assertEquals(failureStage == Stage.NONE ? 1 : 0, report.relationCount());
        if (failureStage == Stage.NONE) {
            assertEquals("written", report.status());
            assertNull(report.disabledReason());
        } else {
            assertEquals("failed", report.status());
            assertEquals("write_failed", report.disabledReason());
            assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.neo4j.chunkWriter.write.failed"));
            assertEquals("write_failed", TraceStore.get("retrieval.kg.neo4j.chunkWriter.write.fallback"));
        }
        verify(fixture.session, times(expectedAttempts.size())).executeWrite(any());
        verify(fixture.session).close();
        verifyNoMoreInteractions(fixture.session);
    }

    private static Neo4jKgChunkWriter writer(Session session) throws Exception {
        Neo4jKnowledgeGraphProperties properties = new Neo4jKnowledgeGraphProperties();
        properties.setEnabled(true);
        properties.setUri("bolt://127.0.0.1:1");
        properties.setUser("synthetic-user");
        properties.setPassword("synthetic-fixture");
        properties.setDatabase("neo4j");
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(properties, new BrainStateProperties());
        Driver driver = mock(Driver.class);
        when(driver.session(any(SessionConfig.class))).thenReturn(session);
        Field field = Neo4jKgChunkWriter.class.getDeclaredField("driver");
        field.setAccessible(true);
        field.set(writer, driver);
        return writer;
    }

    private static KgChunk syntheticChunk() {
        return new KgChunk("al10-chunk", "al10-session", "AL10 synthetic fixture",
                List.of(new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.8)),
                List.of(GraphRagPortMappingConnector.semanticRelation(
                        "Alpha", "Beta", "CO_MENTIONED_WITH", 0.7, "synthetic")),
                "GENERAL", 0.8, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static final class RecordingSession {
        final Session session = mock(Session.class);
        final List<Stage> attempts = new ArrayList<>();
        final List<List<Stage>> completedCallbacks = new ArrayList<>();

        RecordingSession(Stage failureStage) {
            when(session.executeWrite(any())).thenAnswer(invocation -> {
                TransactionCallback<Object> callback = invocation.getArgument(0);
                TransactionContext transaction = mock(TransactionContext.class);
                List<Stage> pending = new ArrayList<>();
                when(transaction.run(anyString(), any(Value.class))).thenAnswer(write -> {
                    Stage stage = stageOf(write.getArgument(0));
                    attempts.add(stage);
                    if (stage == failureStage) {
                        throw new IllegalStateException("synthetic_write_failure");
                    }
                    pending.add(stage);
                    return mock(Result.class);
                });
                Object result = callback.execute(transaction);
                completedCallbacks.add(List.copyOf(pending));
                return result;
            });
        }

        private static Stage stageOf(String cypher) {
            if (cypher.equals(Neo4jKgChunkWriter.chunkUpsertCypher())) return Stage.CHUNK;
            if (cypher.equals(Neo4jKgChunkWriter.entityUpsertCypher())) return Stage.ENTITY;
            if (cypher.equals(Neo4jKgChunkWriter.relationUpsertCypher())) return Stage.RELATION;
            throw new AssertionError("Unexpected synthetic write stage");
        }
    }
}
