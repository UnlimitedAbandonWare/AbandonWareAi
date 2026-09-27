// src/main/java/com/example/lms/trace/CloudPointerClient.java
package com.example.lms.trace;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;




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
    private static final System.Logger LOG = System.getLogger(CloudPointerClient.class.getName());

    private static final String URLS   = System.getenv().getOrDefault("CLOUD_POINTER_URLS", "");
    private static final String TOKEN  = System.getenv().getOrDefault("CLOUD_POINTER_TOKEN", "");
    private static final int TIMEOUT   = parseInt(System.getenv().getOrDefault("CLOUD_POINTER_TIMEOUT_MS", "1200"), 1200);

    private CloudPointerClient() {}

    public static void trySend(String type, String stage, Map<String, Object> kv) {
        if (URLS == null || URLS.isBlank()) return;
        try {
            byte[] body = MAPPER.writeValueAsBytes(buildEvent(type, stage, kv));
            for (String u : URLS.split(",")) {
                String url = u.trim();
                if (url.isEmpty()) continue;
                post(url, body);
            }
        } catch (Exception e) {
            String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType(e), "unknown");
            TraceStore.inc("trace.cloudPointer.suppressed.dispatch.count");
            TraceStore.put("trace.cloudPointer.suppressed.stage", "event_dispatch");
            TraceStore.put("trace.cloudPointer.suppressed.errorType", safeErrorType);
            TraceStore.put("trace.cloudPointer.suppressed.eventDispatch", true);
            TraceStore.put("trace.cloudPointer.suppressed.eventDispatch.errorType", safeErrorType);
            LOG.log(System.Logger.Level.DEBUG,
                    "Cloud pointer send skipped stage=event_dispatch errorType=" + safeErrorType);
        }
    }

    static Map<String, Object> buildEvent(String type, String stage, Map<String, Object> kv) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", SafeRedactor.traceLabelOrFallback(type, "event"));
        ev.put("stage", SafeRedactor.traceLabelOrFallback(stage, "stage"));
        ev.put("kv", SafeRedactor.diagnosticValue("cloudPointer.kv.payload", kv == null ? Map.of() : kv, 2048));
        return ev;
    }

    private static void post(String url, byte[] body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (!ConfigValueGuards.isMissing(TOKEN)) {
                conn.setRequestProperty("Authorization", "Bearer " + TOKEN.trim());
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            // Read response code to ensure the request is sent
            int code = conn.getResponseCode();
            // ignore code
        } catch (Exception e) {
            String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType(e), "unknown");
            TraceStore.inc("trace.cloudPointer.suppressed.httpPost.count");
            TraceStore.put("trace.cloudPointer.suppressed.stage", "http_post");
            TraceStore.put("trace.cloudPointer.suppressed.errorType", safeErrorType);
            TraceStore.put("trace.cloudPointer.suppressed.httpPost", true);
            TraceStore.put("trace.cloudPointer.suppressed.httpPost.errorType", safeErrorType);
            LOG.log(System.Logger.Level.DEBUG,
                    "Cloud pointer post skipped stage=http_post endpointHash=" + SafeRedactor.hashValue(url)
                            + " endpointLength=" + safeLength(url)
                            + " errorType=" + safeErrorType);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String errorType(Exception e) {
        if (e == null) {
            return null;
        }
        if (e instanceof MalformedURLException || e instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return e.getClass().getSimpleName();
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignore) {
            TraceStore.put("trace.cloudPointer.suppressed.stage", "parseInt");
            TraceStore.put("trace.cloudPointer.suppressed.errorType", "invalid_number");
            TraceStore.put("trace.cloudPointer.suppressed.parseInt", true);
            TraceStore.put("trace.cloudPointer.suppressed.parseInt.errorType", "invalid_number");
            return def;
        }
    }
}
