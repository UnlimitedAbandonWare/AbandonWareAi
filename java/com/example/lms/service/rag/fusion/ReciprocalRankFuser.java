package com.example.lms.service.rag.fusion;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.data.segment.TextSegment;
import java.util.*;

/** Reciprocal Rank Fusion (k=60) with stable dedupe. */
public class ReciprocalRankFuser {
    private final int k;
    public ReciprocalRankFuser() { this(60); }
    public ReciprocalRankFuser(int k) { this.k = Math.max(1, k); }

    public List<Content> fuse(List<List<Content>> lists, int topK) {
        if (lists == null || lists.isEmpty()) return List.of();

        Map<String, Double> scores = new HashMap<>();
        Map<String, Content> first = new LinkedHashMap<>();

        for (List<Content> list : lists) {
            if (list == null) continue;
            int rank = 0;
            for (Content c : list) {
                if (c == null) continue;
                String key = keyOf(c);
                rank++;
                double add = 1.0 / (k + rank);
                scores.merge(key, add, Double::sum);
                first.putIfAbsent(key, c);
            }
        }

        return scores.entrySet().stream()
                .sorted((a,b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, topK))
                .map(e -> first.get(e.getKey()))
                .toList();
    }

    private static String keyOf(Content c) {
        String t = Optional.ofNullable(c.textSegment())
                .map(TextSegment::text)
                .orElseGet(c::toString);
        // 간단한 안정 키: 줄바꿈/공백 축약 + 해시
        String norm = t == null ? "" : t.replaceAll("\\s+", " ").trim();
        return Integer.toHexString(norm.hashCode());
    }
}