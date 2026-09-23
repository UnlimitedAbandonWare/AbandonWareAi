package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.HybridRetriever;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;




/**
 * Retrieves evidence snippets from the internal hybrid retriever.  The
 * {@code internal.read} scope is required because the retriever may access
 * private embeddings or knowledge bases.
 */
@Component
@RequiresScopes({ToolScope.INTERNAL_READ})
public class RagRetrieveTool implements AgentTool {
    private final HybridRetriever retriever;
    private final ObjectProvider<OverdriveGuard> overdriveGuardProvider;

    public RagRetrieveTool(HybridRetriever retriever) {
        this(retriever, null);
    }

    @Autowired
    public RagRetrieveTool(HybridRetriever retriever, ObjectProvider<OverdriveGuard> overdriveGuardProvider) {
        this.retriever = retriever;
        this.overdriveGuardProvider = overdriveGuardProvider;
    }

    @Override
    public String id() {
        return "rag.retrieve";
    }

    @Override
    public String description() {
        return "Retrieve evidence snippets from internal RAG sources.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request.input();
        String query = textOrNull(input.get("query"));
        Integer topK = positiveIntOrNull(input.get("topK"), "topK");
        String domain = textOrNull(input.get("domain"));
        if (input.containsKey("sessionId")) {
            parseSessionId(input.get("sessionId"));
        }
        int requestedK = topK == null ? 6 : topK;
        TraceStore.put("tool.rag.retrieve.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("tool.rag.retrieve.queryLength", query == null ? 0 : query.length());
        TraceStore.put("tool.rag.retrieve.requestedK", requestedK);
        List<Map<String, Object>> results = retriever.retrieve(query, topK, domain);
        List<Map<String, Object>> safeResults = results == null ? List.of() : results;
        TraceStore.put("tool.rag.retrieve.returnedCount", safeResults.size());
        boolean zeroResults = safeResults.isEmpty();
        TraceStore.put("tool.rag.retrieve.zeroResults", zeroResults);
        if (zeroResults) {
            TraceStore.put("tool.rag.retrieve.emptyReason", "hybridRetriever_returned_empty");
        } else {
            TraceStore.put("tool.rag.retrieve.emptyReason", null);
        }
        TraceStore.put("tool.rag.retrieve.overdriveTriggered", overdriveTriggered(query, safeResults));
        return ToolResponse.ok().put("results", safeResults);
    }

    private boolean overdriveTriggered(String query, List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            TraceStore.put("tool.rag.retrieve.overdriveSkippedReason", "no_results");
            return false;
        }
        if (overdriveGuardProvider == null) {
            TraceStore.put("tool.rag.retrieve.overdriveSkippedReason", "overdrive_guard_unavailable");
            return false;
        }
        OverdriveGuard guard = overdriveGuardProvider.getIfAvailable();
        if (guard == null) {
            TraceStore.put("tool.rag.retrieve.overdriveSkippedReason", "overdrive_guard_unavailable");
            return false;
        }
        try {
            boolean triggered = guard.shouldActivate(query, toContents(results));
            TraceStore.put("tool.rag.retrieve.overdriveSkippedReason", null);
            return triggered;
        } catch (Throwable ex) {
            TraceStore.put("tool.rag.retrieve.overdriveSkippedReason", "overdrive_guard_exception");
            TraceStore.put("tool.rag.retrieve.overdriveErrorClass",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            return false;
        }
    }

    private static List<Content> toContents(List<Map<String, Object>> results) {
        List<Content> out = new ArrayList<>();
        for (Map<String, Object> result : results) {
            String text = evidenceText(result);
            if (!text.isBlank()) {
                out.add(Content.from(text));
            }
        }
        return out;
    }

    private static String evidenceText(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return "";
        }
        Object snippet = firstNonNull(result.get("snippet"), result.get("text"), result.get("content"), result.get("title"));
        return snippet == null ? "" : String.valueOf(snippet);
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long parseSessionId(Object raw) {
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            String value = raw == null ? null : String.valueOf(raw);
            TraceStore.inc("agent.tool.ragRetrieve.sessionId.invalid.count");
            TraceStore.put("agent.tool.ragRetrieve.sessionId.invalid.stage", "sessionId");
            TraceStore.put("agent.tool.ragRetrieve.sessionId.invalid.errorType", "invalid_number");
            TraceStore.put("agent.tool.ragRetrieve.sessionId.invalid.valueHash", SafeRedactor.hashValue(value));
            TraceStore.put("agent.tool.ragRetrieve.sessionId.invalid.valueLength", value == null ? 0 : value.length());
            return null;
        }
    }

    private static String textOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer positiveIntOrNull(Object value, String stage) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException error) {
            traceSuppressed(stage, value, error);
            return null;
        }
    }

    private static void traceSuppressed(String stage, Object value, Throwable error) {
        String raw = value == null ? null : String.valueOf(value);
        TraceStore.put("tool.rag.retrieve.suppressed", true);
        TraceStore.put("tool.rag.retrieve.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("tool.rag.retrieve.suppressed.errorType",
                error instanceof NumberFormatException ? "invalid_number" : error.getClass().getSimpleName());
        TraceStore.put("tool.rag.retrieve.suppressed.valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put("tool.rag.retrieve.suppressed.valueLength", raw == null ? 0 : raw.length());
    }
}
