package com.example.lms.service.rag.consensus;

import com.example.lms.search.probe.BranchQualityProbe;
import com.example.lms.search.probe.EvidenceSignals;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure risk overlay for the existing BQ/ER/RC Self-Ask lanes.
 *
 * <p>No Spring wiring, external calls, LLM calls, or search calls belong here.</p>
 */
public final class ThreeLaneRiskConsensus {

    private ThreeLaneRiskConsensus() {
    }

    public record Config(
            boolean enabled,
            double riskPenaltyLambda,
            int minLaneCoverage,
            double minRrfWeight,
            double maxRrfWeight) {

        public Config {
            riskPenaltyLambda = clamp(riskPenaltyLambda, 0.0d, 1.0d);
            minLaneCoverage = Math.max(1, Math.min(3, minLaneCoverage));
            minRrfWeight = clamp(minRrfWeight, 0.01d, 1.25d);
            maxRrfWeight = clamp(maxRrfWeight, minRrfWeight, 2.5d);
        }
    }

    public record Decision(
            String lane,
            String role,
            boolean enabled,
            double laneRisk,
            double riskMultiplier,
            double adjustedRrfWeight,
            Map<String, Object> tracePayload) {

        public Decision {
            lane = normalizeLane(lane);
            role = ThreeLaneRiskConsensus.role(lane);
            laneRisk = round4(clamp(laneRisk, 0.0d, 1.0d));
            riskMultiplier = round4(clamp(riskMultiplier, 0.10d, 1.0d));
            adjustedRrfWeight = round4(adjustedRrfWeight);
            tracePayload = tracePayload == null ? Map.of() : Map.copyOf(tracePayload);
        }
    }

    public static Decision evaluate(
            String lane,
            EvidenceSignals.LaneEvidenceSignals sig,
            BranchQualityProbe.BranchQualityMetrics metric,
            double baseWeight,
            double laneRdi,
            Config config) {

        Config cfg = config == null ? new Config(false, 0.45d, 2, 0.05d, 1.25d) : config;
        String safeLane = normalizeLane(lane);
        String role = role(safeLane);
        double safeBase = clamp(baseWeight, cfg.minRrfWeight(), cfg.maxRrfWeight());
        if (!cfg.enabled()) {
            return decision(safeLane, role, false, 0.0d, 1.0d, safeBase, reason("disabled"));
        }

        double rdi = clamp(laneRdi, 0.0d, 1.0d);
        double branchRisk = metric == null ? 0.0d : clamp(metric.riskPenalty(), 0.0d, 1.0d);
        double contradiction = sig == null ? 0.0d : clamp(sig.contradictionRate(), 0.0d, 1.0d);
        double duplicate = sig == null ? 0.0d : clamp(sig.duplicateRate(), 0.0d, 1.0d);
        double authority = sig == null ? 0.50d : clamp(sig.authorityAvg(), 0.0d, 1.0d);

        double laneRisk = clamp(
                (0.35d * rdi)
                        + (0.30d * branchRisk)
                        + (0.15d * contradiction)
                        + (0.10d * duplicate)
                        + (0.10d * (1.0d - authority)),
                0.0d,
                1.0d);
        double riskMultiplier = clamp(1.0d - (cfg.riskPenaltyLambda() * laneRisk), 0.10d, 1.0d);
        double adjusted = clamp(safeBase * riskMultiplier, cfg.minRrfWeight(), cfg.maxRrfWeight());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", "risk_adjusted");
        payload.put("laneRdi", round4(rdi));
        payload.put("branchRisk", round4(branchRisk));
        payload.put("contradictionRate", round4(contradiction));
        payload.put("duplicateRate", round4(duplicate));
        payload.put("authorityAvg", round4(authority));
        return decision(safeLane, role, true, laneRisk, riskMultiplier, adjusted, payload);
    }

    public static String role(String lane) {
        return switch (normalizeLane(lane)) {
            case "BQ" -> "STRICT";
            case "ER" -> "RELAXED";
            case "RC" -> "EXPLORE";
            default -> "UNKNOWN";
        };
    }

    private static Decision decision(
            String lane,
            String role,
            boolean enabled,
            double laneRisk,
            double riskMultiplier,
            double adjusted,
            Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lane", normalizeLane(lane));
        payload.put("role", role);
        payload.put("enabled", enabled);
        payload.put("laneRisk", round4(laneRisk));
        payload.put("riskMultiplier", round4(riskMultiplier));
        payload.put("adjustedRrfWeight", round4(adjusted));
        if (details != null) {
            payload.putAll(details);
        }
        return new Decision(lane, role, enabled, laneRisk, riskMultiplier, adjusted, payload);
    }

    private static Map<String, Object> reason(String reason) {
        return Map.of("reason", reason == null || reason.isBlank() ? "unknown" : reason);
    }

    private static String normalizeLane(String lane) {
        if (lane == null || lane.isBlank()) {
            return "UNKNOWN";
        }
        String safe = lane.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (safe) {
            case "BQ", "ER", "RC" -> safe;
            default -> "UNKNOWN";
        };
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
