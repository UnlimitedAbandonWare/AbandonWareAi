package com.example.lms.infra.resilience;

import com.example.lms.service.guard.GuardContext;

/**
 * Request-scoped "원인 스냅샷".
 *
 * <p>TraceStore/로그로 남길 때, ctxFlags 를 Map 으로 자유롭게 확장하면
 * 필드가 들쑥날쑥해져서 diff/분석이 어려워집니다. 이 record 는
 * 분석에 필요한 핵심 플래그만을 고정된 스키마로 제공합니다.</p>
 */
public record AuxCtxFlagsSnapshot(
        boolean auxHardDown,
        boolean auxDegraded,
        boolean strikeMode,
        boolean compressionMode,
        boolean bypassMode,
        boolean aggressivePlan,
        boolean webRateLimited,
        boolean highRiskQuery,
        double irregularityScore,
        String bypassReason
) {
    public static AuxCtxFlagsSnapshot from(GuardContext ctx) {
        if (ctx == null) {
            return new AuxCtxFlagsSnapshot(
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    0.0,
                    null
            );
        }
        return new AuxCtxFlagsSnapshot(
                ctx.isAuxHardDown(),
                ctx.isAuxDegraded(),
                ctx.isStrikeMode(),
                ctx.isCompressionMode(),
                ctx.isBypassMode(),
                ctx.isAggressivePlan(),
                ctx.isWebRateLimited(),
                ctx.isHighRiskQuery(),
                ctx.getIrregularityScore(),
                safeShort(ctx.getBypassReason(), 120)
        );
    }

    private static String safeShort(String s, int maxLen) {
        if (s == null) return null;
        String v = s.strip();
        if (v.length() <= maxLen) return v;
        return v.substring(0, Math.max(0, maxLen - 1)) + "…";
    }
}
