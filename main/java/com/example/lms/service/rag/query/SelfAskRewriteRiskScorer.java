package com.example.lms.service.rag.query;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic scorer for Self-Ask rewrite risk.
 *
 * <p>The scorer only consumes already-available request metadata and redacted
 * trace counters. It does not call providers or inspect raw snippets.</p>
 */
public final class SelfAskRewriteRiskScorer {

    public static final String POLICY = "risk-weighted-v1";
    public static final String EMERGENT_POLICY = "risk-emergent-v1";
    private static final String[] WEB_PROVIDER_PREFIXES = {
            "web.naver",
            "web.brave",
            "web.serpapi",
            "web.tavily"
    };
    private static final String[] AFTER_FILTER_TRACE_PREFIXES = {
            "web.naver",
            "web.brave",
            "web.serpapi",
            "web.tavily",
            "web",
            "search"
    };

    private SelfAskRewriteRiskScorer() {
    }

    public record EmergentConfig(
            boolean enabled,
            double maxRiskDelta,
            double maxTemperatureDelta,
            double maxLaneWeightDelta,
            double maxSearchRangeDelta) {

        public EmergentConfig {
            maxRiskDelta = clamp(maxRiskDelta, 0.0d, 0.12d);
            maxTemperatureDelta = clamp(maxTemperatureDelta, 0.0d, 0.08d);
            maxLaneWeightDelta = clamp(maxLaneWeightDelta, 0.0d, 0.30d);
            maxSearchRangeDelta = clamp(maxSearchRangeDelta, 0.0d, 0.08d);
        }

        public static EmergentConfig defaults() {
            return new EmergentConfig(true, 0.06d, 0.03d, 0.12d, 0.08d);
        }

        public static EmergentConfig disabled() {
            return new EmergentConfig(false, 0.0d, 0.0d, 0.0d, 0.0d);
        }
    }

    public record EmergentAdjustment(
            boolean enabled,
            String policy,
            double riskDelta,
            double temperatureDelta,
            double searchRangeDelta,
            String reason,
            Map<String, Double> components,
            Map<String, Double> laneWeightDeltas) {

        public EmergentAdjustment {
            policy = policy == null || policy.isBlank() ? "disabled" : policy;
            reason = reason == null || reason.isBlank() ? "none" : reason;
            components = components == null ? Map.of() : Map.copyOf(components);
            laneWeightDeltas = laneWeightDeltas == null ? Map.of() : Map.copyOf(laneWeightDeltas);
        }

        public static EmergentAdjustment disabled() {
            return new EmergentAdjustment(false, "disabled", 0.0d, 0.0d, 0.0d,
                    "disabled", Map.of(), Map.of());
        }
    }

    public record Score(
            boolean enabled,
            String policy,
            double preRisk,
            double evidenceRisk,
            boolean evidenceAvailable,
            double currentRiskScore,
            double accumulatedRiskScore,
            double rewriteRiskScore,
            String rewriteRiskBand,
            String primaryFactor,
            double rewriteTemperatureWeighted,
            double validationTemperature,
            double explorationTemperature,
            double softmaxTemperature,
            double spikePenalty,
            double rewriteOverreachScore,
            String rewriteHonestyStatus,
            String rewriteOverreachType,
            String rewriteSourceHash12,
            Map<String, Double> components,
            Map<String, Double> laneWeights,
            EmergentAdjustment emergentAdjustment) {
    }

    public record RewriteHonesty(
            String honestyStatus,
            String overreachType,
            double overreachScore,
            String sourceHash12) {

        public RewriteHonesty {
            honestyStatus = normalizeStatus(honestyStatus, overreachScore);
            overreachType = overreachType == null || overreachType.isBlank() ? "none" : overreachType;
            overreachScore = round4(clamp01(overreachScore));
            sourceHash12 = sourceHash12 == null ? "" : sourceHash12;
        }
    }

    public static RewriteHonesty assessRewriteHonesty(String source, String rewrite) {
        String src = source == null ? "" : source;
        String candidate = rewrite == null || rewrite.isBlank() ? "" : rewrite;
        String srcLower = src.toLowerCase(Locale.ROOT);
        String candLower = candidate.toLowerCase(Locale.ROOT);
        double score = 0.0d;
        String type = "none";

        boolean hasCorrection = hasCorrectionSignal(srcLower);
        boolean hasInterpretation = containsAny(srcLower, "해석", "번역기", "라고", "claims", "interprets");
        boolean candidateAssertsSensitive = containsAny(candLower, "자살 권유", "자살하래", "suicide instruction",
                "self-harm instruction")
                || (containsAny(candLower, "자살", "suicide", "self-harm")
                && containsAny(candLower, "권유", "하래", "명령", "유도", "instruction"));
        if (hasCorrection && candidateAssertsSensitive) {
            score = 0.92d;
            type = "sensitive_inference";
        }

        boolean sourceSeparatesFounderCase = containsAny(srcLower, "다른 대표", "대표 이야기", "사기당할", "창업")
                && containsAny(srcLower, "dgx", "spark", "션");
        boolean candidateMergesFounderCase = containsAny(candLower, "션 창업", "dgx 창업", "션 사기 대표",
                "션 사업자 대출", "션 대표");
        if (sourceSeparatesFounderCase && candidateMergesFounderCase && score < 0.82d) {
            score = 0.82d;
            type = "actor_context_merge";
        }

        boolean candidateHardensInterpretation = containsAny(candLower, "인신공격 확정", "욕설 확정", "비난 확정",
                "무시 확정", "사실 확정", "abuse confirmed");
        if ((hasCorrection || hasInterpretation) && candidateHardensInterpretation && score < 0.68d) {
            score = 0.68d;
            type = "quote_to_fact";
        }

        boolean sourceHasNegation = containsAny(srcLower, "아니다", "아니고", "않", "절대 아니", "그런 뜻 아니다",
                "그런 생각", "not said", "not intended");
        boolean candidateDropsNegation = sourceHasNegation
                && containsAny(candLower, "자살", "인신공격", "창업 의도", "욕설", "모욕")
                && !containsAny(candLower, "아니다", "오해", "정정", "부정", "not", "misunderstanding");
        if (candidateDropsNegation && score < 0.56d) {
            score = 0.56d;
            type = "negation_dropped";
        }

        return new RewriteHonesty(normalizeStatus(null, score), type, score, SafeRedactor.hash12(src));
    }

    public static Score score(
            String query,
            Map<String, Object> metadata,
            Map<String, Object> trace,
            int observedEvidenceCount,
            int targetEvidenceK,
            double baseRewriteTemperature,
            double minRewriteTemperature,
            double maxRewriteTemperature,
            boolean enabled) {
        return score(query, metadata, trace, observedEvidenceCount, targetEvidenceK,
                baseRewriteTemperature, minRewriteTemperature, maxRewriteTemperature,
                enabled, EmergentConfig.disabled());
    }

    public static Score score(
            String query,
            Map<String, Object> metadata,
            Map<String, Object> trace,
            int observedEvidenceCount,
            int targetEvidenceK,
            double baseRewriteTemperature,
            double minRewriteTemperature,
            double maxRewriteTemperature,
            boolean enabled,
            EmergentConfig emergentConfig) {

        double minTemp = clamp(minRewriteTemperature, 0.0d, 1.0d);
        double maxTemp = clamp(maxRewriteTemperature, minTemp, 1.0d);
        double baseTemp = clamp(baseRewriteTemperature, minTemp, maxTemp);
        if (!enabled) {
            return new Score(false, "disabled", 0.0d, 0.0d, false, 0.0d, 0.0d,
                    0.0d, "LOW", "disabled", baseTemp, baseTemp, baseTemp, 0.35d, 0.0d,
                    0.0d, "CLEAN", "none", SafeRedactor.hash12(query),
                    Map.of(), uniformLaneWeights(), EmergentAdjustment.disabled());
        }

        double value = firstFinite(0.34d,
                metaDouble(metadata, "resource.valueScore"),
                metaDouble(metadata, "decisionValueScore"));
        value = clamp01(value);
        double optimism = firstFinite(deriveOptimism(query, value),
                metaDouble(metadata, "resource.optimismScore"),
                metaDouble(metadata, "optimismScore"));
        optimism = clamp01(optimism);
        double confidence = firstFinite(0.55d,
                metaDouble(metadata, "resource.riskAdjustedConfidence"),
                metaDouble(metadata, "entityConfidenceRiskAdjusted"),
                metaDouble(metadata, "confidenceScore"),
                metaDouble(metadata, "entityConfidence"));
        confidence = clamp01(confidence);
        double uncertainty = 1.0d - confidence;
        double modeRisk = modeRisk(query, metadata);
        RewriteHonesty rewriteHonesty = honestyFromMetadata(query, metadata, trace);
        double rewriteOverreach = rewriteHonesty.overreachScore();
        double historyPenalty = providerFailure(trace) * 0.50d + latencyPressure(trace) * 0.30d
                + traceFlag(trace, "qtx.llm.modelRequired") * 0.20d;

        double preRisk = clamp01(0.28d * value
                + 0.22d * optimism
                + 0.20d * uncertainty
                + 0.15d * modeRisk
                + 0.15d * clamp01(historyPenalty)
                + 0.10d * rewriteOverreach);

        double scarcity = scarcity(observedEvidenceCount, targetEvidenceK, trace);
        double providerFailure = providerFailure(trace);
        double afterFilterStarvation = afterFilterStarvation(trace);
        double contradiction = contradiction(trace);
        double latencyPressure = latencyPressure(trace);
        boolean evidenceAvailable = observedEvidenceCount >= 0 || hasEvidenceTrace(trace);
        double evidenceRisk = evidenceAvailable
                ? clamp01(0.30d * scarcity
                        + 0.25d * providerFailure
                        + 0.20d * afterFilterStarvation
                        + 0.15d * contradiction
                        + 0.10d * latencyPressure)
                : 0.0d;

        double currentRisk = evidenceAvailable ? clamp01(0.65d * preRisk + 0.35d * evidenceRisk) : preRisk;
        double previousRisk = previousRisk(metadata, trace, currentRisk);
        double spikePenalty = spikePenalty(previousRisk, currentRisk, evidenceRisk, preRisk,
                afterFilterStarvation, providerFailure);
        double accumulatedRisk = clamp01(0.70d * previousRisk + 0.30d * currentRisk + spikePenalty);
        String primaryFactor = primaryFactor(value, optimism, uncertainty, modeRisk, historyPenalty,
                rewriteOverreach,
                scarcity, providerFailure, afterFilterStarvation, contradiction, latencyPressure, evidenceAvailable);
        double weightedTemp = clamp(baseTemp - 0.14d * accumulatedRisk - 0.04d * latencyPressure
                - 0.04d * rewriteOverreach, minTemp, maxTemp);
        double softmaxTemperature = clamp(0.35d + 0.40d * accumulatedRisk, 0.35d, 0.75d);
        Map<String, Double> laneWeights = laneWeights(query, metadata, uncertainty, scarcity,
                providerFailure, contradiction, optimism, softmaxTemperature);
        Map<String, Double> components = components(value, optimism, uncertainty, modeRisk, historyPenalty,
                rewriteOverreach,
                scarcity, providerFailure, afterFilterStarvation, contradiction, latencyPressure,
                previousRisk, spikePenalty);
        EmergentAdjustment emergent = emergentAdjustment(
                emergentConfig,
                trace,
                observedEvidenceCount,
                currentRisk,
                accumulatedRisk,
                scarcity,
                providerFailure,
                afterFilterStarvation,
                contradiction,
                latencyPressure);
        double adjustedCurrentRisk = clamp01(currentRisk + emergent.riskDelta());
        double adjustedAccumulatedRisk = clamp01(accumulatedRisk + emergent.riskDelta());
        double adjustedTemperature = clamp(weightedTemp + emergent.temperatureDelta(), minTemp, maxTemp);
        double validationTemperature = validationTemperature(adjustedTemperature, adjustedAccumulatedRisk,
                minTemp, maxTemp);
        double explorationTemperature = explorationTemperature(adjustedTemperature, validationTemperature,
                emergent, minTemp, maxTemp);
        Map<String, Double> adjustedLaneWeights = adjustLaneWeights(laneWeights, emergent);
        components = new LinkedHashMap<>(components);
        components.put("validationTemperature", round4(validationTemperature));
        components.put("explorationTemperature", round4(explorationTemperature));
        if (emergent.enabled()) {
            components.put("emergentRiskDelta", round4(emergent.riskDelta()));
            components.put("emergentSearchRangeDelta", round4(emergent.searchRangeDelta()));
        }

        String policy = emergent.enabled() ? EMERGENT_POLICY : POLICY;
        return new Score(true, policy, round4(preRisk), round4(evidenceRisk), evidenceAvailable,
                round4(adjustedCurrentRisk), round4(adjustedAccumulatedRisk), round4(adjustedAccumulatedRisk),
                band(adjustedAccumulatedRisk), primaryFactor, round4(adjustedTemperature),
                round4(validationTemperature), round4(explorationTemperature),
                round4(softmaxTemperature), round4(spikePenalty), round4(rewriteOverreach),
                rewriteHonesty.honestyStatus(), rewriteHonesty.overreachType(), rewriteHonesty.sourceHash12(),
                components, adjustedLaneWeights, emergent);
    }

    private static double validationTemperature(double temperature, double accumulatedRisk,
            double minTemp, double maxTemp) {
        double coolDown = 0.03d * clamp01(accumulatedRisk);
        return clamp(temperature - coolDown, minTemp, maxTemp);
    }

    private static double explorationTemperature(double temperature,
            double validationTemperature,
            EmergentAdjustment emergent,
            double minTemp,
            double maxTemp) {
        double trustedLift = emergent != null && emergent.enabled() && emergent.riskDelta() < 0.0d
                ? clamp(-emergent.riskDelta() * 0.50d, 0.0d, 0.04d)
                : 0.0d;
        double candidate = clamp(temperature + trustedLift, minTemp, maxTemp);
        return Math.max(validationTemperature, candidate);
    }

    private static EmergentAdjustment emergentAdjustment(EmergentConfig config,
            Map<String, Object> trace,
            int observedEvidenceCount,
            double currentRisk,
            double accumulatedRisk,
            double scarcity,
            double providerFailure,
            double afterFilterStarvation,
            double contradiction,
            double latencyPressure) {
        EmergentConfig cfg = config == null ? EmergentConfig.disabled() : config;
        if (!cfg.enabled()) {
            return EmergentAdjustment.disabled();
        }

        double rejected = validationRejected(trace);
        double finalGateFailed = finalGateFailed(trace);
        double negativeReward = negativeReward(trace);
        double positiveReward = positiveReward(trace);
        double acceptedQuality = acceptedQuality(trace, observedEvidenceCount);
        double recoveryPressure = clamp01(Math.max(Math.max(scarcity, afterFilterStarvation),
                Math.max(providerFailure, contradiction * 0.80d)));

        double negative = clamp01(0.24d * providerFailure
                + 0.22d * afterFilterStarvation
                + 0.16d * contradiction
                + 0.12d * latencyPressure
                + 0.14d * rejected
                + 0.10d * finalGateFailed
                + 0.10d * negativeReward
                + 0.08d * scarcity);
        double positive = clamp01(0.42d * acceptedQuality + 0.24d * positiveReward);

        double net = clamp(negative - 0.70d * positive, -1.0d, 1.0d);
        double riskDelta = clamp(net * cfg.maxRiskDelta(), -cfg.maxRiskDelta(), cfg.maxRiskDelta());
        if (Math.abs(riskDelta) < 0.0005d) {
            riskDelta = 0.0d;
        }

        double temperatureDelta = net > 0.0d
                ? -clamp(recoveryPressure * cfg.maxTemperatureDelta(), 0.0d, cfg.maxTemperatureDelta())
                : 0.0d;
        double searchRangeDelta = net > 0.0d
                ? clamp(recoveryPressure * cfg.maxSearchRangeDelta(), 0.0d, cfg.maxSearchRangeDelta())
                : 0.0d;

        Map<String, Double> laneDeltas = new LinkedHashMap<>();
        double rcBoost = clamp(Math.max(providerFailure, contradiction) * cfg.maxLaneWeightDelta(),
                0.0d, cfg.maxLaneWeightDelta());
        double bqBoost = clamp(afterFilterStarvation * cfg.maxLaneWeightDelta() * 0.50d,
                0.0d, cfg.maxLaneWeightDelta());
        laneDeltas.put("BQ", round4(bqBoost));
        laneDeltas.put("ER", 0.0d);
        laneDeltas.put("RC", round4(rcBoost));

        Map<String, Double> components = new LinkedHashMap<>();
        components.put("negative", round4(negative));
        components.put("positive", round4(positive));
        components.put("providerFailure", round4(providerFailure));
        components.put("afterFilterStarvation", round4(afterFilterStarvation));
        components.put("contradiction", round4(contradiction));
        components.put("latencyPressure", round4(latencyPressure));
        components.put("validationRejected", round4(rejected));
        components.put("finalGateFailed", round4(finalGateFailed));
        components.put("negativeReward", round4(negativeReward));
        components.put("positiveReward", round4(positiveReward));
        components.put("acceptedQuality", round4(acceptedQuality));
        components.put("baselineCurrentRisk", round4(currentRisk));
        components.put("baselineAccumulatedRisk", round4(accumulatedRisk));

        return new EmergentAdjustment(true, EMERGENT_POLICY, round4(riskDelta),
                round4(temperatureDelta), round4(searchRangeDelta), emergentReason(negative, positive),
                components, laneDeltas);
    }

    private static Map<String, Double> adjustLaneWeights(Map<String, Double> laneWeights,
            EmergentAdjustment emergent) {
        if (laneWeights == null || laneWeights.isEmpty() || emergent == null || !emergent.enabled()) {
            return laneWeights == null ? uniformLaneWeights() : laneWeights;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        double sum = 0.0d;
        for (String lane : new String[] { "BQ", "ER", "RC" }) {
            double base = laneWeights.getOrDefault(lane, 1.0d);
            double delta = emergent.laneWeightDeltas().getOrDefault(lane, 0.0d);
            double next = clamp(base + delta, 0.25d, 2.50d);
            out.put(lane, next);
            sum += next;
        }
        if (sum > 0.0d) {
            for (String lane : new String[] { "BQ", "ER", "RC" }) {
                out.put(lane, round4(3.0d * out.get(lane) / sum));
            }
        }
        return out;
    }

    private static String emergentReason(double negative, double positive) {
        if (negative > positive + 0.05d) {
            return "recover_risk";
        }
        if (positive > negative + 0.05d) {
            return "validated_soften";
        }
        return "balanced";
    }

    private static RewriteHonesty honestyFromMetadata(String query, Map<String, Object> metadata,
            Map<String, Object> trace) {
        double score = firstFinite(Double.NaN,
                metaDouble(metadata, "selfask.rewrite.overreachScore"),
                metaDouble(metadata, "resource.rewriteOverreachScore"),
                traceDouble(trace, "selfask.rewrite.overreachScore"),
                traceDouble(trace, "ml.risk.rewrite.overreachScore"));
        String status = firstNonBlank(
                mapString(metadata, "selfask.rewrite.honestyStatus"),
                mapString(metadata, "resource.rewriteHonestyStatus"),
                traceString(trace, "selfask.rewrite.honestyStatus"),
                traceString(trace, "ml.risk.rewrite.honestyStatus"));
        String type = firstNonBlank(
                mapString(metadata, "selfask.rewrite.overreachType"),
                mapString(metadata, "resource.rewriteOverreachType"),
                traceString(trace, "selfask.rewrite.overreachType"),
                traceString(trace, "ml.risk.rewrite.overreachType"));
        String sourceHash = firstNonBlank(
                mapString(metadata, "selfask.rewrite.sourceHash"),
                mapString(metadata, "resource.rewriteSourceHash12"),
                traceString(trace, "selfask.rewrite.sourceHash"),
                traceString(trace, "ml.risk.rewrite.sourceHash"));
        if (!Double.isFinite(score)) {
            score = scoreForStatus(status);
        }
        if (!Double.isFinite(score)) {
            score = 0.0d;
        }
        return new RewriteHonesty(status, type, score,
                sourceHash == null || sourceHash.isBlank() ? SafeRedactor.hash12(query) : sourceHash);
    }

    private static double scoreForStatus(String status) {
        if (status == null || status.isBlank()) {
            return Double.NaN;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "OVERREACH" -> 0.80d;
            case "CHECK" -> 0.45d;
            case "CLEAN" -> 0.0d;
            default -> Double.NaN;
        };
    }

    private static String normalizeStatus(String status, double score) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (s.equals("CLEAN") || s.equals("CHECK") || s.equals("OVERREACH")) {
            return s;
        }
        if (score >= 0.70d) {
            return "OVERREACH";
        }
        if (score >= 0.35d) {
            return "CHECK";
        }
        return "CLEAN";
    }

    private static boolean hasCorrectionSignal(String text) {
        return containsAny(text, "아니다", "아니고", "아님", "절대 아니", "그런 뜻 아니다",
                "그런 생각", "오해", "정정", "다른 대표", "대표 이야기", "not intended", "not said");
    }

    private static double previousRisk(Map<String, Object> metadata, Map<String, Object> trace, double fallback) {
        return clamp01(firstFinite(fallback,
                metaDouble(metadata, "resource.rewriteRiskAccumulated"),
                metaDouble(metadata, "resource.rewriteRiskScore"),
                traceDouble(trace, "ml.risk.rewrite.accumulatedScore"),
                traceDouble(trace, "ml.risk.rewrite.accumulated"),
                traceDouble(trace, "resource.rewriteRiskScore"),
                traceDouble(trace, "ml.risk.rewrite.score")));
    }

    private static double spikePenalty(double previousRisk,
            double currentRisk,
            double evidenceRisk,
            double preRisk,
            double afterFilterStarvation,
            double providerFailure) {
        double suddenRise = Math.max(0.0d, currentRisk - previousRisk - 0.20d) * 0.25d;
        double evidenceSpike = Math.max(0.0d, evidenceRisk - preRisk - 0.25d) * 0.20d;
        double starvationSpike = afterFilterStarvation > 0.0d ? 0.03d : 0.0d;
        double providerSpike = providerFailure >= 0.66d ? 0.03d : 0.0d;
        return clamp(suddenRise + evidenceSpike + starvationSpike + providerSpike, 0.0d, 0.12d);
    }

    private static Map<String, Double> components(double value,
            double optimism,
            double uncertainty,
            double modeRisk,
            double historyPenalty,
            double rewriteOverreach,
            double scarcity,
            double providerFailure,
            double afterFilterStarvation,
            double contradiction,
            double latencyPressure,
            double previousRisk,
            double spikePenalty) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("value", round4(0.28d * clamp01(value)));
        out.put("optimism", round4(0.22d * clamp01(optimism)));
        out.put("uncertainty", round4(0.20d * clamp01(uncertainty)));
        out.put("mode", round4(0.15d * clamp01(modeRisk)));
        out.put("history", round4(0.15d * clamp01(historyPenalty)));
        out.put("rewriteOverreach", round4(0.10d * clamp01(rewriteOverreach)));
        out.put("scarcity", round4(0.30d * clamp01(scarcity)));
        out.put("providerFailure", round4(0.25d * clamp01(providerFailure)));
        out.put("afterFilterStarvation", round4(0.20d * clamp01(afterFilterStarvation)));
        out.put("contradiction", round4(0.15d * clamp01(contradiction)));
        out.put("latencyPressure", round4(0.10d * clamp01(latencyPressure)));
        out.put("previousScore", round4(previousRisk));
        out.put("spikePenalty", round4(spikePenalty));
        return out;
    }

    private static Map<String, Double> laneWeights(String query,
            Map<String, Object> metadata,
            double uncertainty,
            double scarcity,
            double providerFailure,
            double contradiction,
            double optimism,
            double softmaxTemperature) {
        double freshness = wantsFresh(query, metadata) ? 1.0d : 0.0d;
        double aliasNoise = aliasNoise(query, metadata);
        double bq = 1.00d + 0.40d * freshness + 0.25d * scarcity;
        double er = 1.00d + 0.35d * aliasNoise + 0.20d * uncertainty;
        double rc = 1.00d + 0.45d * contradiction + 0.30d * optimism + 0.20d * providerFailure;
        double t = clamp(softmaxTemperature, 0.35d, 0.75d);
        double max = Math.max(bq, Math.max(er, rc));
        double ebq = Math.exp((bq - max) / t);
        double eer = Math.exp((er - max) / t);
        double erc = Math.exp((rc - max) / t);
        double sum = ebq + eer + erc;
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("BQ", round4(3.0d * ebq / sum));
        out.put("ER", round4(3.0d * eer / sum));
        out.put("RC", round4(3.0d * erc / sum));
        return out;
    }

    private static Map<String, Double> uniformLaneWeights() {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("BQ", 1.0d);
        out.put("ER", 1.0d);
        out.put("RC", 1.0d);
        return out;
    }

    private static String primaryFactor(double value,
            double optimism,
            double uncertainty,
            double modeRisk,
            double historyPenalty,
            double rewriteOverreach,
            double scarcity,
            double providerFailure,
            double afterFilterStarvation,
            double contradiction,
            double latencyPressure,
            boolean evidenceAvailable) {
        String name = "value";
        double best = value;
        if (optimism > best) {
            name = "optimism";
            best = optimism;
        }
        if (uncertainty > best) {
            name = "uncertainty";
            best = uncertainty;
        }
        if (modeRisk > best) {
            name = "mode";
            best = modeRisk;
        }
        if (historyPenalty > best) {
            name = "history";
            best = historyPenalty;
        }
        if (rewriteOverreach > best) {
            name = "rewriteOverreach";
            best = rewriteOverreach;
        }
        if (evidenceAvailable) {
            if (scarcity > best) {
                name = "scarcity";
                best = scarcity;
            }
            if (providerFailure > best) {
                name = "providerFailure";
                best = providerFailure;
            }
            if (afterFilterStarvation > best) {
                name = "afterFilterStarvation";
                best = afterFilterStarvation;
            }
            if (contradiction > best) {
                name = "contradiction";
                best = contradiction;
            }
            if (latencyPressure > best) {
                name = "latencyPressure";
            }
        }
        return name;
    }

    private static double modeRisk(String query, Map<String, Object> metadata) {
        double risk = 0.15d;
        if (metaBool(metadata, "nightmareMode") || metaBool(metadata, "ruleBreak")) {
            risk = Math.max(risk, 0.80d);
        }
        if (metaBool(metadata, "isExploration") || metaBool(metadata, "enableSelfAsk")) {
            risk = Math.max(risk, 0.35d);
        }
        if (wantsFresh(query, metadata)) {
            risk = Math.max(risk, 0.35d);
        }
        return clamp01(risk);
    }

    private static double deriveOptimism(String query, double value) {
        double base = value >= 0.65d ? 0.45d : 0.28d;
        if (containsAny(query, "latest", "price", "risk", "news", "\ucd5c\uc2e0", "\uac00\uaca9", "\ub9ac\uc2a4\ud06c")) {
            base += 0.08d;
        }
        return clamp01(base);
    }

    private static boolean wantsFresh(String query, Map<String, Object> metadata) {
        return metaBool(metadata, "wantsFresh")
                || metaBool(metadata, "freshnessRequired")
                || containsAny(query, "latest", "recent", "today", "price", "news",
                "\ucd5c\uc2e0", "\uadfc\ud669", "\uc624\ub298", "\uac00\uaca9");
    }

    private static double aliasNoise(String query, Map<String, Object> metadata) {
        double risk = 0.0d;
        if (metaBool(metadata, "aliasCorrected") || metaBool(metadata, "typoCorrected")) {
            risk = Math.max(risk, 0.75d);
        }
        Object aliases = metadata == null ? null : metadata.get("aliases");
        if (aliases instanceof Iterable<?> it) {
            int count = 0;
            for (Object ignored : it) {
                count++;
                if (count >= 3) {
                    break;
                }
            }
            risk = Math.max(risk, Math.min(1.0d, count / 3.0d));
        }
        String q = query == null ? "" : query;
        if (q.matches(".*[A-Za-z][^\\s]{12,}.*") || q.contains("/") || q.contains("_")) {
            risk = Math.max(risk, 0.35d);
        }
        return clamp01(risk);
    }

    private static double scarcity(int observedEvidenceCount, int targetEvidenceK, Map<String, Object> trace) {
        int target = Math.max(1, targetEvidenceK);
        if (observedEvidenceCount >= 0) {
            return clamp01(1.0d - (observedEvidenceCount / (double) target));
        }
        double traced = firstFinite(Double.NaN,
                traceDouble(trace, "rag.selfask.count"),
                traceDouble(trace, "cfvm.sig.docCount"),
                traceDouble(trace, "rag.fusion.sizes.selfask"));
        if (Double.isFinite(traced)) {
            return clamp01(1.0d - (traced / target));
        }
        return 0.0d;
    }

    private static double providerFailure(Map<String, Object> trace) {
        double disabledCount = 0.0d;
        for (String prefix : WEB_PROVIDER_PREFIXES) {
            disabledCount += traceFlag(trace, prefix + ".providerDisabled");
        }
        if (traceString(trace, "selfask.3way.api.disabledReason") != null) {
            disabledCount += 0.75d;
        }
        double rateOrTimeout = Math.min(1.0d, (
                traceDoubleOrZero(trace, "web.await.events.timeout.count")
                        + traceDoubleOrZero(trace, "web.await.events.nonOk.count")
                        + traceDoubleOrZero(trace, "web.failsoft.rateLimitBackoff.skipped.cooldown.count")) / 3.0d);
        return clamp01(Math.max(disabledCount / WEB_PROVIDER_PREFIXES.length, rateOrTimeout));
    }

    private static double afterFilterStarvation(Map<String, Object> trace) {
        for (String prefix : AFTER_FILTER_TRACE_PREFIXES) {
            double raw = firstFinite(Double.NaN,
                    traceDouble(trace, prefix + ".filter.rawCount"),
                    traceDouble(trace, prefix + ".rawCount"),
                    traceDouble(trace, prefix + ".returnedCount"));
            double after = firstFinite(Double.NaN,
                    traceDouble(trace, prefix + ".afterFilterCount"),
                    traceDouble(trace, prefix + ".filter.afterCount"));
            if (Double.isFinite(raw) && raw > 0.0d && Double.isFinite(after) && after <= 0.0d) {
                return 1.0d;
            }
        }
        return 0.0d;
    }

    private static double contradiction(Map<String, Object> trace) {
        return clamp01(firstFinite(0.0d,
                traceDouble(trace, "overdrive.contradiction.mean"),
                traceDouble(trace, "extremez.risk.contradictionScore"),
                traceDouble(trace, "rag.contradiction.score"),
                traceDouble(trace, "learning.validation.contradictionScore")));
    }

    private static double latencyPressure(Map<String, Object> trace) {
        double events = traceDoubleOrZero(trace, "web.await.events.count");
        double failures = traceDoubleOrZero(trace, "web.await.events.timeout.count")
                + traceDoubleOrZero(trace, "web.await.events.nonOk.count")
                + traceDoubleOrZero(trace, "web.await.skipped.count")
                + traceDoubleOrZero(trace, "web.await.budgetExhausted")
                + traceDoubleOrZero(trace, "web.failsoft.rateLimitBackoff.skipped.cooldown.count");
        double ratio = events > 0.0d ? failures / events : Math.min(1.0d, failures);
        double remaining = Math.max(
                Math.max(
                        traceDoubleOrZero(trace, "web.failsoft.rateLimitBackoff.naver.remainingMs"),
                        traceDoubleOrZero(trace, "web.failsoft.rateLimitBackoff.brave.remainingMs")),
                Math.max(
                        traceDoubleOrZero(trace, "web.failsoft.rateLimitBackoff.serpapi.remainingMs"),
                        traceDoubleOrZero(trace, "web.failsoft.rateLimitBackoff.tavily.remainingMs")));
        return clamp01(Math.max(ratio, remaining > 0.0d ? 0.70d : 0.0d));
    }

    private static boolean hasEvidenceTrace(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        for (String key : trace.keySet()) {
            if (key != null && (key.contains("returnedCount") || key.contains("afterFilterCount")
                    || key.contains("rawCount") || key.contains("rag.selfask.count"))) {
                return true;
            }
        }
        return false;
    }

    private static double validationRejected(Map<String, Object> trace) {
        String decision = traceString(trace, "learning.validation.decision");
        if (decision != null && decision.equalsIgnoreCase("rejected")) {
            return 1.0d;
        }
        return containsTraceToken(trace, "learning.validation.rejectReasons",
                "sample_score_below_threshold",
                "contamination_risk",
                "provider_disabled",
                "final_gate_failed",
                "unconfirmed_high_risk_requery") ? 1.0d : 0.0d;
    }

    private static double finalGateFailed(Map<String, Object> trace) {
        if (traceFlag(trace, "learning.validation.finalGateFailed") > 0.0d) {
            return 1.0d;
        }
        return containsTraceToken(trace, "learning.validation.rejectReasons", "final_gate_failed") ? 1.0d : 0.0d;
    }

    private static double negativeReward(Map<String, Object> trace) {
        double reward = firstFinite(Double.NaN,
                traceDouble(trace, "cfvm.reward.adjusted"),
                traceDouble(trace, "cfvm.kalloc.reward"));
        return Double.isFinite(reward) ? clamp01(-reward) : 0.0d;
    }

    private static double positiveReward(Map<String, Object> trace) {
        double reward = firstFinite(Double.NaN,
                traceDouble(trace, "cfvm.reward.adjusted"),
                traceDouble(trace, "cfvm.kalloc.reward"));
        return Double.isFinite(reward) ? clamp01(reward) : 0.0d;
    }

    private static double acceptedQuality(Map<String, Object> trace, int observedEvidenceCount) {
        String decision = traceString(trace, "learning.validation.decision");
        boolean accepted = decision != null && decision.equalsIgnoreCase("accepted")
                && !containsTraceToken(trace, "learning.validation.rejectReasons",
                "sample_score_below_threshold",
                "contamination_risk",
                "provider_disabled",
                "final_gate_failed",
                "unconfirmed_high_risk_requery");
        if (!accepted) {
            return 0.0d;
        }
        double evidence = observedEvidenceCount >= 0
                ? observedEvidenceCount
                : firstFinite(0.0d,
                        traceDouble(trace, "learning.runtime.evidenceCount"),
                        traceDouble(trace, "learning.validation.evidenceCount"),
                        traceDouble(trace, "cfvm.sig.docCount"),
                        traceDouble(trace, "rag.selfask.count"));
        double evidenceScore = clamp01(evidence / 5.0d);
        double diversity = clamp01(firstFinite(0.0d,
                traceDouble(trace, "learning.runtime.contextDiversity"),
                traceDouble(trace, "learning.validation.contextDiversity"),
                traceDouble(trace, "dataset.contextDiversity")));
        double laneCoverage = clamp01(firstFinite(0.0d,
                traceDouble(trace, "learning.validation.selfAskLaneCoverage"),
                traceDouble(trace, "learning.runtime.laneCoverage")));
        boolean requeryRequired = traceFlag(trace, "learning.validation.requeryRequired") > 0.0d;
        boolean requeryConfirmed = !requeryRequired
                || traceFlag(trace, "learning.validation.requeryConfirmed") > 0.0d
                || traceFlag(trace, "selfask.3way.requery.confirmed") > 0.0d;
        double sampleScore = clamp01(firstFinite(0.0d, traceDouble(trace, "learning.validation.sampleScore")));
        return clamp01(0.30d * evidenceScore
                + 0.20d * diversity
                + 0.20d * laneCoverage
                + 0.15d * (requeryConfirmed ? 1.0d : 0.0d)
                + 0.15d * sampleScore);
    }

    private static boolean containsTraceToken(Map<String, Object> trace, String key, String... tokens) {
        if (trace == null || key == null || tokens == null || tokens.length == 0) {
            return false;
        }
        Object value = trace.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Iterable<?> it) {
            for (Object item : it) {
                if (containsToken(item, tokens)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (containsToken(item, tokens)) {
                    return true;
                }
            }
            return false;
        }
        return containsToken(value, tokens);
    }

    private static boolean containsToken(Object value, String... tokens) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (token != null && !token.isBlank()
                    && text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static double traceFlag(Map<String, Object> trace, String key) {
        if (trace == null || key == null) {
            return 0.0d;
        }
        Object value = trace.get(key);
        return truthy(value) ? 1.0d : 0.0d;
    }

    private static boolean metaBool(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return false;
        }
        return truthy(metadata.get(key));
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0d;
        }
        String s = String.valueOf(value).trim();
        return s.equalsIgnoreCase("true") || s.equals("1")
                || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("y");
    }

    private static double metaDouble(Map<String, Object> metadata, String key) {
        return toDouble(metadata == null ? null : metadata.get(key));
    }

    private static double traceDouble(Map<String, Object> trace, String key) {
        return toDouble(trace == null ? null : trace.get(key));
    }

    private static double traceDoubleOrZero(Map<String, Object> trace, String key) {
        double v = traceDouble(trace, key);
        return Double.isFinite(v) ? v : 0.0d;
    }

    private static String traceString(Map<String, Object> trace, String key) {
        if (trace == null || key == null) {
            return null;
        }
        Object value = trace.get(key);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String mapString(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return null;
        }
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
                TraceStore.put("selfask.rewriteRisk.suppressed.stage", "toDouble");
                TraceStore.put("selfask.rewriteRisk.suppressed.errorType", "invalid_number");
                TraceStore.put("selfask.rewriteRisk.suppressed.toDouble", true);
                TraceStore.put("selfask.rewriteRisk.suppressed.toDouble.errorType", "invalid_number");
                return Double.NaN;
            }
        }
        if (value instanceof Boolean b) {
            return b ? 1.0d : 0.0d;
        }
        return Double.NaN;
    }

    private static double firstFinite(double fallback, double... values) {
        if (values != null) {
            for (double value : values) {
                if (Double.isFinite(value)) {
                    return value;
                }
            }
        }
        return fallback;
    }

    private static boolean containsAny(String raw, String... needles) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String text = raw.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isBlank()
                    && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String band(double risk) {
        if (risk >= 0.70d) {
            return "HIGH";
        }
        if (risk >= 0.35d) {
            return "MEDIUM";
        }
        return "LOW";
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
