
package com.abandonware.ai.agent.integrations;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.IvfFlatIndex
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.IvfFlatIndex
role: config
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
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int dim = bb.getInt(); // dim
        int rows = bb.getInt(); // rows
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
            String id = meta.rowToId.get(row);
            out.add(new AnnHit(id, -it.score));
        }
        return out;
    }

    // Writer used by AnnIndexer
    public static void save(Path dir, float[][] mat, AnnMeta meta) throws IOException {
        Files.createDirectories(dir);
        AnnMeta.save(dir, meta);
        Path vec = dir.resolve("vectors.f32");
        ByteBuffer bb = ByteBuffer.allocate(8 + mat.length * mat[0].length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(mat[0].length);
        bb.putInt(mat.length);
        for (int r=0;r<mat.length;r++) for (int d=0;d<mat[0].length;d++) bb.putFloat(mat[r][d]);
        Files.write(vec, bb.array());
    }
}