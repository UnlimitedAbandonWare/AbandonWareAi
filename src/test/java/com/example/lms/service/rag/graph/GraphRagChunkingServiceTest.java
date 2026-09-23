package com.example.lms.service.rag.graph;

import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.ner.NamedEntityExtractor;
import com.example.lms.service.rag.knowledge.UniversalContextLexicon;
import com.example.lms.service.vector.DocumentChunkingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphRagChunkingServiceTest {

    @Test
    void conversationTurnPropagatesAllDisabledChildState() {
        BrainStateProperties props = new BrainStateProperties();
        props.setEnabled(false);
        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                text -> List.of(),
                mock(Neo4jKgChunkWriter.class),
                new UniversalContextLexicon(),
                mock(VectorStoreService.class),
                mock(BrainStateService.class),
                mock(ChatMessageRepository.class));

        GraphRagChunkingService.IngestReport report = service.ingestConversationTurn(
                "session-disabled",
                "User text must not be ingested",
                "Assistant text must not be ingested");

        assertFalse(report.enabled());
        assertEquals("disabled", report.status());
        assertEquals("brain_state_disabled", report.disabledReason());
        assertEquals(2, report.backend().get("reports"));
        assertEquals(2, report.backend().get("disabledReports"));
    }

    @Test
    void ingestTextChunksExtractsEntitiesAndQueuesVectorMetadata() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> List.of("Alpha", "Beta");
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, "", null));

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));

        GraphRagChunkingService.IngestReport report = service.ingestText(
                "s1", "Alpha helps Beta near the coast", "USER", "general");

        assertTrue(report.enabled());
        assertEquals(BrainStateText.hash12("s1"), report.sessionId());
        assertEquals(1, report.chunkCount());
        assertEquals(2, report.entityCount());
        assertEquals(1, report.relationCount());
        assertEquals("passed", report.backend().get("meaningfulGate"));
        assertEquals(1, report.backend().get("persistedChunkCount"));
        assertEquals(0, report.backend().get("skippedLowSignalChunks"));
        assertEquals("disabled", report.backend().get("neo4jStatus"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.startsWith("brain-chunk:"),
                org.mockito.ArgumentMatchers.eq("s1"),
                org.mockito.ArgumentMatchers.contains("Alpha"),
                metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals("BRAIN_STATE", meta.get("doc_type"));
        assertEquals("USER", meta.get("source_tag"));
        assertTrue(meta.containsKey("brain_text_hash"));
        assertEquals(1, meta.get("brain_port_mapping_count"));
        assertTrue(((List<?>) meta.get("brain_connector_hashes")).stream()
                .allMatch(hash -> String.valueOf(hash).length() == 12));
        assertFalse(meta.containsValue("Alpha helps Beta near the coast"));
        assertEquals("queued", report.backend().get("vectorStatus"));
        assertEquals("recorded", report.backend().get("brainStateStatus"));
        verify(brain).recordChunks(anyList());
    }

    @Test
    void ingestTextClassifiesBrainStateCancellationWithoutRawLeak() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> List.of("Alpha", "Beta");
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, "", null));
        doThrow(new CancellationException("cancelled ownerToken=fake-token"))
                .when(brain).recordChunks(anyList());

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));

        GraphRagChunkingService.IngestReport report = service.ingestText(
                "s1", "Alpha helps Beta near the coast", "USER", "general");

        assertEquals("failed", report.backend().get("brainStateStatus"));
        assertEquals("cancelled", report.backend().get("failureClass"));
        assertFalse(report.backend().toString().contains("fake-token"));
    }

    @Test
    void entityExtractorFailureLeavesTraceBreadcrumbWithoutRawMessage() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> {
            throw new IllegalStateException("raw extractor secret");
        };
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, "", null));

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));

        TraceStore.clear();
        service.ingestText("s1", "Alpha helps Beta near the coast", "USER", "general");

        assertEquals(true, TraceStore.get("retrieval.kg.graphRagChunking.suppressed.entityExtractor.extract"));
        assertEquals("IllegalStateException",
                TraceStore.get("retrieval.kg.graphRagChunking.entityExtractor.extract.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("raw extractor secret"));
    }

    @Test
    void ingestTextRecordsQueryTimeAnchorFrequencySideChannel() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> List.of("Alpha", "Beta");
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        AnchorFrequencyIndex anchorFrequencyIndex = new AnchorFrequencyIndex(true);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, "", null));

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class),
                anchorFrequencyIndex);

        GraphRagChunkingService.IngestReport report = service.ingestText(
                "s1", "Alpha helps Beta near the coast", "USER", "general");
        QueryTimeAnchorMap.AnchorSlice slice = new QueryTimeAnchorMap(anchorFrequencyIndex, true, 5)
                .slice("Alpha Beta", "GENERAL", List.of());

        assertEquals("recorded", report.backend().get("anchorMapStatus"));
        assertEquals(2, report.backend().get("anchorMapEntityCount"));
        assertEquals(1, report.backend().get("anchorMapRelationCount"));
        assertTrue(anchorFrequencyIndex.entities("GENERAL").stream()
                .anyMatch(entity -> "Alpha".equals(entity.name())));
        assertTrue(slice.applied());
        assertFalse(slice.relations().isEmpty());
        assertTrue(slice.seedHashes().stream().allMatch(hash -> hash.matches("[0-9a-f]{12}")));
    }

    @Test
    void ingestTextSkipsQueryTimeAnchorFrequencyWhenRouteIsDisabled() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> List.of("Alpha", "Beta");
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        AnchorFrequencyIndex anchorFrequencyIndex = new AnchorFrequencyIndex();
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, "", null));

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class),
                anchorFrequencyIndex);

        GraphRagChunkingService.IngestReport report = service.ingestText(
                "s1", "Alpha helps Beta near the coast", "USER", "general");

        assertEquals("disabled", report.backend().get("anchorMapStatus"));
        assertEquals("route_disabled", report.backend().get("anchorMapDisabledReason"));
        assertEquals(0, report.backend().get("anchorMapEntityCount"));
        assertTrue(anchorFrequencyIndex.entities("GENERAL").isEmpty());
        assertEquals(0, anchorFrequencyIndex.recordedChunkIdCount());
    }

    @Test
    void graphDbManualLaneDoesNotSeedQueryTimeAnchorMap() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> List.of("Alpha", "Beta");
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        AnchorFrequencyIndex anchorFrequencyIndex = new AnchorFrequencyIndex(true);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                true, "written", null, 1, 2, 1, 1, "neo4j.local", null));

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class),
                anchorFrequencyIndex);

        GraphRagChunkingService.IngestReport report = service.ingestText(
                "s-graphdb",
                "Alpha helps Beta near the graphdb manual lane",
                "GRAPHDB_MANUAL",
                "GENERAL",
                GraphRagChunkingService.IngestOptions.graphDbManual(false, true, true, false));

        assertEquals("indexed", report.status());
        assertEquals("disabled", report.backend().get("anchorMapStatus"));
        assertEquals("graphdb_manual_lane_excludes_query_time_anchor_map",
                report.backend().get("anchorMapDisabledReason"));
        assertEquals(0, report.backend().get("anchorMapEntityCount"));
        assertEquals(0, anchorFrequencyIndex.recordedChunkIdCount());
        assertTrue(anchorFrequencyIndex.entities("GENERAL").isEmpty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.startsWith("graphdb-chunk:"),
                org.mockito.ArgumentMatchers.eq("graphdb-manual:" + BrainStateText.hash12("s-graphdb")),
                org.mockito.ArgumentMatchers.contains("Alpha"),
                metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals("GRAPHDB_MANUAL_LEARNING", meta.get("doc_type"));
        assertEquals("GRAPHDB_MANUAL", meta.get("source_tag"));
        assertEquals("MANUAL_GRAPHDB", meta.get("origin"));
        assertEquals("graphdb_manual_learning", meta.get("ingest_lane"));
        assertEquals("graphdb-manual:" + BrainStateText.hash12("s-graphdb"),
                meta.get(VectorMetaKeys.META_SID_LOGICAL));
        assertEquals("graphdb-manual:" + BrainStateText.hash12("s-graphdb"),
                meta.get(VectorMetaKeys.META_ORIGINAL_SID));
        assertEquals(BrainStateText.hash12("s-graphdb"), meta.get("graphdb_manual_session_hash"));
        assertEquals("false", meta.get("raw_session_id_included"));
        assertFalse(meta.containsValue("s-graphdb"));
        assertEquals("GENERAL", meta.get("domain"));
        assertEquals(2, meta.get("brain_entity_count"));
        assertEquals(1, meta.get("brain_relation_count"));
        assertFalse(meta.containsValue("Alpha helps Beta near the graphdb manual lane"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KgChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeChunks(chunksCaptor.capture());
        KgChunk written = chunksCaptor.getValue().get(0);
        assertTrue(written.chunkId().startsWith("graphdb-chunk:"));
        assertEquals("s-graphdb", written.sessionId());
        assertEquals("GRAPHDB_MANUAL", written.sourceTag());
        assertEquals("GRAPHDB_MANUAL_LEARNING", written.docType());
        assertEquals("MANUAL_GRAPHDB", written.origin());
        assertEquals("graphdb_manual_learning", written.ingestLane());
        assertEquals("GENERAL", written.domain());
        assertTrue(written.entities().stream().allMatch(entity -> "GENERAL".equals(entity.domain())));
        assertFalse(written.relations().isEmpty());
        Map<String, Object> relationParams = new Neo4jKgChunkWriter(
                new com.example.lms.service.rag.kg.Neo4jKnowledgeGraphProperties(),
                new BrainStateProperties())
                .relationParameters(written, written.relations().get(0));
        assertEquals("GENERAL", relationParams.get("domain"));
        assertEquals("graphdb_manual_learning", relationParams.get("relationSource"));
        verify(brain, never()).recordChunks(anyList());
    }

    @Test
    void anchorFrequencyIndexRecordsIdempotentlyAndBoundsChunkKeys() {
        AnchorFrequencyIndex anchorFrequencyIndex = new AnchorFrequencyIndex(true);

        AnchorFrequencyIndex.RecordReport first = anchorFrequencyIndex.record(List.of(testChunk("chunk-stable")));
        AnchorFrequencyIndex.RecordReport second = anchorFrequencyIndex.record(List.of(testChunk("chunk-stable")));

        assertEquals(1, first.chunkCount());
        assertEquals(2, first.entityRecordCount());
        assertEquals(1, first.relationRecordCount());
        assertEquals(0, second.chunkCount());
        assertEquals(0, second.entityRecordCount());

        List<KgChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 8_205; i++) {
            chunks.add(new KgChunk(
                    "chunk-bounded-" + i,
                    "s1",
                    "",
                    List.of(),
                    List.of(),
                    "GENERAL",
                    0.0,
                    Instant.now()));
        }
        anchorFrequencyIndex.record(chunks);

        assertTrue(anchorFrequencyIndex.recordedChunkIdCount() <= 8_192);
    }

    @Test
    void ingestTextPreservesUawThumbnailSourceTagForBrainGraph() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> List.of("Alpha", "Beta");
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, "", null));

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));

        service.ingestText("thumb", "Alpha and Beta thumbnail", "UAW_THUMBNAIL", "UAW_THUMB");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.startsWith("brain-chunk:"),
                org.mockito.ArgumentMatchers.eq("thumb"),
                org.mockito.ArgumentMatchers.contains("Alpha"),
                metaCaptor.capture());
        assertEquals("UAW_THUMBNAIL", metaCaptor.getValue().get("source_tag"));
        assertEquals("UAW_THUMB", metaCaptor.getValue().get("domain"));
    }

    @Test
    void ingestPreparedUawThumbnailQueuesRelationThumbnailVectorPayload() {
        BrainStateProperties props = new BrainStateProperties();
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, "", null));

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                text -> List.of(),
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));

        KgChunk chunk = new KgChunk(
                "uaw-thumb:abc123",
                "__UAW_THUMBNAIL__",
                "caption: Alpha and Beta route thumbnail",
                List.of(
                        new KgChunk.KgEntity("Alpha", "THUMBNAIL_ANCHOR", "UAW_THUMB", 0.91d),
                        new KgChunk.KgEntity("Beta", "THUMBNAIL_ANCHOR", "UAW_THUMB", 0.91d)),
                List.of(GraphRagPortMappingConnector.semanticRelation(
                        "Alpha",
                        "Beta",
                        "UAW_THUMBNAIL_RELATED_TO",
                        0.91d,
                        "uaw-thumbnail:fixture")),
                "UAW_THUMB",
                0.91d,
                Instant.now(),
                "UAW_THUMBNAIL",
                "BRAIN_STATE",
                "uaw_thumbnail",
                "uaw_thumbnail_warmup");

        GraphRagChunkingService.IngestReport report = service.ingestPreparedChunks(
                "__UAW_THUMBNAIL__",
                "UAW_THUMBNAIL",
                List.of(chunk),
                BrainStateText.hash12(chunk.sourceText()));

        assertEquals("indexed", report.status());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> vectorTextCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.eq("uaw-thumb:abc123"),
                org.mockito.ArgumentMatchers.eq("__UAW_THUMBNAIL__"),
                vectorTextCaptor.capture(),
                metaCaptor.capture());
        String vectorText = vectorTextCaptor.getValue();
        Map<String, Object> meta = metaCaptor.getValue();
        assertTrue(vectorText.contains("relationBreadcrumbs:"));
        assertTrue(vectorText.contains("Alpha --UAW_THUMBNAIL_RELATED_TO--> Beta"));
        assertTrue(vectorText.contains("relationSummary: relation thumbnail anchors=2 relations=1"));
        assertFalse(vectorText.contains("caption: Alpha and Beta route thumbnail"));
        assertEquals("relation_thumbnail_v1", meta.get("kg_relation_thumbnail_mode"));
        assertEquals("uaw_thumbnail_warmup", meta.get("kg_relation_thumbnail_source"));
        assertEquals("Alpha --UAW_THUMBNAIL_RELATED_TO--> Beta", meta.get("kg_relation_breadcrumb"));
        assertTrue(String.valueOf(meta.get("kg_relation_summary"))
                .contains("relation thumbnail anchors=2 relations=1"));
        assertTrue(String.valueOf(meta.get("kg_relation_thumbnail_hash")).matches("[0-9a-f]{12}"));
        assertTrue(String.valueOf(meta.get("kg_relation_anchor_hash12")).matches("[0-9a-f]{12}"));
        assertEquals(1, meta.get("brain_relation_count"));
        assertFalse(meta.containsValue("uaw-thumbnail:fixture"));
    }

    @Test
    void ingestTextPreservesKnowledgeDeltaSourceTagForBrainGraph() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> List.of("GraphRAG", "Neo4j");
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, "", null));

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));

        service.ingestText("__KNOWLEDGE_DELTA__", "GraphRAG USES Neo4j", "KNOWLEDGE_DELTA", "GENERAL");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.startsWith("brain-chunk:"),
                org.mockito.ArgumentMatchers.eq("__KNOWLEDGE_DELTA__"),
                org.mockito.ArgumentMatchers.contains("GraphRAG"),
                metaCaptor.capture());
        assertEquals("KNOWLEDGE_DELTA", metaCaptor.getValue().get("source_tag"));
        assertEquals("GENERAL", metaCaptor.getValue().get("domain"));
    }

    @Test
    void ingestTextSkipsLowSignalChunksWithoutPersisting() {
        BrainStateProperties props = new BrainStateProperties();
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                text -> List.of(),
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));

        GraphRagChunkingService.IngestReport report = service.ingestText(
                "s1", "plain text with no extracted entity", "USER", "general");

        assertTrue(report.enabled());
        assertEquals("skipped", report.status());
        assertEquals(0, report.chunkCount());
        assertEquals(0, report.entityCount());
        assertEquals(0, report.neo4jWriteCount());
        assertEquals("low_signal_chunks", report.disabledReason());
        assertEquals("skipped_low_signal", report.backend().get("meaningfulGate"));
        assertEquals(0, report.backend().get("persistedChunkCount"));
        assertEquals(1, report.backend().get("skippedLowSignalChunks"));
        assertFalse(report.backend().containsValue("plain text with no extracted entity"));
        verify(vectorStoreService, never()).enqueue(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap());
        verify(brain, never()).recordChunks(anyList());
        verify(writer, never()).writeChunks(anyList());
    }

    @Test
    void chunkAndExtractFallsBackToNoEntitiesWithoutFailing() {
        BrainStateProperties props = new BrainStateProperties();
        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                text -> {
                    throw new RuntimeException("ner unavailable");
                },
                mock(Neo4jKgChunkWriter.class),
                new UniversalContextLexicon(),
                mock(VectorStoreService.class),
                mock(BrainStateService.class),
                mock(ChatMessageRepository.class));

        List<KgChunk> chunks = service.chunkAndExtract("plain text with no extractor", "GENERAL");

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).entities().isEmpty());
        assertTrue(chunks.get(0).relations().isEmpty());
    }

    @Test
    void graphDbManualLaneAddsDeterministicEntitiesWhenExtractorReturnsTooFew() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> List.of();
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<KgChunk> chunks = List.copyOf((List<KgChunk>) invocation.getArgument(0));
            int entities = chunks.stream().mapToInt(chunk -> chunk.entities().size()).sum();
            int relations = chunks.stream().mapToInt(chunk -> chunk.relations().size()).sum();
            int portMappings = chunks.stream()
                    .flatMap(chunk -> chunk.relations().stream())
                    .map(KgChunk.KgRelation::connectorHash12)
                    .filter(hash -> hash != null && !hash.isBlank())
                    .toList()
                    .size();
            return new Neo4jKgChunkWriter.WriteReport(
                    true, "written", null, chunks.size(), entities, relations, portMappings, "neo4j.local", null);
        });

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));

        GraphRagChunkingService.IngestReport report = service.ingestText(
                "manual-session",
                "GraphDB manual learning links Alpha chunk to Beta evidence.",
                "GRAPHDB_MANUAL",
                "SMOKE_GRAPHDB",
                GraphRagChunkingService.IngestOptions.graphDbManual(false, true, true, false));

        assertEquals("indexed", report.status());
        assertTrue(report.entityCount() >= 2);
        assertTrue(report.relationCount() >= 1);
        assertEquals("queued", report.backend().get("vectorStatus"));
        assertEquals("written", report.backend().get("neo4jStatus"));
        assertEquals("disabled", report.backend().get("brainStateStatus"));
        assertTrue((int) report.backend().get("neo4jPortMappingCount") >= 1);
        assertEquals(List.of("vector", "neo4j"), report.backend().get("persistenceSucceededTargets"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KgChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeChunks(chunksCaptor.capture());
        assertTrue(chunksCaptor.getValue().stream()
                .flatMap(chunk -> chunk.relations().stream())
                .allMatch(relation -> relation.connectorHash12().matches("[0-9a-f]{12}")));
        verify(brain, never()).recordChunks(anyList());
    }

    @Test
    void vectorFailureDoesNotBlockBrainStateOrNeo4jPersistence() {
        BrainStateProperties props = new BrainStateProperties();
        NamedEntityExtractor extractor = text -> List.of("Alpha", "Beta");
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        doThrow(new RuntimeException("ownerToken=fake-token raw text"))
                .when(vectorStoreService).enqueue(anyString(), anyString(), anyString(), anyMap());
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(
                false, "disabled", "disabled", 0, 0, 0, "", null));

        GraphRagChunkingService service = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                extractor,
                writer,
                new UniversalContextLexicon(),
                vectorStoreService,
                brain,
                mock(ChatMessageRepository.class));

        GraphRagChunkingService.IngestReport report = service.ingestText(
                "s1", "Alpha helps Beta near the coast", "USER", "general");

        assertEquals("indexed", report.status());
        assertEquals("failed", report.backend().get("vectorStatus"));
        assertEquals(1, report.backend().get("vectorAttemptCount"));
        assertEquals(0, report.backend().get("vectorQueuedCount"));
        assertEquals(1, report.backend().get("vectorFailureCount"));
        assertEquals("recorded", report.backend().get("brainStateStatus"));
        assertEquals("disabled", report.backend().get("neo4jStatus"));
        assertFalse(report.backend().containsValue("Alpha helps Beta near the coast"));
        verify(brain).recordChunks(anyList());
        verify(writer).writeChunks(anyList());
    }

    @Test
    void graphRagChunkingFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/graph/GraphRagChunkingService.java"));

        assertGraphChunkStage(source, "vector.enqueue");
        assertGraphChunkStage(source, "brainState.record");
        assertGraphChunkStage(source, "neo4j.write");
        assertGraphChunkStage(source, "entityExtractor.extract");
        assertGraphChunkStage(source, "inferDomain.lexicon");
        assertGraphChunkStage(source, "parseSessionId");
        assertTrue(source.contains("log.debug(\"[GraphRagChunkingService] fail-soft stage={} err={}"));
        assertFalse(source.contains("log.debug(\"[GraphRagChunkingService] fail-soft stage={} err={}\", stage, failureClass(ex));"));
        assertTrue(source.contains(
                "String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, \"unknown\");"));
        assertFalse(source.contains("catch (Exception ignore) {"));
        assertFalse(source.contains("catch (NumberFormatException ignore) {"));
        assertTrue(source.contains("catch (NumberFormatException ex) {"));
    }

    private static KgChunk testChunk(String chunkId) {
        return new KgChunk(
                chunkId,
                "s1",
                "Alpha helps Beta near the coast",
                List.of(
                        new KgChunk.KgEntity("Alpha", "ENTITY", "GENERAL", 0.8),
                        new KgChunk.KgEntity("Beta", "ENTITY", "GENERAL", 0.8)),
                List.of(new KgChunk.KgRelation("Alpha", "Beta", "RELATIONSHIP_LINKS", 0.7)),
                "GENERAL",
                0.8,
                Instant.now());
    }

    private static void assertGraphChunkStage(String source, String stage) {
        assertTrue(source.contains("traceSuppressed(\"" + stage + "\""),
                "GraphRagChunkingService fail-soft path needs stage breadcrumb: " + stage);
    }
}
