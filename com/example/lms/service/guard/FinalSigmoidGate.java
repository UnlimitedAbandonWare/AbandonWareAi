package com.example.lms.service.guard;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FinalSigmoidGate.class);

    private final double base;
    private final double wHall;
    private final double wPolicy;
    private final double wCitation;
    private final double threshold;
    private final boolean aggressiveMode;
    private final GateMode mode;

    public enum GateMode { HARD, SOFT, DEGRADE, LOG_ONLY }

    public enum GateResult { PASS, WARN, DEGRADE, BLOCK }

    public FinalSigmoidGate(
            @org.springframework.beans.factory.annotation.Value("${jammini.guard.base:3.0}") double base,
            @org.springframework.beans.factory.annotation.Value("${jammini.guard.wHall:2.0}") double wHall,
            @org.springframework.beans.factory.annotation.Value("${jammini.guard.wPolicy:1.5}") double wPolicy,
            @org.springframework.beans.factory.annotation.Value("${jammini.guard.wCitation:0.5}") double wCitation,
            @org.springframework.beans.factory.annotation.Value("${gate.finalSigmoid.threshold:0.10}") double threshold,
            @org.springframework.beans.factory.annotation.Value("${jammini.guard.mode:aggressive}") String guardMode,
            @org.springframework.beans.factory.annotation.Value("${gate.finalSigmoid.mode:soft}") String gateMode
    ) {
        this.base = base;
        this.wHall = wHall;
        this.wPolicy = wPolicy;
        this.wCitation = wCitation;
        this.threshold = threshold;
        this.aggressiveMode = "aggressive".equalsIgnoreCase(guardMode);
        this.mode = parseGateMode(gateMode);
    }

    private static GateMode parseGateMode(String s) {
        if (s == null) return GateMode.SOFT;
        String v = s.trim().toLowerCase();
        if ("hard".equals(v)) return GateMode.HARD;
        if ("degrade".equals(v)) return GateMode.DEGRADE;
        if ("log-only".equals(v) || "logonly".equals(v)) return GateMode.LOG_ONLY;
        return GateMode.SOFT;
    }

    public double score(double hallucinationScore, double policyRisk, double citationScore) {
        double g = base
                + (wHall * hallucinationScore)
                + (wPolicy * policyRisk)
                + (wCitation * citationScore);
        // sigmoid
        return 1.0 / (1.0 + Math.exp(-g));
    }

    /**
     * New gate entry point returning GateResult.
     * Keeps legacy aggressiveMode semantics but adds mode-based behaviours.
     */
    public GateResult check(double compositeScore, double policyRisk, boolean hasStrongEvidence) {
        // Strong evidence + low policy risk ⇒ always pass
        if (hasStrongEvidence && policyRisk < 0.7) {
            return GateResult.PASS;
        }

        // Preserve legacy aggressiveMode heuristics
        if (aggressiveMode) {
            if (compositeScore >= -0.5 && policyRisk < 0.5) {
                return GateResult.PASS;
            }
            if (hasStrongEvidence && compositeScore >= -0.3) {
                return GateResult.PASS;
            }
        }

        boolean passes = compositeScore >= threshold;
        if (passes) {
            return GateResult.PASS;
        }

        // Mode-based fallback when threshold is not met
        switch (mode) {
            case HARD:
                return GateResult.BLOCK;
            case SOFT:
                return GateResult.WARN;
            case DEGRADE:
                return GateResult.DEGRADE;
            case LOG_ONLY:
                log.warn("[FinalSigmoidGate] LOG_ONLY: score={} < threshold={}", compositeScore, threshold);
                return GateResult.PASS;
            default:
                return GateResult.WARN;
        }
    }

    /**
     * Backwards compatible boolean API used by existing callers.
     * Any non-BLOCK result is treated as allowed.
     */
    public boolean allow(double compositeScore, double policyRisk, boolean hasStrongEvidence) {
        GateResult result = check(compositeScore, policyRisk, hasStrongEvidence);
        return result != GateResult.BLOCK;
    }

    public boolean allow(double compositeScore) {
        return allow(compositeScore, 0.0, false);
    }


    /**
     * Simple probability-based gate that looks only at a single probability value
     * and an optional GuardContext. This is deliberately much lighter-weight than
     * the full composite sigmoid logic and is intended for callers that already
     * have a calibrated probability (e.g. ONNX or fused retriever score).
     *
     * Mode thresholds:
     *  SAFE       : 0.90
     *  BRAVE      : 0.75
     *  ZERO_BREAK : 0.70
     *  RULE_BREAK : 0.65
     */
    public boolean pass9x(double prob, GuardContext ctx) {
        double threshold = 0.90; // default SAFE
        if (ctx != null && ctx.getMode() != null) {
            String mode = ctx.getMode();
            switch (mode) {
                case "BRAVE" -> threshold = 0.75;
                case "ZERO_BREAK" -> threshold = 0.70;
                case "RULE_BREAK" -> threshold = 0.65;
                default -> threshold = 0.90;
            }
        }
        return prob >= threshold;
    }

}
