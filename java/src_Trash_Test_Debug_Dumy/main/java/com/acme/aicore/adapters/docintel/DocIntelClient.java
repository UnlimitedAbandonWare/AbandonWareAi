package com.acme.aicore.adapters.docintel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Thin wrapper over the Azure Document Intelligence v4 REST API.
 *
 * <p>This client sends an asynchronous analyze request using the <em>prebuilt-read</em>
 * model to extract text from arbitrary documents. It polls the operation‑location for up
 * to 60 seconds and returns the concatenated text of all pages. If the request fails or
 * the secrets are unavailable, it returns {@code null} rather than throwing an error.</p>
 */
import org.springframework.stereotype.Component;

@Component
public class DocIntelClient {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Invokes the prebuilt‑read model to extract text from the provided bytes.
     *
     * @param bytes raw file contents encoded as a byte array
     * @return the extracted text, or {@code null} if unavailable or secrets are missing
     * @throws Exception if the API returns an error response
     */
    public String analyzeRead(byte[] bytes) throws Exception {
        if (!DocIntelSecretRegistry.available()) return null;

        String endpoint = DocIntelSecretRegistry.endpoint();
        String key = DocIntelSecretRegistry.apiKey();
        String url = endpoint + "/documentintelligence/documentModels/prebuilt-read:analyze"
                + "?_overload=analyzeDocument&api-version=2024-11-30&features=languages";

        String body = M.createObjectNode()
                .put("base64Source", Base64.getEncoder().encodeToString(bytes))
                .toString();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Ocp-Apim-Subscription-Key", key)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() != 202) {
            throw new IllegalStateException("Analyze POST failed: " + resp.statusCode());
        }

        String op = resp.headers().firstValue("operation-location")
                .orElseThrow(() -> new IllegalStateException("Missing Operation-Location"));
        // Poll the operation endpoint with 1s backoff up to 60 seconds.
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            HttpRequest get = HttpRequest.newBuilder(URI.create(op))
                    .header("Ocp-Apim-Subscription-Key", key)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> r = HTTP.send(get, HttpResponse.BodyHandlers.ofString());
            JsonNode j = M.readTree(r.body());
            String status = j.path("status").asText("");
            if ("succeeded".equalsIgnoreCase(status)) {
                JsonNode result = j.path("analyzeResult");
                String content = result.path("content").asText(null);
                if (content == null || content.isBlank()) {
                    // fall back to concatenating lines from each page
                    StringBuilder sb = new StringBuilder();
                    result.path("pages").forEach(p ->
                            p.path("lines").forEach(line -> {
                                String c = line.path("content").asText("");
                                if (!c.isBlank()) {
                                    sb.append(c).append('\n');
                                }
                            })
                    );
                    content = sb.toString();
                }
                return (content == null || content.isBlank()) ? null : content;
            }
            if ("failed".equalsIgnoreCase(status)) {
                throw new IllegalStateException("Analyze failed: " + j.path("error").toString());
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Analyze timeout");
            }
            Thread.sleep(1000L);
        }
    }
}