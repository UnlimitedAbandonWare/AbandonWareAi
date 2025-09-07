package com.example.lms.vector;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Simple heuristic-based classifier for determining a coarse topic from a
 * user query.  This implementation recognises a handful of Genshin Impact
 * keywords and returns either {@code genshin} or {@code default}.  The
 * classification is case-insensitive.  In the absence of a match the
 * default topic is returned.
 */
@Component
public class TopicClassifier {
    private static final Set<String> GENSHIN_KEYS = Set.of(
            "원신", "genshin", "hoyoverse", "hoyolab",
            "마비카", "스커크", "푸리나", "나탈란",
            "심연", "나선", "도도코", "티바트"
    );

    /**
     * Classify the given query string into a topic.  Returns
     * {@code genshin} when any of the Genshin keywords appear in the
     * query, otherwise returns {@code default}.
     */
    public String classify(String query) {
        if (query == null || query.isBlank()) {
            return "default";
        }
        String q = query.toLowerCase();
        for (String k : GENSHIN_KEYS) {
            if (q.contains(k.toLowerCase())) {
                return "genshin";
            }
        }
        return "default";
    }
}