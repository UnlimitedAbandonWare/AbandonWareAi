package com.example.lms.service.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Projection Merge (투영 합성)
 *
 * grounded(근거 기반) 답변과 creative(자유 아이디어) 답변을 합성합니다.
 */
@Service
public class ProjectionMergeService {
    private static final String CANONICAL_HEADER = "### (실험적 아이디어 · 비공식)";
    private static final String LEGACY_HEADER = "### 異붿륫/鍮꾧났???꾩씠?붿뼱";

    @Value("${projection.merge.keep-free-side-notes:true}")
    private boolean keepFreeSideNotes = true;

    @Value("${projection.merge.free-header:### (실험적 아이디어 · 비공식)}")
    private String freeHeader = "### (실험적 아이디어 · 비공식)";

    public String mergeDualView(String grounded, String creative) {
        return merge(grounded, creative, Map.of());
    }

    public String merge(String grounded, String creative, Map<String, Object> config) {
        String g = (grounded == null) ? "" : grounded.trim();
        String c = (creative == null) ? "" : creative.trim();
        Map<String, Object> effectiveConfig = config == null ? Map.of() : config;

        if (g.isBlank() && c.isBlank())
            return "";
        if (c.isBlank())
            return g;
        if (g.isBlank())
            return c;

        boolean keep = resolveBool(effectiveConfig.get("keep-free-side-notes"), keepFreeSideNotes);
        String header = resolveString(effectiveConfig.get("free-header"), resolveString(freeHeader, CANONICAL_HEADER));
        if (!keep)
            return g;

        c = creativeBody(c, header);
        if (c.isBlank()) return g;
        String block = "\n\n---\n" + header + "\n" + c;
        return g.endsWith(block) ? g : g + block;
    }

    /** Only the application-owned trailing section may be normalized after final polish. */
    public String normalizeMergedView(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        String header = resolveString(freeHeader, CANONICAL_HEADER);
        String marker = "\n\n---\n" + header + "\n";
        int start = answer.lastIndexOf(marker);
        if (start < 0) return answer;
        return answer.substring(0, start + marker.length())
                + creativeBody(answer.substring(start + marker.length()), header);
    }

    private static String creativeBody(String creative, String header) {
        String body = creative.strip();
        while (!body.isEmpty()) {
            int newline = body.indexOf('\n');
            String first = (newline < 0 ? body : body.substring(0, newline)).strip();
            if (!first.equals(header) && !first.equals(CANONICAL_HEADER) && !first.equals(LEGACY_HEADER)) break;
            body = newline < 0 ? "" : body.substring(newline + 1).stripLeading();
        }
        return body;
    }

    private static boolean resolveBool(Object v, boolean fallback) {
        if (v == null)
            return fallback;
        if (v instanceof Boolean b)
            return b;
        String s = String.valueOf(v).trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    private static String resolveString(Object v, String fallback) {
        if (v == null)
            return fallback;
        String s = String.valueOf(v).trim();
        return s.isBlank() ? fallback : s;
    }
}
