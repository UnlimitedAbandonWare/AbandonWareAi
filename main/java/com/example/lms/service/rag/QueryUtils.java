package com.example.lms.service.rag;

import com.example.lms.service.VectorMetaKeys;
import com.google.common.collect.MapMaker;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility methods for constructing {@link Query} instances in a version-safe manner.
 */
public final class QueryUtils {

    // Sidecars belong to query instances, not vendor value-equal queries.
    private static final Map<Query, Map<String, Object>> QUERY_METADATA =
            new MapMaker().weakKeys().makeMap();

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
        Map<String, Object> mdMap = new LinkedHashMap<>();
        if (sessionId != null) {
            mdMap.put(LangChainRAGService.META_SID, sessionId);
        }
        if (extraMeta != null && !extraMeta.isEmpty()) {
            mdMap.putAll(extraMeta);
        }

        // Default allowlist to avoid LOG/TRACE/QUARANTINE segments contaminating retrieval
        // (caller can override by explicitly setting allowed_doc_types).
        mdMap.putIfAbsent(VectorMetaKeys.META_ALLOWED_DOC_TYPES, "KB,MEMORY,LEGACY");
        return buildQuery(text, mdMap);
    }

    /**
     * Build a new {@link Query} containing only the user text.
     */
    public static Query buildQuery(String text) {
        return new Query(safeText(text));
    }

    /**
     * Build a query with repository-owned sidecar metadata instead of shadowing
     * LangChain4j's vendor-owned {@code dev.langchain4j.rag.query.Metadata}.
     */
    public static Query buildQuery(String text, Map<String, Object> metadata) {
        Map<String, Object> mdMap = copyMap(metadata);
        Object sessionId = firstPresent(mdMap,
                LangChainRAGService.META_SID,
                "sid",
                "sessionId",
                "chatMemoryId");
        Query query = sessionId == null
                ? new Query(safeText(text))
                : new Query(safeText(text), Metadata.from(UserMessage.from(safeText(text)), sessionId, List.<ChatMessage>of()));
        remember(query, mdMap);
        return query;
    }

    public static Query rebuild(Query original, String text) {
        return buildQuery(text, metadata(original));
    }

    public static Query rebuild(Query original, String text, Map<String, Object> extraMeta) {
        Map<String, Object> mdMap = metadata(original);
        if (extraMeta != null && !extraMeta.isEmpty()) {
            mdMap.putAll(extraMeta);
        }
        return buildQuery(text, mdMap);
    }

    public static void mergeMetadata(Query query, Map<String, Object> extraMeta) {
        if (query == null || extraMeta == null || extraMeta.isEmpty()) {
            return;
        }
        Map<String, Object> mdMap = new LinkedHashMap<>(metadata(query));
        mdMap.putAll(copyMap(extraMeta));
        remember(query, mdMap);
    }

    public static Map<String, Object> metadata(Query query) {
        if (query == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(metadata(query.metadata()));
        synchronized (QUERY_METADATA) {
            Map<String, Object> stored = QUERY_METADATA.get(query);
            if (stored != null && !stored.isEmpty()) {
                out.putAll(stored);
            }
        }
        return out.isEmpty() ? Map.of() : out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> metadata(Object metadata) {
        if (metadata == null) {
            return Map.of();
        }
        if (metadata instanceof Map<?, ?> raw) {
            return copyMap(raw);
        }
        if (metadata instanceof dev.langchain4j.data.document.Metadata md) {
            return copyMap(md.toMap());
        }
        if (metadata instanceof Metadata md) {
            Map<String, Object> out = new LinkedHashMap<>();
            Object sessionId = md.chatMemoryId();
            if (sessionId != null) {
                out.put(LangChainRAGService.META_SID, sessionId);
            }
            return out.isEmpty() ? Map.of() : out;
        }
        return Map.of();
    }

    public static Object sessionId(Query query) {
        return firstPresent(metadata(query), LangChainRAGService.META_SID, "sid", "sessionId", "chatMemoryId");
    }

    private static void remember(Query query, Map<String, Object> metadata) {
        if (query == null || metadata == null || metadata.isEmpty()) {
            return;
        }
        synchronized (QUERY_METADATA) {
            QUERY_METADATA.put(query, Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
        }
    }

    private static String safeText(String text) {
        return text == null ? "" : text;
    }

    private static Object firstPresent(Map<String, Object> metadata, String... keys) {
        if (metadata == null || metadata.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, Object> copyMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out.isEmpty() ? Map.of() : out;
    }
}
