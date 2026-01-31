package com.example.lms.context;

/**
 * Jammini 듀얼 시선 프로필.
 * - DEEP_MEMORY: 기억 + 안정 시선 (엄격한 Guard, 적극적 Memory 저장)
 * - WILD_PRO   : 무기억 + 자유 시선 (완화된 Guard, Ephemeral Memory)
 * - PROJECTION : 두 시선 융합 (기본값)
 */
public enum JamminiProfile {

    /** 시선1: 기억 저장형 Jammini */
    DEEP_MEMORY,

    /** 시선2: 무기억 자유형 Pro Jammini */
    WILD_PRO,

    /** 통합: 두 시선을 병합하는 Projection 모드 */
    PROJECTION;

    /** 이 프로필에서 장기 메모리가 활성화되어 있는지 여부 */
    public boolean isMemoryEnabled() {
        return this == DEEP_MEMORY || this == PROJECTION;
    }

    /** 이 프로필이 엄격한 가드 모드인지 여부 */
    public boolean isStrictGuard() {
        return this == DEEP_MEMORY;
    }

    /** 이 프로필이 공격적/자유 모드인지 여부 */
    public boolean isAggressiveMode() {
        return this == WILD_PRO;
    }
}
