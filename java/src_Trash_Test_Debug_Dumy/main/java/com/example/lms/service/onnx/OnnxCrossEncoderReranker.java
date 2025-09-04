package com.example.lms.service.onnx;

import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.support.ContentCompat;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.rag.content.Content;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
/**
 * A cross‑encoder reranker backed by a local ONNX runtime.
 * Bean registration is handled centrally in RerankerConfig.
 */

public class OnnxCrossEncoderReranker implements CrossEncoderReranker {

    private final OnnxRuntimeService onnx;

    public OnnxCrossEncoderReranker(OnnxRuntimeService onnx) {
        this.onnx = onnx;
    }

    /*
     * ---------------------------- Internal API (AbandonWare) ----------------------------
     */

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        // Standardised rerank implementation using ContentCompat for robust text extraction
        if (candidates == null || candidates.isEmpty() || query == null || query.isBlank()) {
            return java.util.Collections.emptyList();
        }
        final int n = candidates.size();
        final int hardCap = 24;
        final int k = Math.max(1, Math.min(Math.min(topN, hardCap), n));
        final java.util.List<Scored> scored = new java.util.ArrayList<>(n);
        for (Content c : candidates) {
            String text = ContentCompat.textOf(c);
            double s = onnx.scorePair(query, text);
            scored.add(new Scored(c, s));
        }
        scored.sort(java.util.Comparator.<Scored>comparingDouble(x -> x.score).reversed());
        final java.util.List<Content> out = new java.util.ArrayList<>(k);
        for (int i = 0; i < k; i++) out.add(scored.get(i).content);
        return out;
    }

    /** Simple carrier for content and its associated score */
    private record Scored(Content content, double score) {}

    @Override
    public List<Content> rerank(String query, List<Content> candidates) {
        if (candidates == null) return Collections.emptyList();
        return rerank(query, candidates, candidates.size());
    }
    /*
     * ---------------------------- LangChain4j 호환 시그니처(선택적) ----------------------------
     * 외부에서 직접 호출할 수 있도록 유지하지만, 인터페이스 구현은 강제하지 않습니다.
     */


    public List<Document> rerank(List<Document> documents, String query) {
        return rerank(documents, query, documents == null ? 0 : documents.size());
    }


    public List<Document> rerank(List<Document> documents, String query, int topK) {
        if (documents == null || documents.isEmpty() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        int n = documents.size();
        // 2차 안전 컷(너무 큰 N 방지; 24~32 추천)
        int hardCap = 24;
        int k = Math.max(1, Math.min(Math.min(topK, hardCap), n));
        // Build a list of document texts
        String[] docs = new String[n];
        for (int i = 0; i < n; i++) {
            docs[i] = extractText(documents.get(i));
        }
        // Compute similarity score for each candidate using the scorePair API
        // Prefer batch scoring when available
var pairs = new java.util.ArrayList<org.apache.commons.lang3.tuple.Pair<String,String>>(n);
for (int i = 0; i < n; i++) pairs.add(org.apache.commons.lang3.tuple.Pair.of(query, docs[i]));
double[] row = onnx.scoreBatch(pairs);

        java.util.List<Integer> indices = new java.util.ArrayList<>(n);
for (int i = 0; i < n; i++) indices.add(i);
        indices.sort((i1, i2) -> Double.compare(row[i2], row[i1]));
        List<Document> result = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            result.add(documents.get(indices.get(i)));
        }
        return result;
    }

    /**
     * Extract textual content from an arbitrary Document instance. The LangChain4j
     * {@link Document} interface exposes a {@code text()} method. Should that
     * method be unavailable (e.g., when a proxy implementation is used), this
     * helper falls back to common conventions such as {@code getText()},
     * {@code getContent()} or the {@code toString()} representation.
     *
     * @param doc the document to convert to plain text
     * @return the extracted text, never {@code null}
     */
    private String extractText(@Nullable Object doc) {
        if (doc == null) {
            return "";
        }
        // Try known accessor methods via reflection
        for (String methodName : new String[]{"text", "getText", "getContent", "content"}) {
            try {
                Method m = doc.getClass().getMethod(methodName);
                Object res = m.invoke(doc);
                if (res instanceof String) {
                    return (String) res;
                }
            } catch (Exception ignored) {
                // ignore and try next
            }
        }
        return doc.toString();
    }
}