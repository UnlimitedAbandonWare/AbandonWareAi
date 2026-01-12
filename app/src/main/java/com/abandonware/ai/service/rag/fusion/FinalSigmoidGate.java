package com.abandonware.ai.service.rag.fusion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Jammini Aggressive-Hybrid Patch v2.0
 *
 * - 시선1(기억): 증거 있으면 무조건 통과 + 메모리 저장
 * - 시선2(UX): 불필요한 차단 제거, 뭐라도 보여주기
 * - 시선4(자유): 점수 계산됐으면 거의 무조건 통과
 */
@Component
public class FinalSigmoidGate {
    private final double threshold;
    private final boolean aggressiveMode;

    public FinalSigmoidGate(
            @Value("${gate.finalSigmoid.threshold:0.10}") double threshold,
            @Value("${jammini.guard.mode:aggressive}") String guardMode
    ) {
        this.threshold = threshold;
        this.aggressiveMode = "aggressive".equalsIgnoreCase(guardMode);
    }

    /**
     * Risk 기반 점수 계산 (기존 로직 유지)
     */
    public double score(double hallucinationRisk, double policyRisk, double lowCitationPenalty) {
        double base = 3.0;
        double wHall = 2.0;
        double wPolicy = 1.5;
        double wCitation = 0.5;
        double z = base - (wHall * hallucinationRisk + wPolicy * policyRisk + wCitation * lowCitationPenalty);
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /**
     * 🔥 [PATCH] Aggressive Mode: 점수가 0 이상이면 거의 무조건 통과
     */
    public boolean allow(double compositeScore) {
        // Aggressive 모드: NaN만 아니면 통과
        if (aggressiveMode) {
            if (compositeScore >= 0.0) {
                return true;
            }
        }
        // 레거시 시그모이드 로직 (매우 낮은 threshold 적용)
        return compositeScore >= threshold;
    }
}
