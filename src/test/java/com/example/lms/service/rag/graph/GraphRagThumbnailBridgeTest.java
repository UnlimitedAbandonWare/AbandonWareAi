package com.example.lms.service.rag.graph;

import com.example.lms.search.TraceStore;
import com.example.lms.uaw.thumbnail.UawThumbnailPersistedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GraphRagThumbnailBridgeTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void thumbnailEventsUseTheConfiguredApplicationExecutor() {
        GraphRagChunkingService chunkingService = mock(GraphRagChunkingService.class);
        AtomicInteger submissions = new AtomicInteger();
        Executor directExecutor = command -> {
            submissions.incrementAndGet();
            command.run();
        };

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GraphRagChunkingService.class, () -> chunkingService);
            context.registerBean("applicationTaskExecutor", Executor.class, () -> directExecutor);
            context.registerBean(GraphRagThumbnailBridge.class);
            context.refresh();

            context.getBean(GraphRagThumbnailBridge.class).captureThumbnail(relationEvent());

            verify(chunkingService, timeout(1_000)).ingestPreparedChunks(
                    org.mockito.ArgumentMatchers.eq("__UAW_THUMBNAIL__"),
                    org.mockito.ArgumentMatchers.eq("UAW_THUMBNAIL"),
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.anyString());
            assertEquals(1, submissions.get());
        }
    }

    @Test
    void thumbnailSubmissionRejectionFailsSoftWithoutRunningIngest() {
        GraphRagChunkingService chunkingService = mock(GraphRagChunkingService.class);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("synthetic rejection");
        };

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GraphRagChunkingService.class, () -> chunkingService);
            context.registerBean("applicationTaskExecutor", Executor.class, () -> rejectingExecutor);
            context.registerBean(GraphRagThumbnailBridge.class);
            context.refresh();

            assertDoesNotThrow(() -> context.getBean(GraphRagThumbnailBridge.class)
                    .captureThumbnail(relationEvent()));

            assertEquals(1L, TraceStore.get("uaw.thumbnail.relationThumbnail.submit.rejectedCount"));
            verifyNoInteractions(chunkingService);
        }
    }

    @Test
    void thumbnailEventIndexesRelationBreadcrumbsWithoutRawGraphText() {
        GraphRagChunkingService chunkingService = mock(GraphRagChunkingService.class);
        GraphRagThumbnailBridge bridge = new GraphRagThumbnailBridge(chunkingService);
        UawThumbnailPersistedEvent event = new UawThumbnailPersistedEvent(
                "UAW_thumbnail.v1",
                "UAW_THUMB",
                "THUMBNAIL",
                "Alpha and Beta are related in the coastal route context.",
                List.of("Alpha", "Beta", "coastal route"),
                0.8d);

        bridge.ingestNow(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KgChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(chunkingService).ingestPreparedChunks(
                org.mockito.ArgumentMatchers.eq("__UAW_THUMBNAIL__"),
                org.mockito.ArgumentMatchers.eq("UAW_THUMBNAIL"),
                chunks.capture(),
                org.mockito.ArgumentMatchers.eq(BrainStateText.hash12(event.graphText())));
        verify(chunkingService, never()).ingestText(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        assertEquals(3, chunks.getValue().size());
        assertTrue(chunks.getValue().stream()
                .allMatch(chunk -> chunk.sourceText().startsWith("relationBreadcrumbs:")
                        && chunk.sourceText().contains("relationSummary:")
                        && !chunk.sourceText().contains("context=")
                        && !chunk.sourceText().contains(event.graphText())
                        && chunk.entities().size() == 2
                        && chunk.relations().size() == 1));
        assertTrue(chunks.getValue().stream()
                .flatMap(chunk -> chunk.relations().stream())
                .allMatch(relation -> "UAW_THUMBNAIL_RELATED_TO".equals(relation.kind())
                        && relation.connectorHash12().matches("[0-9a-f]{12}")));
        assertTrue(chunks.getValue().stream()
                .map(KgChunk::chunkId)
                .allMatch(id -> id.startsWith("uaw-thumb-rel:") && id.length() > "uaw-thumb-rel:".length()));
    }

    @Test
    void thumbnailEventCapsCoreAnchorsAndRelationPairs() {
        TraceStore.clear();
        GraphRagChunkingService chunkingService = mock(GraphRagChunkingService.class);
        GraphRagThumbnailBridge bridge = new GraphRagThumbnailBridge(chunkingService);
        UawThumbnailPersistedEvent event = new UawThumbnailPersistedEvent(
                "UAW_thumbnail.v1",
                "UAW_THUMB",
                "THUMBNAIL",
                "High fan-out thumbnail should be sliced to core relation anchors only.",
                List.of(
                        "Anchor01", "Anchor02", "Anchor03", "Anchor04",
                        "Anchor05", "Anchor06", "Anchor07", "Anchor08",
                        "Anchor09", "Anchor10", "Anchor11", "Anchor12"),
                0.8d);

        bridge.ingestNow(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KgChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(chunkingService).ingestPreparedChunks(
                org.mockito.ArgumentMatchers.eq("__UAW_THUMBNAIL__"),
                org.mockito.ArgumentMatchers.eq("UAW_THUMBNAIL"),
                chunks.capture(),
                org.mockito.ArgumentMatchers.eq(BrainStateText.hash12(event.graphText())));
        java.util.Set<String> coreAnchors = java.util.Set.of(
                "Anchor01", "Anchor02", "Anchor03", "Anchor04",
                "Anchor05", "Anchor06", "Anchor07", "Anchor08");

        assertEquals(16, chunks.getValue().size());
        assertTrue(chunks.getValue().stream()
                .flatMap(chunk -> chunk.relations().stream())
                .allMatch(relation -> coreAnchors.contains(relation.source())
                        && coreAnchors.contains(relation.target())));
        assertEquals(12, TraceStore.get("uaw.thumbnail.relationThumbnail.inputAnchorCount"));
        assertEquals(8, TraceStore.get("uaw.thumbnail.relationThumbnail.selectedAnchorCount"));
        assertEquals(8, TraceStore.get("uaw.thumbnail.relationThumbnail.anchorBudget"));
        assertEquals(16, TraceStore.get("uaw.thumbnail.relationThumbnail.pairBudget"));
        assertEquals(16, TraceStore.get("uaw.thumbnail.relationThumbnail.emittedPairCount"));
        assertEquals(true, TraceStore.get("uaw.thumbnail.relationThumbnail.sliced"));
        assertTrue(TraceStore.getAll().keySet().stream()
                .allMatch(key -> key.startsWith("uaw.thumbnail.relationThumbnail")));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains("Anchor09"));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains(event.graphText()));
        TraceStore.clear();
    }

    @Test
    void thumbnailEventPrioritizesCaptionAnchorsBeforePairSlicing() {
        TraceStore.clear();
        GraphRagChunkingService chunkingService = mock(GraphRagChunkingService.class);
        GraphRagThumbnailBridge bridge = new GraphRagThumbnailBridge(chunkingService);
        UawThumbnailPersistedEvent event = new UawThumbnailPersistedEvent(
                "UAW_thumbnail.v1",
                "UAW_THUMB",
                "THUMBNAIL",
                "Alpha and Gamma are the core relationship anchors for this thumbnail.",
                List.of(
                        "Anchor01", "Anchor02", "Anchor03", "Anchor04",
                        "Anchor05", "Anchor06", "Anchor07", "Anchor08",
                        "Alpha", "Gamma", "Anchor09", "Anchor10"),
                0.8d);

        bridge.ingestNow(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KgChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(chunkingService).ingestPreparedChunks(
                org.mockito.ArgumentMatchers.eq("__UAW_THUMBNAIL__"),
                org.mockito.ArgumentMatchers.eq("UAW_THUMBNAIL"),
                chunks.capture(),
                org.mockito.ArgumentMatchers.eq(BrainStateText.hash12(event.graphText())));
        java.util.Set<String> selectedAnchors = chunks.getValue().stream()
                .flatMap(chunk -> chunk.relations().stream())
                .flatMap(relation -> java.util.stream.Stream.of(relation.source(), relation.target()))
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(selectedAnchors.contains("Alpha"));
        assertTrue(selectedAnchors.contains("Gamma"));
        assertTrue(chunks.getValue().stream()
                .flatMap(chunk -> chunk.relations().stream())
                .anyMatch(relation -> "Alpha".equals(relation.source()) && "Gamma".equals(relation.target())));
        assertTrue(!selectedAnchors.contains("Anchor07"));
        assertTrue(!selectedAnchors.contains("Anchor08"));
        assertEquals(12, TraceStore.get("uaw.thumbnail.relationThumbnail.inputAnchorCount"));
        assertEquals(8, TraceStore.get("uaw.thumbnail.relationThumbnail.selectedAnchorCount"));
        assertEquals(16, TraceStore.get("uaw.thumbnail.relationThumbnail.emittedPairCount"));
        assertEquals(true, TraceStore.get("uaw.thumbnail.relationThumbnail.sliced"));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains("Alpha and Gamma are the core"));
        TraceStore.clear();
    }

    @Test
    void thumbnailEventDoesNotPrioritizeAnchorFromCaptionSubstring() {
        TraceStore.clear();
        GraphRagChunkingService chunkingService = mock(GraphRagChunkingService.class);
        GraphRagThumbnailBridge bridge = new GraphRagThumbnailBridge(chunkingService);
        UawThumbnailPersistedEvent event = new UawThumbnailPersistedEvent(
                "UAW_thumbnail.v1",
                "UAW_THUMB",
                "THUMBNAIL",
                "Alphabet soup mentions Gamma as the only explicit core anchor.",
                List.of(
                        "Anchor01", "Anchor02", "Anchor03", "Anchor04",
                        "Anchor05", "Anchor06", "Anchor07", "Anchor08",
                        "Alpha", "Gamma", "Anchor09", "Anchor10"),
                0.8d);

        bridge.ingestNow(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KgChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(chunkingService).ingestPreparedChunks(
                org.mockito.ArgumentMatchers.eq("__UAW_THUMBNAIL__"),
                org.mockito.ArgumentMatchers.eq("UAW_THUMBNAIL"),
                chunks.capture(),
                org.mockito.ArgumentMatchers.eq(BrainStateText.hash12(event.graphText())));
        java.util.Set<String> selectedAnchors = chunks.getValue().stream()
                .flatMap(chunk -> chunk.relations().stream())
                .flatMap(relation -> java.util.stream.Stream.of(relation.source(), relation.target()))
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(selectedAnchors.contains("Gamma"));
        assertTrue(selectedAnchors.contains("Anchor07"));
        assertTrue(!selectedAnchors.contains("Alpha"));
        assertEquals(12, TraceStore.get("uaw.thumbnail.relationThumbnail.inputAnchorCount"));
        assertEquals(8, TraceStore.get("uaw.thumbnail.relationThumbnail.selectedAnchorCount"));
        assertEquals(16, TraceStore.get("uaw.thumbnail.relationThumbnail.emittedPairCount"));
        assertEquals(true, TraceStore.get("uaw.thumbnail.relationThumbnail.sliced"));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains("Alphabet soup"));
        TraceStore.clear();
    }

    @Test
    void thumbnailIngestFailureLeavesTraceBreadcrumbWithoutRawCaptionOrException() {
        TraceStore.clear();
        GraphRagChunkingService chunkingService = mock(GraphRagChunkingService.class);
        doThrow(new IllegalStateException("ownerToken=raw-thumbnail-failure"))
                .when(chunkingService).ingestPreparedChunks(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyString());
        GraphRagThumbnailBridge bridge = new GraphRagThumbnailBridge(chunkingService);
        UawThumbnailPersistedEvent event = new UawThumbnailPersistedEvent(
                "UAW_thumbnail.v1",
                "UAW_THUMB",
                "THUMBNAIL",
                "private thumbnail ownerToken=caption-secret",
                List.of("Alpha", "Beta"),
                0.8d);

        bridge.ingestNow(event);

        assertEquals(Boolean.TRUE, TraceStore.get("uaw.thumbnail.relationThumbnail.ingest.failed"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.thumbnail.relationThumbnail.ingest.failureClass"));
        assertEquals("skip_thumbnail_ingest", TraceStore.get("uaw.thumbnail.relationThumbnail.ingest.fallback"));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains("raw-thumbnail-failure"));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains("caption-secret"));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        TraceStore.clear();
    }

    private static UawThumbnailPersistedEvent relationEvent() {
        return new UawThumbnailPersistedEvent(
                "UAW_thumbnail.v1",
                "UAW_THUMB",
                "THUMBNAIL",
                "Synthetic Alpha and Beta relation.",
                List.of("Alpha", "Beta"),
                0.8d);
    }
}
