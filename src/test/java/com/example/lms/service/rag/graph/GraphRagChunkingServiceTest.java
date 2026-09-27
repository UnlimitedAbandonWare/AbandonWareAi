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
    private static GraphRagChunkingService.IngestReport ingestAuthorized(GraphRagChunkingService service, String text) {
        var sessions = mock(com.example.lms.repository.ChatSessionRepository.class);
        var messages = mock(ChatMessageRepository.class);
        var session = new com.example.lms.domain.ChatSession("synthetic", "owner", "ANON");
        session.setId(7L);
        session.setMemoryProfile(com.example.lms.domain.enums.MemoryProfile.LIGHT);
        var message = new com.example.lms.domain.ChatMessage(session, "user", text);
        message.setId(11L);
        when(sessions.findByIdForUpdate(7L)).thenReturn(java.util.Optional.of(session));
        when(messages.findById(11L)).thenReturn(java.util.Optional.of(message));
        var authority = new GeneralGraphSourceAuthority(sessions, messages, new com.fasterxml.jackson.databind.ObjectMapper());
        var scope = GeneralGraphScope.authorize(session, null, "owner").orElseThrow().withPolicy(1, true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sourceAuthority", authority);
        return service.ingestSource(scope, authority.source(scope, 11L).orElseThrow());
    }


    @Test
    void verifiedShortCorrectionIsIndexedWithoutInventedEntities() {
        var vector = mock(VectorStoreService.class);
        var writer = mock(Neo4jKgChunkWriter.class);
        var brain = mock(BrainStateService.class);
        var service = new GraphRagChunkingService(new BrainStateProperties(),
                new DocumentChunkingService(), text -> List.of(), writer,
                new UniversalContextLexicon(), vector, brain, mock(ChatMessageRepository.class));
        var report = ingestAuthorized(service, "그 조건은 취소했어.");
        assertEquals(1, report.chunkCount());
        assertEquals(0, report.entityCount());
        assertEquals(0, report.relationCount());
        assertEquals("source_verified", report.backend().get("meaningfulGate"));
        verify(vector).enqueue(anyString(), org.mockito.ArgumentMatchers.eq("7"),
                org.mockito.ArgumentMatchers.contains("그 조건은 취소했어."), anyMap());
        verify(brain).recordChunks(anyList());
    }

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

        GraphRagChunkingService.IngestReport report = ingestAuthorized(service, "Alpha helps Beta near the coast");

        assertTrue(report.enabled());
        assertEquals(BrainStateText.hash12("7"), report.sessionId());
        assertEquals(1, report.chunkCount());
        assertEquals(2, report.entityCount());
        assertEquals(1, report.relationCount());
        assertEquals("source_verified", report.backend().get("meaningfulGate"));
        assertEquals(1, report.backend().get("persistedChunkCount"));
        assertEquals(0, report.backend().get("skippedLowSignalChunks"));
        assertEquals("disabled", report.backend().get("neo4jStatus"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(
                org.mockito.ArgumentMatchers.matches("[a-f0-9]{64}"),
                org.mockito.ArgumentMatchers.eq("7"),
                org.mockito.ArgumentMatchers.contains("Alpha"),
                metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertEquals("BRAIN_STATE", meta.get("doc_type"));
        assertEquals("USER", meta.get("source_tag"));
        assertEquals("true", meta.get("general_graph_private"));
        assertEquals("chat-message:11", meta.get("general_graph_source_id"));
        assertEquals(1L, meta.get("general_graph_consent_epoch"));
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

        GraphRagChunkingService.IngestReport report = ingestAuthorized(service, "Alpha helps Beta near the coast");

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
    void unscopedConversationCannotSeedGlobalAnchorFrequency() {
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

        assertEquals("source_authority_missing", report.disabledReason());
        assertTrue(anchorFrequencyIndex.entities("GENERAL").isEmpty());
        assertFalse(slice.applied());
        org.mockito.Mockito.verifyNoInteractions(vectorStoreService, writer, brain);
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

        assertEquals("source_authority_missing", report.disabledReason());
        assertTrue(anchorFrequencyIndex.entities("GENERAL").isEmpty());
        org.mockito.Mockito.verifyNoInteractions(vectorStoreService, writer, brain);
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
    void unscopedUawThumbnailIsRefused() {
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

        var report = service.ingestText("thumb", "Alpha and Beta thumbnail", "UAW_THUMBNAIL", "UAW_THUMB");

        assertEquals("source_authority_missing", report.disabledReason());
        org.mockito.Mockito.verifyNoInteractions(vectorStoreService, writer, brain);
    }

    @Test
    void unscopedPreparedThumbnailIsRefused() {
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

        assertEquals("disabled", report.status());
        assertEquals("source_authority_missing", report.disabledReason());
        org.mockito.Mockito.verifyNoInteractions(vectorStoreService, writer, brain);
    }

    @Test
    void unscopedKnowledgeDeltaIsRefused() {
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

        var report = service.ingestText("__KNOWLEDGE_DELTA__", "GraphRAG USES Neo4j", "KNOWLEDGE_DELTA", "GENERAL");

        assertEquals("source_authority_missing", report.disabledReason());
        org.mockito.Mockito.verifyNoInteractions(vectorStoreService, writer, brain);
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

        GraphRagChunkingService.IngestReport report = ingestAuthorized(service, "Alpha helps Beta near the coast");

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
