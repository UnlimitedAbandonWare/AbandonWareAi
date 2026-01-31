package com.example.lms.vector;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Simple heuristic-based classifier for determining a coarse topic from a
 * user query. This implementation recognises a handful of Genshin Impact
 * keywords and returns either {@code genshin} or {@code default}. The
 * classification is case-insensitive. In the absence of a match the
 * default topic is returned.
 */
@Component
public class TopicClassifier {
    private static final Set<String> GENSHIN_KEYWORDS = Set.of(
            "원신","genshin","성유물","티바트","나히다","라이덴","파티","원소","유라","감우"
    );

    public String classify(String query) {
        if (query == null || query.isBlank()) return "default";
        String q = query.toLowerCase(Locale.ROOT);
        for (String k : GENSHIN_KEYWORDS) {
            if (q.contains(k.toLowerCase(Locale.ROOT))) return "genshin";
        }
        return "default";
    }
}