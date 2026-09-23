
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




public interface TokenEmbedder {
    float[][] embedTokens(String text);
}

class RemoteTokenEmbedder implements TokenEmbedder {
    private final String url = System.getenv().getOrDefault("TOKEN_EMBED_URL", "");
    private final String key = System.getenv().getOrDefault("TOKEN_EMBED_KEY", "");
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public float[][] embedTokens(String text) {
        if (url.isBlank()) {
            // degrade: split to tokens and use heuristic embedder per token
            List<String> toks = TextUtils.tokenize(text);
            HeuristicEmbedder he = new HeuristicEmbedder();
            float[][] out = new float[Math.min(64, toks.size())][];
            int lim = Math.min(64, toks.size());
            for (int i=0;i<lim;i++) out[i] = he.embed(toks.get(i));
            return out;
        }
        try {
            String payload = om.writeValueAsString(Map.of("input", text));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json");
            if (!ConfigValueGuards.isMissing(key)) b.header("Authorization", "Bearer " + key);
            HttpResponse<String> resp = client.send(b.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return embedTokensFallback(text);
            JsonNode root = om.readTree(resp.body());
            // expect {"token_embeddings":[[/* ... */],/* ... */]} or {"last_hidden_state":[[/* ... */],/* ... */]}
            JsonNode arr = root.get("token_embeddings");
            if (arr == null) arr = root.get("last_hidden_state");
            if (arr != null && arr.isArray()) {
                int rows = arr.size();
                if (rows == 0 || !arr.get(0).isArray() || arr.get(0).isEmpty()) {
                    return embedTokensFallback(text);
                }
                int dim = arr.get(0).size();
                float[][] out = new float[Math.min(rows,64)][dim];
                int lim = Math.min(rows, 64);
                for (int i=0;i<lim;i++) {
                    JsonNode row = arr.get(i);
                    if (row == null || !row.isArray() || row.size() != dim) {
                        return embedTokensFallback(text);
                    }
                    for (int d=0; d<dim; d++) {
                        double value = row.get(d).asDouble(Double.NaN);
                        if (!Double.isFinite(value)) {
                            return embedTokensFallback(text);
                        }
                        out[i][d] = (float) value;
                    }
                }
                return out;
            }
            return embedTokensFallback(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            traceSuppressed("embedTokens.interrupted", text, e);
            return embedTokensFallback(text);
        } catch (Exception e) {
            traceSuppressed("embedTokens", text, e);
            return embedTokensFallback(text);
        }
    }

    private float[][] embedTokensFallback(String text) {
        List<String> toks = TextUtils.tokenize(text);
        HeuristicEmbedder he = new HeuristicEmbedder();
        float[][] out = new float[Math.min(64, toks.size())][];
        int lim = Math.min(64, toks.size());
        for (int i=0;i<lim;i++) out[i] = he.embed(toks.get(i));
        return out;
    }

    private void traceSuppressed(String stage, String text, Throwable error) {
        TraceStore.put("agent.remoteTokenEmbedder.suppressed", true);
        TraceStore.put("agent.remoteTokenEmbedder.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.remoteTokenEmbedder.suppressed.errorType", errorType(error));
        TraceStore.put("agent.remoteTokenEmbedder.suppressed.textHash", SafeRedactor.hashValue(text));
        TraceStore.put("agent.remoteTokenEmbedder.suppressed.textLength", text == null ? 0 : text.length());
        TraceStore.put("agent.remoteTokenEmbedder.suppressed.endpointHash", SafeRedactor.hashValue(url));
        TraceStore.put("agent.remoteTokenEmbedder.suppressed.endpointLength", url == null ? 0 : url.length());
    }

    private static String errorType(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
