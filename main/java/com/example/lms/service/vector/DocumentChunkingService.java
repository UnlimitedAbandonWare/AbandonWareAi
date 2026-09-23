package com.example.lms.service.vector;

import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentChunkingService {
    private static final Logger log = LoggerFactory.getLogger(DocumentChunkingService.class);

    @Value("${vector.ingest.chunk-overlap.enabled:true}")
    private boolean enabled;

    @Value("${vector.ingest.chunk-overlap.chunk-size-chars:1000}")
    private int chunkSizeChars;

    @Value("${vector.ingest.chunk-overlap.overlap-chars:120}")
    private int overlapChars;

    @Value("${vector.ingest.chunk-overlap.min-split-chars:1400}")
    private int minSplitChars;

    public record Chunk(String text, Map<String, Object> metadata) {
    }

    public List<Chunk> split(String text, Map<String, Object> baseMeta) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        Map<String, Object> base = baseMeta == null ? new LinkedHashMap<>() : new LinkedHashMap<>(baseMeta);
        int chunkSize = Math.max(128, chunkSizeChars);
        int overlap = Math.max(0, Math.min(overlapChars, chunkSize - 1));
        // The configured chunk size is a hard ceiling. A larger legacy
        // min-split threshold must never permit an oversized single chunk.
        if (!enabled || text.length() <= chunkSize) {
            Map<String, Object> md = new LinkedHashMap<>(base);
            md.putIfAbsent(VectorMetaKeys.META_CHUNK_COUNT, 1);
            md.putIfAbsent(VectorMetaKeys.META_CHUNK_INDEX, 0);
            md.putIfAbsent(VectorMetaKeys.META_CHUNK_OVERLAP, 0);
            return List.of(new Chunk(text, md));
        }

        Object parentId = base.get(VectorMetaKeys.META_DOC_ID);
        if (parentId == null || !StringUtils.hasText(String.valueOf(parentId))) {
            parentId = base.get(VectorMetaKeys.META_ORIGINAL_ID);
        }
        String parentDocId = parentId != null && StringUtils.hasText(String.valueOf(parentId))
                ? String.valueOf(parentId) : "doc-" + DigestUtils.sha256Hex(text);
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(text.length(), start + chunkSize);
            int end = semanticEnd(text, start, hardEnd, chunkSize);
            if (end > start && end < text.length()
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            pieces.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            int nextStart = Math.max(start + 1, end - overlap);
            if (nextStart > 0 && nextStart < text.length()
                    && Character.isHighSurrogate(text.charAt(nextStart - 1))
                    && Character.isLowSurrogate(text.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart;
        }

        List<Chunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            Map<String, Object> md = new LinkedHashMap<>(base);
            md.put(VectorMetaKeys.META_PARENT_DOC_ID, parentDocId);
            md.put(VectorMetaKeys.META_DOC_ID, parentDocId);
            md.put(VectorMetaKeys.META_CHUNK_ID, parentDocId + "#" + i);
            md.put(VectorMetaKeys.META_CHUNK_INDEX, i);
            md.put(VectorMetaKeys.META_CHUNK_COUNT, pieces.size());
            md.put(VectorMetaKeys.META_CHUNK_OVERLAP, overlap);
            chunks.add(new Chunk(pieces.get(i), md));
        }
        TraceStore.put("rag.chunk.count", chunks.size());
        TraceStore.put("rag.chunk.overlap", overlap);
        log.debug("[VectorIngest][chunk] enabled={}, parentHash={}, chunks={}, size={}, overlap={}",
                enabled, DigestUtils.sha256Hex(parentDocId.getBytes(StandardCharsets.UTF_8)).substring(0, 12),
                chunks.size(), chunkSize, overlap);
        return chunks;
    }

    private static int semanticEnd(String text, int start, int hardEnd, int chunkSize) {
        if (text == null || hardEnd >= text.length()) {
            return hardEnd;
        }
        int minEnd = Math.min(hardEnd, start + Math.max(128, (int) (chunkSize * 0.55d)));
        int best = -1;

        for (int i = hardEnd - 1; i >= minEnd; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                best = i + 1;
                if (i > start && text.charAt(i - 1) == '\n') {
                    return best;
                }
                break;
            }
            if (isSentenceBoundary(c) && hasBoundaryAfter(text, i)) {
                best = i + 1;
                break;
            }
        }
        return best > start ? best : hardEnd;
    }

    private static boolean isSentenceBoundary(char c) {
        return c == '.' || c == '!' || c == '?' || c == ';'
                || c == '。' || c == '！' || c == '？'
                || c == '다' || c == '요';
    }

    private static boolean hasBoundaryAfter(String text, int idx) {
        if (idx + 1 >= text.length()) {
            return true;
        }
        char next = text.charAt(idx + 1);
        return Character.isWhitespace(next)
                || next == '"' || next == '\'' || next == ')' || next == ']' || next == '}';
    }
}
