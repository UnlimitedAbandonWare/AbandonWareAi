package com.example.lms.service.rag;

import com.example.lms.service.VectorMetaKeys;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility methods for constructing {@link Query} instances in a version-safe manner.
 */
public final class QueryUtils {

    private QueryUtils() {}

    /**
     * Build a new {@link Query} with the supplied text and optional session/history context.
     *
     * <p>History is accepted for API compatibility but is not embedded into metadata
     * to keep metadata light-weight.
     */
    public static Query buildQuery(String text, Object sessionId, List<?> history) {
        return buildQuery(text, sessionId, history, null);
    }

    /**
     * Build a new {@link Query} with optional additional metadata hints.
     */
    public static Query buildQuery(String text, Object sessionId, List<?> history, Map<String, Object> extraMeta) {
        Map<String, Object> mdMap = new HashMap<>();
        if (sessionId != null) {
            mdMap.put(LangChainRAGService.META_SID, sessionId);
        }
        if (extraMeta != null && !extraMeta.isEmpty()) {
            mdMap.putAll(extraMeta);
        }

        // Default allowlist to avoid LOG/TRACE/QUARANTINE segments contaminating retrieval
        // (caller can override by explicitly setting allowed_doc_types).
        mdMap.putIfAbsent(VectorMetaKeys.META_ALLOWED_DOC_TYPES, "KB,MEMORY,LEGACY");
        Metadata md = Metadata.from(mdMap);
        return new Query(text, md);
    }

    /**
     * Build a new {@link Query} containing only the user text.
     */
    public static Query buildQuery(String text) {
        return new Query(text);
    }
}
