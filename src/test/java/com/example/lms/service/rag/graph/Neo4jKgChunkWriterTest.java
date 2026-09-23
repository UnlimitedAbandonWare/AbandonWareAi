package com.example.lms.service.rag.graph;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphProperties;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Neo4jKgChunkWriterTest {

    private static final Pattern CYPHER_PARAMETER_PATTERN = Pattern.compile("\\$([A-Za-z][A-Za-z0-9_]*)");

    @Test
    void disabledNeo4jDoesNotAttemptWrites() {
        Neo4jKnowledgeGraphProperties neo4j = new Neo4jKnowledgeGraphProperties();
        neo4j.setEnabled(false);
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(neo4j, new BrainStateProperties());

        Neo4jKgChunkWriter.WriteReport report = writer.writeChunks(List.of(chunk()));

        assertFalse(report.enabled());
        assertEquals("disabled", report.status());
        assertEquals("disabled", report.disabledReason());
        assertEquals(0, report.chunkCount());
    }

    @Test
    void dependencyCancellationUsesStableFailureClassWithoutRawMessage() throws Exception {
        TraceStore.clear();
        Neo4jKnowledgeGraphProperties neo4j = enabledNeo4j();
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(neo4j, new BrainStateProperties());
        Driver driver = mock(Driver.class);
        when(driver.session(any(SessionConfig.class)))
                .thenThrow(new CancellationException("cancelled ownerToken=fake-token"));
        setDriver(writer, driver);

        Neo4jKgChunkWriter.WriteReport write = writer.writeChunks(List.of(chunk()));
        Neo4jKgChunkWriter.ManualEvidenceReport read = writer.readManualEvidence("GENERAL", 3);

        assertEquals("failed", write.status());
        assertEquals("cancelled", write.failureClass());
        assertFalse(write.toString().contains("fake-token"));
        assertEquals("failed", read.status());
        assertEquals("cancelled", read.failureClass());
        assertFalse(read.toString().contains("fake-token"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.neo4j.chunkWriter.write.failed"));
        assertEquals("cancelled", TraceStore.get("retrieval.kg.neo4j.chunkWriter.write.failureClass"));
        assertEquals("write_failed", TraceStore.get("retrieval.kg.neo4j.chunkWriter.write.fallback"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.neo4j.chunkWriter.read.failed"));
        assertEquals("cancelled", TraceStore.get("retrieval.kg.neo4j.chunkWriter.read.failureClass"));
        assertEquals("read_failed", TraceStore.get("retrieval.kg.neo4j.chunkWriter.read.fallback"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("fake-token"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        TraceStore.clear();
    }

    @Test
    void chunkParametersCarryHashAndLengthInsteadOfRawText() {
        Neo4jKnowledgeGraphProperties neo4j = new Neo4jKnowledgeGraphProperties();
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(neo4j, new BrainStateProperties());

        Map<String, Object> params = writer.chunkParameters(chunk());

        assertEquals("chunk-1", params.get("chunkId"));
        assertEquals(12, String.valueOf(params.get("sessionHash")).length());
        assertEquals(12, String.valueOf(params.get("textHash")).length());
        assertEquals("very secret text".length(), params.get("textLength"));
        assertTrue(params.containsKey("domain"));
        assertFalse(params.containsKey("sessionId"));
        assertFalse(params.containsValue("s1"));
        assertFalse(params.containsValue("very secret text"));
    }

    @Test
    void relationParametersCarryPortMappingWithoutRawChunkText() {
        Neo4jKnowledgeGraphProperties neo4j = new Neo4jKnowledgeGraphProperties();
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(neo4j, new BrainStateProperties());
        KgChunk chunk = chunk();

        Map<String, Object> params = writer.relationParameters(chunk, chunk.relations().get(0));

        assertEquals("semantic_out", params.get("sourcePort"));
        assertEquals("semantic_in", params.get("targetPort"));
        assertEquals("CO_MENTIONED_WITH", params.get("connectorKind"));
        assertEquals(12, String.valueOf(params.get("connectorHash12")).length());
        assertEquals("conversation-brain-state", params.get("relationSource"));
        assertFalse(params.containsValue("very secret text"));
    }

    @Test
    void chunkParametersCarryGraphDbManualBoundaryMetadataWithoutRawText() {
        Neo4jKnowledgeGraphProperties neo4j = new Neo4jKnowledgeGraphProperties();
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(neo4j, new BrainStateProperties());

        Map<String, Object> params = writer.chunkParameters(graphDbManualChunk());

        assertEquals("GRAPHDB_MANUAL", params.get("sourceTag"));
        assertEquals("GRAPHDB_MANUAL_LEARNING", params.get("docType"));
        assertEquals("MANUAL_GRAPHDB", params.get("origin"));
        assertEquals("graphdb_manual_learning", params.get("ingestLane"));
        assertEquals(12, String.valueOf(params.get("sessionHash")).length());
        assertFalse(params.containsKey("sessionId"));
        assertFalse(params.containsValue("manual private text"));
    }

    @Test
    void entityParametersMatchHashScopedChunkUpsertCypherWithoutRawText() {
        Neo4jKnowledgeGraphProperties neo4j = new Neo4jKnowledgeGraphProperties();
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(neo4j, new BrainStateProperties());
        KgChunk chunk = graphDbManualChunk();

        Map<String, Object> params = writer.entityParameters(chunk, chunk.entities().get(0));

        assertTrue(Neo4jKgChunkWriter.entityUpsertCypher().contains("sessionHash: $sessionHash"));
        assertTrue(Neo4jKgChunkWriter.entityUpsertCypher().contains("textHash: $textHash"));
        assertTrue(Neo4jKgChunkWriter.entityUpsertCypher().contains("ingestLane: $ingestLane"));
        assertEquals(12, String.valueOf(params.get("sessionHash")).length());
        assertEquals(12, String.valueOf(params.get("textHash")).length());
        assertEquals("graphdb_manual_learning", params.get("ingestLane"));
        assertEquals("Alpha", params.get("name"));
        assertEquals("GENERAL", params.get("domain"));
        assertEquals("ENTITY", params.get("type"));
        assertFalse(params.containsKey("sessionId"));
        assertFalse(params.containsValue("s1"));
        assertFalse(params.containsValue("manual private text"));
    }

    @Test
    void graphDbManualCypherPlaceholdersMatchWriterParameters() {
        Neo4jKnowledgeGraphProperties neo4j = new Neo4jKnowledgeGraphProperties();
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(neo4j, new BrainStateProperties());
        KgChunk chunk = graphDbManualChunkWithRelation();

        assertNoMissingParameters(
                Neo4jKgChunkWriter.chunkUpsertCypher(),
                writer.chunkParameters(chunk));
        assertNoMissingParameters(
                Neo4jKgChunkWriter.entityUpsertCypher(),
                writer.entityParameters(chunk, chunk.entities().get(0)));
        assertNoMissingParameters(
                Neo4jKgChunkWriter.relationUpsertCypher(),
                writer.relationParameters(chunk, chunk.relations().get(0)));
        assertNoMissingParameters(
                Neo4jKgChunkWriter.manualEvidenceCypher(),
                writer.manualEvidenceParameters("general", 100));
    }

    @Test
    void graphDbManualRelationsCarryManualLaneSource() {
        Neo4jKnowledgeGraphProperties neo4j = new Neo4jKnowledgeGraphProperties();
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(neo4j, new BrainStateProperties());
        KgChunk chunk = graphDbManualChunkWithRelation();

        Map<String, Object> params = writer.relationParameters(chunk, chunk.relations().get(0));

        assertEquals("graphdb_manual_learning", params.get("relationSource"));
        assertFalse(params.containsValue("manual relation private text"));
    }

    @Test
    void manualEvidenceReadIsBoundToGraphDbManualLearningLane() {
        String cypher = Neo4jKgChunkWriter.manualEvidenceCypher();

        assertTrue(cypher.contains("coalesce(c.ingestLane, '') = 'graphdb_manual_learning'"));
        assertTrue(cypher.contains("OPTIONAL MATCH (e)-[r:RELATED_TO {source: 'graphdb_manual_learning'}]->(hop:KgEntity)"));
        assertTrue(cypher.contains("connectorHash: r.connectorHash12"));
        assertTrue(cypher.contains("relationSource: r.source"));
        assertTrue(cypher.contains("c.sessionHash AS sessionHash"));
        assertTrue(cypher.contains("c.origin AS origin"));
        assertTrue(cypher.contains("MATCH (c:KgChunkNode)"));
        assertTrue(cypher.contains("RELATED_TO"));
        assertFalse(cypher.contains("c.sessionId AS sessionId"));
        assertFalse(cypher.contains("sessionId AS"));
        assertFalse(cypher.contains("WHEN coalesce(r.source"));
        assertFalse(cypher.contains("sourceText"));
        assertFalse(cypher.contains("kg_score"));
        assertFalse(cypher.contains("rag.eval.kgAxis"));
    }

    @Test
    void chunkUpsertCypherPersistsManualLaneMetadataWithoutRawTextColumn() {
        String cypher = Neo4jKgChunkWriter.chunkUpsertCypher();

        assertTrue(cypher.contains("MERGE (c:KgChunkNode {"));
        assertTrue(cypher.contains("sessionHash: $sessionHash"));
        assertTrue(cypher.contains("textHash: $textHash"));
        assertTrue(cypher.contains("ingestLane: $ingestLane"));
        assertFalse(cypher.contains("MERGE (c:KgChunkNode {chunkId: $chunkId})"));
        assertTrue(cypher.contains("c.chunkId = $chunkId"));
        assertTrue(cypher.contains("REMOVE c.`sessionId`"));
        assertTrue(cypher.contains("c.textLength = $textLength"));
        assertTrue(cypher.contains("c.sourceTag = $sourceTag"));
        assertTrue(cypher.contains("c.docType = $docType"));
        assertTrue(cypher.contains("c.origin = $origin"));
        assertFalse(cypher.contains("c.sessionId ="));
        assertFalse(cypher.contains("$sessionId"));
        assertFalse(cypher.contains("sourceText"));
        assertFalse(cypher.contains("rawText"));
        assertFalse(cypher.contains("kg_score"));
        assertFalse(cypher.contains("rag.eval.kgAxis"));
    }

    @Test
    void entityUpsertCypherBindsEntitiesToChunkWithoutGraphDbNamespaceLeak() {
        String cypher = Neo4jKgChunkWriter.entityUpsertCypher();

        assertTrue(cypher.contains("MATCH (c:KgChunkNode {"));
        assertTrue(cypher.contains("sessionHash: $sessionHash"));
        assertTrue(cypher.contains("textHash: $textHash"));
        assertTrue(cypher.contains("ingestLane: $ingestLane"));
        assertFalse(cypher.contains("MATCH (c:KgChunkNode {chunkId: $chunkId})"));
        assertTrue(cypher.contains("MERGE (e:KgEntity {name: $name, domain: $domain})"));
        assertTrue(cypher.contains("MERGE (c)-[r:CONTAINS_ENTITY]->(e)"));
        assertFalse(cypher.contains("sourceText"));
        assertFalse(cypher.contains("com.example.lms.graphdb"));
        assertFalse(cypher.contains("BrainStateService"));
    }

    @Test
    void relationUpsertCypherUsesParameterizedRelationSource() {
        String cypher = Neo4jKgChunkWriter.relationUpsertCypher();

        assertTrue(cypher.contains("source: $relationSource"));
        assertTrue(cypher.contains("r.source = $relationSource"));
        assertFalse(cypher.contains("r.source = 'conversation-brain-state'"));
    }

    @Test
    void manualEvidenceCandidateRecordIsHashOnlyAtDtoBoundary() {
        List<String> componentNames = Arrays.stream(Neo4jKgChunkWriter.ManualEvidenceCandidate.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        Neo4jKgChunkWriter.ManualEvidenceCandidate candidate =
                new Neo4jKgChunkWriter.ManualEvidenceCandidate(
                        "graphdb-chunk:private",
                        "sessionhash12",
                        "texthashonly",
                        120,
                        "GENERAL",
                        0.8d,
                        "GRAPHDB_MANUAL",
                        "GRAPHDB_MANUAL_LEARNING",
                        "MANUAL_GRAPHDB",
                        "graphdb_manual_learning",
                        List.of("entityhash12"),
                        List.of(new Neo4jKgChunkWriter.ManualHop(
                                "targethash12",
                                "RELATED_TO",
                                "connectorhash",
                                "graphdb_manual_learning")));

        assertTrue(componentNames.contains("sessionHash"));
        assertTrue(componentNames.contains("entityHashes"));
        List<String> hopComponentNames = Arrays.stream(Neo4jKgChunkWriter.ManualHop.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertTrue(hopComponentNames.contains("connectorHash"));
        assertFalse(componentNames.contains("sessionId"));
        assertFalse(componentNames.contains("entities"));
        assertEquals("sessionhash12", candidate.sessionHash());
        assertEquals(List.of("entityhash12"), candidate.entityHashes());
        assertEquals("targethash12", candidate.hops().get(0).targetHash());
        assertEquals("connectorhash", candidate.hops().get(0).connectorHash());
    }

    @Test
    void manualEvidenceParametersClampLimitAndNormalizeDomain() {
        Neo4jKnowledgeGraphProperties neo4j = new Neo4jKnowledgeGraphProperties();
        Neo4jKgChunkWriter writer = new Neo4jKgChunkWriter(neo4j, new BrainStateProperties());

        Map<String, Object> params = writer.manualEvidenceParameters("demo", 500);

        assertEquals("DEMO", params.get("domain"));
        assertEquals(50, params.get("limit"));
    }

    private static KgChunk chunk() {
        return new KgChunk(
                "chunk-1",
                "s1",
                "very secret text",
                List.of(new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.8)),
                List.of(GraphRagPortMappingConnector.semanticRelation(
                        "Alpha",
                        "Beta",
                        "CO_MENTIONED_WITH",
                        0.7,
                        "test")),
                "GENERAL",
                0.8,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static Neo4jKnowledgeGraphProperties enabledNeo4j() {
        Neo4jKnowledgeGraphProperties neo4j = new Neo4jKnowledgeGraphProperties();
        neo4j.setEnabled(true);
        neo4j.setUri("bolt://neo4j.local:7687");
        neo4j.setUser("graph-user");
        neo4j.setPassword("fake-password");
        return neo4j;
    }

    private static void setDriver(Neo4jKgChunkWriter writer, Driver driver) throws Exception {
        Field field = Neo4jKgChunkWriter.class.getDeclaredField("driver");
        field.setAccessible(true);
        field.set(writer, driver);
    }

    private static KgChunk graphDbManualChunk() {
        return new KgChunk(
                "graphdb-chunk:1",
                "s1",
                "manual private text",
                List.of(new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.8)),
                List.of(),
                "GENERAL",
                0.8,
                Instant.parse("2026-01-01T00:00:00Z"),
                "GRAPHDB_MANUAL",
                "GRAPHDB_MANUAL_LEARNING",
                "MANUAL_GRAPHDB",
                "graphdb_manual_learning");
    }

    private static KgChunk graphDbManualChunkWithRelation() {
        return new KgChunk(
                "graphdb-chunk:2",
                "s1",
                "manual relation private text",
                List.of(
                        new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.8),
                        new KgChunk.KgEntity("Beta", "ENTITY", "GENERAL", 0.8)),
                List.of(GraphRagPortMappingConnector.semanticRelation(
                        "Alpha",
                        "Beta",
                        "CO_MENTIONED_WITH",
                        0.7,
                        "manual")),
                "GENERAL",
                0.8,
                Instant.parse("2026-01-01T00:00:00Z"),
                "GRAPHDB_MANUAL",
                "GRAPHDB_MANUAL_LEARNING",
                "MANUAL_GRAPHDB",
                "graphdb_manual_learning");
    }

    private static void assertNoMissingParameters(String cypher, Map<String, Object> params) {
        Set<String> placeholders = cypherParameters(cypher);
        Set<String> missing = new LinkedHashSet<>(placeholders);
        missing.removeAll(params.keySet());
        assertTrue(missing.isEmpty(), "Missing Cypher parameters: " + missing);
    }

    private static Set<String> cypherParameters(String cypher) {
        Matcher matcher = CYPHER_PARAMETER_PATTERN.matcher(cypher);
        Set<String> out = new LinkedHashSet<>();
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }
}
