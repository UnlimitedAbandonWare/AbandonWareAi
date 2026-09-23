package com.example.lms.service.rag;

import com.example.lms.config.SelfAskProperties;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.prompt.pose.PromptPoseTrace;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.query.SelfAskRewriteRiskScorer;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SelfAskPlanner {

    private static final Logger log = LoggerFactory.getLogger(SelfAskPlanner.class);

    private static final String MODEL_BQ = "llmrouter.gemma";
    private static final String MODEL_ER = "llmrouter.light";
    private static final String MODEL_RC = "llmrouter.api3";
    private static final String HONEST_REWRITE_CONTRACT = """
            Preserve source boundaries. Separate quote, fact, inference, and recommendation.
            Keep speaker attribution and negations such as "not intended", "misunderstanding", or "different case".
            Do not turn ambiguous distress, insult, debt, or equipment-spend context into a factual accusation.
            Output a search query only; never invent intent, actor ownership, or confirmed harm.
            """;

    private final ChatModel chatModel;
    private final ObjectProvider<DynamicChatModelFactory> modelFactoryProvider;
    private final ObjectProvider<SelfAskProperties> selfAskPropertiesProvider;

    public SelfAskPlanner(@Qualifier("localChatModel") ChatModel chatModel,
            ObjectProvider<DynamicChatModelFactory> modelFactoryProvider) {
        this(chatModel, modelFactoryProvider, null);
    }

    @Autowired
    public SelfAskPlanner(@Qualifier("localChatModel") ChatModel chatModel,
            ObjectProvider<DynamicChatModelFactory> modelFactoryProvider,
            ObjectProvider<SelfAskProperties> selfAskPropertiesProvider) {
        this.chatModel = chatModel;
        this.modelFactoryProvider = modelFactoryProvider;
        this.selfAskPropertiesProvider = selfAskPropertiesProvider;
    }

    /**
     * Backward-compatible 1..N self-ask planner used by older retrievers.
     */
    public List<String> plan(String question, int max) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        int limit = Math.max(1, max);
        String sys = "You are a Self-Ask search planner. Rewrite the user question into "
                + "one or two concise search queries. Output one query per line only.";

        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        try {
            String out = fallbackPlanningModel().chat(List.of(
                    SystemMessage.from(sys),
                    UserMessage.from(question))).aiMessage().text();
            fallbackSplitToSet(out, uniq, limit);
        } catch (Exception e) {
            log.debug("[SelfAskPlanner] fail-soft stage={}", "plan");
            log.warn("[SelfAskPlanner] Planning failed: {}", safeReason(e));
        }
        return new ArrayList<>(uniq);
    }

    private ChatModel fallbackPlanningModel() {
        if (chatModel == null) {
            throw new IllegalStateException("SelfAskPlanner ChatModel unavailable");
        }
        return chatModel;
    }

    private void fallbackSplitToSet(String raw, LinkedHashSet<String> uniq, int max) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] lines = raw.split("\\R");
        for (String line : lines) {
            String s = line == null ? "" : cleanLaneText(line);
            if (!s.isEmpty()) {
                uniq.add(s);
            }
            if (uniq.size() >= Math.max(1, max)) {
                break;
            }
        }
    }

    public enum SubQuestionType {
        BQ, ER, RC
    }

    public static final class SubQuestion {
        public final SubQuestionType type;
        public final String text;
        public final Map<String, Object> meta;

        public SubQuestion(SubQuestionType type, String text, Map<String, Object> meta) {
            this.type = type;
            this.text = text;
            this.meta = meta == null ? Map.of() : meta;
        }

        @Override
        public String toString() {
            return type + ":" + text;
        }
    }

    private String laneSystemPrompt(SubQuestionType lane) {
        return switch (lane) {
            case BQ -> "Create exactly one Korean-safe search query for factual background, definition, scope, and official terminology. Avoid emotional labels. "
                    + HONEST_REWRITE_CONTRACT;
            case ER -> "Create exactly one Korean-safe search query for entities, aliases, speaker attribution, targets, typo/STT variants, and relationships. Do not merge what one speaker said with another speaker's interpretation. "
                    + HONEST_REWRITE_CONTRACT;
            case RC -> "Create exactly one Korean-safe search query for corrections, negations, misunderstandings, counterexamples, missing context, causes, and comparisons. Preserve what was explicitly denied or corrected. "
                    + HONEST_REWRITE_CONTRACT;
        };
    }

    public List<SubQuestion> generateThreeLanes(String query, long timeoutMs) {
        return generateThreeLanes(query, timeoutMs, Double.NaN);
    }

    public List<SubQuestion> generateThreeLanes(String query, long timeoutMs, double rewriteTemperature) {
        return generateThreeLanes(query, timeoutMs, rewriteTemperature, Map.of());
    }

    private static Map<String, Double> effectiveLaneWeights(Map<String, Double> laneWeights) {
        if (laneWeights != null && !laneWeights.isEmpty()) {
            return laneWeights;
        }
        Map<String, Double> poseWeights = PromptPoseTrace.laneWeights();
        return poseWeights == null ? Map.of() : poseWeights;
    }

    private static double effectiveRewriteTemperature(double rewriteTemperature) {
        Double creativeTemperature = creativeEffectiveSelfAskTemperature();
        if (creativeTemperature != null) {
            return sanitizeRewriteTemperature(creativeTemperature);
        }
        if (Double.isFinite(rewriteTemperature) && rewriteTemperature > 0.0d) {
            return rewriteTemperature;
        }
        Double poseTemperature = PromptPoseTrace.selfAskTemperature();
        if (poseTemperature != null && Double.isFinite(poseTemperature) && poseTemperature > 0.0d) {
            return sanitizeRewriteTemperature(poseTemperature);
        }
        return rewriteTemperature;
    }

    private static int effectiveLaneLimit(Map<String, Double> laneWeights) {
        if (laneWeights != null && !laneWeights.isEmpty()) {
            return 3;
        }
        Integer poseCount = PromptPoseTrace.selfAskCount();
        return poseCount == null ? 3 : Math.max(1, Math.min(3, poseCount));
    }

    public List<SubQuestion> generateThreeLanes(String query, long timeoutMs, double rewriteTemperature,
            Map<String, Double> laneWeights) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, Double> effectiveLaneWeights = effectiveLaneWeights(laneWeights);
        double effectiveRewriteTemperature = effectiveRewriteTemperature(rewriteTemperature);
        int laneLimit = effectiveLaneLimit(laneWeights);
        ArrayList<SubQuestionType> laneOrder = new ArrayList<>(List.of(SubQuestionType.values()));
        laneOrder.sort(Comparator.comparingDouble((SubQuestionType lane) -> laneWeight(lane, effectiveLaneWeights)).reversed());
        List<SubQuestionType> selectedLaneOrder = List.copyOf(
                laneOrder.subList(0, Math.min(laneLimit, laneOrder.size())));
        boolean promptPoseApplied = (laneWeights == null || laneWeights.isEmpty()) && !effectiveLaneWeights.isEmpty();
        Double poseTemperature = PromptPoseTrace.selfAskTemperature();
        if (poseTemperature != null && Double.isFinite(poseTemperature)
                && (!Double.isFinite(rewriteTemperature) || rewriteTemperature <= 0.0d)) {
            promptPoseApplied = true;
        }
        if ((laneWeights == null || laneWeights.isEmpty()) && laneLimit < 3) {
            promptPoseApplied = true;
        }
        try {
            TraceStore.put("selfask.3way.requery.confirmed", null);
            TraceStore.put("selfask.3way.weights", safeLaneWeights(effectiveLaneWeights));
            TraceStore.put("selfask.3way.laneLimit", laneLimit);
            TraceStore.put("selfask.3way.laneOrder", selectedLaneOrder.stream().map(Enum::name).toList());
            TraceStore.put("selfask.promptPose.applied", promptPoseApplied);
            String promptPoseArm = PromptPoseTrace.arm();
            if (promptPoseArm != null && !promptPoseArm.isBlank()) {
                TraceStore.put("selfask.promptPose.arm", promptPoseArm);
            }
        } catch (Exception traceError) {
            log.debug("[SelfAskPlanner] fail-soft stage={}", "tracePlannerSetup");
            traceSelfAskSkipped("trace_planner_setup", traceError);
        }
        if (timeoutMs > 0 && timeoutMs < 250) {
            List<SubQuestion> fallback = fallbackThreeLanes(query, "short_timeout", effectiveLaneWeights,
                    effectiveRewriteTemperature, timeoutMs, laneLimit);
            traceRequeryConfirmed(fallback);
            return fallback;
        }

        ArrayList<SubQuestion> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        boolean usedFallback = false;

        for (SubQuestionType lane : selectedLaneOrder) {
            String modelId = modelIdForLane(lane);
            String provider = providerForLane(lane, modelId);
            double laneWeight = laneWeight(lane, effectiveLaneWeights);
            double laneTemperature = laneTemperature(baseTemperatureForLane(lane, effectiveRewriteTemperature), laneWeight);
            long laneTimeoutMs = laneTimeout(baseTimeoutForLane(lane, timeoutMs), laneWeight);
            laneTimeoutMs = zero100LaneTimeboxMs(lane, laneTimeoutMs);
            try {
                String sub = generateLane(query, lane, modelId, laneTimeoutMs, laneTemperature, laneWeight);
                if (!sub.isEmpty() && seen.add(canon(sub))) {
                    out.add(new SubQuestion(lane, sub,
                            laneMeta(lane, modelId, "false", provider, "", laneWeight, laneTemperature, laneTimeoutMs)));
                }
            } catch (Exception e) {
                log.debug("[SelfAskPlanner] fail-soft stage={}", "generateThreeLanes.lane");
                usedFallback = true;
                String reason = classifyFailure(e);
                String sub = lane == SubQuestionType.RC
                        ? localCounterFallback(query, timeoutMs, reason, effectiveRewriteTemperature, effectiveLaneWeights)
                        : fallbackText(query, lane);
                if (seen.add(canon(sub))) {
                    String model = lane == SubQuestionType.RC ? localFallbackModelLabel() : modelId;
                    String fallbackProvider = lane == SubQuestionType.RC ? "local-fallback" : provider;
                    out.add(new SubQuestion(lane, sub,
                            laneMeta(lane, model, "true", fallbackProvider, reason, laneWeight, laneTemperature, laneTimeoutMs)));
                }
                traceLane(lane, modelId, provider, "true", reason,
                        laneWeight, laneTemperature, laneTimeoutMs);
                log.debug("[SelfAskPlanner][3way] lane={} modelHash={} fallbackReason={}", lane, SafeRedactor.hashValue(modelId), reason);
            }
        }

        if (out.isEmpty() || out.size() < laneLimit) {
            usedFallback = true;
            for (SubQuestion sq : fallbackThreeLanes(query, "dedupe_or_empty", effectiveLaneWeights,
                    effectiveRewriteTemperature, timeoutMs, laneLimit)) {
                if (seen.add(canon(sq.text))) {
                    out.add(sq);
                }
                if (out.size() >= laneLimit) {
                    break;
                }
            }
        }
        try {
            TraceStore.put("selfask.3way.rewriteTemperature", String.valueOf(sanitizeRewriteTemperature(effectiveRewriteTemperature)));
            traceRequeryConfirmed(out);
        } catch (Exception traceError) {
            log.debug("[SelfAskPlanner] fail-soft stage={}", "traceRequeryConfirmed");
            traceSelfAskSkipped("trace_requery_confirmed", traceError);
        }
        log.debug("[SelfAskPlanner][3way] lanes={}, timeoutMs={}, fallback={}", out.size(), timeoutMs, usedFallback);
        return out;
    }

    public java.util.Optional<SubQuestion> regenerateLane(
            String query,
            SubQuestionType lane,
            long timeoutMs,
            double rewriteTemperature,
            double laneWeight) {
        if (query == null || query.isBlank() || lane == null) {
            traceRegenerate(lane, "invalid_input", 0, Math.max(0L, timeoutMs), true);
            return java.util.Optional.empty();
        }
        double safeWeight = Math.max(0.25d, Math.min(2.50d, Double.isFinite(laneWeight) ? laneWeight : 1.0d));
        double laneTemperature = laneTemperature(baseTemperatureForLane(lane, rewriteTemperature), safeWeight);
        long laneTimeoutMs = laneTimeout(baseTimeoutForLane(lane, timeoutMs), safeWeight);
        laneTimeoutMs = zero100LaneTimeboxMs(lane, laneTimeoutMs);
        String modelId = modelIdForLane(lane);
        String provider = providerForLane(lane, modelId);
        try {
            String sub = generateLane(query, lane, modelId, laneTimeoutMs, laneTemperature, safeWeight);
            if (sub == null || sub.isBlank()) {
                traceRegenerate(lane, "empty_output", 1, laneTimeoutMs, true);
                return java.util.Optional.empty();
            }
            traceRegenerate(lane, "single_lane_retry", 1, laneTimeoutMs, false);
            return java.util.Optional.of(new SubQuestion(lane, sub,
                    laneMeta(lane, modelId, "false", provider, "", safeWeight, laneTemperature, laneTimeoutMs)));
        } catch (Exception e) {
            log.debug("[SelfAskPlanner] fail-soft stage={}", "regenerateLane");
            String reason = classifyFailure(e);
            traceRegenerate(lane, reason, 1, laneTimeoutMs, true);
            String sub = fallbackText(query, lane);
            traceLane(lane, modelId, provider, "true", reason,
                    safeWeight, laneTemperature, laneTimeoutMs);
            return java.util.Optional.of(new SubQuestion(lane, sub,
                    laneMeta(lane, modelId, "true", provider, reason,
                            safeWeight, laneTemperature, laneTimeoutMs)));
        }
    }

    private String generateLane(String query, SubQuestionType lane, String modelId, long timeoutMs,
            double rewriteTemperature, double laneWeight) {
        ChatModel model = modelFor(modelId, timeoutMs, rewriteTemperature);
        String sub = model.chat(List.of(
                SystemMessage.from(laneSystemPrompt(lane)),
                UserMessage.from(query))).aiMessage().text();
        sub = cleanLaneText(sub);
        SelfAskRewriteRiskScorer.RewriteHonesty honesty =
                SelfAskRewriteRiskScorer.assessRewriteHonesty(query, sub);
        traceRewriteHonesty(lane, honesty, sub);
        if ("OVERREACH".equals(honesty.honestyStatus())) {
            sub = honestFallbackText(query, lane);
        }
        traceLane(lane, modelId, providerForLane(lane, modelId), "false", "",
                laneWeight, rewriteTemperature, timeoutMs, SafeRedactor.hash12(sub));
        return sub;
    }

    private ChatModel modelFor(String modelId, long timeoutMs, double rewriteTemperature) {
        DynamicChatModelFactory factory = modelFactoryProvider == null ? null : modelFactoryProvider.getIfAvailable();
        if (factory != null && modelId != null && !modelId.isBlank()) {
            int timeoutSeconds = timeoutMs > 0
                    ? Math.max(1, (int) Math.ceil(timeoutMs / 1000.0d))
                    : 8;
            double sanitized = ModelCapabilities.sanitizeTemperature(modelId,
                    sanitizeRewriteTemperature(rewriteTemperature));
            return factory.lcWithTimeout(modelId, sanitized, 0.8d, 96, timeoutSeconds);
        }
        return fallbackPlanningModel();
    }

    private String localCounterFallback(String query, long timeoutMs, String disabledReason,
            double rewriteTemperature, Map<String, Double> laneWeights) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        double laneWeight = laneWeight(SubQuestionType.RC, laneWeights);
        double laneTemperature = laneTemperature(rewriteTemperature, laneWeight);
        long laneTimeoutMs = laneTimeout(timeoutMs, laneWeight);
        laneTimeoutMs = zero100LaneTimeboxMs(SubQuestionType.RC, laneTimeoutMs);
        for (String modelId : List.of(modelIdForLane(SubQuestionType.BQ), modelIdForLane(SubQuestionType.ER))) {
            try {
                String sub = generateLane(query, SubQuestionType.RC, modelId, laneTimeoutMs,
                        laneTemperature, laneWeight);
                if (!sub.isBlank()) {
                    parts.add(sub);
                }
            } catch (Exception e) {
                log.debug("[SelfAskPlanner] fail-soft stage={}", "localCounterFallback");
                log.debug("[SelfAskPlanner][3way] local RC fallback modelHash={} failed: {}", SafeRedactor.hashValue(modelId), safeReason(e));
            }
        }
        String merged = mergeSearchQuery(query, parts);
        traceLane(SubQuestionType.RC, localFallbackModelLabel(), "local-fallback", "true", disabledReason,
                laneWeight, laneTemperature, laneTimeoutMs);
        return merged.isBlank() ? fallbackText(query, SubQuestionType.RC) : merged;
    }

    private String localFallbackModelLabel() {
        return modelIdForLane(SubQuestionType.BQ) + "+" + modelIdForLane(SubQuestionType.ER);
    }

    private static List<SubQuestion> fallbackThreeLanes(String query, String reason,
            Map<String, Double> laneWeights, double rewriteTemperature, long timeoutMs) {
        return fallbackThreeLanes(query, reason, laneWeights, rewriteTemperature, timeoutMs, 3);
    }

    private static List<SubQuestion> fallbackThreeLanes(String query, String reason,
            Map<String, Double> laneWeights, double rewriteTemperature, long timeoutMs, int laneLimit) {
        ArrayList<SubQuestion> out = new ArrayList<>();
        ArrayList<SubQuestionType> lanes = new ArrayList<>(List.of(SubQuestionType.values()));
        lanes.sort(Comparator.comparingDouble((SubQuestionType lane) -> laneWeight(lane, laneWeights)).reversed());
        int limit = Math.max(1, Math.min(3, laneLimit));
        for (SubQuestionType lane : lanes.subList(0, Math.min(limit, lanes.size()))) {
            double laneWeight = laneWeight(lane, laneWeights);
            double laneTemperature = laneTemperature(rewriteTemperature, laneWeight);
            long laneTimeoutMs = laneTimeout(timeoutMs, laneWeight);
            laneTimeoutMs = zero100LaneTimeboxMs(lane, laneTimeoutMs);
            out.add(new SubQuestion(lane, fallbackText(query, lane),
                    laneMeta(lane, "deterministic", "true", "local", reason,
                            laneWeight, laneTemperature, laneTimeoutMs)));
        }
        return out;
    }

    private static String fallbackText(String query, SubQuestionType lane) {
        return honestFallbackText(query, lane);
    }

    private static String honestFallbackText(String query, SubQuestionType lane) {
        String q = query == null ? "" : query.trim();
        return switch (lane) {
            case BQ -> q + " factual context terminology correction";
            case ER -> q + " speaker attribution alias entity relation";
            case RC -> q + " correction negation misunderstanding counterexample missing context";
        };
    }

    private static String mergeSearchQuery(String query, LinkedHashSet<String> parts) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, query);
        if (parts != null) {
            for (String part : parts) {
                addTokens(tokens, part);
            }
        }
        String merged = String.join(" ", tokens).trim();
        return merged.length() <= 180 ? merged : merged.substring(0, 180).trim();
    }

    private static void addTokens(LinkedHashSet<String> tokens, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String token : raw.replaceAll("[\\r\\n\"']", " ").split("\\s+")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                tokens.add(t);
            }
            if (tokens.size() >= 28) {
                return;
            }
        }
    }

    private static String cleanLaneText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("(?m)^\\s*[-*\\d.)]+\\s*", "")
                .replace("\"", "")
                .replace("'", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String canon(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String modelIdForLane(SubQuestionType lane) {
        SelfAskProperties.Lane cfg = laneConfig(lane);
        if (cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()) {
            return cfg.getModel().trim();
        }
        return defaultModelForLane(lane);
    }

    private String providerForLane(SubQuestionType lane, String modelId) {
        SelfAskProperties.Lane cfg = laneConfig(lane);
        if (cfg != null && cfg.getProvider() != null && !cfg.getProvider().isBlank()) {
            return cfg.getProvider().trim();
        }
        return providerForModel(modelId);
    }

    private double baseTemperatureForLane(SubQuestionType lane, double requested) {
        if (Double.isFinite(requested) && requested > 0.0d) {
            return requested;
        }
        SelfAskProperties.Lane cfg = laneConfig(lane);
        if (cfg != null && Double.isFinite(cfg.getTemperature()) && cfg.getTemperature() > 0.0d) {
            return cfg.getTemperature();
        }
        return 0.2d;
    }

    private long baseTimeoutForLane(SubQuestionType lane, long requestedTimeoutMs) {
        if (requestedTimeoutMs > 0L) {
            return requestedTimeoutMs;
        }
        SelfAskProperties.Lane cfg = laneConfig(lane);
        return cfg == null ? 0L : Math.max(0L, cfg.getTimeoutMs());
    }

    private SelfAskProperties.Lane laneConfig(SubQuestionType lane) {
        SelfAskProperties props = selfAskPropertiesProvider == null ? null : selfAskPropertiesProvider.getIfAvailable();
        SelfAskProperties.ThreeWay threeWay = props == null ? null : props.getThreeWay();
        if (threeWay == null || lane == null) {
            return null;
        }
        return switch (lane) {
            case BQ -> threeWay.getBq();
            case ER -> threeWay.getEr();
            case RC -> threeWay.getRc();
        };
    }

    private static String defaultModelForLane(SubQuestionType lane) {
        return switch (lane) {
            case BQ -> MODEL_BQ;
            case ER -> MODEL_ER;
            case RC -> MODEL_RC;
        };
    }

    private static Map<String, Object> laneMeta(SubQuestionType lane,
            String model,
            String fallback,
            String provider,
            String disabledReason,
            double laneWeight,
            double rewriteTemperature,
            long timeoutMs) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("lane", lane.name());
        out.put("branchId", lane.name());
        out.put("intentAxis", intentAxis(lane));
        out.put("consensusRole", consensusRole(lane));
        out.put("zero100Phase", traceString("zero100.phase", ""));
        out.put("zero100ActiveLane", traceString("zero100.activeLane", ""));
        out.put("model", model == null ? "" : model);
        out.put("provider", provider == null || provider.isBlank() ? providerForModel(model) : provider);
        out.put("fallback", fallback == null ? "false" : fallback);
        String safeDisabledReason = SafeRedactor.traceLabelOrFallback(disabledReason == null ? "" : disabledReason, "unknown");
        out.put("disabledReason", safeDisabledReason);
        out.put("failureClass", normalizeFailureClass(safeDisabledReason));
        out.put("weight", round4(laneWeight));
        out.put("temperature", round4(sanitizeRewriteTemperature(rewriteTemperature)));
        out.put("timeoutMs", Math.max(0L, timeoutMs));
        return out;
    }

    private static String intentAxis(SubQuestionType lane) {
        return switch (lane) {
            case BQ -> "domain_definition";
            case ER -> "alias_synonym";
            case RC -> "relation_hypothesis";
        };
    }

    private static String consensusRole(SubQuestionType lane) {
        return switch (lane) {
            case BQ -> "STRICT";
            case ER -> "RELAXED";
            case RC -> "EXPLORE";
        };
    }

    private static long zero100LaneTimeboxMs(SubQuestionType lane, long fallbackMs) {
        if (lane == null || fallbackMs <= 0L) {
            return fallbackMs;
        }
        try {
            Object raw = TraceStore.get("zero100.branch.timeboxMs");
            if (raw instanceof Map<?, ?> map) {
                Object value = map.get(lane.name());
                long parsed = parseLong(value, -1L);
                if (parsed > 0L) {
                    return Math.max(250L, Math.min(fallbackMs, parsed));
                }
            }
        } catch (Exception ignore) {
            log.debug("[SelfAskPlanner] fail-soft stage={}", "zero100LaneTimeboxMs");
            // Trace hints are best-effort only.
        }
        return fallbackMs;
    }

    private static String traceString(String key, String defaultValue) {
        Object value = TraceStore.get(key);
        if (value == null) {
            return defaultValue;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? defaultValue : s;
    }

    private static long parseLong(Object value, long defaultValue) {
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                log.debug("[SelfAskPlanner] fail-soft stage={} errorType={}", "parseLong", "invalid_number");
                return defaultValue;
            }
            return n.longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            log.debug("[SelfAskPlanner] fail-soft stage={} errorType={}", "parseLong", "invalid_number");
            return defaultValue;
        }
    }

    private static String providerForModel(String modelId) {
        if (modelId == null) {
            return "unknown";
        }
        String m = modelId.toLowerCase(Locale.ROOT);
        if (m.contains("api3")) {
            return "api3";
        }
        if (m.contains("gemma") || m.contains("light") || m.contains("qwen")) {
            return "local";
        }
        return "unknown";
    }

    private static void traceLane(SubQuestionType lane,
            String model,
            String provider,
            String fallback,
            String disabledReason,
            double laneWeight,
            double rewriteTemperature,
            long timeoutMs) {
        traceLane(lane, model, provider, fallback, disabledReason, laneWeight, rewriteTemperature, timeoutMs, "");
    }

    private static void traceLane(SubQuestionType lane,
            String model,
            String provider,
            String fallback,
            String disabledReason,
            double laneWeight,
            double rewriteTemperature,
            long timeoutMs,
            String seedHash12) {
        try {
            Map<String, Object> event = laneMeta(lane, model, fallback, provider, disabledReason,
                    laneWeight, rewriteTemperature, timeoutMs);
            if (seedHash12 != null && !seedHash12.isBlank()) {
                event.put("seedHash12", seedHash12);
            }
            TraceStore.append("selfask.3way.events", event);
            if (lane == SubQuestionType.RC) {
                TraceStore.put("selfask.3way.api.provider",
                        provider == null || provider.isBlank() ? providerForModel(MODEL_RC) : provider);
                if (disabledReason != null && !disabledReason.isBlank()) {
                    TraceStore.put("selfask.3way.api.disabledReason", SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
                }
            }
        } catch (Throwable ignore) {
            log.debug("[SelfAskPlanner] fail-soft stage={}", "traceLane");
            // Trace is best-effort and must never affect retrieval.
        }
    }

    private static void traceRegenerate(SubQuestionType lane,
            String reason,
            int maxAttempts,
            long timeoutMs,
            boolean fallback) {
        try {
            TraceStore.put("selfask.regenerate.reason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            TraceStore.put("selfask.regenerate.maxAttempts", Math.max(0, maxAttempts));
            TraceStore.put("selfask.regenerate.lane", lane == null ? "" : lane.name());
            TraceStore.put("selfask.regenerate.timeoutMs", Math.max(0L, timeoutMs));
            TraceStore.put("selfask.regenerate.fallback", fallback);
        } catch (Throwable ignore) {
            log.debug("[SelfAskPlanner] fail-soft stage={}", "regenerateLane");
            // Trace is best-effort and must never affect retrieval.
        }
    }

    private static void traceRewriteHonesty(SubQuestionType lane,
            SelfAskRewriteRiskScorer.RewriteHonesty honesty,
            String rewrite) {
        if (honesty == null) {
            return;
        }
        try {
            TraceStore.put("selfask.rewrite.honestyStatus", honesty.honestyStatus());
            TraceStore.put("selfask.rewrite.overreachType", honesty.overreachType());
            TraceStore.put("selfask.rewrite.overreachScore", String.valueOf(honesty.overreachScore()));
            TraceStore.put("selfask.rewrite.sourceHash", honesty.sourceHash12());
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("lane", lane == null ? "unknown" : lane.name());
            event.put("status", honesty.honestyStatus());
            event.put("overreachType", honesty.overreachType());
            event.put("overreachScore", honesty.overreachScore());
            event.put("sourceHash12", honesty.sourceHash12());
            event.put("rewriteHash12", SafeRedactor.hash12(rewrite));
            TraceStore.append("selfask.rewrite.honesty.events", event);
        } catch (Throwable ignore) {
            log.debug("[SelfAskPlanner] fail-soft stage={}", "traceRewriteHonesty");
            // Trace is best-effort and must never affect retrieval.
        }
    }

    private static String normalizeFailureClass(String reason) {
        if (reason == null || reason.isBlank()) {
            return "none";
        }
        return reason;
    }

    private static void traceRequeryConfirmed(List<SubQuestion> lanes) {
        if (lanes == null || lanes.isEmpty()) {
            return;
        }
        java.util.EnumSet<SubQuestionType> seen = java.util.EnumSet.noneOf(SubQuestionType.class);
        for (SubQuestion sq : lanes) {
            if (sq != null && sq.type != null) {
                seen.add(sq.type);
            }
        }
        if (seen.containsAll(java.util.List.of(SubQuestionType.BQ, SubQuestionType.ER, SubQuestionType.RC))) {
            TraceStore.put("selfask.3way.requery.confirmed", true);
        }
    }

    private static double laneWeight(SubQuestionType lane, Map<String, Double> laneWeights) {
        if (lane == null || laneWeights == null) {
            return 1.0d;
        }
        Double value = laneWeights.get(lane.name());
        if (value == null || !Double.isFinite(value)) {
            return 1.0d;
        }
        return Math.max(0.25d, Math.min(2.50d, value));
    }

    private static double laneTemperature(double rewriteTemperature, double laneWeight) {
        if (creativeEffectiveSelfAskTemperature() != null) {
            return sanitizeRewriteTemperature(rewriteTemperature);
        }
        double multiplier = 0.85d + 0.15d * laneWeight;
        return sanitizeRewriteTemperature(rewriteTemperature * multiplier);
    }

    private static long laneTimeout(long timeoutMs, double laneWeight) {
        if (timeoutMs <= 0L) {
            return 0L;
        }
        double multiplier = Math.max(0.75d, Math.min(1.25d, 0.85d + 0.20d * laneWeight));
        return Math.max(250L, Math.round(timeoutMs * multiplier));
    }

    private static Map<String, Double> safeLaneWeights(Map<String, Double> laneWeights) {
        java.util.LinkedHashMap<String, Double> out = new java.util.LinkedHashMap<>();
        for (SubQuestionType lane : SubQuestionType.values()) {
            out.put(lane.name(), round4(laneWeight(lane, laneWeights)));
        }
        return out;
    }

    private static String classifyFailure(Exception e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage());
        String lower = msg.toLowerCase(Locale.ROOT);
        String name = e == null ? "" : e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (e instanceof java.util.concurrent.CancellationException
                || e instanceof InterruptedException
                || name.contains("cancel")
                || name.contains("interrupt")
                || lower.contains("cancelled")
                || lower.contains("canceled")
                || lower.contains("interrupted")) {
            return "cancelled";
        }
        if (lower.contains("api key") || lower.contains("missing") || lower.contains("unauthorized")
                || lower.contains("401") || lower.contains("403")) {
            return "missing-key-or-unauthorized";
        }
        if (lower.contains("429") || lower.contains("rate")) {
            return "rate-limit";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "timeout";
        }
        if (lower.contains("5xx") || lower.contains("500") || lower.contains("502")
                || lower.contains("503") || lower.contains("504")) {
            return "server-error";
        }
        if (lower.contains("malformed") || lower.contains("parse") || lower.contains("schema")) {
            return "malformed-response";
        }
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    private static String safeReason(Exception e) {
        return classifyFailure(e);
    }

    private static void traceSelfAskSkipped(String stage, Exception error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        log.debug("[SelfAskPlanner] telemetry skipped stage={} errorType={}", safeStage, errorType(error));
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    private static double sanitizeRewriteTemperature(double requested) {
        double normalizedRequested = Double.isFinite(requested) ? requested : 0.2d;
        Double creative = creativeEffectiveSelfAskTemperature();
        double max = creative == null ? 0.55d : 1.0d;
        GuardContext context = GuardContextHolder.get();
        Double mandatoryCap = effectiveSelfAskCap(context);
        if (mandatoryCap != null) {
            max = Math.min(max, mandatoryCap);
        }
        double boundedMax = Math.max(0.0d, max);
        double effectiveFloor = Math.min(0.12d, boundedMax);
        return Math.max(effectiveFloor, Math.min(boundedMax, normalizedRequested));
    }

    private static Double creativeEffectiveSelfAskTemperature() {
        GuardContext context = GuardContextHolder.get();
        if (!validCompleteCreativeProfile(context)) {
            return null;
        }
        String profile = String.valueOf(context.getPlanOverride("creative.emergence.profile"));
        double requested = context.planDouble("creative.emergence.selfAsk.temperature", Double.NaN);
        if (!validCreativeSelfAskBand(profile, requested)) {
            return null;
        }
        double effective = context.planDouble(
                "creative.emergence.selfAsk.effectiveTemperature", requested);
        if (!Double.isFinite(effective) || effective <= 0.0d) {
            return null;
        }
        Double mandatoryCap = effectiveSelfAskCap(context);
        return mandatoryCap == null ? Math.min(1.0d, effective) : Math.min(effective, mandatoryCap);
    }

    private static boolean validCompleteCreativeProfile(GuardContext context) {
        if (context == null || context.isSensitiveTopic()
                || context.planBool("privacy.boundary.enforce", false)
                || !context.planBool("creative.emergence.active", false)
                || !"explore".equals(context.getPlanOverride("promptPose.application.intentSlot"))) {
            return false;
        }
        String requestedHash = String.valueOf(
                context.getPlanOverride("creative.emergence.requestedOptionsHash"))
                .toLowerCase(java.util.Locale.ROOT);
        if (!requestedHash.matches("hash:[0-9a-f]{12}")) {
            return false;
        }
        return switch (String.valueOf(context.getPlanOverride("creative.emergence.profile"))) {
            case "VIVID" -> creativeProfileValuesInRange(context,
                    0.85d, 0.90d, 0.70d, 0.76d,
                    1.10d, 1.25d, 0.95d, 0.97d,
                    1.05d, 1.20d, 0.95d, 0.97d,
                    0.80d, 0.88d);
            case "WILD" -> creativeProfileValuesInRange(context,
                    0.91d, 0.97d, 0.77d, 0.83d,
                    1.26d, 1.45d, 0.97d, 0.99d,
                    1.21d, 1.40d, 0.97d, 0.99d,
                    0.89d, 0.97d);
            case "FERAL" -> creativeProfileValuesInRange(context,
                    0.98d, 1.00d, 0.84d, 0.85d,
                    1.46d, 1.50d, 0.99d, 1.00d,
                    1.41d, 1.50d, 0.99d, 1.00d,
                    0.98d, 1.00d);
            default -> false;
        };
    }

    private static boolean creativeProfileValuesInRange(
            GuardContext context,
            double searchTempMin, double searchTempMax,
            double searchRateMin, double searchRateMax,
            double candidateTempMin, double candidateTempMax,
            double candidateTopPMin, double candidateTopPMax,
            double finalTempMin, double finalTempMax,
            double finalTopPMin, double finalTopPMax,
            double selfAskMin, double selfAskMax) {
        return creativeValueInRange(context, "creative.emergence.search.temperature", searchTempMin, searchTempMax)
                && creativeValueInRange(context, "creative.emergence.search.rate", searchRateMin, searchRateMax)
                && creativeValueInRange(context, "creative.emergence.candidate.temperature",
                        candidateTempMin, candidateTempMax)
                && creativeValueInRange(context, "creative.emergence.candidate.topP",
                        candidateTopPMin, candidateTopPMax)
                && creativeValueInRange(context, "creative.emergence.final.temperature",
                        finalTempMin, finalTempMax)
                && creativeValueInRange(context, "creative.emergence.final.topP",
                        finalTopPMin, finalTopPMax)
                && creativeValueInRange(context, "creative.emergence.selfAsk.temperature",
                        selfAskMin, selfAskMax);
    }

    private static boolean creativeValueInRange(
            GuardContext context, String key, double min, double max) {
        double value = context.planDouble(key, Double.NaN);
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private static Double effectiveSelfAskCap(GuardContext context) {
        if (context == null) {
            return null;
        }
        Double selfAsk = finiteNonNegative(context.planDouble("llm.selfAsk.temperature.max"));
        Double explore = finiteNonNegative(context.planDouble("llm.explore.temperature.max"));
        if (selfAsk == null) {
            return explore;
        }
        return explore == null ? selfAsk : Math.min(selfAsk, explore);
    }

    private static Double finiteNonNegative(Double value) {
        return value != null && Double.isFinite(value) ? Math.max(0.0d, value) : null;
    }

    private static boolean validCreativeSelfAskBand(String profile, double value) {
        if (!Double.isFinite(value)) {
            return false;
        }
        return switch (profile) {
            case "VIVID" -> value >= 0.80d && value <= 0.88d;
            case "WILD" -> value >= 0.89d && value <= 0.97d;
            case "FERAL" -> value >= 0.98d && value <= 1.00d;
            default -> false;
        };
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }
}
