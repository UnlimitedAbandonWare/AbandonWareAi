package com.example.lms.service.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * EmbeddingModel decorator that adds per-text caching.
 * No behavioural change other than avoiding duplicate model calls.
 */
public final class DecoratingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final EmbeddingCache cache;
    private final Duration ttl;

    public DecoratingEmbeddingModel(EmbeddingModel delegate, EmbeddingCache cache, Duration ttl) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Objects.requireNonNullElseGet(cache, EmbeddingCache.InMemory::new);
        this.ttl = (ttl == null) ? Duration.ofMinutes(15) : ttl;
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        if (textSegment == null) return Response.from(Embedding.from(new float[0]));
        String key = EmbeddingCache.keyFor(textSegment.text());
        float[] vec = cache.getOrCompute(key, () -> {
            Response<Embedding> r = delegate.embed(textSegment);
            return (r == null || r.content() == null) ? new float[0] : r.content().vector();
        }, ttl);
        return Response.from(Embedding.from(vec));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        if (textSegments == null || textSegments.isEmpty()) {
            return Response.from(List.of());
        }
        List<Embedding> out = new ArrayList<>(textSegments.size());
        for (TextSegment ts : textSegments) {
            out.add(embed(ts).content());
        }
        return Response.from(out);
    }
}