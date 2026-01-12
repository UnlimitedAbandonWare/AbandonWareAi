package com.example.lms.service.service.rag.fusion;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Merge adapter that deduplicates multi-branch results (e.g., from Self-Ask sub-queries).
 * Canonical key order: metadata.source -> metadata.url -> metadata.id -> text prefix.
 */
public class MultiQueryMergeAdapter {

    public static class Content {
        public String id;
        public String title;
        public String snippet;
        public String source;
        public String url;
        public Double score;

        public Content() {}
    }

    private String canonicalKey(Content c) {
        if (c == null) return "";
        if (c.source != null && !c.source.isEmpty()) return "S::" + c.source;
        if (c.url != null && !c.url.isEmpty())     return "U::" + c.url;
        if (c.id != null && !c.id.isEmpty())       return "I::" + c.id;
        String prefix = c.snippet != null ? c.snippet : (c.title != null ? c.title : "");
        prefix = prefix.length() > 80 ? prefix.substring(0,80) : prefix;
        return "T::" + prefix;
    }

    public List<Content> merge(List<List<Content>> branches) {
        if (branches == null || branches.isEmpty()) return Collections.emptyList();
        Map<String, Content> map = new LinkedHashMap<>();
        for (List<Content> b : branches) {
            if (b == null) continue;
            for (Content c : b) {
                String key = canonicalKey(c);
                map.merge(key, c, (a,b2) -> maxScore(a,b2));
            }
        }
        return new ArrayList<>(map.values());
    }

    private Content maxScore(Content a, Content b) {
        if (a == null) return b;
        if (b == null) return a;
        double sa = a.score != null ? a.score : 0.0;
        double sb = b.score != null ? b.score : 0.0;
        return sb > sa ? b : a;
    }
}