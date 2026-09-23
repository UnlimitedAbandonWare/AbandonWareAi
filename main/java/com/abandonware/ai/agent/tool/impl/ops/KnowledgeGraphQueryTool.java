package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class KnowledgeGraphQueryTool implements AgentTool {
    private final ObjectProvider<KnowledgeGraphHandler> handlerProvider;

    public KnowledgeGraphQueryTool(ObjectProvider<KnowledgeGraphHandler> handlerProvider) {
        this.handlerProvider = handlerProvider;
    }

    @Override
    public String id() {
        return "kg.query";
    }

    @Override
    public String description() {
        return "Query the active KnowledgeGraphHandler and return bounded redacted KG evidence rows.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        String query = input.get("query") == null ? "" : String.valueOf(input.get("query")).trim();
        int topK = boundedInt(input.get("topK"), 5, 1, 20);
        TraceStore.put("tool.kg.query.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("tool.kg.query.queryLength", query.length());
        TraceStore.put("tool.kg.query.requestedK", topK);

        if (query.isBlank()) {
            TraceStore.put("tool.kg.query.status", "SKIPPED");
            TraceStore.put("tool.kg.query.skipped.reason", "EMPTY_QUERY");
            TraceStore.put("tool.kg.query.returnedCount", 0);
            return ToolResponse.ok()
                    .put("available", true)
                    .put("results", List.of())
                    .put("skippedReason", "EMPTY_QUERY");
        }

        KnowledgeGraphHandler handler = handlerProvider == null ? null : handlerProvider.getIfAvailable();
        if (handler == null) {
            TraceStore.put("tool.kg.query.status", "SKIPPED");
            TraceStore.put("tool.kg.query.skipped.reason", "KG_HANDLER_UNAVAILABLE");
            TraceStore.put("tool.kg.query.returnedCount", 0);
            return ToolResponse.ok()
                    .put("available", false)
                    .put("results", List.of())
                    .put("skippedReason", "KG_HANDLER_UNAVAILABLE");
        }

        long start = System.nanoTime();
        try {
            List<Content> retrieved = handler.retrieve(new Query(query));
            List<Content> safe = retrieved == null ? List.of() : retrieved;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < safe.size() && rows.size() < topK; i++) {
                rows.add(row(safe.get(i), i + 1));
            }
            TraceStore.put("tool.kg.query.status", "OK");
            TraceStore.put("tool.kg.query.returnedCount", rows.size());
            TraceStore.put("tool.kg.query.zeroResults", rows.isEmpty());
            TraceStore.put("tool.kg.query.durationMs", elapsedMs(start));
            TraceStore.put("tool.kg.query.skipped.reason", rows.isEmpty() ? "NO_KG_RESULTS" : null);
            return ToolResponse.ok()
                    .put("available", true)
                    .put("returnedCount", rows.size())
                    .put("results", rows);
        } catch (RuntimeException ex) {
            TraceStore.put("tool.kg.query.status", "FAIL_SOFT");
            TraceStore.put("tool.kg.query.skipped.reason", "KG_QUERY_EXCEPTION");
            TraceStore.put("tool.kg.query.failReason",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            TraceStore.put("tool.kg.query.failMsgHash", SafeRedactor.hashValue(ex.getMessage()));
            TraceStore.put("tool.kg.query.durationMs", elapsedMs(start));
            TraceStore.put("tool.kg.query.returnedCount", 0);
            return ToolResponse.ok()
                    .put("available", true)
                    .put("results", List.of())
                    .put("skippedReason", "KG_QUERY_EXCEPTION");
        }
    }

    private static Map<String, Object> row(Content content, int rank) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank", rank);
        if (content == null || content.textSegment() == null) {
            row.put("snippet", Map.of("present", false));
            return row;
        }
        String text = content.textSegment().text();
        row.put("snippet", SafeRedactor.diagnosticValue("kg.query.snippet", text, 360));
        if (content.textSegment().metadata() != null) {
            row.put("metadata", SafeRedactor.diagnosticValue(
                    "kg.query.metadata", content.textSegment().metadata().toMap(), 360));
        }
        return row;
    }

    private static int boundedInt(Object value, int fallback, int min, int max) {
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                TraceStore.put("tool.kg.query.suppressed", true);
                TraceStore.put("tool.kg.query.suppressed.stage", "topK");
                TraceStore.put("tool.kg.query.suppressed.errorType", "invalid_number");
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
