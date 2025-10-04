
package com.abandonware.ai.agent.integrations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * HNSW placeholder delegating to IVF flat fallback.
 */
public class HnswIndex implements AnnIndex {
    private final IvfFlatIndex fallback;
    public HnswIndex(Path dir) { this.fallback = new IvfFlatIndex(dir); }

    @Override
    public List<AnnHit> search(float[] query, int k, int efOrNprobe) throws IOException {
        // For simplicity, reuse IVF Flat reader; real HNSW not implemented.
        return fallback.search(query, k, efOrNprobe);
    }
}
