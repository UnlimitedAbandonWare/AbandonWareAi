package com.abandonware.ai.vector.qdrant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "qdrant", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QdrantVectorStoreAdapter {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreAdapter.class);

    private final QdrantClient client;

    public QdrantVectorStoreAdapter(QdrantClient client) {
        this.client = client;
        try {
            this.client.ensureCollection();
        } catch (Exception e) {
            logFailSoft("ensureCollection", e);
        }
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isDebugEnabled()) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.debug("[AWX][vector][qdrant-adapter] failSoft stage={} errorType={}", stage, errorType);
        }
    }

    public void upsert(List<float[]> vectors, List<String> ids, List<Map<String,Object>> payloads) {
        client.upsert(vectors, ids, payloads);
    }

    public List<Map<String,Object>> search(float[] query, int topK, Map<String,Object> filter) {
        return client.search(query, topK, filter);
    }
}
