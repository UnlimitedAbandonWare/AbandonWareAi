package com.example.lms.rag.model;

/**
 * High-level domain of a user query.
 * Used to adapt web search, guardrails, and prompt persona.
 */
public enum QueryDomain {
    STUDY, // 교육, 시험, 코딩 테스트
    GENERAL, // 일반 상식, 잡담
    GAME, // 게임(원신, 롤, 블루아카 등)
    SUBCULTURE, // 애니, 웹툰, 라노벨, 아이돌, V튜버 등
    SENSITIVE; // 성인, 혐오, 불법, 자해/폭력 등

    /**
     * Safe default when classification fails.
     */
    public static QueryDomain safeDefault() {
        return GENERAL;
    }

    /**
     * 저위험 도메인 여부 판단.
     * 게임, 서브컬처, 일반 도메인은 저위험으로 분류.
     */
    public boolean isLowRisk() {
        return this == GAME || this == SUBCULTURE || this == GENERAL;
    }
}
