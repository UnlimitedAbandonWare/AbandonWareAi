
package com.abandonware.ai.agent.integrations;

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
            if (!key.isBlank()) b.header("Authorization", "Bearer " + key);
            HttpResponse<String> resp = client.send(b.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return embedTokensFallback(text);
            JsonNode root = om.readTree(resp.body());
            // expect {"token_embeddings":[[/ * ... * /],/ * ... * /]} or {"last_hidden_state":[[/ * ... * /],/ * ... * /]}
            JsonNode arr = root.get("token_embeddings");
            if (arr == null) arr = root.get("last_hidden_state");
            if (arr != null && arr.isArray()) {
                int rows = arr.size();
                int dim = arr.get(0).size();
                float[][] out = new float[Math.min(rows,64)][dim];
                int lim = Math.min(rows, 64);
                for (int i=0;i<lim;i++) {
                    JsonNode row = arr.get(i);
                    for (int d=0; d<dim; d++) {
                        out[i][d] = (float) row.get(d).asDouble();
                    }
                }
                return out;
            }
            return embedTokensFallback(text);
        } catch (Exception e) {
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
}