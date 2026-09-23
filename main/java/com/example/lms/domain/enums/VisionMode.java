package com.example.lms.domain.enums;

/**
 * Jammini Dual-Vision 모드 정의
 *
 * - STRICT (View 1): Memory-Strict, 검색 결과와 기억을 절대적 진실로 취급
 * - FREE   (View 2): Free-Idea, 창의적 추론 허용, 기억 저장 안 함
 * - HYBRID: 기본값, STRICT + FREE 혼합
 */
public enum VisionMode {

    /**
     * View 1: Memory-Strict (기존 Jammini)
     * - 검색 결과와 기억을 절대적 진실로 취급
     * - 정보가 없으면 "모른다"고 답함
     * - 답변을 장기 기억에 저장함
     */
    STRICT,

    /**
     * View 2: Free-Idea (실험적 Jammini)
     * - 창의적 추론 허용, "정보 없음" 회피
     * - 기억 저장소를 사용하지 않거나(Ephemeral), 저장하지 않음
     * - 게임/서브컬처/가벼운 대화용
     */
    FREE,

    /**
     * Hybrid: 기본 모드
     * - 먼저 STRICT 기반 답변 생성
     * - 저위험 도메인이면 FREE 섹션 추가
     * - STRICT 답변만 메모리에 저장
     */
    HYBRID
}
