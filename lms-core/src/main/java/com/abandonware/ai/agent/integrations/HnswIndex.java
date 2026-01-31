
package com.abandonware.ai.agent.integrations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.HnswIndex
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.HnswIndex
role: config
*/
public class HnswIndex implements AnnIndex {
    private final IvfFlatIndex fallback;
    public HnswIndex(Path dir) { this.fallback = new IvfFlatIndex(dir); }

    @Override
    public List<AnnHit> search(float[] query, int k, int efOrNprobe) throws IOException {
        // For simplicity, reuse IVF Flat reader; real HNSW disabled in OSS build.
        return fallback.search(query, k, efOrNprobe);
    }
}