package com.example.lms.domain.enums;

/**
 * MemoryReinforcementService에서 사용할 메모리 게이트 프로파일.
 * VisionMode와 GuardProfile에 따라 결정됨.
 */
public enum MemoryGateProfile {

    /**
     * HARD: 엄격 모드 (기존 동작)
     * - 약한 스니펫, 근거 부족이면 쉽게 버림
     * - STUDY, SENSITIVE 도메인에 적용
     */
    HARD,

    /**
     * BALANCED: 균형 모드 (기본값)
     * - 근거가 조금 부족해도 보존 시도
     * - 일반 도메인에 적용
     */
    BALANCED,

    /**
     * RELAXED: 완화 모드
     * - 서브컬처/게임/자유대화: 웬만하면 저장
     * - 단, 고위험 콘텐츠는 여전히 차단
     */
    RELAXED
}
