package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class TraceSnapshotTool implements AgentTool {

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
                .put("available", true)
                .put("keyCount", trace.size())
                .put("returnedCount", entries.size())
                .put("truncated", truncated)
                .put("entries", entries);
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
