package com.example.lms.service.rag.retriever;

import com.abandonware.ai.addons.synthesis.ContextItem;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter that exposes the Addons OCR retriever as a LangChain4j {@link ContentRetriever}.
 *
 * <p>
 * The underlying implementation is provided by {@code com.abandonware.ai.addons.ocr.OcrRetriever}
 * (auto-configured when AddonsAutoConfiguration is on the classpath). This adapter converts
 * {@link ContextItem} results into {@link Content} instances with metadata so downstream pipelines
 * can dedupe/trace as needed.
 * </p>
 *
 * <p>
 * This is a best-effort auxiliary axis: it is disabled by default and always fail-soft.
 * </p>
 */
@Component
public class OcrRetriever implements ContentRetriever {

    private final ObjectProvider<com.abandonware.ai.addons.ocr.OcrRetriever> delegateProvider;

    private final boolean enabled;
    private final int topK;
    private final int maxChars;

    public OcrRetriever(
            ObjectProvider<com.abandonware.ai.addons.ocr.OcrRetriever> delegateProvider,
            @Value("${rag.ocr.enabled:false}") boolean enabled,
            @Value("${rag.ocr.topK:6}") int topK,
            @Value("${rag.ocr.maxChars:1200}") int maxChars
    ) {
        this.delegateProvider = delegateProvider;
        this.enabled = enabled;
        this.topK = Math.max(0, topK);
        // Keep at least a reasonable bound even when configured too low.
        this.maxChars = Math.max(200, maxChars);
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (!enabled) {
            return List.of();
        }

        com.abandonware.ai.addons.ocr.OcrRetriever delegate =
                (delegateProvider == null) ? null : delegateProvider.getIfAvailable();
        if (delegate == null) {
            TraceStore.append("ocr.skip", "no_delegate");
            return List.of();
        }

        String q = (query == null) ? null : query.text();
        if (q == null || q.isBlank()) {
            return List.of();
        }

        try {
            List<ContextItem> items = delegate.retrieve(q);
            if (items == null || items.isEmpty()) {
                TraceStore.put("ocr.hits", 0);
                return List.of();
            }

            int limit = (topK <= 0) ? items.size() : Math.min(topK, items.size());
            List<Content> out = new ArrayList<>(limit);

            for (int i = 0; i < items.size() && out.size() < limit; i++) {
                ContextItem item = items.get(i);
                if (item == null) continue;

                String snippet = firstNonBlank(item.snippet(), item.title());
                if (snippet.isBlank()) continue;

                String text = clamp(snippet, maxChars);

                Map<String, Object> meta = new HashMap<>();
                meta.put("source", "ocr");
                meta.put("ocr_id", item.id());
                meta.put("ocr_title", item.title());
                meta.put("ocr_source", item.source());
                meta.put("ocr_rank", item.rank());
                meta.put("ocr_score", item.score());

                // Best-effort URL passthrough (if the upstream provides it via meta)
                try {
                    if (item.meta() != null && item.meta().get("url") != null) {
                        meta.put("url", String.valueOf(item.meta().get("url")));
                    }
                } catch (Exception ignore) {
                }

                out.add(Content.from(TextSegment.from(text, Metadata.from(meta))));
            }

            TraceStore.put("ocr.hits", out.size());
            return out;
        } catch (Exception e) {
            // OCR is auxiliary: never break the whole retrieval chain.
            TraceStore.append("ocr.fail", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static String firstNonBlank(String a, String b) {
        String x = safeTrim(a);
        if (!x.isBlank()) return x;
        return safeTrim(b);
    }

    private static String safeTrim(String s) {
        return (s == null) ? "" : s.trim();
    }

    private static String clamp(String s, int maxChars) {
        String t = safeTrim(s);
        if (t.length() <= maxChars) return t;
        return t.substring(0, Math.max(0, maxChars)) + "…";
    }
}
