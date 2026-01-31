
package com.example.lms.service.embedding;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/** Tiny in-memory embedding cache keyed by text hash; keeps only last N entries. */
@Component
public class EmbeddingCache {
    private final ConcurrentHashMap<Integer, float[]> map = new ConcurrentHashMap<>();
    private final int max = 10_000;

    public float[] getOrPut(String text, java.util.function.Function<String,float[]> supplier){
        int key = (text==null?0:text.hashCode());
        float[] v = map.get(key);
        if (v != null) return v;
        v = supplier.apply(text);
        if (map.size() > max) map.clear();
        map.put(key, v);
        return v;
    }
}