package com.abandonware.ai.vector.qdrant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "qdrant", name = "enabled", havingValue = "true", matchIfMissing = true)
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.vector.qdrant.QdrantVectorStoreAdapter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.vector.qdrant.QdrantVectorStoreAdapter
role: config
*/
public class QdrantVectorStoreAdapter {

    private final QdrantClient client;

    public QdrantVectorStoreAdapter(QdrantClient client) {
        this.client = client;
        try { this.client.ensureCollection(); } catch (Exception ignore) {}
    }

    public void upsert(List<float[]> vectors, List<String> ids, List<Map<String,Object>> payloads) {
        client.upsert(vectors, ids, payloads);
    }

    public List<Map<String,Object>> search(float[] query, int topK, Map<String,Object> filter) {
        return client.search(query, topK, filter);
    }
}