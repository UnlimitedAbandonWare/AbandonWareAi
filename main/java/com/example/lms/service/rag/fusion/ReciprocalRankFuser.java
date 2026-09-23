package com.example.lms.service.rag.fusion;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ReciprocalRankFuser {
    private final int k;

    public ReciprocalRankFuser() {
        this(60);
    }

    public ReciprocalRankFuser(int k) {
        this.k = Math.max(1, k);
    }

    public List<Content> fuse(List<List<Content>> sourceLists, int topK) {
        if (sourceLists == null || sourceLists.isEmpty()) {
            return List.of();
        }
        Map<String, Double> scores = new HashMap<>();
        Map<String, Content> firstSeen = new LinkedHashMap<>();
        for (List<Content> source : sourceLists) {
            if (source == null) {
                continue;
            }
            int rank = 0;
            for (Content content : source) {
                if (content == null) {
                    continue;
                }
                rank++;
                String key = keyOf(content);
                firstSeen.putIfAbsent(key, content);
                scores.merge(key, 1.0d / ((double) k + rank), Double::sum);
            }
        }
        if (scores.isEmpty()) {
            return List.of();
        }
        int limit = topK <= 0 ? scores.size() : Math.min(topK, scores.size());
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        List<Content> out = new ArrayList<>(limit);
        for (Map.Entry<String, Double> entry : ranked.subList(0, limit)) {
            Content content = firstSeen.get(entry.getKey());
            if (content != null) {
                out.add(content);
            }
        }
        return out;
    }

    private static String keyOf(Content content) {
        String text = Optional.ofNullable(content.textSegment())
                .map(TextSegment::text)
                .orElseGet(content::toString);
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
