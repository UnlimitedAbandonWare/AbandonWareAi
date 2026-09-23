package com.example.lms.search.probe;

import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Default-on Self-Ask branch quality overlay.
 *
 * <p>It is deliberately stateless: the 9-tile matrix is emitted as bounded
 * diagnostics for the current request, not persisted as a new learning store.</p>
 */
@Component
public class BranchQualityProbe {

    public enum BranchAction {
        PASS,
        SHRINK,
        REWRITE_RETRY,
        SUPPRESS
    }

    public record Thresholds(
            double minContextContribution,
            double maxRiskPenalty,
            double moeTemperature,
            double baseDppLambda,
            double minDppLambda,
            double maxDppLambda,
            int baseTopK,
            double baseTemperature) {
    }

    public record BranchQualityMetrics(
            String branchId,
            String intentAxis,
            int retrievedCount,
            double duplicateRatio,
            double authorityScore,
            double rerankConfidence,
            double contextContribution,
            double riskPenalty,
            int matrixTile,
            double moeWeight,
            double rrfWeight,
            int adjustedTopK,
            double adjustedTemperature,
            double dppLambda,
            BranchAction action,
            String reason) {

        public boolean shouldRetry() {
            return action == BranchAction.REWRITE_RETRY;
        }

        public boolean shouldShrink() {
            return action == BranchAction.SHRINK || action == BranchAction.SUPPRESS;
        }

        public Map<String, Object> tracePayload() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("branchId", branchId);
            out.put("intentAxis", intentAxis);
            out.put("retrievedCount", Math.max(0, retrievedCount));
            out.put("duplicateRatio", duplicateRatio);
            out.put("authorityScore", authorityScore);
            out.put("rerankConfidence", rerankConfidence);
            out.put("contextContribution", contextContribution);
            out.put("riskPenalty", riskPenalty);
            out.put("matrixTile", matrixTile);
            out.put("moeWeight", moeWeight);
            out.put("rrfWeight", rrfWeight);
            out.put("adjustedTopK", Math.max(1, adjustedTopK));
            out.put("adjustedTemperature", adjustedTemperature);
            out.put("dppLambda", dppLambda);
            out.put("action", action == null ? BranchAction.PASS.name() : action.name());
            out.put("reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            return out;
        }

        private BranchQualityMetrics withMoe(double moeWeight, double rrfWeight) {
            return new BranchQualityMetrics(
                    branchId,
                    intentAxis,
                    retrievedCount,
                    duplicateRatio,
                    authorityScore,
                    rerankConfidence,
                    contextContribution,
                    riskPenalty,
                    matrixTile,
                    round4(moeWeight),
                    round4(rrfWeight),
                    adjustedTopK,
                    adjustedTemperature,
                    dppLambda,
                    action,
                    reason);
        }
    }

    public Map<String, BranchQualityMetrics> evaluateLanes(
            Map<String, EvidenceSignals.LaneEvidenceSignals> signals,
            Thresholds thresholds) {
        Map<String, BranchQualityMetrics> raw = new LinkedHashMap<>();
        if (signals != null) {
            for (Map.Entry<String, EvidenceSignals.LaneEvidenceSignals> entry : signals.entrySet()) {
                EvidenceSignals.LaneEvidenceSignals sig = entry.getValue();
                String lane = sig == null ? entry.getKey() : sig.lane();
                raw.put(safeBranchId(lane), evaluateLane(sig, thresholds));
            }
        }
        return normalizeMoe(raw, thresholds);
    }

    public BranchQualityMetrics evaluateLane(
            EvidenceSignals.LaneEvidenceSignals sig,
            Thresholds thresholds) {
        Thresholds t = sanitize(thresholds);
        String branchId = safeBranchId(sig == null ? "unknown" : sig.lane());
        String intentAxis = EvidenceSignals.laneRole(branchId);
        int docCount = sig == null ? 0 : Math.max(0, sig.docCount());
        double duplicateRatio = sig == null ? 1.0d : clamp01(sig.duplicateRate());
        double authorityScore = sig == null ? 0.0d : clamp01(sig.authorityAvg());
        double rerankConfidence = sig == null ? 0.0d : clamp01(sig.grandasReadiness());
        double contribution = sig == null ? 0.0d : clamp01(
                (0.35d * sig.grandasReadiness())
                        + (0.20d * sig.confidence())
                        + (0.20d * sig.crossLaneSupportRate())
                        + (0.15d * sig.strongCitationRate())
                        + (0.10d * (1.0d - sig.duplicateRate())));
        double risk = sig == null ? 1.0d : clamp01(
                (0.35d * sig.duplicateRate())
                        + (0.25d * sig.contradictionRate())
                        + (0.20d * (1.0d - sig.authorityAvg()))
                        + (0.10d * (1.0d - sig.strongCitationRate()))
                        + (0.10d * (docCount == 0 ? 1.0d : 0.0d)));
        return build(branchId, intentAxis, docCount, duplicateRatio, authorityScore,
                rerankConfidence, contribution, risk, t);
    }

    public BranchQualityMetrics evaluateAttempt(
            String lane,
            int requestedTopK,
            int returnedCount,
            int afterFilterCount,
            double laneWeight,
            double baseTemperature,
            boolean fallback,
            String failureClass,
            Thresholds thresholds) {
        Thresholds t = sanitize(thresholds);
        String branchId = safeBranchId(lane);
        String intentAxis = EvidenceSignals.laneRole(branchId);
        int returned = Math.max(0, returnedCount);
        int afterFilter = Math.max(0, Math.min(returned, afterFilterCount));
        int requested = Math.max(1, requestedTopK);
        double duplicateRatio = returned <= 0 ? 1.0d : clamp01(1.0d - ((double) afterFilter / returned));
        double authorityScore = 0.50d;
        double rerankConfidence = clamp01((double) afterFilter / requested);
        double failureRisk = fallback || hasFailure(failureClass) ? 1.0d : 0.0d;
        double contextContribution = clamp01(
                (0.40d * rerankConfidence)
                        + (0.25d * (1.0d - duplicateRatio))
                        + (0.20d * clamp01(laneWeight / 2.50d))
                        + (0.15d * authorityScore));
        double riskPenalty = clamp01(
                (0.55d * duplicateRatio)
                        + (0.25d * (afterFilter == 0 ? 1.0d : 0.0d))
                        + (0.20d * failureRisk));
        Thresholds adjusted = new Thresholds(
                t.minContextContribution(),
                t.maxRiskPenalty(),
                t.moeTemperature(),
                t.baseDppLambda(),
                t.minDppLambda(),
                t.maxDppLambda(),
                requested,
                baseTemperature);
        return build(branchId, intentAxis, returned, duplicateRatio, authorityScore,
                rerankConfidence, contextContribution, riskPenalty, adjusted);
    }

    public double dynamicDppLambda(
            Collection<BranchQualityMetrics> metrics,
            double baseLambda,
            double minLambda,
            double maxLambda) {
        double worstStrength = diversityPressure(metrics);
        return round4(clamp(baseLambda - (0.30d * worstStrength), minLambda, maxLambda));
    }

    public double diversityPressure(Collection<BranchQualityMetrics> metrics) {
        double worstStrength = 0.0d;
        if (metrics != null) {
            for (BranchQualityMetrics metric : metrics) {
                if (metric == null) {
                    continue;
                }
                double strength = diversityStrength(
                        metric.duplicateRatio(),
                        metric.riskPenalty(),
                        metric.contextContribution());
                worstStrength = Math.max(worstStrength, strength);
            }
        }
        return round4(clamp01(worstStrength));
    }

    public Map<String, Object> tracePayload(BranchQualityMetrics metric) {
        return metric == null ? Map.of() : metric.tracePayload();
    }

    private Map<String, BranchQualityMetrics> normalizeMoe(
            Map<String, BranchQualityMetrics> raw,
            Thresholds thresholds) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Thresholds t = sanitize(thresholds);
        double temp = clamp(t.moeTemperature(), 0.10d, 2.50d);
        double sum = 0.0d;
        Map<String, Double> exps = new LinkedHashMap<>();
        for (Map.Entry<String, BranchQualityMetrics> entry : raw.entrySet()) {
            BranchQualityMetrics metric = entry.getValue();
            double logit = (metric == null ? 0.0d : metric.contextContribution() - metric.riskPenalty()) / temp;
            double exp = Math.exp(clamp(logit, -20.0d, 20.0d));
            exps.put(entry.getKey(), exp);
            sum += exp;
        }
        Map<String, BranchQualityMetrics> out = new LinkedHashMap<>();
        int n = Math.max(1, raw.size());
        for (Map.Entry<String, BranchQualityMetrics> entry : raw.entrySet()) {
            BranchQualityMetrics metric = entry.getValue();
            double moe = sum <= 0.0d ? 1.0d : (n * exps.getOrDefault(entry.getKey(), 0.0d) / sum);
            double localMultiplier = clamp(0.35d + metric.contextContribution() - (0.45d * metric.riskPenalty()),
                    0.10d, 1.25d);
            double rrf = clamp(moe * localMultiplier, 0.05d, 1.25d);
            out.put(entry.getKey(), metric.withMoe(moe, rrf));
        }
        return out;
    }

    private BranchQualityMetrics build(
            String branchId,
            String intentAxis,
            int retrievedCount,
            double duplicateRatio,
            double authorityScore,
            double rerankConfidence,
            double contextContribution,
            double riskPenalty,
            Thresholds thresholds) {
        Thresholds t = sanitize(thresholds);
        BranchAction action = decideAction(retrievedCount, duplicateRatio, contextContribution, riskPenalty, t);
        String reason = reason(action, retrievedCount, duplicateRatio, contextContribution, riskPenalty, t);
        int matrixTile = matrixTile(branchId, contextContribution, riskPenalty, t);
        double kMultiplier = kMultiplier(action, duplicateRatio, riskPenalty);
        int adjustedTopK = Math.max(1, (int) Math.ceil(Math.max(1, t.baseTopK()) * kMultiplier));
        double adjustedTemperature = round4(clamp(
                t.baseTemperature() * (0.85d - (0.20d * riskPenalty) + (0.10d * contextContribution)),
                0.12d,
                0.55d));
        double dppLambda = dynamicDppLambda(
                java.util.List.of(new BranchQualityMetrics(
                        branchId,
                        intentAxis,
                        retrievedCount,
                        round4(duplicateRatio),
                        round4(authorityScore),
                        round4(rerankConfidence),
                        round4(contextContribution),
                        round4(riskPenalty),
                        matrixTile,
                        1.0d,
                        1.0d,
                        adjustedTopK,
                        adjustedTemperature,
                        t.baseDppLambda(),
                        action,
                        reason)),
                t.baseDppLambda(),
                t.minDppLambda(),
                t.maxDppLambda());
        double rrfWeight = clamp(0.35d + contextContribution - (0.45d * riskPenalty), 0.05d, 1.25d);
        return new BranchQualityMetrics(
                branchId,
                intentAxis,
                Math.max(0, retrievedCount),
                round4(duplicateRatio),
                round4(authorityScore),
                round4(rerankConfidence),
                round4(contextContribution),
                round4(riskPenalty),
                matrixTile,
                1.0d,
                round4(rrfWeight),
                adjustedTopK,
                adjustedTemperature,
                dppLambda,
                action,
                reason);
    }

    private static BranchAction decideAction(
            int retrievedCount,
            double duplicateRatio,
            double contextContribution,
            double riskPenalty,
            Thresholds thresholds) {
        if (retrievedCount <= 0) {
            return BranchAction.REWRITE_RETRY;
        }
        if (riskPenalty >= 0.90d && contextContribution < thresholds.minContextContribution()) {
            return BranchAction.SUPPRESS;
        }
        if (contextContribution < thresholds.minContextContribution()) {
            return BranchAction.REWRITE_RETRY;
        }
        if (riskPenalty > thresholds.maxRiskPenalty() || duplicateRatio >= 0.55d) {
            return BranchAction.SHRINK;
        }
        return BranchAction.PASS;
    }

    private static String reason(
            BranchAction action,
            int retrievedCount,
            double duplicateRatio,
            double contextContribution,
            double riskPenalty,
            Thresholds thresholds) {
        if (retrievedCount <= 0) {
            return "empty_branch";
        }
        if (action == BranchAction.PASS) {
            return "threshold_pass";
        }
        if (duplicateRatio >= 0.55d) {
            return "duplicate_pressure";
        }
        if (contextContribution < thresholds.minContextContribution()) {
            return "contribution_low";
        }
        if (riskPenalty > thresholds.maxRiskPenalty()) {
            return "risk_high";
        }
        return action == null ? "" : action.name().toLowerCase(Locale.ROOT);
    }

    private static int matrixTile(
            String branchId,
            double contextContribution,
            double riskPenalty,
            Thresholds thresholds) {
        int axis = switch (safeBranchId(branchId)) {
            case "BQ" -> 0;
            case "ER" -> 1;
            case "RC" -> 2;
            default -> 0;
        };
        int band;
        if (contextContribution >= thresholds.minContextContribution()
                && riskPenalty <= thresholds.maxRiskPenalty()) {
            band = 2;
        } else if (contextContribution >= thresholds.minContextContribution() * 0.50d
                && riskPenalty < 0.90d) {
            band = 1;
        } else {
            band = 0;
        }
        return (axis * 3) + band + 1;
    }

    private static double kMultiplier(BranchAction action, double duplicateRatio, double riskPenalty) {
        return switch (action == null ? BranchAction.PASS : action) {
            case PASS -> 1.0d;
            case REWRITE_RETRY -> duplicateRatio > 0.50d ? 0.60d : 0.85d;
            case SHRINK -> riskPenalty > 0.80d ? 0.50d : 0.65d;
            case SUPPRESS -> 0.50d;
        };
    }

    private static double diversityStrength(
            double duplicateRatio,
            double riskPenalty,
            double contextContribution) {
        return clamp01(
                (0.55d * duplicateRatio)
                        + (0.30d * riskPenalty)
                        + (0.15d * (1.0d - contextContribution)));
    }

    private static Thresholds sanitize(Thresholds t) {
        if (t == null) {
            return new Thresholds(0.35d, 0.65d, 0.65d, 0.70d, 0.35d, 0.85d, 4, 0.20d);
        }
        double minDpp = clamp(t.minDppLambda(), 0.0d, 1.0d);
        double maxDpp = clamp(t.maxDppLambda(), minDpp, 1.0d);
        return new Thresholds(
                clamp01(t.minContextContribution()),
                clamp01(t.maxRiskPenalty()),
                clamp(t.moeTemperature(), 0.10d, 2.50d),
                clamp(t.baseDppLambda(), minDpp, maxDpp),
                minDpp,
                maxDpp,
                Math.max(1, t.baseTopK()),
                clamp(t.baseTemperature(), 0.12d, 0.55d));
    }

    private static String safeBranchId(String lane) {
        if (lane == null || lane.isBlank()) {
            return "unknown";
        }
        String v = lane.trim().toUpperCase(Locale.ROOT);
        if ("BQ".equals(v) || "ER".equals(v) || "RC".equals(v)) {
            return v;
        }
        return SafeRedactor.traceLabelOrFallback(lane.trim().toLowerCase(Locale.ROOT), "unknown");
    }

    private static boolean hasFailure(String failureClass) {
        if (failureClass == null || failureClass.isBlank()) {
            return false;
        }
        String v = failureClass.trim().toLowerCase(Locale.ROOT);
        return !"none".equals(v) && !"ok".equals(v);
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0d, 1.0d);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }
}
