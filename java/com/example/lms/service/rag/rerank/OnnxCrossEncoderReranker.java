package com.example.lms.service.rag.rerank;

import com.example.lms.service.onnx.OnnxRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Cross‑encoder reranker backed by a local ONNX runtime.  When enabled via
 * {@code abandonware.reranker.backend=onnx-runtime} this bean will rerank
 * arbitrary scored documents using a weighted combination of their existing
 * score and the ONNX model's similarity output.  When the model is not
 * loaded the input list is returned unchanged.
 */
@Component("onnxCrossEncoderReranker")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "abandonware.reranker", name = "backend", havingValue = "__never__")
public class OnnxCrossEncoderReranker implements CrossEncoderReranker {
    private final OnnxRuntimeService onnx;

    /**
     * Rerank a list of content items using the ONNX cross‑encoder model.  When
     * the model is not active the input list is returned unchanged.  Scores
     * are computed by pairing the query with each candidate's textual
     * representation via {@link OnnxRuntimeService#scorePair(String, String)}.
     * The candidates are sorted in descending order of the model scores and
     * truncated to the requested {@code topN} count.
     */
    @Override
    public java.util.List<dev.langchain4j.rag.content.Content> rerank(String query,
                                                                     java.util.List<dev.langchain4j.rag.content.Content> candidates,
                                                                     int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return java.util.List.of();
        }
        if (!onnx.active()) {
            // When no model is loaded return the topN slice of the original list.
            int n = Math.max(1, Math.min(topN, candidates.size()));
            return new java.util.ArrayList<>(candidates.subList(0, n));
        }
        int n = candidates.size();
        int k = Math.max(1, Math.min(topN, n));
        // Compute scores for each candidate.
        java.util.List<dev.langchain4j.rag.content.Content> snapshot = new java.util.ArrayList<>(candidates);
        java.util.List<java.util.Map.Entry<dev.langchain4j.rag.content.Content, Double>> scored = new java.util.ArrayList<>(n);
        for (dev.langchain4j.rag.content.Content c : snapshot) {
            String text;
            try {
                var seg = c.textSegment();
                text = (seg != null && seg.text() != null) ? seg.text() : c.toString();
            } catch (Exception e) {
                text = c.toString();
            }
            double score = onnx.scorePair(query != null ? query : "", text != null ? text : "");
            scored.add(new java.util.AbstractMap.SimpleEntry<>(c, score));
        }
        scored.sort(java.util.Map.Entry.<dev.langchain4j.rag.content.Content, Double>comparingByValue().reversed());
        java.util.List<dev.langchain4j.rag.content.Content> result = new java.util.ArrayList<>(k);
        for (int i = 0; i < k && i < scored.size(); i++) {
            result.add(scored.get(i).getKey());
        }
        return result;
    }

    @Override
    public java.util.List<dev.langchain4j.rag.content.Content> rerank(String query,
                                                                     java.util.List<dev.langchain4j.rag.content.Content> candidates) {
        return rerank(query, candidates, candidates == null ? 0 : candidates.size());
    }


    public RerankerStatus status() {
        return new RerankerStatus(onnx.active(), "onnx-runtime", onnx.active() ? "model loaded" : "model not loaded");
    }
}