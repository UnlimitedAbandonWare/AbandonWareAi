package com.example.lms.service.fallback;

import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Locale;
import java.util.Set;




/**
 * 간단 휴리스틱:
 * - 질의에서 게임/도메인(예: 원신)과 비도메인 문제어(예: 에스코피에)를 함께 감지
 * - 상황별 대안 후보를 몇 개 제안
 */
public final class FallbackHeuristics {

    private FallbackHeuristics() {}

    private static final Set<String> GENSHIN_MARKERS = Set.of("원신", "genshin");
    private static final Set<String> STAR_RAIL_MARKERS = Set.of("붕괴 스타레일", "star rail", "honkai star rail");

    // 비도메인(게임 외) 문제어 예시 - 필요 시 확장
    private static final Set<String> NON_GAME_TERMS = Set.of(
            "에스코피에", "에스코피", "escoffier", "auguste escoffier"
    );

    public static Detection detect(String query) {
        if (!StringUtils.hasText(query)) return null;
        String q = query.toLowerCase(Locale.ROOT);

        String domain = null;
        if (containsAny(q, GENSHIN_MARKERS)) domain = "원신";
        else if (containsAny(q, STAR_RAIL_MARKERS)) domain = "붕괴 스타레일";
        if (domain == null) return null;

        for (String t : NON_GAME_TERMS) {
            if (q.contains(t.toLowerCase(Locale.ROOT))) {
                return new Detection(domain, normalizeSurface(query, t));
            }
        }
        return null;
    }

    /** 도메인별 ‘가능한 후보’ 추천 (짧고 무해한 제안 위주) */
    public static List<String> suggestAlternatives(String domain, String wrongTerm) {
        if ("원신".equals(domain)) {
            // ‘에스코피에’ ↔ 요리 연상 → 요리 콘셉트/최근 회자 캐릭터 위주로 2~3개
            if (wrongTerm != null && wrongTerm.contains("에스코피")) {
                return List.of("향릉(요리 콘셉트)", "클로린드(최근 캐릭터)");
            }
            return List.of("클로린드", "향릉");
        }
        if ("붕괴 스타레일".equals(domain)) {
            return List.of("단항", "연경"); // 가벼운 예시
        }
        return List.of();
    }

    private static boolean containsAny(String q, Set<String> words) {
        for (String w : words) if (q.contains(w.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** 원문 표면형 유지에 가깝게 반환 */
    private static String normalizeSurface(String original, String tokenLowerOrMixed) {
        int idx = original.toLowerCase(Locale.ROOT).indexOf(tokenLowerOrMixed.toLowerCase(Locale.ROOT));
        return (idx >= 0) ? original.substring(idx, Math.min(original.length(), idx + tokenLowerOrMixed.length())) : tokenLowerOrMixed;
    }

    public record Detection(String domain, String wrongTerm) {}
}