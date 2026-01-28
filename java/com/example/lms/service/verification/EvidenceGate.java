// src/main/java/com/example/lms/service/verification/EvidenceGate.java
package com.example.lms.service.verification;

import com.example.lms.service.rag.detector.RiskBand;
import com.example.lms.service.disambiguation.DisambiguationResult;

/**
 * Gate for deciding whether content generation should proceed based on the
 * disambiguation result and collected evidence.
 *
 * <p>이 인터페이스는 두 층의 결정을 담당합니다.</p>
 * <ul>
 *   <li>정량 지표 기반의 선행 게이트
 *       ({@link #hasSufficientCoverage(EvidenceVerificationResult, RiskBand)},
 *        {@link #canProceed(EvidenceVerificationResult, RiskBand)})</li>
 *   <li>최종 생성 허용 여부
 *       ({@link #allowGeneration(DisambiguationResult, EvidenceSnapshot)})</li>
 * </ul>
 *
 * <p>RiskBand 인자가 있는 메서드는 의료/법률처럼 고위험 질의일수록
 * 더 보수적인 임계값을 적용하기 위한 용도입니다. 기존 호출부와의
 * 호환을 위해 RiskBand 인자가 없는 오버로드도 함께 제공합니다.</p>
 */
public interface EvidenceGate {

    // ────────────────────────────
    // RiskBand-aware coverage API
    // ────────────────────────────

    /**
     * Returns {@code true} if the collected evidence is strong enough for
     * generation to proceed under the given {@link RiskBand}.
     */
    boolean hasSufficientCoverage(EvidenceVerificationResult ev, RiskBand risk);

    /**
     * Returns {@code true} if we are allowed to proceed at all (even when
     * coverage is marginal), for example when evidence is not contradictory.
     */
    boolean canProceed(EvidenceVerificationResult ev, RiskBand risk);

    // ────────────────────────────────
    // Backwards compatible overloads
    // ────────────────────────────────

    /**
     * Legacy overload that assumes {@link RiskBand#HIGH}.  This keeps older
     * 호출부가 더 보수적인 정책으로 동작하도록 합니다.
     */
    default boolean hasSufficientCoverage(EvidenceVerificationResult ev) {
        return hasSufficientCoverage(ev, RiskBand.HIGH);
    }

    /**
     * Legacy overload that assumes {@link RiskBand#HIGH}.
     */
    default boolean canProceed(EvidenceVerificationResult ev) {
        return canProceed(ev, RiskBand.HIGH);
    }

    // ────────────────────────────────
    // Final generation gate
    // ────────────────────────────────

    /**
     * Decide whether generation is allowed.  Implementations should inspect
     * both the resolved entity (if any) and the evidence to determine if
     * hallucination or misinformation risk is acceptable.
     *
     * @param dr the result of any entity disambiguation step; may be null
     * @param ev snapshot of evidence collected during retrieval
     * @return true if generation is allowed, false if a fallback or
     *         clarification should be returned instead
     */
    boolean allowGeneration(DisambiguationResult dr, EvidenceSnapshot ev);
}
