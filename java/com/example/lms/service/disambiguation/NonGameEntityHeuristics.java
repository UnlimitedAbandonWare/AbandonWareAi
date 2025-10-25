package com.example.lms.service.disambiguation;

import java.util.Set;



public final class NonGameEntityHeuristics {
    private static final Set<String> NON_GAME_PROPER_NOUNS = Set.of(
            "에스코피에", "에스코피", "escoffier", "auguste escoffier",
            // 교육/일반 도메인 키워드: 학원/국비/국비지원을 포함하여, 게임 도메인과 함께 등장할 경우
            // 비도메인 쿼리로 취급하게 한다.
            "학원", "국비", "국비지원", "교육", "훈련"
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