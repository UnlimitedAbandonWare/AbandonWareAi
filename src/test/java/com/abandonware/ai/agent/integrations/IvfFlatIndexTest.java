package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IvfFlatIndexTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void emptyIndexCanBeSavedAndSearchedAsEmpty() throws Exception {
        IvfFlatIndex.save(tempDir, new float[0][], new AnnMeta());

        IvfFlatIndex index = new IvfFlatIndex(tempDir);

        assertTrue(index.search(new float[] { 1.0f, 0.0f }, 5, 64).isEmpty());
    }

    @Test
    void searchSkipsRowsMissingMetadataInsteadOfThrowing() throws Exception {
        IvfFlatIndex.save(tempDir, new float[][]{{1.0f, 0.0f}}, new AnnMeta());
        IvfFlatIndex index = new IvfFlatIndex(tempDir);

        List<AnnIndex.AnnHit> hits = assertDoesNotThrow(
                () -> index.search(new float[]{1.0f, 0.0f}, 5, 64));

        assertTrue(hits.isEmpty());
    }

    @Test
    void searchSkipsMalformedMetadataRowsInsteadOfThrowing() throws Exception {
        IvfFlatIndex.save(tempDir, new float[][]{{1.0f, 0.0f}}, new AnnMeta());
        Files.writeString(tempDir.resolve("meta.tsv"), "doc-a\tbad-row\n");
        IvfFlatIndex index = new IvfFlatIndex(tempDir);

        List<AnnIndex.AnnHit> hits = assertDoesNotThrow(
                () -> index.search(new float[]{1.0f, 0.0f}, 5, 64));

        assertTrue(hits.isEmpty());
        assertTrue(Boolean.TRUE.equals(TraceStore.get("agent.annMeta.malformedRow")));
        assertTrue(String.valueOf(TraceStore.get("agent.annMeta.malformedRow.idHash")).startsWith("hash:"));
    }

    @Test
    void searchReturnsFiniteScoreWhenStoredVectorContainsNonFiniteValues() throws Exception {
        AnnMeta meta = new AnnMeta();
        meta.rowToId.add("doc-a");
        meta.idToRow.put("doc-a", 0);
        IvfFlatIndex.save(tempDir, new float[][]{{Float.NaN, 0.0f}}, meta);
        IvfFlatIndex index = new IvfFlatIndex(tempDir);

        List<AnnIndex.AnnHit> hits = index.search(new float[]{1.0f, 0.0f}, 5, 64);

        assertFalse(hits.isEmpty());
        assertTrue(Double.isFinite(hits.get(0).score()));
    }

    @Test
    void searchRejectsNegativeVectorDimensionAsIOException() throws Exception {
        IvfFlatIndex.save(tempDir, new float[0][], new AnnMeta());
        byte[] malformedHeader = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(-1)
                .putInt(1)
                .array();
        Files.write(tempDir.resolve("vectors.f32"), malformedHeader);

        IvfFlatIndex index = new IvfFlatIndex(tempDir);

        assertThrows(IOException.class,
                () -> index.search(new float[]{1.0f}, 1, 1));
    }

    @Test
    void searchRejectsTruncatedVectorHeaderAsIOException() throws Exception {
        IvfFlatIndex.save(tempDir, new float[0][], new AnnMeta());
        Files.write(tempDir.resolve("vectors.f32"), new byte[]{1, 2, 3, 4});

        IvfFlatIndex index = new IvfFlatIndex(tempDir);

        assertThrows(IOException.class,
                () -> index.search(new float[]{1.0f}, 1, 1));
    }

    @Test
    void searchRejectsPayloadShorterThanDeclaredShapeAsIOException() throws Exception {
        IvfFlatIndex.save(tempDir, new float[0][], new AnnMeta());
        byte[] headerWithoutPayload = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(2)
                .putInt(1)
                .array();
        Files.write(tempDir.resolve("vectors.f32"), headerWithoutPayload);

        IvfFlatIndex index = new IvfFlatIndex(tempDir);

        assertThrows(IOException.class,
                () -> index.search(new float[]{1.0f, 0.0f}, 1, 1));
    }
}
