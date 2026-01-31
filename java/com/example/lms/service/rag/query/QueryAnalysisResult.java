package com.example.lms.service.rag.query;

import java.util.List;
import java.util.Collections;

/**
 * LLM 쿼리 분석 결과를 담는 불변 DTO
 * 
 * <p>
 * 사용자 쿼리에서 추출된 의도(Intent), 핵심 엔티티, 확장 키워드 등을 포함합니다.
 * 이 정보는 ContextOrchestrator와 EvidenceAwareGuard에서 동적 Threshold 계산 및
 * 전탐사 모드 활성화 여부 결정에 사용됩니다.
 */

public record QueryAnalysisResult(
        String originalQuery,
        QueryIntent intent,
        List<String> entities,
        List<String> expandedKeywords,
        boolean wantsFresh,
        boolean isExploration,
        List<String> searchQueries,
        double confidenceScore,
        String expectedDomain,
        List<String> contextHints,
        List<String> noiseDomains) {

    /**
     * 쿼리 의도를 나타내는 Enum.
     */
    public enum QueryIntent {
        SEARCH,
        INFO,
        COMPARE,
        TRENDING,
        GENERAL
    }

    /**
     * Compact constructor.
     * <p>
     * - null 리스트를 방어적으로 빈 리스트로 치환합니다.
     * - intent 가 null 인 경우 GENERAL 로 기본값을 설정합니다.
     */
    public QueryAnalysisResult {
        entities = entities != null ? List.copyOf(entities) : Collections.emptyList();
        expandedKeywords = expandedKeywords != null ? List.copyOf(expandedKeywords) : Collections.emptyList();
        searchQueries = searchQueries != null ? List.copyOf(searchQueries) : Collections.emptyList();
        contextHints = contextHints != null ? List.copyOf(contextHints) : Collections.emptyList();
        noiseDomains = noiseDomains != null ? List.copyOf(noiseDomains) : Collections.emptyList();
        intent = intent != null ? intent : QueryIntent.GENERAL;
    }

    /**
     * 엔티티 중심 쿼리 여부 판단.
     * <p>
     * - 의도가 SEARCH/INFO 인 경우
     * - 엔티티가 하나 이상 존재하는 경우
     * - expectedDomain 이 명시된 경우
     */
    public boolean isEntityQuery() {
        return (intent == QueryIntent.SEARCH || intent == QueryIntent.INFO)
                && entities != null
                && !entities.isEmpty()
                && expectedDomain != null
                && !expectedDomain.isBlank();
    }

    /**
     * 동적 Threshold 계산.
     * <p>
     * - 전탐사 모드(isExploration)인 경우 낮은 threshold (0.10) 반환
     * - 신뢰도(confidenceScore)가 높을수록 threshold를 약간 낮춤
     * - 기본값은 0.35
     * 
     * @return 계산된 동적 threshold 값 (0.10 ~ 0.35 범위)
     */
    public double getDynamicThreshold() {
        // 전탐사 모드: 가장 낮은 threshold
        if (isExploration) {
            return 0.10;
        }

        // 기본 threshold
        double baseThreshold = 0.35;

        // 신뢰도가 높으면 threshold를 약간 낮춤 (더 많은 결과 허용)
        // confidenceScore: 0.0 ~ 1.0
        double adjustment = confidenceScore * 0.10;

        return Math.max(0.15, baseThreshold - adjustment);
    }

    /**
     * 빈 분석 결과 팩토리.
     */
    public static QueryAnalysisResult empty(String query) {
        return new QueryAnalysisResult(
                query,
                QueryIntent.GENERAL,
                Collections.emptyList(),
                Collections.emptyList(),
                false,
                false,
                Collections.emptyList(),
                0.0,
                null,
                Collections.emptyList(),
                Collections.emptyList());
    }

    /**
     * LLM 호출 실패 등 분석이 불가할 때 사용하는 전탐사 폴백 결과.
     */
    public static QueryAnalysisResult explorationFallback(String query) {
        return new QueryAnalysisResult(
                query,
                QueryIntent.SEARCH,
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                true,
                List.of(query),
                0.5,
                null,
                Collections.emptyList(),
                Collections.emptyList());
    }
}
