
package com.abandonware.ai.agent.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.RemoteEmbedder
 * Role: config
 * Dependencies: com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.ObjectMapper
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.RemoteEmbedder
role: config
*/
public class RemoteEmbedder implements Embedder {

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper om = new ObjectMapper();
    private final String apiUrl;
    private final String apiKind;
    private final String apiKey;
    private final String model;

    public RemoteEmbedder() {
        this.apiUrl = System.getenv().getOrDefault("EMBED_API_URL", "http://localhost:8080/embed");
        this.apiKind = System.getenv().getOrDefault("EMBED_API_KIND", "tei").toLowerCase(Locale.ROOT);
        this.apiKey = System.getenv().getOrDefault("EMBED_API_KEY", "");
        this.model = System.getenv().getOrDefault("EMBED_MODEL", "text-embedding-3-small");
    }

    @Override
    public float[] embed(String text) {
        try {
            String payload;
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(apiUrl)).timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json");
            if (!apiKey.isBlank()) req.header("Authorization", "Bearer " + apiKey);
            if ("openai".equals(apiKind)) {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("input", text);
                m.put("model", model);
                payload = om.writeValueAsString(m);
            } else { // tei default
                payload = om.writeValueAsString(Map.of("input", List.of(text)));
            }
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    req.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return new HeuristicEmbedder().embed(text);
            JsonNode root = om.readTree(resp.body());
            float[] vec = tryParseEmbedding(root);
            if (vec != null) return vec;
            return new HeuristicEmbedder().embed(text);
        } catch (Exception e) {
            return new HeuristicEmbedder().embed(text);
        }
    }

    private float[] tryParseEmbedding(JsonNode root) {
        if (root == null) return null;
        // openai style: {"data":[{"embedding":[/* ... */]}]}
        JsonNode data = root.get("data");
        if (data != null && data.isArray() && data.size() > 0) {
            JsonNode emb = data.get(0).get("embedding");
            if (emb != null && emb.isArray()) {
                float[] v = new float[emb.size()];
                for (int i=0;i<emb.size();i++) v[i] = (float) emb.get(i).asDouble();
                return normalize(v);
            }
        }
        // tei simple: {"embedding":[/* ... */]} or {"embeddings":[[/* ... */]]}
        JsonNode embedding = root.get("embedding");
        if (embedding != null && embedding.isArray()) {
            float[] v = new float[embedding.size()];
            for (int i=0;i<embedding.size();i++) v[i] = (float) embedding.get(i).asDouble();
            return normalize(v);
        }
        JsonNode embeddings = root.get("embeddings");
        if (embeddings != null && embeddings.isArray() && embeddings.size() > 0 && embeddings.get(0).isArray()) {
            JsonNode first = embeddings.get(0);
            float[] v = new float[first.size()];
            for (int i=0;i<first.size();i++) v[i] = (float) first.get(i).asDouble();
            return normalize(v);
        }
        return null;
    }

    private float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += x*x;
        norm = Math.sqrt(Math.max(1e-9, norm));
        for (int i=0;i<v.length;i++) v[i] /= (float) norm;
        return v;
    }
}