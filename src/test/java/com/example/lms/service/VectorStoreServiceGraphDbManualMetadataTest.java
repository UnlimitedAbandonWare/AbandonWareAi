package com.example.lms.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorStoreServiceGraphDbManualMetadataTest {

    @Test
    void graphDbManualMetadataDoesNotProjectRawTextIntoSummaryOrKeywords() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0, List.class);
            return Response.from(segments.stream()
                    .map(ignored -> Embedding.from(new float[]{1.0f}))
                    .toList());
        });
        VectorStoreService service = new VectorStoreService(model, store);
        setInt(service, "batchSize", 1000);
        setBoolean(service, "shadowWriteEnabled", false);

        String rawText = "Alpha private graphdb manual payload with Beta owner context "
                + "that must stay out of vector metadata summary and keywords. ".repeat(3);
        String vectorSid = "graphdb-manual:abc123def456";
        service.enqueue(
                "graphdb-chunk:abc123",
                vectorSid,
                rawText,
                Map.of(
                        VectorMetaKeys.META_DOC_TYPE, "GRAPHDB_MANUAL_LEARNING",
                        VectorMetaKeys.META_SOURCE_TAG, "GRAPHDB_MANUAL",
                        VectorMetaKeys.META_ORIGIN, "MANUAL_GRAPHDB",
                        "ingest_lane", "graphdb_manual_learning",
                        "nonfinite_score", Double.NaN,
                        "graphdb_manual_session_hash", "abc123def456",
                        "raw_session_id_included", "false"));

        service.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).addAll(anyList(), anyList(), segmentsCaptor.capture());
        TextSegment segment = segmentsCaptor.getValue().get(0);
        Map<String, Object> metadata = segment.metadata().toMap();
        String metadataText = metadata.toString();

        assertEquals(rawText, segment.text());
        assertEquals(vectorSid, metadata.get(VectorMetaKeys.META_SID));
        assertEquals("graphdb_manual_learning", metadata.get("ingest_lane"));
        assertEquals("graphdb_manual_payload_boundary", metadata.get("summary_redacted"));
        assertEquals("graphdb_manual_payload_boundary", metadata.get("keywords_redacted"));
        assertEquals("false", metadata.get("raw_text_metadata_included"));
        assertFalse(metadata.containsKey("nonfinite_score"));
        assertFalse(metadata.containsKey("summary"));
        assertFalse(metadata.containsKey("keywords"));
        assertFalse(metadataText.contains("Alpha private graphdb manual payload"));
        assertFalse(metadataText.contains("Beta owner context"));
    }

    private static void setInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
