
package com.abandonware.ai.agent.integrations;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;




/**
 * Remote embedder calling TEI/OpenAI-like endpoints.
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
            if (!ConfigValueGuards.isMissing(apiKey)) req.header("Authorization", "Bearer " + apiKey);
            if ("openai".equals(apiKind)) {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("input", text);
                m.put("model", model);
                payload = om.writeValueAsString(m);
            } else { // tei default
                payload = om.writeValueAsString(Map.of("input", List.of(text)));
            }
            HttpResponse<String> resp = client.send(
                    req.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return new HeuristicEmbedder().embed(text);
            JsonNode root = om.readTree(resp.body());
            float[] vec = tryParseEmbedding(root);
            if (vec != null) return vec;
            return new HeuristicEmbedder().embed(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            traceSuppressed("embed", text, e);
            return new HeuristicEmbedder().embed(text);
        } catch (Exception e) {
            traceSuppressed("embed", text, e);
            return new HeuristicEmbedder().embed(text);
        }
    }

    private float[] tryParseEmbedding(JsonNode root) {
        if (root == null) return null;
        // openai style: {"data":[{"embedding":[/* ... */]}]}
        JsonNode data = root.get("data");
        if (data != null && data.isArray() && data.size() > 0) {
            return vectorFromArray(data.get(0).get("embedding"));
        }
        // tei simple: {"embedding":[/* ... */]} or {"embeddings":[[/* ... */]]}
        JsonNode embedding = root.get("embedding");
        if (embedding != null && embedding.isArray()) {
            return vectorFromArray(embedding);
        }
        JsonNode embeddings = root.get("embeddings");
        if (embeddings != null && embeddings.isArray() && embeddings.size() > 0 && embeddings.get(0).isArray()) {
            return vectorFromArray(embeddings.get(0));
        }
        return null;
    }

    private float[] vectorFromArray(JsonNode embedding) {
        if (embedding == null || !embedding.isArray() || embedding.size() == 0) {
            return null;
        }
        float[] v = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            double value = embedding.get(i).asDouble(Double.NaN);
            if (!Double.isFinite(value)) {
                return null;
            }
            v[i] = (float) value;
            if (!Float.isFinite(v[i])) {
                return null;
            }
        }
        return normalize(v);
    }

    private float[] normalize(float[] v) {
        if (v == null || v.length == 0) {
            return null;
        }
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        if (norm <= 1e-12d) {
            return null;
        }
        norm = Math.sqrt(Math.max(1e-9, norm));
        for (int i=0;i<v.length;i++) v[i] = (float) (v[i] / norm);
        return v;
    }

    private void traceSuppressed(String stage, String text, Throwable error) {
        TraceStore.put("agent.remoteEmbedder.suppressed", true);
        TraceStore.put("agent.remoteEmbedder.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.remoteEmbedder.suppressed.errorType", errorType(error));
        TraceStore.put("agent.remoteEmbedder.suppressed.textHash", SafeRedactor.hashValue(text));
        TraceStore.put("agent.remoteEmbedder.suppressed.textLength", text == null ? 0 : text.length());
        TraceStore.put("agent.remoteEmbedder.suppressed.endpointHash", SafeRedactor.hashValue(apiUrl));
        TraceStore.put("agent.remoteEmbedder.suppressed.endpointLength", apiUrl == null ? 0 : apiUrl.length());
    }

    private static String errorType(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
