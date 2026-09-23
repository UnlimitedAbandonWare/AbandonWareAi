package com.abandonware.ai.agent.integrations.index;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class Bm25LocalIndexTraceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidTimestampKeepsDocumentAndLeavesTypeOnlyTrace() throws Exception {
        Path tsv = tempDir.resolve("index.tsv");
        Files.writeString(tsv, "doc1\tTitle\tneedle text\thttps://example.test\traw private ts\n");
        Bm25LocalIndex index = new Bm25LocalIndex();

        index.loadFromTsv(tsv);

        Bm25LocalIndex.Doc doc = index.search("needle", 1).get(0).getKey();
        assertEquals(0L, doc.ts);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.bm25.tsv.suppressed"));
        assertEquals("timestamp.parseFallback", TraceStore.get("agent.bm25.tsv.suppressed.stage"));
        assertEquals(1L, TraceStore.get("agent.bm25.tsv.timestamp.parseFallback.count"));
        assertEquals("invalid_number", TraceStore.get("agent.bm25.tsv.timestamp.parseFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private ts"));
    }

    @Test
    void unreadableTsvPathLeavesTypeOnlyTrace() {
        Bm25LocalIndex index = new Bm25LocalIndex();

        index.loadFromTsv(tempDir);

        assertEquals(Boolean.TRUE, TraceStore.get("agent.bm25.tsv.suppressed"));
        assertEquals("loadFallback", TraceStore.get("agent.bm25.tsv.suppressed.stage"));
        assertEquals(1L, TraceStore.get("agent.bm25.tsv.loadFallback.count"));
        assertEquals("IOException", TraceStore.get("agent.bm25.tsv.loadFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(tempDir.toString()));
    }
}
