package com.example.lms.domain.enums;

/**
 * 답변 모드: 시선1(FACT) vs 시선2(CREATIVE) vs 하이브리드(BALANCED)
 */
public enum AnswerMode {
    /**
     * 시선1: 정합성 우선, 증거 필수
     */
    FACT,
    /**
     * 시선2: 자유 발상, 추측 허용
     */
    CREATIVE,
    /**
     * 하이브리드: 팩트 + 아이디어
     */
    BALANCED;

    /**
     * 문자열로부터 AnswerMode를 생성한다.
     * null 또는 인식할 수 없는 값이면 BALANCED를 반환한다.
     */
    public static AnswerMode fromString(String s) {
        if (s == null) {
            return BALANCED;
        }
        String upper = s.trim().toUpperCase();
        return switch (upper) {
            case "FACT" -> FACT;
            case "CREATIVE" -> CREATIVE;
            default -> BALANCED;
        };
    }
}
