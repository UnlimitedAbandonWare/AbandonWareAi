package com.example.lms.service.rag.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

@Component
public class EmbeddingCache {
    private final EmbeddingModel delegate;
    private final Cache<String, float[]> cache = Caffeine.newBuilder()
            .maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(10))
            .recordStats()
            .build();

    public EmbeddingCache(@Qualifier("embeddingModel") EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    private static String key(TextSegment s) {
        return DigestUtils.sha256Hex(Objects.toString(s.text(), ""));
    }

    public Embedding embed(TextSegment s) {
        String k = key(s);
        float[] v = cache.getIfPresent(k);
        if (v == null) {
            v = delegate.embed(s).content().vector();
            cache.put(k, v);
        }
        return Embedding.from(v);
    }

    public List<Embedding> embedAll(List<TextSegment> segs) {
        List<TextSegment> miss = new ArrayList<>();
        float[][] out = new float[segs.size()][];
        for (int i = 0; i < segs.size(); i++) {
            String k = key(segs.get(i));
            float[] v = cache.getIfPresent(k);
            if (v == null) miss.add(segs.get(i)); else out[i] = v;
        }
        if (!miss.isEmpty()) {
            var fresh = delegate.embedAll(miss).content();
            int m = 0;
            for (int i = 0; i < segs.size(); i++) if (out[i] == null) {
                float[] v = fresh.get(m++).vector();
                cache.put(key(segs.get(i)), v); out[i] = v;
            }
        }
        return IntStream.range(0, segs.size())
                .mapToObj(i -> Embedding.from(out[i]))
                .toList();
    }
}