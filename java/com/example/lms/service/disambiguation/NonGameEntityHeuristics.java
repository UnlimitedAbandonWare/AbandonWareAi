package com.example.lms.service.disambiguation;

import java.util.Set;

public final class NonGameEntityHeuristics {
    private static final Set<String> NON_GAME_PROPER_NOUNS = Set.of(
            "에스코피에", "에스코피", "escoffier", "auguste escoffier"
    );

    private NonGameEntityHeuristics() {}

    /** 예: '원신'과 비도메인 고유명사가 동시에 있으면 true */
    public static boolean containsForbiddenPair(String query) {
        if (query == null || query.isBlank()) return false;
        String q = query.toLowerCase();
        boolean mentionsGenshin = q.contains("원신") || q.contains("genshin");
        if (!mentionsGenshin) return false;
        for (String n : NON_GAME_PROPER_NOUNS) {
            if (q.contains(n)) return true;
        }
        return false;
    }
}
