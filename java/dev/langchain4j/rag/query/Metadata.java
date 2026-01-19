package dev.langchain4j.rag.query;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Compatibility shim for query metadata. */
public final class Metadata {

    private static final String META_SID = com.example.lms.service.rag.LangChainRAGService.META_SID;

    private final Map<String, Object> map;

    private Metadata(Map<String, Object> map) {
        this.map = Collections.unmodifiableMap(map);
    }

    public static Metadata from(Object userMessage, Object chatMemoryId, Object chatHistory) {
        Map<String, Object> m = new HashMap<>();
        if (chatMemoryId != null) {
            m.put(META_SID, chatMemoryId);
        }
        return new Metadata(m);
    }

    public static Metadata from(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return new Metadata(Collections.emptyMap());
        }
        return new Metadata(new HashMap<>(map));
    }

    public Metadata merge(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> m = new HashMap<>(this.map);
        m.putAll(extra);
        return new Metadata(m);
    }

    public Object chatMemoryId() {
        return map.get(META_SID);
    }

    public Map<String, Object> asMap() {
        return map;
    }
}
