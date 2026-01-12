package com.example.lms.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class VectorStoreHealthIndicator implements HealthIndicator {

    private final EmbeddingStore<TextSegment> store;

    public VectorStoreHealthIndicator(EmbeddingStore<TextSegment> store) {
        this.store = store;
    }

    @Override
    public Health health() {
        EmbeddingStore<?> effective = unwrapWriterStore(store);
        if (effective instanceof InMemoryEmbeddingStore) {
            return Health.down()
                    .withDetail("vectorStore", "fallback-inmemory")
                    .withDetail("effectiveStore", effective.getClass().getName())
                    .withDetail("outerStore", store == null ? null : store.getClass().getName())
                    .build();
        }
        return Health.up()
                .withDetail("vectorStore", effective == null ? null : effective.getClass().getSimpleName())
                .withDetail("outerStore", store == null ? null : store.getClass().getSimpleName())
                .build();
    }

    /**
     * Best-effort unwrapping:
     * <ul>
     *   <li>composite EmbeddingStore beans often keep a private field named "writer"</li>
     *   <li>decorators often keep "delegate"</li>
     * </ul>
     */
    private static EmbeddingStore<?> unwrapWriterStore(EmbeddingStore<?> s) {
        if (s == null) return null;
        // 1) try field "writer"
        EmbeddingStore<?> viaWriter = reflectStoreField(s, "writer");
        if (viaWriter != null && viaWriter != s) {
            return unwrapWriterStore(viaWriter);
        }
        // 2) try field "delegate"
        EmbeddingStore<?> viaDelegate = reflectStoreField(s, "delegate");
        if (viaDelegate != null && viaDelegate != s) {
            return unwrapWriterStore(viaDelegate);
        }
        return s;
    }

    private static EmbeddingStore<?> reflectStoreField(EmbeddingStore<?> s, String fieldName) {
        try {
            java.lang.reflect.Field f = s.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(s);
            if (v instanceof EmbeddingStore<?> es) {
                return es;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
