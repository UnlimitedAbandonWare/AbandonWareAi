package com.example.lms.service.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import com.example.lms.llm.QueryTransform;
import java.util.List;

/**
 * Minimal adapter that delegates all calls to a base {@link EmbeddingModel}
 * while retaining a second constructor argument for a query transform.  This
 * class exists solely to satisfy the build when the expected V2 preprocessing
 * wrapper is not present.  All embedding operations are forwarded to the
 * underlying base model.
 */
public final class PreprocessedEmbeddingModelV2 implements EmbeddingModel {
    private final EmbeddingModel base;
        @SuppressWarnings("unused")
    private final QueryTransform queryTransform; // placeholder for future use

    public PreprocessedEmbeddingModelV2(EmbeddingModel base, QueryTransform qt) {
        this.base = base;
        this.queryTransform = qt;
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return base.embed(segment);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return base.embedAll(segments);
    }
}