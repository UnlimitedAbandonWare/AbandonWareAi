package com.example.lms.llm;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import java.util.List;

@RequiredArgsConstructor
public class PreprocessedEmbeddingModelV2 implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final QueryTransform transform;

    @Override
    public Response<Embedding> embed(String text) {
        Response<Embedding> r = delegate.embed(text);
        Embedding e = r.content();
        if (e == null) return r;
        float[] y = transform.apply(e.vector());
        return Response.from(Embedding.from(y), r.tokenUsage());
    }

    public Response<Embedding> embed(TextSegment segment) {
        Response<Embedding> r = delegate.embed(segment.text());
        Embedding e = r.content();
        if (e == null) return r;
        float[] y = transform.apply(e.vector());
        return Response.from(Embedding.from(y), r.tokenUsage());
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        // Keep index embeddings unchanged (backward-compatible)
        return delegate.embedAll(segments);
    }
}
