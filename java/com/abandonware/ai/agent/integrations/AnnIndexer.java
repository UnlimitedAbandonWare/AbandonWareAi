
package com.abandonware.ai.agent.integrations;

import java.io.*;
import java.nio.file.*;
import java.util.*;



/**
 * Command-line indexer building a trivial IVF-Flat file from local repo chunks.
 */
public class AnnIndexer {

    public static void main(String[] args) throws Exception {
        Path repo = Paths.get(".").toAbsolutePath().normalize();
        String kind = System.getenv().getOrDefault("ANN_KIND", "ivf");
        int chunkChars = Integer.parseInt(System.getenv().getOrDefault("ANN_CHUNK_CHARS", "700"));
        Path outDir = Paths.get(System.getenv().getOrDefault("ANN_INDEX_DIR", "./data/ann_index")).toAbsolutePath();

        System.out.println("[AnnIndexer] building " + kind + " into " + outDir);
        Bm25Index idx = new Bm25Index(repo);
        idx.ensureBuilt();

        Embedder embedder = selectEmbedder();
        int n = idx.size();
        List<float[]> vecs = new ArrayList<>();
        AnnMeta meta = new AnnMeta();
        for (int i=0;i<n;i++) {
            Bm25Index.Chunk c = idx.getChunk(i);
            float[] v = embedder.embed(c.title + "\n" + c.body);
            vecs.add(v);
            meta.idToRow.put(c.id, i);
            meta.rowToId.add(c.id);
        }
        float[][] mat = vecs.toArray(new float[0][]);
        if ("ivf".equals(kind)) {
            IvfFlatIndex.save(outDir, mat, meta);
        } else {
            // reuse ivf format for simplicity
            IvfFlatIndex.save(outDir, mat, meta);
        }
        System.out.println("[AnnIndexer] done.");
    }

    static Embedder selectEmbedder() {
        String backend = System.getenv().getOrDefault("EMBED_BACKEND", "heuristic");
        if ("remote".equalsIgnoreCase(backend)) return new RemoteEmbedder();
        return new HeuristicEmbedder();
    }
}