package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        String eventId = input.get("eventId") == null ? "" : String.valueOf(input.get("eventId")).trim();
        String mode = input.get("mode") == null ? "" : String.valueOf(input.get("mode")).trim();
        boolean hasFilter = input.containsKey("requestIdHash") || input.containsKey("traceIdHash");
        if (hasFilter && (!eventId.isBlank() || (!mode.isBlank() && !"correlated".equals(mode)))) {
            return invalid("ambiguous_target");
        }
        if (!eventId.isBlank() && !mode.isBlank() && !"event_id".equals(mode)) {
            return invalid("ambiguous_target");
        }
        if (mode.isBlank() && !hasFilter && eventId.isBlank()) return invalid("missing_target");
        if ("correlated".equals(mode) && !hasFilter) return invalid("missing_target");
        if ("event_id".equals(mode) && eventId.isBlank()) return invalid("missing_target");
        if (!mode.isBlank() && !"correlated".equals(mode) && !"event_id".equals(mode)
                && !"global_recent".equals(mode)) return invalid("invalid_mode");
        DebugEventStore store = debugEvents == null ? null : debugEvents.getIfAvailable();
        if (store == null) {
            return ToolResponse.ok().put("providerSurface", "java_agent_tool")
                    .put("available", false).put("sourceStatus", "unavailable")
                    .put("reason", "debug_event_store_missing").put("events", List.of());
        }
        if ("correlated".equals(mode) || (mode.isBlank() && hasFilter)) {
            return correlated(store, input);
        }
        if (!eventId.isBlank() && (mode.isBlank() || "event_id".equals(mode))) {
            DebugEvent event = store.get(eventId);
            return ToolResponse.ok()
                    .put("providerSurface", "java_agent_tool")
                    .put("scope", "event_id")
                    .put("available", true)
                    .put("sourceStatus", event == null ? "unavailable" : "available")
                    .put("reason", event == null ? "event_not_retained" : null)
                    .put("event", event == null ? null : eventRow(event));
        }
        int limit = boundedInt(input.get("limit"), 20, 1, 100);
        List<Map<String, Object>> events = store.list(limit).stream().map(DebugTraceLookupTool::eventRow).toList();
        ToolResponse response = ToolResponse.ok()
                .put("providerSurface", "java_agent_tool")
                .put("scope", "global_recent")
                .put("available", true)
                .put("sourceStatus", "available")
                .put("limit", limit)
                .put("returnedCount", events.size())
                .put("events", events);
        DebugAiMetricsService metrics = debugAiMetrics == null ? null : debugAiMetrics.getIfAvailable();
        if (metrics != null) {
            response.put("debugAi", metrics.compactSnapshot(Math.min(limit, 50)));
            response.put("debugAiScope", "global_metrics");
        }
        return response;
    }

    private static ToolResponse correlated(DebugEventStore store, Map<String, Object> input) {
        String requestHash = hashFilter(input.get("requestIdHash"));
        String traceHash = hashFilter(input.get("traceIdHash"));
        if (requestHash == null && traceHash == null) return invalid("invalid_filter");
        if ("invalid".equals(requestHash) || "invalid".equals(traceHash)) return invalid("invalid_filter");

        Object cursorInput = input.get("cursor");
        String afterId = cursorInput == null ? null
                : decodeCursor(String.valueOf(cursorInput), requestHash, traceHash);
        if (cursorInput != null && afterId == null) return invalid("invalid_cursor");

        int limit = boundedInt(input.get("limit"), 20, 1, 100);
        DebugEventStore.EventPage page = store.page(requestHash, traceHash, afterId, limit);
        List<Map<String, Object>> events = page.items().stream().map(DebugTraceLookupTool::eventRow).toList();
        boolean evicted = "evicted".equals(page.cursorStatus());
        return ToolResponse.ok()
                .put("providerSurface", "java_agent_tool")
                .put("scope", "correlated")
                .put("available", true)
                .put("sourceStatus", evicted ? "evicted" : "available")
                .put("reason", evicted ? "cursor_evicted" : null)
                .put("requestIdHash", requestHash)
                .put("traceIdHash", traceHash)
                .put("returnedCount", events.size())
                .put("hasMore", page.hasMore())
                .put("nextCursor", page.nextId() == null ? null : encodeCursor(page.nextId(), requestHash, traceHash))
                .put("cursorStatus", page.cursorStatus())
                .put("events", events);
    }

    private static String hashFilter(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.matches("hash:[0-9a-f]{12}") ? text : "invalid";
    }

    // Keep the existing HTTP /events/page v1 cursor shape, including its filter binding.
    private static String encodeCursor(String id, String requestHash, String traceHash) {
        String value = "v1\n" + id + "\n" + Objects.toString(requestHash, "")
                + "\n" + Objects.toString(traceHash, "");
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeCursor(String cursor, String requestHash, String traceHash) {
        if (cursor.length() > 512 || !cursor.matches("[A-Za-z0-9_-]+")) return null;
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\n", -1);
            if (parts.length != 4 || !"v1".equals(parts[0])
                    || !parts[1].matches("[0-9a-f]{1,16}-[0-9a-f]{1,16}")
                    || !Objects.toString(requestHash, "").equals(parts[2])
                    || !Objects.toString(traceHash, "").equals(parts[3])
                    || !encodeCursor(parts[1], requestHash, traceHash).equals(cursor)) return null;
            return parts[1];
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ToolResponse invalid(String reason) {
        throw ToolInvocationException.badRequest(reason);
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
        row.put("requestIdHash", event.requestId());
        row.put("traceIdHash", event.traceId());
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
