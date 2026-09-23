package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class DebugTraceLookupTool implements AgentTool {
    private final ObjectProvider<DebugEventStore> debugEvents;
    private final ObjectProvider<DebugAiMetricsService> debugAiMetrics;

    public DebugTraceLookupTool(ObjectProvider<DebugEventStore> debugEvents,
                                ObjectProvider<DebugAiMetricsService> debugAiMetrics) {
        this.debugEvents = debugEvents;
        this.debugAiMetrics = debugAiMetrics;
    }

    @Override
    public String id() {
        return "debug.trace.lookup";
    }

    @Override
    public String description() {
        return "Return a bounded redacted DebugEventStore summary or event lookup.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        DebugEventStore store = debugEvents == null ? null : debugEvents.getIfAvailable();
        if (store == null) {
            return ToolResponse.ok().put("available", false).put("events", List.of());
        }
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        String eventId = input.get("eventId") == null ? "" : String.valueOf(input.get("eventId")).trim();
        if (!eventId.isBlank()) {
            DebugEvent event = store.get(eventId);
            return ToolResponse.ok()
                    .put("available", true)
                    .put("event", event == null ? null : eventRow(event));
        }
        int limit = boundedInt(input.get("limit"), 20, 1, 100);
        List<Map<String, Object>> events = store.list(limit).stream().map(DebugTraceLookupTool::eventRow).toList();
        ToolResponse response = ToolResponse.ok()
                .put("available", true)
                .put("limit", limit)
                .put("events", events);
        DebugAiMetricsService metrics = debugAiMetrics == null ? null : debugAiMetrics.getIfAvailable();
        if (metrics != null) {
            response.put("debugAi", metrics.compactSnapshot(Math.min(limit, 50)));
        }
        return response;
    }

    private static Map<String, Object> eventRow(DebugEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", event.id());
        row.put("ts", event.ts());
        row.put("level", event.level() == null ? "" : event.level().name());
        row.put("probe", event.probe() == null ? "" : event.probe().name());
        row.put("fingerprint", SafeRedactor.safeMessage(event.fingerprint(), 160));
        row.put("message", SafeRedactor.safeMessage(event.message(), 240));
        row.put("where", SafeRedactor.safeMessage(event.where(), 160));
        row.put("data", SafeRedactor.diagnosticValue("debugEvent.data", event.data(), 800));
        row.put("errorClass", event.error() == null ? "" : SafeRedactor.safeMessage(event.error().type(), 160));
        return row;
    }

    private static int boundedInt(Object value, int fallback, int min, int max) {
        int parsed = fallback;
        if (value instanceof Number n) {
            parsed = n.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignore) {
                traceSuppressed("limit", value, ignore);
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static void traceSuppressed(String stage, Object value, Throwable error) {
        String raw = value == null ? null : String.valueOf(value);
        TraceStore.put("agent.ops.debugTraceLookup.suppressed", true);
        TraceStore.put("agent.ops.debugTraceLookup.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.ops.debugTraceLookup.suppressed.errorType", "invalid_number");
        TraceStore.put("agent.ops.debugTraceLookup.suppressed.valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put("agent.ops.debugTraceLookup.suppressed.valueLength", raw == null ? 0 : raw.length());
    }
}
