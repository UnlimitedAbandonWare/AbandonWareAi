// src/main/java/com/example/lms/trace/CloudPointerClient.java
package com.example.lms.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;




/**
 * Lightweight HTTP sink for forwarding trace events to external collectors.
 * Controlled via environment variables:
 *
 *   CLOUD_POINTER_URLS       CSV of endpoints, e.g. "https://log1/ingest,https://log2/ingest"
 *   CLOUD_POINTER_TOKEN      Optional bearer token (Authorization: Bearer)
 *   CLOUD_POINTER_TIMEOUT_MS Request timeout in milliseconds (default 1200)
 *
 * Failures are swallowed (best-effort, non-blocking).
 */
public final class CloudPointerClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String URLS   = System.getenv().getOrDefault("CLOUD_POINTER_URLS", "");
    private static final String TOKEN  = System.getenv().getOrDefault("CLOUD_POINTER_TOKEN", "");
    private static final int TIMEOUT   = parseInt(System.getenv().getOrDefault("CLOUD_POINTER_TIMEOUT_MS", "1200"), 1200);

    private CloudPointerClient() {}

    public static void trySend(String type, String stage, java.util.Map<String, Object> kv) {
        if (URLS == null || URLS.isBlank()) return;
        try {
            java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
            ev.put("type", type);
            ev.put("stage", stage);
            ev.put("kv", kv);
            byte[] body = MAPPER.writeValueAsBytes(ev);
            for (String u : URLS.split(",")) {
                String url = u.trim();
                if (url.isEmpty()) continue;
                post(url, body);
            }
        } catch (Exception ignore) {
            // Ignore all errors
        }
    }

    private static void post(String url, byte[] body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (TOKEN != null && !TOKEN.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + TOKEN);
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            // Read response code to ensure the request is sent
            int code = conn.getResponseCode();
            // ignore code
        } catch (Exception ignore) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception ignore) { return def; }
    }
}