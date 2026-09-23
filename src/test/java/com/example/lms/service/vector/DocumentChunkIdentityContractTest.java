package com.example.lms.service.vector;

import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DocumentChunkIdentityContractTest {
    @AfterEach
    void cleanup() {
        TraceStore.clear();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void invalidParentIdentifiersCannotCollideBetweenDifferentDocuments(String invalidId) {
        DocumentChunkingService service = service();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(VectorMetaKeys.META_DOC_ID, invalidId);
        metadata.put(VectorMetaKeys.META_ORIGINAL_ID, invalidId);

        var first = service.split("A".repeat(300), metadata);
        var second = service.split("B".repeat(300), metadata);

        assertTrue(first.size() > 1);
        assertTrue(second.size() > 1);
        assertTrue(java.util.Collections.disjoint(ids(first), ids(second)),
                "Distinct documents must not overwrite each other's chunk IDs");
        assertEquals(ids(first), ids(service.split("A".repeat(300), metadata)),
                "Repeated ingestion must retain deterministic IDs");
        assertEquals(invalidId, metadata.get(VectorMetaKeys.META_DOC_ID),
                "Chunking must preserve the caller's metadata map");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void invalidDocumentIdUsesExistingNonblankOriginalIdWithoutRewritingIt(String invalidId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(VectorMetaKeys.META_DOC_ID, invalidId);
        metadata.put(VectorMetaKeys.META_ORIGINAL_ID, " original-id ");

        var chunks = service().split("A".repeat(300), metadata);

        assertEquals(" original-id #0", chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_ID));
        assertEquals(" original-id ", chunks.get(0).metadata().get(VectorMetaKeys.META_PARENT_DOC_ID));
    }

    @Test
    void nonblankDocumentIdRetainsPrecedenceAndExactValue() {
        var chunks = service().split("A".repeat(300), Map.of(
                VectorMetaKeys.META_DOC_ID, " stable-doc ",
                VectorMetaKeys.META_ORIGINAL_ID, "other-id"));

        assertEquals(" stable-doc #0", chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_ID));
    }

    @Test
    void absentIdentifiersAlreadyUseStableDistinctFallback() {
        var service = service();
        var first = service.split("A".repeat(300), Map.of());
        var second = service.split("B".repeat(300), Map.of());

        assertTrue(java.util.Collections.disjoint(ids(first), ids(second)));
        assertEquals(ids(first), ids(service.split("A".repeat(300), Map.of())));
    }

    private static Set<Object> ids(List<DocumentChunkingService.Chunk> chunks) {
        return chunks.stream().map(chunk -> chunk.metadata().get(VectorMetaKeys.META_CHUNK_ID))
                .collect(Collectors.toSet());
    }

    private static DocumentChunkingService service() {
        var service = new DocumentChunkingService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "chunkSizeChars", 128);
        ReflectionTestUtils.setField(service, "overlapChars", 12);
        return service;
    }
}
