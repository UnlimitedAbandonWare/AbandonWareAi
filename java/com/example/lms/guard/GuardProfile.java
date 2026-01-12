package com.example.lms.guard;

import com.example.lms.domain.enums.AnswerMode;

/**
 * [CANONICAL] 가드레일 프로필.
 *
 * <p>
 * GuardProfile 값은 EvidenceAwareGuard, CitationGate, FinalSigmoidGate,
 * MemoryReinforcementService 등 전역 가드/메모리 파이프라인에서 사용됩니다.
 * </p>
 *
 * <ul>
 * <li>SAFE / STRICT : 가장 보수적인 모드 (의료/법률/금융 등)</li>
 * <li>NORMAL : 기본값 (학습/업무)</li>
 * <li>BALANCED : 일상 대화/잡담용 균형 모드</li>
 * <li>SUBCULTURE : 게임/애니/밈 등 서브컬처 도메인</li>
 * <li>BRAVE / WILD : 브레이브/제한 완화 모드</li>
 * <li>PROFILE_MEMORY : 시선1 - 메모리 적극 저장</li>
 * <li>PROFILE_FREE : 시선2 - 메모리 비활성 Pro 모드</li>
 * </ul>
 */
public enum GuardProfile {

    // 기본 모드
    SAFE, // 최고 엄격 (의료/법률/금융)
    STRICT, // 엄격 (일반 학습/연구)
    NORMAL, // 일반 (기본값)
    BALANCED, // 균형 (일반 대화)
    SUBCULTURE, // 서브컬처 완화 (게임/애니/밈)
    BRAVE, // 공격적 (Brave 플랜)
    WILD, // 최대 완화 (ZeroBreak/HyperNova)

    // 메모리 연계 프로필
    PROFILE_MEMORY, // 시선1: 메모리 적극 저장
    PROFILE_FREE, // 시선2: 메모리 비활성
    PROFILE_HEX; // 시선3: 중재 모드

    /**
     * 모드별 최소 evidence 점수.
     */
    public double minEvidenceScore() {
        return switch (this) {
            case SAFE, STRICT -> 0.75;
            case NORMAL, BALANCED, PROFILE_MEMORY, PROFILE_HEX -> 0.55;
            case SUBCULTURE -> 0.40;
            case BRAVE, WILD, PROFILE_FREE -> 0.25;
        };
    }

    /**
     * 모드별 최소 citation 개수.
     */
    public int minCitationCount() {
        return switch (this) {
            case SAFE, STRICT -> 3;
            case NORMAL, BALANCED, PROFILE_MEMORY, PROFILE_HEX -> 2;
            case SUBCULTURE, BRAVE -> 1;
            case WILD, PROFILE_FREE -> 0;
        };
    }

    /**
     * 약한 답변(근거 부족, 템플릿 응답 등)을 허용할지 여부.
     */
    public boolean allowWeakAnswer() {
        return this == SUBCULTURE
                || this == BRAVE
                || this == WILD
                || this == PROFILE_FREE
                || this == PROFILE_HEX;
    }

    /**
     * AnswerMode 기반 GuardProfile 자동 결정.
     * <p>
     * FACT -> STRICT<br>
     * CREATIVE -> PROFILE_FREE<br>
     * BALANCED -> NORMAL
     * </p>
     */
    public static GuardProfile fromAnswerMode(AnswerMode mode) {
        if (mode == null) {
            return NORMAL;
        }
        return switch (mode) {
            case ALL_ROUNDER -> NORMAL;
            case FACT -> STRICT;
            case CREATIVE -> PROFILE_FREE;
            case BALANCED -> NORMAL;
        };
    }

    /**
     * 메모리 강화(강화 학습용 snippet 저장)를 활성화할지 여부.
     *
     * <p>
     * WILD / PROFILE_FREE 모드는 세션 내 캐시만 허용하고,
     * 장기 메모리 저장은 비활성화합니다.
     * </p>
     */
    public boolean isMemoryReinforcementEnabled() {
        return this != PROFILE_FREE && this != WILD;
    }
}
