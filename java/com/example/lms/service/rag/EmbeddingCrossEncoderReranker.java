package com.example.lms.service.rag.rerank;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component("embeddingCrossEncoderReranker") // ✅ ChatService @Qualifier 와 이름 매칭
@Primary
@RequiredArgsConstructor
public class EmbeddingCrossEncoderReranker implements CrossEncoderReranker {

    private final EmbeddingModel embeddingModel;

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int limit) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        final int n = candidates.size();
        final int k = (limit <= 0) ? n : Math.min(limit, n);

        try {
            final float[] qv = embeddingModel.embed(query).content().vector();

            final List<Content> snapshot = new ArrayList<>(candidates);
            final List<TextSegment> segments = snapshot.stream()
                    .map(c -> Optional.ofNullable(c)
                            .map(Content::textSegment)
                            .orElseGet(() -> TextSegment.from(String.valueOf(c))))
                    .collect(Collectors.toList());

            long t0 = System.nanoTime();
            Response<List<Embedding>> resp = embeddingModel.embedAll(segments);
            long embedMs = (System.nanoTime() - t0) / 1_000_000L;

            List<Embedding> docVecs = (resp != null ? resp.content() : null);
            if (docVecs == null || docVecs.size() != n) {
                log.warn("embedAll() size mismatch. got={}, expected={}. fallback=original(top {})",
                        (docVecs == null ? 0 : docVecs.size()), n, k);
                return new ArrayList<>(snapshot.subList(0, k));
            }

            record Scored(int idx, Content c, double s) {}
            List<Scored> scored = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                scored.add(new Scored(i, snapshot.get(i), cosine(qv, docVecs.get(i).vector())));
            }

            long s0 = System.nanoTime();
            scored.sort((a, b) -> {
                int byScore = Double.compare(b.s(), a.s());
                return (byScore != 0) ? byScore : Integer.compare(a.idx(), b.idx());
            });
            long sortMs = (System.nanoTime() - s0) / 1_000_000L;

            if (log.isDebugEnabled()) {
                log.debug("[Rerank] q='{}' embed.ms={} sort.ms={} topN/total={}/{}",
                        abbreviate(query, 120), embedMs, sortMs, k, n);
            }

            return scored.stream().limit(k).map(Scored::c).collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Embedding rerank failed. query='{}', reason={}", abbreviate(query, 200), e.getMessage());
            return new ArrayList<>(candidates.subList(0, Math.min(k, candidates.size())));
        }
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        double den = Math.sqrt(na) * Math.sqrt(nb);
        return (den == 0) ? 0.0 : dot / (den + 1e-9);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return (s.length() <= max) ? s : (s.substring(0, Math.max(0, max - 1)) + "…");
    }
}
