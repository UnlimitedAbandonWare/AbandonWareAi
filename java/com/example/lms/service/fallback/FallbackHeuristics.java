package com.example.lms.service.fallback;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 간단 휴리스틱:
 * - 질의에서 게임/도메인(예: 원신)과 비도메인 문제어(예: 에스코피에)를 함께 감지
 * - 상황별 대안 후보를 몇 개 제안
 *
 * <p>LLM/RAG 기반 처리가 우선이며, 이 클래스는
 * 아주 명백한 오용 패턴이 보일 때만 보조 신호를 제공합니다.</p>
 */
public final class FallbackHeuristics {

    // 게임 도메인 마커 (필요 시 확장 가능)
    private static final Set<String> GENSHIN_MARKERS = Set.of(
            "원신", "genshin", "genshin impact"
    );
    private static final Set<String> STAR_RAIL_MARKERS = Set.of(
            "스타레일", "스타 레일", "star rail", "honkai star rail"
    );

    // 비도메인(게임 외) 문제어 예시 - 필요 시 확장
    private static final Set<String> NON_GAME_TERMS = Set.of(
            "에스코피에", "에스코피", "escoffier", "auguste escoffier"
    );

    private FallbackHeuristics() {
    }

    /**
     * 게임 질의와 비게임 고유명사가 함께 등장하는 "굉장히 확실한" 경우에만
     * Detection 을 반환합니다.
     *
     * <p>그 외 애매한 경우에는 null 을 반환하여, RAG/LLM 파이프라인이
     * 자유롭게 판단하도록 둡니다.</p>
     */
    public static Detection detect(String query) {
        // Legacy dictionary-based guardrail has been disabled.
        // Semantic risk is now evaluated by the model-based Guard/RiskScorer.
        return null;
    }

    public static java.util.List<String> suggestAlternatives(String domain, String wrongTerm) {
        if (!StringUtils.hasText(domain) || !StringUtils.hasText(wrongTerm)) {
            return java.util.List.of();
        }
        // TODO: 도메인별 추천 후보를 외부 설정/지식베이스로 이전
        return java.util.List.of();
    }

    public record Detection(String domain, String wrongTerm) {
    }
}
