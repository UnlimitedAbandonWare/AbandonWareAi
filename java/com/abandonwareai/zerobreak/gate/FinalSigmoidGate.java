package com.abandonwareai.zerobreak.gate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Jammini Aggressive-Hybrid Patch v3.0
 * - 증거가 있으면 거의 무조건 통과 (Evidence-First)
 * - 정책 리스크(policyRisk)가 높으면 신중하게 처리
 * - 매직 넘버를 전부 프로퍼티로 externalize
 */
@Component
public class FinalSigmoidGate {

    private final double base;
    private final double wHall;
    private final double wPolicy;
    private final double wCitation;
    private final double threshold;
    private final boolean aggressiveMode;

    // [수정] Spring이 이 생성자를 주입에 사용하도록 명시
    @Autowired
    public FinalSigmoidGate(
            @Value("${jammini.guard.base:3.0}") double base,
            @Value("${jammini.guard.wHall:2.0}") double wHall,
            @Value("${jammini.guard.wPolicy:1.5}") double wPolicy,
            @Value("${jammini.guard.wCitation:0.5}") double wCitation,
            @Value("${gate.finalSigmoid.threshold:0.10}") double threshold,
            @Value("${jammini.guard.mode:aggressive}") String guardMode
    ) {
        this.base = base;
        this.wHall = wHall;
        this.wPolicy = wPolicy;
        this.wCitation = wCitation;
        this.threshold = threshold;
        this.aggressiveMode = "aggressive".equalsIgnoreCase(guardMode);
    }

    /**
     * [추가] 하위 호환 및 수동 생성을 위한 보조 생성자
     * ZeroBreakAdminController 및 PatchAutoConfiguration에서 호출됨
     */
    public FinalSigmoidGate(double threshold, String guardMode) {
        // 기본 가중치 값은 @Value의 기본값과 동일하게 설정 (3.0, 2.0, 1.5, 0.5)
        this(3.0, 2.0, 1.5, 0.5, threshold, guardMode);
    }

    /**
     * Risk 기반 점수 계산 (매직 넘버 제거)
     */
    public double score(double hallucinationRisk, double policyRisk, double lowCitationPenalty) {
        double z = base - (wHall * hallucinationRisk + wPolicy * policyRisk + wCitation * lowCitationPenalty);
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /**
     * 확장된 시그니처: 증거 유무와 정책 리스크 함께 고려
     */
    public boolean allow(double compositeScore, double policyRisk, boolean hasStrongEvidence) {
        // 증거가 확실하고 정책 위반이 심각하지 않으면 무조건 통과
        if (hasStrongEvidence && policyRisk < 0.7) {
            return true;
        }

        if (aggressiveMode) {
            // Aggressive 모드 + 정책 리스크 낮으면 관대하게
            if (compositeScore >= -0.5 && policyRisk < 0.5) {
                return true;
            }
            // 증거 있으면 좀 더 관대하게
            if (hasStrongEvidence && compositeScore >= -0.3) {
                return true;
            }
        }

        return compositeScore >= threshold;
    }

    /**
     * 하위 호환용 (기존 호출부)
     */
    public boolean allow(double compositeScore) {
        return allow(compositeScore, 0.0, false);
    }
}
