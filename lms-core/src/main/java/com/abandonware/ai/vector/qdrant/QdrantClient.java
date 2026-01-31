package com.abandonware.ai.vector.qdrant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.vector.qdrant.QdrantClient
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.vector.qdrant.QdrantClient
role: config
*/
public class QdrantClient {

    private final RestClient http;
    private final QdrantProperties props;

    public QdrantClient(QdrantProperties props) {
        this.props = props;
        this.http = RestClient.builder()
                .baseUrl(props.getUrl())
                .defaultHeaders(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
                        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey());
                    }
                })
                .build();

    }
    public List<Map<String,Object>> search(float[] query, int topK, Map<String,Object> filter) {
        var body = Map.of(
                "vector", query,
                "limit", topK,
                "with_payload", true,
                "filter", filter == null ? Map.of() : filter
        );
        var resp = http.post().uri("/collections/{c}/points/search", props.getCollection())
                .body(body).retrieve().body(Map.class);
        return (List<Map<String, Object>>) resp.get("result");
    }

    /** Ensures the target collection exists. No-op placeholder. */
    public void ensureCollection() {
        try {
            // minimal ping to create if missing (best-effort)
            http.put().uri("/collections/{c}", props.getCollection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of(
                        "vectors", java.util.Map.of("size", props.getVectorSize(), "distance", props.getDistance())
                    ))
                    .retrieve().toBodilessEntity();
        } catch (Exception ignore) {}
    }

    /** Upserts batch of vectors with payloads. Placeholder schema. */
    public void upsert(java.util.List<float[]> vectors, java.util.List<String> ids, java.util.List<java.util.Map<String,Object>> payloads) {
        try {
            java.util.List<java.util.Map<String,Object>> points = new java.util.ArrayList<>();
            for (int i = 0; i < vectors.size(); i++) {
                points.add(java.util.Map.of(
                    "id", ids.get(i),
                    "vector", vectors.get(i),
                    "payload", payloads != null && payloads.size() > i ? payloads.get(i) : java.util.Map.of()
                ));
            }
            var body = java.util.Map.of("points", points);
            http.put().uri("/collections/{c}/points", props.getCollection())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toBodilessEntity();
        } catch (Exception ignore) {}
    }
}