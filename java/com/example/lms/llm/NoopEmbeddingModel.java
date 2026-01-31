package com.example.lms.llm;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.Collections;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;




/**
 * A no-op embedding model that returns zero vectors of a fixed dimension.
 * Used to gracefully degrade when no real embedding provider is available.
 */
@Profile("test")
@Component
public class NoopEmbeddingModel implements EmbeddingModel {

    private final int dim;

    public NoopEmbeddingModel(int dim) {
        this.dim = dim;
    }

    @Override
    public Response<Embedding> embed(String text) {
        // No real call; return an empty vector so downstream vector search can skip safely.
        // (All-zero vectors can lead to effectively random retrieval in some vector stores.)
        return Response.from(Embedding.from(new float[0]));
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        // Delegate to the String-based variant; text may be null.
        String text = (segment == null) ? null : segment.text();
        return embed(text);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        if (segments == null) {
            return Response.from(Collections.emptyList());
        }
        List<Embedding> embeddings = segments.stream()
                .map(s -> Embedding.from(new float[0]))
                .toList();
        return Response.from(embeddings);
    }

    // (선택) 기존 호출부 마이그레이션을 돕는 헬퍼 - 인터페이스 구현은 아님
    public Response<List<Embedding>> embedAllStrings(List<String> texts) {
        if (texts == null) {
            return Response.from(Collections.emptyList());
        }
        List<TextSegment> segments = texts.stream()
                .map(t -> TextSegment.from(t == null ? "" : t))
                .toList();
        return embedAll(segments);
    }
}