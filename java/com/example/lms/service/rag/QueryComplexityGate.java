// src/main/java/com/example/lms/service/rag/QueryComplexityGate.java
package com.example.lms.service.rag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 쿼리 복잡도를 판단해 검색 흐름을 제어하는 게이트웨이.
 * SIMPLE   : 짧거나 키워드형 / 직접 검색 연산자 포함
 * AMBIGUOUS : 평범한 문장 (간단 분석만 필요)
 * COMPLEX  : 구어체, 다중 질문사, 문장 분절 등 복잡한 자연어
 */
@Component
public class QueryComplexityGate {
    /** 선택 주입되는 ML 기반 분류기. 없으면 규칙 기반 로직을 사용한다. */
    @Autowired(required = false)
    private QueryComplexityClassifier classifier;

    public enum Level { SIMPLE, AMBIGUOUS, COMPLEX }

    // 1) 구어체·언질문 힌트
    private static final Set<String> COLLOQUIAL_HINTS = Set.of(
            "있던데", "왜이래", "왜 이래", "뭐야", "난 없어", "그건가",
            "그거", "저거", "이거", "어떻게 해야", "알려줘", "좀"
    );

    // 2) 의문사(WH-) 힌트
    private static final Set<String> WH_WORDS = Set.of(
            "누가", "언제", "어디", "왜", "어떻게", "얼마", "몇", "무엇", "뭐", "뭘"
    );

    // 3) 직접 검색 연산자(키워드형) 힌트
    private static final Pattern DIRECT_SEARCH = Pattern.compile(
            "(?i)(site:|filetype:|\\binurl:|\\bintitle:|\".+\"|\\bvs\\b" +
                    "|사양|스펙|가격|정의|다운로드|설치)"
    );

    // 4) 문장 분절·복합도 힌트
    private static final Pattern MULTI_CLAUSE = Pattern.compile(
            "[.,!?·…；;]{1,}.*[가-힣\\w].*"
    );

    /**
     * 쿼리 문자열 q를 보고 SIMPLE/AMBIGUOUS/COMPLEX 중 하나로 분류.
     */
    public Level assess(String q) {
        /* 1) ML 분류기가 존재하면 우선 사용 */
        if (classifier != null) {
            return classifier.classify(q);
        }

        /* 2) 분류기가 없거나 초기화 실패 시 → 기존 규칙 기반 로직 */
        if (q == null) return Level.SIMPLE;
        String s = q.strip();

        // (A) 매우 짧거나 직접 검색 연산자 포함 → SIMPLE
        if (s.length() <= 24 || DIRECT_SEARCH.matcher(s).find()) {
            return Level.SIMPLE;
        }

        // (B) 구어체 힌트, 다수의 WH 힌트, 혹은 다중 문장 분절 → COMPLEX
        int hintHits = (int) COLLOQUIAL_HINTS.stream().filter(s::contains).count();
        int whHits   = (int) WH_WORDS.stream().filter(s::contains).count();
        boolean multi = MULTI_CLAUSE.matcher(s).find()
                || s.chars().filter(ch -> ch == '?').count() >= 2;

        if (hintHits >= 1 || whHits >= 2 || multi) {
            return Level.COMPLEX;
        }

        // (C) 그 외 → AMBIGUOUS
        return Level.AMBIGUOUS;
    }

    /** COMPLEX 판정 시 Self-Ask 단계 사용 여부 */
    public boolean needsSelfAsk(String q) {
        return assess(q) == Level.COMPLEX;
    }

    /** AMBIGUOUS 또는 COMPLEX 판정 시 Analyze 단계 사용 여부 */
    public boolean needsAnalyze(String q) {
        Level lv = assess(q);
        return lv == Level.AMBIGUOUS || lv == Level.COMPLEX;
    }
}
