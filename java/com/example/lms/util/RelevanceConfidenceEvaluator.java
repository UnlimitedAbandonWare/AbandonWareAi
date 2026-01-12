package com.example.lms.util;

import java.util.List;



/**
 * 1차 검색 스니펫들의 '관련도 신뢰 점수'를 계산한다.
 * 시그모이드(μ-CV) 변환으로 0~1 범위 확률값을 반환한다.
 */
public class RelevanceConfidenceEvaluator {

    private static final double EPS = 1e-9;
    private final RelevanceScorer scorer;
    private final double k;                 // 스케일 팩터 (기본 5.0)

    public RelevanceConfidenceEvaluator(RelevanceScorer scorer) {
        this(scorer, 5.0);
    }

    public RelevanceConfidenceEvaluator(RelevanceScorer scorer, double k) {
        this.scorer = scorer;
        this.k = k;
    }

    /**
     * @param query     원본 또는 보강된 사용자 쿼리
     * @param snippets  1차 검색 스니펫 목록
     * @return 0.0 ≤ c ≤ 1.0 (높을수록 신뢰↑)
     */
    public double confidence(String query, List<String> snippets) {
        if (query == null || query.isBlank() || snippets == null || snippets.isEmpty()) return 0.0;

        double sum = 0, sumSq = 0;
        int n = 0;
        for (String s : snippets) {
            double v = scorer.score(query, s);
            sum   += v;
            sumSq += v * v;
            n++;
        }
        if (n == 0) return 0.0;

        double mean = sum / n;
        double variance = Math.max(0.0, (sumSq / n) - (mean * mean));
        double std = Math.sqrt(variance);
        double cv  = std / (mean + EPS);

        /* σ(k·(μ−CV)) 시그모이드 */
        double z = k * (mean - cv);
        return 1.0 / (1.0 + Math.exp(-z));
    }
}