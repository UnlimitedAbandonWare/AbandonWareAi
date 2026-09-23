package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.learn.CfvmBanditStore;
import com.example.lms.service.guard.SensitiveTopicDetector;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class PromptPoseApplicationJudge {

    private static final Logger LOG = LoggerFactory.getLogger(PromptPoseApplicationJudge.class);

    private final PromptPoseProperties props;
    private final CfvmBanditStore banditStore;

    public PromptPoseApplicationJudge(PromptPoseProperties props, CfvmBanditStore banditStore) {
        this.props = props == null ? new PromptPoseProperties() : props;
        this.banditStore = banditStore;
    }

    public PromptPoseApplicationDecision decide(PromptPoseInputSanitizer.SanitizedInput input, int requestedMaxQueries) {
        if (input == null || input.blocked() || !props.isEnabled() || !applicationProps().isEnabled()) {
            return PromptPoseApplicationDecision.disabled(input == null ? "empty_input" : input.skipReason());
        }

        String intentSlot = intentSlot(input);
        String failureSlot = failureSlot();
        String evidenceSlot = evidenceSlot(intentSlot, failureSlot, input.preview());
        Feedback feedback = feedback(intentSlot, failureSlot);
        Knobs knobs = baseKnobs(intentSlot, evidenceSlot);
        knobs = applyFailure(knobs, failureSlot);
        knobs = applyFeedback(knobs, feedback);

        int queryBurstMax = clampInt(knobs.queryBurstMax, 1, effectiveMaxQueryBurst(requestedMaxQueries));
        int selfAskCount = clampInt(knobs.selfAskCount, 1, maxSelfAsk());
        int minCitations = clampInt(knobs.minCitations, 0, 8);
        double answerTemp = clamp(knobs.answerTemperature, 0.0d, maxAnswerTemperature());
        double selfAskTemp = clamp(knobs.selfAskTemperature, 0.0d, maxSelfAskTemperature());
        Map<String, Double> weights = safeWeights(knobs.bqWeight, knobs.erWeight, knobs.rcWeight);
        Map<String, Double> callRatios = normalize(weights, 0.34d, 0.33d, 0.33d);
        Map<String, Double> timeboxRatios = normalize(timeboxWeights(weights, evidenceSlot), 0.38d, 0.32d, 0.30d);
        String compressionMode = queryBurstMax >= 10 || "after_filter_starvation".equals(failureSlot)
                || "context_contamination".equals(failureSlot)
                ? "overdrive_hint"
                : "off";
        String reason = "application:" + intentSlot + ":" + failureSlot + ":" + feedback.slot;
        String hash = SafeRedactor.hash12(String.join("|",
                input.queryHash12(), intentSlot, evidenceSlot, failureSlot, feedback.slot,
                String.valueOf(queryBurstMax), String.valueOf(minCitations), weights.toString()));
        return new PromptPoseApplicationDecision(true, intentSlot, evidenceSlot, failureSlot,
                feedback.slot, feedback.tile, hash, reason, queryBurstMax, selfAskCount,
                answerTemp, selfAskTemp, minCitations, weights, callRatios, timeboxRatios,
                knobs.riskPenaltyLambda, knobs.minLaneCoverage, knobs.confidence,
                feedback.mean, feedback.count, compressionMode);
    }

    public PromptPosePlan mergeIntoPlan(PromptPosePlan base, PromptPoseApplicationDecision decision, String routeModel) {
        if (decision == null || !decision.enabled()) {
            return base;
        }
        PromptPosePlan appPlan = decision.toPlan(routeModel);
        if (base == null || !base.enabled() || base.arm() == PromptPoseArm.NO_DRAFT || !base.hasRoutingHints()) {
            return appPlan;
        }
        Map<String, Double> mergedWeights = mergeWeights(base.laneWeights(), decision.laneWeights());
        return new PromptPosePlan(true,
                base.arm() == PromptPoseArm.NO_DRAFT ? PromptPoseArm.LOCAL_LIGHT : base.arm(),
                base.routeModel().isBlank() ? routeModel : base.routeModel(),
                base.assistantDraftLines(),
                base.queryBurstSeeds(),
                Math.max(base.queryBurstMin(), appPlan.queryBurstMin()),
                Math.max(base.queryBurstMax(), appPlan.queryBurstMax()),
                Math.max(base.selfAskCount(), appPlan.selfAskCount()),
                mergedWeights,
                Math.max(base.answerTemperature(), appPlan.answerTemperature()),
                Math.max(base.selfAskTemperature(), appPlan.selfAskTemperature()),
                Math.max(base.minCitations(), appPlan.minCitations()),
                Math.max(base.confidence(), appPlan.confidence()),
                base.reasonCode().isBlank() ? decision.reasonCode() : base.reasonCode() + "+application");
    }

    private String intentSlot(PromptPoseInputSanitizer.SanitizedInput input) {
        String preview = safeLower(input.preview());
        if (SensitiveTopicDetector.isSensitiveText(preview) || containsAny(preview,
                "citation", "verify", "evidence", "official", "source", "fact", "factual", "accuracy",
                "latest", "current", "medical", "health", "diagnosis", "treatment", "legal", "law",
                "attorney", "privacy", "personal data", "credential", "password", "api key", "security",
                "vulnerability", "self-harm", "self harm", "suicide", "suicidal", "abuse", "trauma", "ptsd",
                "prescription", "lawsuit", "oauth token", "access token", "personal information",
                "검증", "근거", "인용", "출처", "논문", "사실", "정확", "최신", "현재",
                "의료", "건강", "진단", "치료", "법률", "법적", "개인정보", "자격 증명", "비밀번호",
                "보안", "취약점", "자해", "자살", "학대", "폭력", "트라우마", "처방", "소송", "개인 정보")) {
            return "evidence_strict";
        }
        if (containsAny(preview, "patch", "fix", "debug", "failure", "fail", "error", "exception", "장애", "수정")) {
            return "debug_patch";
        }
        if ("compare".equals(input.coarseIntent()) || containsAny(preview, "compare", "vs", "tradeoff", "비교")) {
            return "compare";
        }
        if (containsAny(preview, "explore", "brainstorm", "creative", "idea", "탐색", "응용", "아이디어", "과감")) {
            return "explore";
        }
        return "general";
    }

    private String evidenceSlot(String intentSlot, String failureSlot, String preview) {
        String lower = safeLower(preview);
        if ("evidence_strict".equals(intentSlot) || "insufficient_citations".equals(failureSlot)
                || containsAny(lower, "official", "citation", "검증", "근거", "인용")) {
            return "strict";
        }
        if ("explore".equals(intentSlot)) {
            return "exploratory";
        }
        return "balanced";
    }

    private String failureSlot() {
        Map<String, Object> trace = TraceStore.getAll();
        String raw = firstTrace(trace,
                "blackbox.risk.dominantFailure",
                "extremez.risk.primaryCause",
                "web.brave.skipped.reason",
                "web.naver.skipped.reason",
                "web.tavily.skipped.reason",
                "web.serpapi.skipped.reason",
                "zero100.scheduler.failureClass",
                "queryTransformer.reason",
                "starvationFallback.trigger",
                "promptPose.failureClass");
        String s = safeLower(raw);
        if (s.isBlank() || "none".equals(s)) {
            return "none";
        }
        if (containsAny(s, "citation", "mincitation", "insufficient")) {
            return "insufficient_citations";
        }
        if (containsAny(s, "after_filter", "after-filter", "starvation", "zero-result")) {
            return "after_filter_starvation";
        }
        if (containsAny(s, "timeout", "timedout")) {
            return "timeout";
        }
        if (containsAny(s, "429", "rate")) {
            return "rate_limit";
        }
        if (containsAny(s, "disabled", "missing", "provider")) {
            return "provider_disabled";
        }
        if (containsAny(s, "contamination", "history")) {
            return "context_contamination";
        }
        return safeToken(s, "other");
    }

    private Knobs baseKnobs(String intentSlot, String evidenceSlot) {
        PromptPoseProperties.Application app = applicationProps();
        Knobs k = new Knobs();
        k.queryBurstMax = clampInt(app.getFailureQueryburstCount(), 6, app.getMaxQueryburstCount());
        k.selfAskCount = 2;
        k.minCitations = 3;
        k.answerTemperature = 0.20d;
        k.selfAskTemperature = 0.34d;
        k.bqWeight = 1.0d;
        k.erWeight = 1.0d;
        k.rcWeight = 1.0d;
        k.riskPenaltyLambda = 0.50d;
        k.minLaneCoverage = 2;
        k.confidence = 0.62d;
        if ("explore".equals(intentSlot)) {
            k.queryBurstMax = clampInt(app.getExploreQueryburstCount(), 8, app.getMaxQueryburstCount());
            k.selfAskCount = 3;
            k.minCitations = Math.max(0, app.getMinCitationsExplore());
            k.answerTemperature = 0.30d;
            k.selfAskTemperature = 0.48d;
            k.bqWeight = 0.90d;
            k.erWeight = 1.05d;
            k.rcWeight = 1.25d;
            k.riskPenaltyLambda = 0.48d;
            k.confidence = 0.68d;
        } else if ("strict".equals(evidenceSlot)) {
            k.queryBurstMax = clampInt(app.getStrictQueryburstCount(), 4, app.getMaxQueryburstCount());
            k.selfAskCount = 2;
            k.minCitations = Math.max(0, app.getMinCitationsStrict());
            k.answerTemperature = 0.16d;
            k.selfAskTemperature = 0.30d;
            k.bqWeight = 1.25d;
            k.erWeight = 0.95d;
            k.rcWeight = 0.85d;
            k.riskPenaltyLambda = 0.62d;
            k.minLaneCoverage = 3;
            k.confidence = 0.72d;
        } else if ("debug_patch".equals(intentSlot)) {
            k.queryBurstMax = 10;
            k.selfAskCount = 2;
            k.minCitations = 3;
            k.answerTemperature = 0.18d;
            k.selfAskTemperature = 0.32d;
            k.bqWeight = 1.15d;
            k.erWeight = 1.05d;
            k.rcWeight = 0.95d;
        } else if ("compare".equals(intentSlot)) {
            k.queryBurstMax = 12;
            k.selfAskCount = 3;
            k.minCitations = 3;
            k.answerTemperature = 0.22d;
            k.selfAskTemperature = 0.38d;
            k.bqWeight = 1.05d;
            k.erWeight = 1.15d;
            k.rcWeight = 1.05d;
        }
        return k;
    }

    private Knobs applyFailure(Knobs k, String failureSlot) {
        PromptPoseProperties.Application app = applicationProps();
        if ("insufficient_citations".equals(failureSlot)) {
            k.queryBurstMax = Math.min(k.queryBurstMax, 10);
            k.minCitations = Math.max(k.minCitations, app.getMinCitationsFailure());
            k.bqWeight = Math.max(k.bqWeight, 1.25d);
            k.erWeight = Math.max(k.erWeight, 1.0d);
            k.rcWeight = Math.min(k.rcWeight, 0.95d);
            k.riskPenaltyLambda = Math.max(k.riskPenaltyLambda, 0.70d);
            k.minLaneCoverage = 3;
            k.answerTemperature = Math.min(k.answerTemperature, 0.18d);
        } else if ("after_filter_starvation".equals(failureSlot)) {
            k.queryBurstMax = Math.max(k.queryBurstMax, 12);
            k.minCitations = Math.max(k.minCitations, 3);
            k.erWeight = Math.max(k.erWeight, 1.10d);
            k.rcWeight = Math.max(k.rcWeight, 1.15d);
            k.riskPenaltyLambda = Math.max(k.riskPenaltyLambda, 0.58d);
        } else if ("timeout".equals(failureSlot) || "rate_limit".equals(failureSlot)
                || "provider_disabled".equals(failureSlot)) {
            k.queryBurstMax = Math.min(k.queryBurstMax, 8);
            k.selfAskCount = Math.min(k.selfAskCount, 2);
            k.bqWeight = Math.max(k.bqWeight, 1.10d);
            k.rcWeight = Math.min(k.rcWeight, 1.0d);
            k.riskPenaltyLambda = Math.max(k.riskPenaltyLambda, 0.60d);
        } else if ("context_contamination".equals(failureSlot)) {
            k.queryBurstMax = Math.min(Math.max(k.queryBurstMax, 10), 12);
            k.minCitations = Math.max(k.minCitations, 3);
            k.bqWeight = Math.max(k.bqWeight, 1.15d);
            k.riskPenaltyLambda = Math.max(k.riskPenaltyLambda, 0.65d);
            k.minLaneCoverage = 3;
        }
        return k;
    }

    private Knobs applyFeedback(Knobs k, Feedback feedback) {
        if ("low_reward".equals(feedback.slot)) {
            k.bqWeight = Math.max(k.bqWeight, 1.08d);
            k.minCitations = Math.max(k.minCitations + 1, 3);
            k.riskPenaltyLambda = Math.min(1.0d, k.riskPenaltyLambda + 0.10d);
            k.answerTemperature = Math.min(k.answerTemperature, 0.24d);
        } else if ("high_reward".equals(feedback.slot)) {
            k.rcWeight = Math.min(1.25d, k.rcWeight + 0.05d);
            k.confidence = Math.min(1.0d, k.confidence + 0.08d);
        }
        return k;
    }

    private Feedback feedback(String intentSlot, String failureSlot) {
        String tile = intentSlot + ":" + failureSlot;
        if (banditStore == null) {
            return new Feedback("none", tile, 0.0d, 0L);
        }
        try {
            Map<String, CfvmBanditStore.TileStats> snapshot = banditStore.snapshot();
            Stats stats = statsFor(snapshot, "promptPose:" + tile);
            if (stats.count == 0L) {
                stats = statsFor(snapshot, "promptPose:default");
            }
            if (stats.count == 0L && snapshot != null) {
                stats = aggregatePromptPoseStats(snapshot);
            }
            if (stats.count == 0L) {
                return new Feedback("none", tile, 0.0d, 0L);
            }
            double low = clamp(applicationProps().getLowRewardThreshold(), 0.0d, 1.0d);
            double high = clamp(applicationProps().getHighRewardThreshold(), 0.0d, 1.0d);
            String slot = stats.mean < low ? "low_reward" : (stats.mean > high ? "high_reward" : "steady_reward");
            return new Feedback(slot, tile, round4(stats.mean), stats.count);
        } catch (Throwable t) {
            traceSkipped("cfvm_feedback_snapshot", t);
            return new Feedback("none", tile, 0.0d, 0L);
        }
    }

    private static Stats statsFor(Map<String, CfvmBanditStore.TileStats> snapshot, String key) {
        if (snapshot == null || key == null) {
            return new Stats(0.0d, 0L);
        }
        return stats(snapshot.get(key));
    }

    private static Stats aggregatePromptPoseStats(Map<String, CfvmBanditStore.TileStats> snapshot) {
        double reward = 0.0d;
        long count = 0L;
        for (Map.Entry<String, CfvmBanditStore.TileStats> entry : snapshot.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().startsWith("promptPose:")) {
                continue;
            }
            Stats s = stats(entry.getValue());
            reward += s.mean * s.count;
            count += s.count;
        }
        return count <= 0L ? new Stats(0.0d, 0L) : new Stats(reward / (double) count, count);
    }

    private static Stats stats(CfvmBanditStore.TileStats tile) {
        if (tile == null || tile.arms == null || tile.arms.isEmpty()) {
            return new Stats(0.0d, 0L);
        }
        double reward = 0.0d;
        long count = 0L;
        for (CfvmBanditStore.ArmStats arm : tile.arms.values()) {
            if (arm == null || arm.n <= 0L) {
                continue;
            }
            reward += arm.mean() * arm.n;
            count += arm.n;
        }
        return count <= 0L ? new Stats(0.0d, 0L) : new Stats(reward / (double) count, count);
    }

    private PromptPoseProperties.Application applicationProps() {
        return props.getApplication() == null ? new PromptPoseProperties.Application() : props.getApplication();
    }

    private int effectiveMaxQueryBurst(int requestedMaxQueries) {
        int policyMax = props.getPolicy() == null ? 18 : props.getPolicy().getMaxQueryburstCount();
        int appMax = applicationProps().getMaxQueryburstCount();
        int max = Math.max(1, Math.min(clampInt(policyMax, 1, 32), clampInt(appMax, 1, 32)));
        if (requestedMaxQueries > 0) {
            max = Math.min(max, requestedMaxQueries);
        }
        return max;
    }

    private int maxSelfAsk() {
        int max = props.getPolicy() == null ? 3 : props.getPolicy().getMaxSelfaskCount();
        return clampInt(max, 1, 3);
    }

    private double maxAnswerTemperature() {
        double appMax = applicationProps().getMaxAnswerTemperature();
        double policyMax = props.getPolicy() == null ? 0.55d : props.getPolicy().getMaxTemperature();
        return clamp(Math.min(appMax, policyMax), 0.0d, 1.0d);
    }

    private double maxSelfAskTemperature() {
        double appMax = applicationProps().getMaxSelfAskTemperature();
        double policyMax = props.getPolicy() == null ? 0.55d : props.getPolicy().getMaxTemperature();
        return clamp(Math.min(appMax, policyMax), 0.0d, 1.0d);
    }

    private static Map<String, Double> mergeWeights(Map<String, Double> base, Map<String, Double> overlay) {
        if (base == null || base.isEmpty()) {
            return overlay == null ? Map.of() : Map.copyOf(overlay);
        }
        if (overlay == null || overlay.isEmpty()) {
            return Map.copyOf(base);
        }
        return safeWeights(
                Math.max(value(base, "BQ", 1.0d), value(overlay, "BQ", 1.0d)),
                Math.max(value(base, "ER", 1.0d), value(overlay, "ER", 1.0d)),
                Math.max(value(base, "RC", 1.0d), value(overlay, "RC", 1.0d)));
    }

    private static Map<String, Double> safeWeights(double bq, double er, double rc) {
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        out.put("BQ", round4(clamp(bq, 0.05d, 1.25d)));
        out.put("ER", round4(clamp(er, 0.05d, 1.25d)));
        out.put("RC", round4(clamp(rc, 0.05d, 1.25d)));
        return Map.copyOf(out);
    }

    private static Map<String, Double> normalize(Map<String, Double> raw, double bq, double er, double rc) {
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        out.put("BQ", Math.max(0.0d, value(raw, "BQ", bq)));
        out.put("ER", Math.max(0.0d, value(raw, "ER", er)));
        out.put("RC", Math.max(0.0d, value(raw, "RC", rc)));
        double sum = out.values().stream().mapToDouble(Double::doubleValue).sum();
        if (!Double.isFinite(sum) || sum <= 0.0d) {
            out.put("BQ", bq);
            out.put("ER", er);
            out.put("RC", rc);
            sum = bq + er + rc;
        }
        double denominator = sum <= 0.0d ? 1.0d : sum;
        out.replaceAll((lane, value) -> round4(value / denominator));
        return Map.copyOf(out);
    }

    private static Map<String, Double> timeboxWeights(Map<String, Double> weights, String evidenceSlot) {
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        out.put("BQ", value(weights, "BQ", 1.0d) + ("strict".equals(evidenceSlot) ? 0.18d : 0.08d));
        out.put("ER", value(weights, "ER", 1.0d));
        out.put("RC", value(weights, "RC", 1.0d) + ("exploratory".equals(evidenceSlot) ? 0.12d : 0.0d));
        return out;
    }

    private static double value(Map<String, Double> map, String key, double fallback) {
        if (map == null || key == null) {
            return fallback;
        }
        Double value = map.get(key);
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private static String firstTrace(Map<String, Object> trace, String... keys) {
        if (trace == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = trace.get(key);
            if (value == null) {
                continue;
            }
            String s = String.valueOf(value).trim();
            if (!s.isBlank() && !"none".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return "";
    }

    private static String safeLower(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
    }

    private static String safeToken(String raw, String fallback) {
        String s = safeLower(raw).replaceAll("[^a-z0-9_-]+", "_");
        s = s.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (s.isBlank()) {
            return fallback;
        }
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    private static void traceSkipped(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        TraceStore.put("promptPose.application.feedbackSkipped", true);
        TraceStore.put("promptPose.application.feedbackStage", safeStage);
        TraceStore.put("promptPose.application.feedbackErrorType", errorType);
        LOG.debug("[AWX][prompt][pose] feedback trace skipped stage={} errorType={}",
                safeStage,
                safeErrorType);
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && containsMarker(value, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMarker(String value, String needle) {
        if (needle.codePoints().anyMatch(cp -> cp > 0x7f)) {
            return value.contains(needle);
        }
        int from = 0;
        while (from <= value.length() - needle.length()) {
            int index = value.indexOf(needle, from);
            if (index < 0) {
                return false;
            }
            int end = index + needle.length();
            boolean leftBoundary = index == 0 || !Character.isLetterOrDigit(value.charAt(index - 1));
            boolean rightBoundary = end == value.length() || !Character.isLetterOrDigit(value.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static int clampInt(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double clamp(double value, double low, double high) {
        if (!Double.isFinite(value)) {
            return low;
        }
        return Math.max(low, Math.min(high, value));
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static final class Knobs {
        int queryBurstMax;
        int selfAskCount;
        int minCitations;
        double answerTemperature;
        double selfAskTemperature;
        double bqWeight;
        double erWeight;
        double rcWeight;
        double riskPenaltyLambda;
        int minLaneCoverage;
        double confidence;
    }

    private record Feedback(String slot, String tile, double mean, long count) {
    }

    private record Stats(double mean, long count) {
    }
}
