package com.example.lms.api;

import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostics endpoints for in-memory trace snapshots.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/diagnostics/trace/snapshots?limit=50</li>
 *   <li>GET /api/diagnostics/trace/snapshots/{id}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/diagnostics/trace")
public class TraceSnapshotsDiagnosticsController {

    private final ObjectProvider<TraceSnapshotStore> storeProvider;

    public TraceSnapshotsDiagnosticsController(ObjectProvider<TraceSnapshotStore> storeProvider) {
        this.storeProvider = storeProvider;
    }

    @GetMapping("/snapshots")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", Instant.now().toString());
        out.put("available", store != null);
        out.put("snapshots", store == null ? java.util.List.of() : store.listSummaries(limit));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/snapshots/{id}")
    public ResponseEntity<?> get(@PathVariable("id") String id) {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "TraceSnapshotStore not available"));
        }
        return store.get(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "snapshot not found")));
    }

    /**
     * HTML view for a snapshot (best-effort).
     *
     * <p>Endpoint:</p>
     * <ul>
     *   <li>GET /api/diagnostics/trace/snapshots/{id}/html</li>
     * </ul>
     */
    @GetMapping(value = "/snapshots/{id}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getHtml(@PathVariable("id") String id) {
        TraceSnapshotStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(simpleHtml("TraceSnapshotStore not available", Map.of("id", String.valueOf(id))));
        }
        return store.get(id)
                .map(s -> {
                    String html = s.html();
                    if (html == null || html.isBlank()) {
                        java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<>();
                        kv.put("id", String.valueOf(s.id()));
                        kv.put("ts", String.valueOf(s.tsIso()));
                        kv.put("sid", String.valueOf(s.sid()));
                        kv.put("traceId", String.valueOf(s.traceId()));
                        kv.put("requestId", String.valueOf(s.requestId()));
                        kv.put("reason", String.valueOf(s.reason()));
                        kv.put("method", String.valueOf(s.method()));
                        kv.put("path", String.valueOf(s.path()));
                        kv.put("status", String.valueOf(s.status()));
                        kv.put("error", String.valueOf(s.error()));
                        kv.put("traceEntryCount", String.valueOf(s.traceEntryCount()));
                        kv.put("hasMlBreadcrumbs", String.valueOf(s.hasMlBreadcrumbs()));
                        kv.put("hasHtml", String.valueOf(s.html() != null));
                        html = simpleHtml("Trace snapshot (no stored HTML)", kv);
                    }
                    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_HTML)
                        .body(simpleHtml("snapshot not found", Map.of("id", String.valueOf(id)))));
    }

    private static String simpleHtml(String title, Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
        sb.append("<title>").append(esc(title)).append("</title>");
        sb.append("<style>body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:16px} table{border-collapse:collapse} td,th{border:1px solid #ddd;padding:6px 8px} th{background:#f7f7f7;text-align:left}</style>");
        sb.append("</head><body>");
        sb.append("<h2>").append(esc(title)).append("</h2>");
        sb.append("<table>");
        if (kv != null) {
            for (Map.Entry<String, String> e : kv.entrySet()) {
                sb.append("<tr><th>").append(esc(String.valueOf(e.getKey()))).append("</th><td>")
                        .append(esc(String.valueOf(e.getValue()))).append("</td></tr>");
            }
        }
        sb.append("</table>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
