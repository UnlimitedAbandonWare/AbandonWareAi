package com.example.lms.guard;
import com.example.lms.search.TraceStore;
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
            @org.springframework.beans.factory.annotation.Value("${gate.finalSigmoid.threshold:0.70}") double threshold,
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
                - (wHall * clamp01(hallucinationScore))
                - (wPolicy * clamp01(policyRisk))
                - (wCitation * clamp01(citationScore));
        return sigmoid(g);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 1.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double sigmoid(double value) {
        if (value >= 40.0d) {
            return 1.0d;
        }
        if (value <= -40.0d) {
            return 0.0d;
        }
        return 1.0d / (1.0d + Math.exp(-value));
    }

    /**
     * New gate entry point returning GateResult.
     * Keeps legacy aggressiveMode semantics but adds mode-based behaviours.
     */
    public GateResult check(double compositeScore, double policyRisk, boolean hasStrongEvidence) {
        GateResult result = evaluate(compositeScore, policyRisk, hasStrongEvidence);
        recordGateResult(result, compositeScore, policyRisk, hasStrongEvidence);
        return result;
    }

    private GateResult evaluate(double compositeScore, double policyRisk, boolean hasStrongEvidence) {
        // Strong evidence + low policy risk ⇒ always pass
        if (hasStrongEvidence && policyRisk < 0.7) {
            return GateResult.PASS;
        }

        // Preserve legacy aggressiveMode heuristics
        if (aggressiveMode) {
            // score() returns a normalized probability, so compare against the
            // configured normalized threshold instead of legacy raw-logit cuts.
            if (compositeScore >= threshold && policyRisk < 0.5) {
                return GateResult.PASS;
            }
            if (hasStrongEvidence && compositeScore >= threshold) {
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

    private void recordGateResult(GateResult result, double compositeScore, double policyRisk, boolean hasStrongEvidence) {
        if (result == null) {
            return;
        }
        TraceStore.put("gate.finalSigmoid.compositeScore", finiteOrZero(compositeScore));
        TraceStore.put("gate.finalSigmoid.policyRisk", finiteOrZero(policyRisk));
        TraceStore.put("gate.finalSigmoid.threshold", threshold);
        TraceStore.put("gate.finalSigmoid.hasStrongEvidence", hasStrongEvidence);
        TraceStore.put("gate.finalSigmoid.result", result.name());
        TraceStore.put("gate.finalSigmoid.mode", mode.name());
        TraceStore.put("gate.sigmoid.score", finiteOrZero(compositeScore));
        TraceStore.put("gate.sigmoid.passed", result == GateResult.PASS);
        TraceStore.put("gate.hypernova.override", false);
        if (Boolean.TRUE.equals(TraceStore.get("hypernova.activated"))) {
            TraceStore.put("hypernova.finalGatePassed", result == GateResult.PASS);
        }
        switch (result) {
            case PASS -> TraceStore.inc("gate.pass.count");
            case BLOCK -> TraceStore.inc("gate.block.count");
            case WARN, DEGRADE -> TraceStore.inc("gate.warn.count");
        }
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0d;
    }

    public double getThreshold() {
        return threshold;
    }

    public GateMode getMode() {
        return mode;
    }

    public boolean isAggressiveMode() {
        return aggressiveMode;
    }
}
