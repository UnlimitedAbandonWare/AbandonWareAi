package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class TraceSnapshotTool implements AgentTool {
    private final ObjectProvider<TraceSnapshotStore> snapshots;

    public TraceSnapshotTool() {
        this(null);
    }

    public TraceSnapshotTool(ObjectProvider<TraceSnapshotStore> snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public String id() {
        return "trace.snapshot";
    }

    @Override
    public String description() {
        return "Return a bounded redacted TraceStore snapshot for current request diagnostics.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        String mode = input.get("mode") == null ? "current_request" : String.valueOf(input.get("mode")).trim();
        if ("stored_snapshot".equals(mode)) return storedSnapshot(input);
        if (!"current_request".equals(mode)) throw ToolInvocationException.badRequest("invalid_mode");
        int limit = boundedInt(input.get("limit"), 50, 1, 250);
        String prefix = input.get("prefix") == null ? "" : String.valueOf(input.get("prefix")).trim();

        Map<String, Object> trace = prefix.isBlank()
                ? new TreeMap<>(TraceStore.getAll())
                : new TreeMap<>(TraceStore.getByPrefix(prefix));
        List<Map<String, Object>> entries = new ArrayList<>();
        int seen = 0;
        for (Map.Entry<String, Object> entry : trace.entrySet()) {
            seen++;
            if (entries.size() >= limit) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", SafeRedactor.traceLabelOrFallback(entry.getKey(), "unknown"));
            row.put("value", SafeRedactor.diagnosticValue("trace.snapshot." + entry.getKey(), entry.getValue(), 360));
            entries.add(row);
        }

        boolean truncated = seen > entries.size();
        TraceStore.put("tool.trace.snapshot.keyCount", trace.size());
        TraceStore.put("tool.trace.snapshot.returnedCount", entries.size());
        TraceStore.put("tool.trace.snapshot.truncated", truncated);
        TraceStore.put("tool.trace.snapshot.prefixApplied", !prefix.isBlank());
        if (!prefix.isBlank()) {
            TraceStore.put("tool.trace.snapshot.prefix", SafeRedactor.traceLabelOrFallback(prefix, "unknown"));
        }

        return ToolResponse.ok()
                .put("providerSurface", "java_agent_tool")
                .put("scope", "current_request")
                .put("sourceStatus", "available")
                .put("available", true)
                .put("keyCount", trace.size())
                .put("returnedCount", entries.size())
                .put("truncated", truncated)
                .put("entries", entries);
    }

    private ToolResponse storedSnapshot(Map<String, Object> input) {
        String id = input.get("snapshotId") == null ? "" : String.valueOf(input.get("snapshotId")).trim();
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw ToolInvocationException.badRequest("invalid_snapshot_id");
        }
        String requestHash = optionalHash(input.get("requestIdHash"));
        String traceHash = optionalHash(input.get("traceIdHash"));
        TraceSnapshotStore store = snapshots == null ? null : snapshots.getIfAvailable();
        ToolResponse response = ToolResponse.ok()
                .put("providerSurface", "java_agent_tool")
                .put("scope", "stored_snapshot")
                .put("snapshotId", id)
                .put("storageMode", "memory_only")
                .put("historyComplete", false)
                .put("durableProjectionStatus", "not_observed")
                .put("nextCursor", null)
                .put("hasMore", false)
                .put("returnedCount", 0)
                .put("entries", List.of());
        if (store == null) return response.put("available", false).put("sourceStatus", "unavailable")
                .put("reason", "trace_snapshot_store_missing");
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElse(null);
        if (snapshot == null) return response.put("available", false).put("sourceStatus", "unavailable")
                .put("reason", "snapshot_not_retained");
        if ((requestHash != null && !requestHash.equals(snapshot.requestId()))
                || (traceHash != null && !traceHash.equals(snapshot.traceId()))) {
            return response.put("available", false).put("sourceStatus", "stale")
                    .put("reason", "correlation_mismatch");
        }
        String prefix = input.get("prefix") == null ? "" : String.valueOf(input.get("prefix")).trim();
        int limit = boundedInt(input.get("limit"), 50, 1, 250);
        Map<String, Object> trace = snapshot.trace() == null ? Map.of() : snapshot.trace();
        List<Map<String, Object>> entries = new ArrayList<>();
        int keyCount = 0;
        for (Map.Entry<String, Object> entry : new TreeMap<>(trace).entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            keyCount++;
            if (entries.size() >= limit) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", SafeRedactor.traceLabelOrFallback(entry.getKey(), "unknown"));
            row.put("value", SafeRedactor.diagnosticValue("trace.snapshot." + entry.getKey(), entry.getValue(), 360));
            entries.add(row);
        }
        return response.put("available", true).put("sourceStatus", "available")
                .put("reason", null).put("requestIdHash", snapshot.requestId())
                .put("traceIdHash", snapshot.traceId()).put("keyCount", keyCount)
                .put("returnedCount", entries.size()).put("truncated", keyCount > entries.size())
                .put("entries", entries);
    }

    private static String optionalHash(Object value) {
        if (value == null) return null;
        String hash = String.valueOf(value).trim();
        if (!hash.matches("hash:[0-9a-f]{12}")) throw ToolInvocationException.badRequest("invalid_filter");
        return hash;
    }

    private static int boundedInt(Object value, int fallback, int min, int max) {
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                String raw = String.valueOf(value);
                TraceStore.put("tool.trace.snapshot.suppressed", true);
                TraceStore.put("tool.trace.snapshot.suppressed.stage", "limit");
                TraceStore.put("tool.trace.snapshot.suppressed.errorType", "invalid_number");
                TraceStore.put("tool.trace.snapshot.suppressed.valueHash", SafeRedactor.hashValue(raw));
                TraceStore.put("tool.trace.snapshot.suppressed.valueLength", raw.length());
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }
}
