package com.example.lms.service.rag.graph;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.MemorySnippet;
import com.example.lms.dto.learning.Triple;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDeltaGraphProjectorTest {

    @Test
    void projectsTriplesAndMemoriesThroughBrainStateIngestWithoutRawSourceUrl() {
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        when(chunking.ingestPreparedChunks(anyString(), eq("KNOWLEDGE_DELTA"), anyList(), anyString()))
                .thenReturn(new GraphRagChunkingService.IngestReport(
                        true, "__KNOWLEDGE_DELTA__", "indexed", 1, 2, 1, 1, "hash-only", "", Map.of(
                        "vectorStatus", "queued",
                        "brainStateStatus", "recorded",
                        "neo4jStatus", "written",
                        "neo4jWriteCount", 1)));

        KnowledgeDeltaGraphProjector projector = new KnowledgeDeltaGraphProjector(chunking);
        KnowledgeDelta delta = new KnowledgeDelta(
                List.of(new Triple("GraphRAG", "uses", "Neo4j", "https://example.test/path?api_key=secret")),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        KnowledgeDeltaGraphProjector.ProjectReport report = projector.project(delta);

        assertTrue(report.projected());
        assertEquals(1, report.chunks());
        assertEquals(1, report.neo4jWritten());
        assertEquals("written", report.neo4jStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KgChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(chunking).ingestPreparedChunks(eq("__KNOWLEDGE_DELTA__"), eq("KNOWLEDGE_DELTA"), chunks.capture(), anyString());
        KgChunk chunk = chunks.getValue().get(0);
        assertEquals("USES", chunk.relations().get(0).kind());
        assertEquals("GraphRAG", chunk.relations().get(0).source());
        assertEquals("Neo4j", chunk.relations().get(0).target());
        assertEquals("semantic_out", chunk.relations().get(0).sourcePort());
        assertEquals("semantic_in", chunk.relations().get(0).targetPort());
        assertEquals("USES", chunk.relations().get(0).connectorKind());
        assertEquals(12, chunk.relations().get(0).connectorHash12().length());
        assertTrue(chunk.sourceText().contains("sourceHash"));
        assertFalse(chunk.sourceText().contains("api_key=secret"));
        assertFalse(chunk.sourceText().contains("https://example.test"));
    }

    @Test
    void projectsMemoriesThroughRedactedTextProjection() {
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        when(chunking.ingestText(anyString(), anyString(), eq("KNOWLEDGE_DELTA"), anyString()))
                .thenReturn(new GraphRagChunkingService.IngestReport(
                        true, "__KNOWLEDGE_DELTA__", "indexed", 1, 1, 0, 0, "hash-only", "", Map.of(
                        "vectorStatus", "queued",
                        "brainStateStatus", "recorded",
                        "neo4jStatus", "disabled",
                        "neo4jDisabledReason", "disabled")));

        KnowledgeDeltaGraphProjector projector = new KnowledgeDeltaGraphProjector(chunking);
        KnowledgeDelta delta = new KnowledgeDelta(
                List.of(),
                List.of(),
                List.of(),
                List.of(new MemorySnippet("GraphRAG combines graph and vector retrieval ownerToken=secret", "GraphRAG", 0.91)),
                List.of());

        KnowledgeDeltaGraphProjector.ProjectReport report = projector.project(delta);

        assertTrue(report.projected());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(chunking).ingestText(eq("__KNOWLEDGE_DELTA__"), text.capture(), eq("KNOWLEDGE_DELTA"), eq("GRAPHRAG"));
        assertTrue(text.getValue().contains("GraphRAG"));
        assertFalse(text.getValue().contains("ownerToken=secret"));
    }

    @Test
    void emptyDeltaIsSkippedWithoutCallingChunking() {
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        KnowledgeDeltaGraphProjector.ProjectReport report = new KnowledgeDeltaGraphProjector(chunking)
                .project(new KnowledgeDelta(List.of(), List.of(), List.of(), List.of(), List.of()));

        assertFalse(report.projected());
        assertEquals("empty_delta", report.disabledReason());
    }

    @Test
    void projectionFailureClassNormalizesCancellation() {
        TraceStore.clear();
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        when(chunking.ingestPreparedChunks(anyString(), eq("KNOWLEDGE_DELTA"), anyList(), anyString()))
                .thenThrow(new CancellationException("cancelled ownerToken fake-token"));

        KnowledgeDeltaGraphProjector.ProjectReport report = new KnowledgeDeltaGraphProjector(chunking)
                .project(new KnowledgeDelta(
                        List.of(new Triple("GraphRAG", "uses", "Neo4j", "manual://test")),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));

        assertFalse(report.projected());
        assertEquals("projection_failed", report.disabledReason());
        assertEquals("cancelled", report.failureClass());
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.knowledgeDelta.project.suppressed"));
        assertEquals("cancelled", TraceStore.get("retrieval.kg.knowledgeDelta.project.failureClass"));
        assertEquals("projection_failed", TraceStore.get("retrieval.kg.knowledgeDelta.project.fallback"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        assertFalse(String.valueOf(report).contains("ownerToken"));
        TraceStore.clear();
    }

    @Test
    void projectorFailSoftCatchLeavesStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/graph/KnowledgeDeltaGraphProjector.java"));

        assertTrue(source.contains("traceSuppressed(\"project\""));
        assertTrue(source.contains("log.debug(\"[KnowledgeDeltaGraphProjector] fail-soft stage={} err={}"));
        assertFalse(source.contains("log.debug(\"[KnowledgeDeltaGraphProjector] fail-soft stage={} err={}\", stage, failureClass(failure));"));
        assertTrue(source.contains(
                "String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, \"unknown\");"));
        assertTrue(source.contains("catch (Exception ex) {"));
    }
}
