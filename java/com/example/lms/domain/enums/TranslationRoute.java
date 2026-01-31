// 경로: src/main/java/com/example/lms/domain/enums/TranslationRoute.java
package com.example.lms.domain.enums;


/**
 * 번역이 수행된 경로(소스)를 정의하는 열거형.
 * 강화학습 시 비용 계수(Cost Factor)를 계산하는 데 사용됩니다.
 */
public enum TranslationRoute {

    /** 자체 번역 메모리(TM) */
    MEMORY,

    /** OpenAI GPT-3.5 */
    GPT_3_5,

    /** OpenAI GPT-4 */
    GPT_4,

    /** Google Gemini */
    GEMINI,

    /** Google Translate API */
    GOOGLE_TRANSLATE,

    /* ──────────────── 레거시 호환 ──────────────── */
    @Deprecated MEM,
    @Deprecated GT,
    @Deprecated GPT,

    /* ──────────────── 새로 추가 ──────────────── */
    /** 모든 외부 번역 시도가 실패했을 때 */
    FAILED               // ★ 추가
}