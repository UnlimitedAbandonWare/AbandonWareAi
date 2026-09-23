package com.example.lms.graphdb;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.graph.Neo4jKgChunkWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphDbClientTest {

    @Test
    void statusReportsManualReadWriteBoundaryAndRedactionFlags() {
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.status()).thenReturn(Map.of(
                "enabled", true,
                "endpointHost", "127.0.0.1:7687",
                "disabledReason", "",
                "lastWriteStatus", "written",
                "lastWriteFailureClass", "",
                "lastWrittenChunks", 1,
                "lastWrittenEntities", 2,
                "lastWrittenRelations", 1,
                "lastWrittenPortMappings", 1));

        Map<String, Object> status = new GraphDbClient(writer).status();

        assertEquals("neo4j", status.get("backend"));
        assertEquals("graphdb_manual_learning", status.get("writeBoundary"));
        assertEquals("graphdb_manual_learning", status.get("readBoundary"));
        assertEquals(false, status.get("rawTextIncluded"));
        assertEquals(false, status.get("rawEntityValuesIncluded"));
        assertEquals(false, status.get("rawIdentifiersIncluded"));
        assertEquals(false, status.get("rawSecretsIncluded"));
        assertEquals("written", status.get("lastWriteStatus"));
        assertFalse(status.toString().contains("ownerToken"));
        assertFalse(status.toString().contains("secret"));
    }

    @Test
    void missingWriterStatusKeepsManualBoundaryVisible() {
        Map<String, Object> status = new GraphDbClient(null).status();

        assertEquals(false, status.get("enabled"));
        assertEquals("writer_unavailable", status.get("disabledReason"));
        assertEquals("graphdb_manual_learning", status.get("writeBoundary"));
        assertEquals("graphdb_manual_learning", status.get("readBoundary"));
        assertEquals(false, status.get("rawTextIncluded"));
        assertEquals(false, status.get("rawEntityValuesIncluded"));
        assertEquals(false, status.get("rawIdentifiersIncluded"));
        assertEquals(false, status.get("rawSecretsIncluded"));
    }

    @Test
    void writerStatusCannotOverrideManualBoundaryOrRedactionFlags() {
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.status()).thenReturn(Map.ofEntries(
                Map.entry("enabled", true),
                Map.entry("backend", "raw-neo4j-writer"),
                Map.entry("writeBoundary", "knowledge_graph_handler"),
                Map.entry("readBoundary", "brain_state_fallback"),
                Map.entry("rawTextIncluded", true),
                Map.entry("rawEntityValuesIncluded", true),
                Map.entry("rawIdentifiersIncluded", true),
                Map.entry("rawSecretsIncluded", true),
                Map.entry("endpointHost", "bolt://user:secret@neo4j.internal:7687/private"),
                Map.entry("disabledReason", "ownerToken=secret"),
                Map.entry("lastWriteStatus", "written"),
                Map.entry("uri", "bolt://user:secret@neo4j.internal:7687"),
                Map.entry("password", "super-secret-password"),
                Map.entry("ownerToken", "owner-secret-token"),
                Map.entry("Authorization", "Bearer raw-token"),
                Map.entry("rawText", "private graphdb source text")));

        Map<String, Object> status = new GraphDbClient(writer).status();

        assertEquals("neo4j", status.get("backend"));
        assertEquals("graphdb_manual_learning", status.get("writeBoundary"));
        assertEquals("graphdb_manual_learning", status.get("readBoundary"));
        assertEquals(false, status.get("rawTextIncluded"));
        assertEquals(false, status.get("rawEntityValuesIncluded"));
        assertEquals(false, status.get("rawIdentifiersIncluded"));
        assertEquals(false, status.get("rawSecretsIncluded"));
        assertEquals("written", status.get("lastWriteStatus"));
        assertEquals("neo4j.internal", status.get("endpointHost"));
        assertEquals("redacted", status.get("disabledReason"));
        assertFalse(status.containsKey("uri"));
        assertFalse(status.containsKey("password"));
        assertFalse(status.containsKey("ownerToken"));
        assertFalse(status.containsKey("Authorization"));
        assertFalse(status.containsKey("rawText"));
        assertFalse(status.toString().contains("user:secret"));
        assertFalse(status.toString().contains("super-secret-password"));
        assertFalse(status.toString().contains("owner-secret-token"));
        assertFalse(status.toString().contains("private graphdb source text"));
    }

    @Test
    void invalidEndpointHostLeavesStableTraceReasonWithoutLeakingUrl() {
        TraceStore.clear();
        String ownerToken = "private-owner-token";
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.status()).thenReturn(Map.of(
                "enabled", true,
                "endpointHost", "bolt://bad host/" + ownerToken,
                "disabledReason", "",
                "lastWriteStatus", "failed",
                "lastWriteFailureClass", "",
                "lastWrittenChunks", 0,
                "lastWrittenEntities", 0,
                "lastWrittenRelations", 0,
                "lastWrittenPortMappings", 0));

        Map<String, Object> status = new GraphDbClient(writer).status();

        assertEquals("", status.get("endpointHost"));
        assertEquals("graphDb.client.endpointHost", TraceStore.get("graphdb.client.suppressed.stage"));
        assertEquals("invalid_url", TraceStore.get("graphdb.client.suppressed.errorType"));
        assertEquals(true, TraceStore.get("graphdb.client.suppressed.graphDb.client.endpointHost"));
        assertEquals("invalid_url",
                TraceStore.get("graphdb.client.suppressed.graphDb.client.endpointHost.errorType"));
        assertFalse(String.valueOf(status).contains(ownerToken));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
    }

    @Test
    void nullWriterStatusIsDisabledWithoutRawDiagnostics() {
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.status()).thenReturn(null);

        Map<String, Object> status = new GraphDbClient(writer).status();

        assertEquals(false, status.get("enabled"));
        assertEquals("writer_status_unavailable", status.get("disabledReason"));
        assertEquals("graphdb_manual_learning", status.get("writeBoundary"));
        assertEquals("graphdb_manual_learning", status.get("readBoundary"));
        assertEquals(false, status.get("rawSecretsIncluded"));
    }

    @Test
    void statusAndManualEvidenceNormalizeCancellationFailureClass() {
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.status()).thenReturn(Map.of(
                "enabled", true,
                "endpointHost", "neo4j.local:7687",
                "disabledReason", "",
                "lastWriteStatus", "failed",
                "lastWriteFailureClass", "CancellationException",
                "lastWrittenChunks", 0,
                "lastWrittenEntities", 0,
                "lastWrittenRelations", 0,
                "lastWrittenPortMappings", 0));
        when(writer.readManualEvidence("GENERAL", 5)).thenReturn(new Neo4jKgChunkWriter.ManualEvidenceReport(
                true,
                "failed",
                "cancelled ownerToken=fake-token",
                "neo4j.local:7687",
                0,
                List.of(),
                "InterruptedException"));

        GraphDbClient client = new GraphDbClient(writer);

        Map<String, Object> status = client.status();
        Map<String, Object> evidence = client.manualEvidence("GENERAL", 5);

        assertEquals("cancelled", status.get("lastWriteFailureClass"));
        assertEquals("cancelled", evidence.get("failureClass"));
        assertEquals("redacted", evidence.get("disabledReason"));
        assertFalse(status.toString().contains("fake-token"));
        assertFalse(evidence.toString().contains("fake-token"));
    }

    @Test
    void manualEvidenceRedactsRawEntityAndHopTargetValues() {
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.readManualEvidence("GENERAL", 5)).thenReturn(new Neo4jKgChunkWriter.ManualEvidenceReport(
                true,
                "ok",
                "",
                "127.0.0.1:7687",
                1,
                List.of(new Neo4jKgChunkWriter.ManualEvidenceCandidate(
                        "graphdb-chunk:private",
                        "abcdef123456",
                        "012345abcdef",
                        120,
                        "GENERAL",
                        0.7d,
                        "GRAPHDB_MANUAL",
                        "GRAPHDB_MANUAL_LEARNING",
                        "MANUAL_GRAPHDB",
                        "graphdb_manual_learning",
                        List.of("111111111111", "222222222222"),
                        List.of(new Neo4jKgChunkWriter.ManualHop(
                                "333333333333",
                                "RELATED_TO",
                                "444444444444",
                                "graphdb_manual_learning")))),
                null));

        Map<String, Object> evidence = new GraphDbClient(writer).manualEvidence("GENERAL", 5);

        assertEquals("graphdb_manual_learning", evidence.get("writeBoundary"));
        assertEquals("graphdb_manual_learning", evidence.get("readBoundary"));
        assertEquals(false, evidence.get("rawTextIncluded"));
        assertEquals(false, evidence.get("rawEntityValuesIncluded"));
        assertEquals(false, evidence.get("rawIdentifiersIncluded"));
        assertEquals(false, evidence.get("rawSecretsIncluded"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) evidence.get("candidates");
        Map<String, Object> candidate = candidates.get(0);
        assertEquals(2, candidate.get("entityCount"));
        assertTrue(candidate.containsKey("chunkHash"));
        assertEquals("abcdef123456", candidate.get("sessionHash"));
        assertEquals("012345abcdef", candidate.get("textHash"));
        assertEquals(List.of("111111111111", "222222222222"), candidate.get("entityHashes"));
        assertEquals("MANUAL_GRAPHDB", candidate.get("origin"));
        assertFalse(candidate.containsKey("chunkId"));
        assertFalse(candidate.containsKey("sessionId"));
        assertFalse(candidate.containsKey("entities"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) candidate.get("hops");
        Map<String, Object> hop = hops.get(0);
        assertEquals("333333333333", hop.get("targetHash"));
        assertEquals("444444444444", hop.get("connectorHash"));
        assertEquals("graphdb_manual_learning", hop.get("relationSource"));
        assertFalse(hop.containsKey("target"));
        assertFalse(evidence.toString().contains("Alpha Private"));
        assertFalse(evidence.toString().contains("Beta Private"));
        assertFalse(evidence.toString().contains("Gamma Private"));
        assertFalse(evidence.toString().contains("graphdb-chunk:private"));
        assertFalse(evidence.toString().contains("session-1"));
    }

    @Test
    void manualEvidenceSanitizesRawReportValuesBeforeProjection() {
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.readManualEvidence("GENERAL", 5)).thenReturn(new Neo4jKgChunkWriter.ManualEvidenceReport(
                true,
                "ok",
                "owner-secret-token",
                "bolt://user:secret@neo4j.internal:7687/private",
                1,
                List.of(new Neo4jKgChunkWriter.ManualEvidenceCandidate(
                        "graphdb-chunk:private",
                        "raw-session-id",
                        "Alpha raw text",
                        120,
                        "GENERAL",
                        0.7d,
                        "GRAPHDB_MANUAL",
                        "GRAPHDB_MANUAL_LEARNING",
                        "MANUAL_GRAPHDB",
                        "graphdb_manual_learning",
                        List.of("Alpha Private", "Beta Private"),
                        List.of(new Neo4jKgChunkWriter.ManualHop(
                                "Gamma Private",
                                "RELATED_TO",
                                "connector-secret-value",
                                "graphdb_manual_learning")))),
                "AuthorizationBearerToken"));

        Map<String, Object> evidence = new GraphDbClient(writer).manualEvidence("GENERAL", 5);

        assertEquals("redacted", evidence.get("disabledReason"));
        assertEquals("neo4j.internal", evidence.get("endpointHost"));
        assertEquals("redacted", evidence.get("failureClass"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) evidence.get("candidates");
        Map<String, Object> candidate = candidates.get(0);
        assertTrue(String.valueOf(candidate.get("sessionHash")).matches("[a-f0-9]{12}"));
        assertTrue(String.valueOf(candidate.get("textHash")).matches("[a-f0-9]{12}"));
        @SuppressWarnings("unchecked")
        List<String> entityHashes = (List<String>) candidate.get("entityHashes");
        assertTrue(entityHashes.stream().allMatch(value -> value.matches("[a-f0-9]{12}")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) candidate.get("hops");
        assertTrue(String.valueOf(hops.get(0).get("targetHash")).matches("[a-f0-9]{12}"));
        assertTrue(String.valueOf(hops.get(0).get("connectorHash")).matches("[a-f0-9]{12}"));
        assertFalse(evidence.toString().contains("raw-session-id"));
        assertFalse(evidence.toString().contains("Alpha raw text"));
        assertFalse(evidence.toString().contains("Alpha Private"));
        assertFalse(evidence.toString().contains("Beta Private"));
        assertFalse(evidence.toString().contains("Gamma Private"));
        assertFalse(evidence.toString().contains("connector-secret-value"));
        assertFalse(evidence.toString().contains("user:secret"));
        assertFalse(evidence.toString().contains("owner-secret-token"));
        assertFalse(evidence.toString().contains("AuthorizationBearerToken"));
    }
}
