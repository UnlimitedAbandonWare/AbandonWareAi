package com.example.lms.service.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for {@link BiEncoderReranker}.  This test uses a simple
 * two-dimensional embedding model to verify that the reranker sorts
 * documents by cosine similarity and respects the requested topN cut.
 */
public class BiEncoderRerankerTest {

    /**
     * Construct a {@link Content} instance backed by a simple text segment.
     * The returned proxy implements the Content interface and returns the
     * provided text when {@code textSegment()} is invoked.  All other
     * methods return {@code null}.  This avoids a compile-time dependency on
     * concrete Content implementations.
     */
    private static Content makeContent(String text) {
        return (Content) Proxy.newProxyInstance(
                Content.class.getClassLoader(),
                new Class[]{Content.class},
                (proxy, method, args) -> {
                    if ("textSegment".equals(method.getName())) {
                        return TextSegment.from(text);
                    }
                    return null;
                }
        );
    }

    @Test
    public void testRerankTopN() {
        // Define a trivial embedding model that maps specific strings to fixed vectors.
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                return embed(TextSegment.from(text == null ? null : text));
            }

            @Override
            public Response<Embedding> embed(TextSegment segment) {
                String t = (segment == null || segment.text() == null) ? null : segment.text();
                float[] vec;
                if ("query".equals(t)) {
                    vec = new float[]{1f, 0f};
                } else if ("doc1".equals(t)) {
                    vec = new float[]{1f, 0f};
                } else if ("doc2".equals(t)) {
                    vec = new float[]{0f, 1f};
                } else if ("doc3".equals(t)) {
                    vec = new float[]{0.5f, 0.5f};
                } else {
                    vec = new float[]{0f, 0f};
                }
                return Response.from(Embedding.from(vec));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                List<Embedding> list = new ArrayList<>();
                if (segments != null) {
                    for (TextSegment seg : segments) {
                        list.add(embed(seg).content());
                    }
                }
                return Response.from(list);
            }
        };
        // Instantiate the reranker with minimal dependencies (nulls for unused services)
        BiEncoderReranker reranker = new BiEncoderReranker(model, null, null, null, null, null, null, null);
        // Provide candidates in an arbitrary order
        List<Content> candidates = new ArrayList<>();
        candidates.add(makeContent("doc2"));
        candidates.add(makeContent("doc1"));
        candidates.add(makeContent("doc3"));
        // Request the top 2 results
        List<Content> result = reranker.rerank("query", candidates, 2);
        assertEquals(2, result.size(), "Expected exactly two items returned when topN=2");
        String firstText = result.get(0).textSegment().text();
        String secondText = result.get(1).textSegment().text();
        assertEquals("doc1", firstText, "Highest similarity document should appear first");
        assertEquals("doc3", secondText, "Second highest similarity document should appear second");
    }
}