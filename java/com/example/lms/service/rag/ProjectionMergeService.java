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

    @Value("${projection.merge.keep-free-side-notes:true}")
    private boolean keepFreeSideNotes;

    @Value("${projection.merge.free-header:### (실험적 아이디어 · 비공식)}")
    private String freeHeader;

    public String mergeDualView(String grounded, String creative) {
        return merge(grounded, creative, Map.of());
    }

    public String merge(String grounded, String creative, Map<String, Object> config) {
        String g = (grounded == null) ? "" : grounded.trim();
        String c = (creative == null) ? "" : creative.trim();

        if (g.isBlank() && c.isBlank())
            return "";
        if (c.isBlank())
            return g;
        if (g.isBlank())
            return c;

        boolean keep = resolveBool(config.get("keep-free-side-notes"), keepFreeSideNotes);
        String header = resolveString(config.get("free-header"), freeHeader);
        if (!keep)
            return g;

        return g + "\n\n---\n" + header + "\n" + c;
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
