package com.example.lms.domain.enums;

/**
 * 메모리 접근 모드
 * <ul>
 *     <li>FULL      : 읽기/쓰기 모두 활성 (시선1)</li>
 *     <li>HYBRID    : 읽기만, 쓰기 제한</li>
 *     <li>EPHEMERAL : 메모리 완전 비활성 (시선2 – 휘발성)</li>
 * </ul>
 */
public enum MemoryMode {
    FULL,       // RAG + 메모리 풀 사용
    HYBRID,     // 읽기만, 쓰기 제한
    EPHEMERAL;  // 메모리 완전 비활성

    /**
     * 문자열로부터 MemoryMode를 생성한다.
     * null 또는 인식할 수 없는 값이면 HYBRID를 반환한다.
     */
    public static MemoryMode fromString(String s) {
        if (s == null) {
            return HYBRID;
        }
        String upper = s.trim().toUpperCase();
        return switch (upper) {
            case "FULL" -> FULL;
            case "EPHEMERAL" -> EPHEMERAL;
            default -> HYBRID;
        };
    }

    /**
     * 읽기(검색) 허용 여부
     */
    public boolean isReadEnabled() {
        return this != EPHEMERAL;
    }

    /**
     * 쓰기(저장) 허용 여부
     */
    public boolean isWriteEnabled() {
        return this == FULL;
    }
}
