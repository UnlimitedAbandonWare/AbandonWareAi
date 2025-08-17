package com.example.lms.service.onnx;

import com.example.lms.service.rag.rerank.CrossEncoderReranker;
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
@RequiredArgsConstructor
public class OnnxCrossEncoderReranker implements CrossEncoderReranker {

    private final OnnxRuntimeService onnx;

    /*
     * ---------------------------- Internal API (AbandonWare) ----------------------------
     */

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        int n = candidates.size();
        int k = Math.max(1, Math.min(topN, n));
        // Extract plain text from each candidate
        String[] docs = new String[n];
        for (int i = 0; i < n; i++) {
            Content c = candidates.get(i);
            String text;
            if (c.textSegment() != null) {
                text = c.textSegment().text();
            } else {
                text = String.valueOf(c);
            }
            docs[i] = (text == null ? "" : text);
        }
        float[][] scores = onnx.predict(new String[]{query}, docs);
        float[] row = scores.length > 0 ? scores[0] : new float[n];
        List<Integer> indices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) indices.add(i);
        indices.sort((i1, i2) -> Float.compare(row[i2], row[i1]));
        List<Content> result = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            result.add(candidates.get(indices.get(i)));
        }
        return result;
    }

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
        int k = Math.max(1, Math.min(topK, n));
        // Build a list of document texts
        String[] docs = new String[n];
        for (int i = 0; i < n; i++) {
            docs[i] = extractText(documents.get(i));
        }
        float[][] scores = onnx.predict(new String[]{query}, docs);
        float[] row = scores.length > 0 ? scores[0] : new float[n];
        List<Integer> indices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) indices.add(i);
        indices.sort((i1, i2) -> Float.compare(row[i2], row[i1]));
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