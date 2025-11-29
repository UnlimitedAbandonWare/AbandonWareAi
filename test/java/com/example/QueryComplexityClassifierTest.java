package com.example.rag.planner;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;


import static org.junit.jupiter.api.Assertions.*;

public class QueryComplexityClassifierTest {
    QueryComplexityClassifier classifier = new QueryComplexityClassifier();

    private QueryComplexityClassifier.RoutingThresholds th() {
        return new QueryComplexityClassifier.RoutingThresholds(
            18, 8, 24, 32, 12, 0.25, 0.45, 0.55
        );
    }

    @Test
    void webRequired_whenRecencyCueExists() {
        var hints = new QueryComplexityClassifier.QueryHints(List.of("최근","최신"), Instant.now());
        var d = classifier.evaluate("최신 정책 변화 요약?", hints, th());
        assertEquals(QueryComplexityClassifier.Complexity.WEB_REQUIRED, d.level());
        assertTrue(d.useWeb());
        assertTrue(d.officialSourcesOnly());
        assertEquals(32, d.initialTopK());
    }

    @Test
    void complex_whenLongOrHasExactQuote() {
        var hints = new QueryComplexityClassifier.QueryHints(List.of("최근","최신"), Instant.now());
        var q = "이 문장은 토큰 수가 충분히 많아서 복잡 판정을 유도하기 위한 테스트 문구 입니다";
        var d = classifier.evaluate(q, hints, th());
        assertEquals(QueryComplexityClassifier.Complexity.COMPLEX, d.level());
        assertTrue(d.useVector());
        assertTrue(d.useWeb());
        assertEquals(24, d.initialTopK());
    }

    @Test
    void simple_default() {
        var hints = new QueryComplexityClassifier.QueryHints(List.of("최근","최신"), Instant.now());
        var d = classifier.evaluate("정의: RRF?", hints, th());
        assertEquals(QueryComplexityClassifier.Complexity.SIMPLE, d.level());
        assertFalse(d.useWeb());
        assertEquals(8, d.initialTopK());
    }
}