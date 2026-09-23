package com.abandonware.ai.agent.integrations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25IndexTest {

    @TempDir
    Path tempDir;

    @Test
    void remembersCandidateFileCountInsteadOfChunkCount() throws Exception {
        Path docs = Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(docs.resolve("multi.md"), "# One\nalpha\n# Two\nbeta\n# Three\ngamma\n");
        Bm25Index index = new Bm25Index(tempDir);

        index.ensureBuilt();

        Field state = Bm25Index.class.getDeclaredField("state");
        state.setAccessible(true);
        Object firstBuild = state.get(index);
        index.ensureBuilt();
        assertSame(firstBuild, state.get(index));
        assertTrue(index.size() > 1);
    }

    @Test
    void rebuildsWhenCandidateContentChangesWithoutChangingFileCount() throws Exception {
        Path docs = Files.createDirectories(tempDir.resolve("docs"));
        Path candidate = docs.resolve("mutable.md");
        Files.writeString(candidate, "# Evidence\nstalealphaevidence\n");
        Bm25Index index = new Bm25Index(tempDir);

        index.ensureBuilt();
        assertTrue(index.search("stalealphaevidence", null, 4).size() > 0);

        Files.writeString(candidate, "# Evidence\nfreshbetaevidence with expanded content\n");
        Files.setLastModifiedTime(candidate, FileTime.fromMillis(System.currentTimeMillis() + 2_000L));
        index.ensureBuilt();

        assertTrue(index.search("freshbetaevidence", null, 4).size() > 0);
    }

    @Test
    void failedRebuildRetainsPriorSearchableState() throws Exception {
        Path docs = Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(docs.resolve("stable.md"), "# Stable\nprevioussearchableevidence\n");
        Bm25Index index = new Bm25Index(tempDir);

        index.ensureBuilt();
        int previousSize = index.size();
        assertTrue(index.search("previoussearchableevidence", null, 4).size() > 0);

        Files.write(docs.resolve("a-malformed.md"), new byte[]{(byte) 0xC3, (byte) 0x28});

        assertThrows(IOException.class, index::ensureBuilt);
        assertEquals(previousSize, index.size());
        assertTrue(index.search("previoussearchableevidence", null, 4).size() > 0);
    }

    @Test
    void searchResultRetainsExactChunkAcrossLaterRebuild() throws Exception {
        Path docs = Files.createDirectories(tempDir.resolve("docs"));
        Path candidate = docs.resolve("mutable.md");
        Files.writeString(candidate, "# Before\nsnapshotboundevidence\n");
        Bm25Index index = new Bm25Index(tempDir);

        index.ensureBuilt();
        Bm25Index.SearchResult result = index.search("snapshotboundevidence", null, 4).get(0);
        Bm25Index.Chunk originalChunk = index.getChunk(result.docId);

        Files.writeString(candidate, "# After\nreplacement evidence with different length\n");
        Files.setLastModifiedTime(candidate, FileTime.fromMillis(System.currentTimeMillis() + 2_000L));
        index.ensureBuilt();

        assertSame(originalChunk, result.chunk);
        assertEquals("# Before\nsnapshotboundevidence", result.chunk.body);
    }
}
