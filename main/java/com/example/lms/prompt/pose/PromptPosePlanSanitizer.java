package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class PromptPosePlanSanitizer {

    private static final Set<String> ALLOWED_LANES = Set.of("BQ", "ER", "RC");
    private static final Pattern UNSAFE_TEXT = Pattern.compile(
            "(?is)(authorization\\s*:|bearer\\s+|api[_-]?key|client[_-]?secret|owner\\s*token|ownertoken|"
                    + "-----BEGIN|sk-[A-Za-z0-9_-]{8,}|gsk_[A-Za-z0-9_-]{8,}|"
                    + "sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|"
                    + "\\b(system|developer|assistant|user)\\s*:|prompt\\s*:|[A-Za-z]:\\\\|/Users/|/home/)");

    private final PromptPoseProperties props;

    public PromptPosePlanSanitizer(PromptPoseProperties props) {
        this.props = props == null ? new PromptPoseProperties() : props;
    }

    public PromptPosePlan sanitize(PromptPosePlan raw) {
        if (raw == null) {
            return PromptPosePlan.noDraft(defaultRoute(), "empty_plan");
        }
        if (!raw.enabled()) {
            return PromptPosePlan.disabled(blankTo(raw.reasonCode(), "disabled"));
        }

        PromptPoseProperties.Policy policy = props.getPolicy();
        int maxDraftLines = clamp(policy.getMaxDraftLines(), 0, 12);
        int policyMaxBurst = clamp(policy.getMaxQueryburstCount(), 1, 18);
        int policyMinBurst = clamp(policy.getMinQueryburstCount(), 1, policyMaxBurst);
        int maxSelfAsk = clamp(policy.getMaxSelfaskCount(), 0, 3);
        double maxTemperature = clamp(policy.getMaxTemperature(), 0.0d, 0.55d);

        String route = sanitizeRoute(raw.routeModel(), policy.isAllowExternalFree());
        List<String> lines = sanitizeLines(raw.assistantDraftLines(), maxDraftLines, 160);
        List<String> seeds = sanitizeLines(raw.queryBurstSeeds(), policyMaxBurst, 96);
        Map<String, Double> laneWeights = sanitizeLaneWeights(raw.laneWeights());

        int queryBurstMax = clamp(raw.queryBurstMax(), 0, policyMaxBurst);
        if (queryBurstMax > 0 && queryBurstMax < policyMinBurst) {
            queryBurstMax = policyMinBurst;
        }
        int queryBurstMin = queryBurstMax == 0 ? 0 : clamp(raw.queryBurstMin(), policyMinBurst, queryBurstMax);
        int selfAskCount = clamp(raw.selfAskCount(), 0, maxSelfAsk);
        double answerTemperature = clamp(raw.answerTemperature(), 0.0d, maxTemperature);
        double selfAskTemperature = clamp(raw.selfAskTemperature(), 0.0d, maxTemperature);
        double confidence = clamp(raw.confidence(), 0.0d, 1.0d);
        int minCitations = clamp(raw.minCitations(), 0, 8);

        boolean hasHints = !lines.isEmpty() || !seeds.isEmpty() || !laneWeights.isEmpty()
                || selfAskCount > 0 || queryBurstMax > 0;
        PromptPoseArm arm = hasHints ? armForRoute(route) : PromptPoseArm.NO_DRAFT;

        return new PromptPosePlan(true, arm, route, lines, seeds, queryBurstMin, queryBurstMax,
                selfAskCount, laneWeights, answerTemperature, selfAskTemperature, minCitations,
                confidence, blankTo(raw.reasonCode(), arm == PromptPoseArm.NO_DRAFT ? "no_draft" : "ok"));
    }

    private String sanitizeRoute(String raw, boolean allowExternalFree) {
        String route = raw == null || raw.isBlank() ? defaultRoute() : raw.trim();
        if (route.equalsIgnoreCase("llmrouter.external") && !allowExternalFree) {
            return defaultRoute();
        }
        if (!route.startsWith("llmrouter.")) {
            return defaultRoute();
        }
        return route;
    }

    private String defaultRoute() {
        String model = props.getDraft() == null ? null : props.getDraft().getModel();
        return model == null || model.isBlank() ? "llmrouter.light" : model.trim();
    }

    private static PromptPoseArm armForRoute(String route) {
        if (route != null && route.equalsIgnoreCase("llmrouter.external")) {
            return PromptPoseArm.EXTERNAL_FREE;
        }
        return PromptPoseArm.LOCAL_LIGHT;
    }

    private static List<String> sanitizeLines(List<String> raw, int maxLines, int maxLen) {
        if (raw == null || raw.isEmpty() || maxLines <= 0) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String line : raw) {
            String cleaned = cleanLine(line, maxLen);
            if (!cleaned.isBlank() && out.stream().noneMatch(cleaned::equalsIgnoreCase)) {
                out.add(cleaned);
            }
            if (out.size() >= maxLines) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static String cleanLine(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.replaceAll("(?m)^\\s*[-*\\d.)]+\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (UNSAFE_TEXT.matcher(s).find()) {
            return "";
        }
        String redacted = SafeRedactor.safeMessage(s, Math.max(32, maxLen));
        if (redacted == null || redacted.isBlank() || UNSAFE_TEXT.matcher(redacted).find()) {
            return "";
        }
        return redacted;
    }

    private static Map<String, Double> sanitizeLaneWeights(Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (e == null || e.getKey() == null) {
                continue;
            }
            String lane = e.getKey().trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_LANES.contains(lane)) {
                continue;
            }
            double v = e.getValue() == null ? 1.0d : e.getValue();
            out.put(lane, round4(clamp(v, 0.25d, 2.50d)));
        }
        return Map.copyOf(out);
    }

    private static int clamp(int value, int low, int high) {
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

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
