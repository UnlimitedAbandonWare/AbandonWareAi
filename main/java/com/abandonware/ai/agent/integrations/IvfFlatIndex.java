
package com.abandonware.ai.agent.integrations;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;



/**
 * Simplified IVF Flat index reader/writer.
 * Stored as floats row-major in 'vectors.f32' and meta in meta.tsv.
 */
public class IvfFlatIndex implements AnnIndex {

    private final Path dir;
    private float[][] vectors; // loaded lazily
    private AnnMeta meta;

    public IvfFlatIndex(Path dir) { this.dir = dir; }

    private void ensureLoaded() throws IOException {
        if (vectors != null) return;
        this.meta = AnnMeta.load(dir);
        Path vec = dir.resolve("vectors.f32");
        if (!Files.exists(vec)) {
            this.vectors = new float[0][];
            return;
        }
        byte[] bytes = Files.readAllBytes(vec);
        int headerBytes = Integer.BYTES * 2;
        if (bytes.length < headerBytes) {
            throw new IOException("Invalid vector index header");
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int dim = bb.getInt(); // dim
        int rows = bb.getInt(); // rows
        if (dim < 0 || rows < 0) {
            throw new IOException("Invalid vector index shape");
        }
        long expectedByteCount;
        try {
            long cellCount = Math.multiplyExact((long) rows, (long) dim);
            expectedByteCount = Math.addExact(headerBytes,
                    Math.multiplyExact(cellCount, (long) Float.BYTES));
        } catch (ArithmeticException ex) {
            throw new IOException("Invalid vector index shape", ex);
        }
        if (expectedByteCount != bytes.length) {
            throw new IOException("Invalid vector index payload length");
        }
        this.vectors = new float[rows][dim];
        for (int r = 0; r < rows; r++) {
            for (int d=0; d<dim; d++) {
                vectors[r][d] = bb.getFloat();
            }
        }
    }

    @Override
    public List<AnnHit> search(float[] query, int k, int efOrNprobe) throws IOException {
        ensureLoaded();
        TopK<Integer> top = new TopK<>(k);
        for (int i=0;i<vectors.length;i++) {
            double dist = Distance.cosine(query, vectors[i]); // lower is better
            top.add(i, -dist); // store negative so higher is better
        }
        List<TopK.Item<Integer>> items = top.toListSortedDesc();
        List<AnnHit> out = new ArrayList<>();
        for (var it : items) {
            int row = it.value;
            String id = rowIdOrNull(row);
            if (id == null || id.isBlank()) {
                continue;
            }
            double score = -it.score;
            if (!Double.isFinite(score)) {
                continue;
            }
            out.add(new AnnHit(id, score));
        }
        return out;
    }

    private String rowIdOrNull(int row) {
        if (meta == null || row < 0 || row >= meta.rowToId.size()) {
            return null;
        }
        return meta.rowToId.get(row);
    }

    // Writer used by AnnIndexer
    public static void save(Path dir, float[][] mat, AnnMeta meta) throws IOException {
        Files.createDirectories(dir);
        AnnMeta.save(dir, meta == null ? new AnnMeta() : meta);
        Path vec = dir.resolve("vectors.f32");
        int rows = mat == null ? 0 : mat.length;
        int dim = rows == 0 || mat[0] == null ? 0 : mat[0].length;
        ByteBuffer bb = ByteBuffer.allocate(8 + rows * dim * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(dim);
        bb.putInt(rows);
        for (int r=0;r<rows;r++) for (int d=0;d<dim;d++) bb.putFloat(mat[r][d]);
        Files.write(vec, bb.array());
    }
}
