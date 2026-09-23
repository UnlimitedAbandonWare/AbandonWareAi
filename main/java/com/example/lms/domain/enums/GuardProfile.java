package com.example.lms.domain.enums;

/**
 * @deprecated Use {@link com.example.lms.guard.GuardProfile} instead.
 *
 * <p>이 enum은 과거 도메인 레벨 GuardProfile 정의를 위한 것으로,
 * 이제는 {@link com.example.lms.guard.GuardProfile}을 Canonical 소스로 사용합니다.
 * 기존 값과의 호환을 위해 JAMMINI / FREE_PRO / RULE_BREAK 를 포함하지만,
 * 실제 동작은 {@link #toCanonical()} 결과를 기반으로 합니다.</p>
 */
@Deprecated
public enum GuardProfile {

    // ─ Canonical names ─
    SAFE,
    STRICT,
    NORMAL,
    BALANCED,
    SUBCULTURE,
    BRAVE,
    WILD,
    PROFILE_MEMORY,
    PROFILE_FREE,

    // ─ Legacy aliases ─
    JAMMINI,
    FREE_PRO,
    RULE_BREAK;

    /**
     * Canonical GuardProfile로 매핑합니다.
     */
    public com.example.lms.guard.GuardProfile toCanonical() {
        return switch (this) {
            case SAFE -> com.example.lms.guard.GuardProfile.SAFE;
            case STRICT -> com.example.lms.guard.GuardProfile.STRICT;
            case NORMAL -> com.example.lms.guard.GuardProfile.NORMAL;
            case BALANCED -> com.example.lms.guard.GuardProfile.BALANCED;
            case SUBCULTURE -> com.example.lms.guard.GuardProfile.SUBCULTURE;
            case BRAVE -> com.example.lms.guard.GuardProfile.BRAVE;
            case WILD -> com.example.lms.guard.GuardProfile.WILD;
            case PROFILE_MEMORY, JAMMINI -> com.example.lms.guard.GuardProfile.PROFILE_MEMORY;
            case PROFILE_FREE, FREE_PRO -> com.example.lms.guard.GuardProfile.PROFILE_FREE;
            case RULE_BREAK -> com.example.lms.guard.GuardProfile.BRAVE;
        };
    }
}
