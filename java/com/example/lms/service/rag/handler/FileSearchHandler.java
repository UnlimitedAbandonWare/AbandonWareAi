package com.example.lms.service.rag.handler;

import com.example.lms.application.port.out.FileSearchPort;
import com.example.lms.telemetry.SseEventPublisher;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

/**
 * Retrieval handler that extracts snippets from uploaded files.  This
 * handler delegates to a {@link FileSearchPort} to perform the actual
 * search.  Any exceptions are swallowed to preserve the fail‑soft nature
 * of the retrieval chain.  When snippets are found they are appended to
 * the accumulator and an SSE trace event is emitted for each snippet.
 */
@Slf4j
@RequiredArgsConstructor
public class FileSearchHandler extends AbstractRetrievalHandler {
    private final FileSearchPort fileSearchPort;
    private final SseEventPublisher sse;
    private final int topK = 5;

    @Override
    protected boolean doHandle(Query query, List<Content> accumulator) {
        if (query == null) {
            return true;
        }
        // Attempt to extract uploaded file URLs from the query metadata map.
        List<String> urls = null;
        try {
            Object meta = query.metadata();
            if (meta != null) {
                java.util.Map<String, Object> map;
                try {
                    java.lang.reflect.Method m = meta.getClass().getMethod("asMap");
                    map = (java.util.Map<String, Object>) m.invoke(meta);
                } catch (NoSuchMethodException e) {
                    try {
                        java.lang.reflect.Method m2 = meta.getClass().getMethod("map");
                        map = (java.util.Map<String, Object>) m2.invoke(meta);
                    } catch (Exception ex) {
                        map = java.util.Map.of();
                    }
                }
                Object list = map.get("uploadedFileUrls");
                if (list instanceof List) {
                    urls = (List<String>) list;
                }
            }
        } catch (Exception e) {
            // ignore metadata parsing errors
        }
        if (urls == null || urls.isEmpty()) {
            return true;
        }
        try {
            List<String> snippets = fileSearchPort.searchFiles(query.text(), urls, topK);
            if (snippets != null && !snippets.isEmpty()) {
                for (String snippet : snippets) {
                    accumulator.add(Content.from(snippet));
                    // emit trace SSE
                    try {
                        sse.emit("filesearch.trace",
                                new SseEventPublisher.Payload()
                                        .kv("snippet", snippet)
                                        .build());
                    } catch (Exception ignored) {
                        // SSE failures are non-critical
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[FileSearch] {}", e.toString());
        }
        // Continue to next handler regardless of outcome
        return true;
    }
}