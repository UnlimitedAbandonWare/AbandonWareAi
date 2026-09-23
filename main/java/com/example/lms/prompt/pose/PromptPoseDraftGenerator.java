package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PromptPoseDraftGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(PromptPoseDraftGenerator.class);

    private static final String SYSTEM = """
            You are PromptPose, a RAG pre-router policy proposer.
            You do not answer the user. Return JSON only.
            Propose short sanitized routing hints for search breadth, SelfAsk lanes, and temperature.
            Never include raw prompts, secrets, file paths, attachments, memory, or evidence snippets.
            """;

    private final PromptPoseProperties props;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DynamicChatModelFactory> modelFactoryProvider;
    private final ObjectProvider<PromptBuilder> promptBuilderProvider;

    public PromptPoseDraftGenerator(
            PromptPoseProperties props,
            ObjectMapper objectMapper,
            ObjectProvider<DynamicChatModelFactory> modelFactoryProvider,
            ObjectProvider<PromptBuilder> promptBuilderProvider) {
        this.props = props == null ? new PromptPoseProperties() : props;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.modelFactoryProvider = modelFactoryProvider;
        this.promptBuilderProvider = promptBuilderProvider;
    }

    public PromptPosePlan generate(PromptPoseInputSanitizer.SanitizedInput input) {
        String route = draftRoute();
        if (input == null || input.blocked()) {
            return PromptPosePlan.noDraft(route, input == null ? "empty_input" : input.skipReason());
        }
        if (props.getDraft() == null || !props.getDraft().isEnabled()) {
            return PromptPosePlan.noDraft(route, "draft_disabled");
        }
        if (route.equalsIgnoreCase("llmrouter.external") && !props.getPolicy().isAllowExternalFree()) {
            return PromptPosePlan.noDraft(route, "external_free_not_allowed");
        }
        DynamicChatModelFactory factory = modelFactoryProvider == null ? null : modelFactoryProvider.getIfAvailable();
        PromptBuilder promptBuilder = promptBuilderProvider == null ? null : promptBuilderProvider.getIfAvailable();
        if (factory == null || promptBuilder == null) {
            return PromptPosePlan.noDraft(route, "draft_dependency_missing");
        }

        try {
            PromptContext ctx = PromptContext.builder()
                    .systemInstruction(SYSTEM)
                    .userQuery(userPayload(input))
                    .build();
            String promptPoseDraftPrompt = promptBuilder.build(ctx);
            int timeoutSeconds = Math.max(1, (int) Math.ceil(clamp(props.getDraft().getTimeoutMs(), 250, 20_000) / 1000.0d));
            int maxTokens = Math.max(64, Math.min(1024, clamp(props.getDraft().getMaxOutputChars(), 256, 4000) / 4));
            ChatModel model = factory.lcWithTimeout(route, 0.0d, 0.8d, maxTokens, timeoutSeconds);
            String raw = model.chat(List.of(
                    SystemMessage.from("Return JSON only. Do not include raw user text."),
                    UserMessage.from(promptPoseDraftPrompt))).aiMessage().text();
            return parseDraftJson(limit(raw, props.getDraft().getMaxOutputChars()), route);
        } catch (Exception e) {
            String failureClass = classify(e);
            traceSkipped("draft_generate", failureClass, e);
            return PromptPosePlan.noDraft(route, failureClass);
        }
    }

    public PromptPosePlan parseDraftJson(String raw, String fallbackRoute) throws java.io.IOException {
        String json = extractJson(raw);
        JsonNode root = objectMapper.readTree(json);
        String route = text(root, "routeModel", fallbackRoute == null || fallbackRoute.isBlank() ? draftRoute() : fallbackRoute);
        PromptPoseArm arm = route.equalsIgnoreCase("llmrouter.external") ? PromptPoseArm.EXTERNAL_FREE : PromptPoseArm.LOCAL_LIGHT;
        return new PromptPosePlan(
                true,
                arm,
                route,
                stringList(root, "assistantDraftLines", "assistantDraft", "draftLines"),
                stringList(root, "queryBurstSeeds", "querySeeds"),
                intValue(root, 0, "queryBurstMin", "queryBurstMinCount"),
                intValue(root, 0, "queryBurstMax", "queryBurstCap", "queryBurstMaxCount"),
                intValue(root, 0, "selfAskCount", "selfaskCount"),
                laneWeights(root.get("laneWeights")),
                doubleValue(root, 0.0d, "answerTemperature"),
                doubleValue(root, 0.0d, "selfAskTemperature", "rewriteTemperature"),
                intValue(root, 0, "minCitations"),
                doubleValue(root, 0.0d, "confidence"),
                text(root, "reasonCode", "draft_ok"));
    }

    private String userPayload(PromptPoseInputSanitizer.SanitizedInput input) {
        return """
                Return compact JSON with keys:
                assistantDraftLines, queryBurstSeeds, queryBurstMin, queryBurstMax, selfAskCount,
                laneWeights, answerTemperature, selfAskTemperature, minCitations, confidence, reasonCode.
                Allowed laneWeights keys: BQ, ER, RC.
                queryHash12=%s
                language=%s
                length=%d
                coarseIntent=%s
                sanitizedPreview=%s
                """.formatted(
                input.queryHash12(),
                input.language(),
                input.originalLength(),
                input.coarseIntent(),
                input.preview());
    }

    private String draftRoute() {
        String route = props.getDraft() == null ? null : props.getDraft().getModel();
        return route == null || route.isBlank() ? "llmrouter.light" : route.trim();
    }

    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("(?is)^```(?:json)?\\s*", "").replaceFirst("(?is)\\s*```$", "").trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private static List<String> stringList(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode node = root == null ? null : root.get(key);
            if (node == null || node.isNull()) {
                continue;
            }
            ArrayList<String> out = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode item : node) {
                    if (item != null && item.isValueNode()) {
                        String s = item.asText("");
                        if (!s.isBlank()) {
                            out.add(s);
                        }
                    }
                }
            } else if (node.isTextual()) {
                for (String line : node.asText("").split("\\R")) {
                    if (!line.isBlank()) {
                        out.add(line);
                    }
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    private static Map<String, Double> laneWeights(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            if (e.getKey() == null || e.getValue() == null || !e.getValue().isNumber()) {
                continue;
            }
            out.put(e.getKey().trim().toUpperCase(Locale.ROOT), e.getValue().asDouble());
        }
        return Map.copyOf(out);
    }

    private static String text(JsonNode root, String key, String fallback) {
        JsonNode node = root == null ? null : root.get(key);
        if (node == null || node.isNull()) {
            return fallback;
        }
        String s = node.asText("");
        return s.isBlank() ? fallback : s;
    }

    private static int intValue(JsonNode root, int fallback, String... keys) {
        for (String key : keys) {
            JsonNode node = root == null ? null : root.get(key);
            if (node != null && node.canConvertToInt()) {
                return node.asInt();
            }
        }
        return fallback;
    }

    private static double doubleValue(JsonNode root, double fallback, String... keys) {
        for (String key : keys) {
            JsonNode node = root == null ? null : root.get(key);
            if (node != null && node.isNumber()) {
                return node.asDouble();
            }
        }
        return fallback;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int max = clamp(maxChars, 256, 4000);
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String classify(Exception e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        String type = e == null ? "exception" : e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (msg.contains("timeout") || type.contains("timeout")) {
            return "timeout";
        }
        if (msg.contains("429") || msg.contains("rate")) {
            return "rate-limit";
        }
        if (msg.contains("disabled") || msg.contains("missing") || msg.contains("api_key")) {
            return "provider-disabled";
        }
        if (type.contains("json") || msg.contains("json")) {
            return "parse_error";
        }
        return "draft_failed";
    }

    private static void traceSkipped(String stage, String failureClass, Exception error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeFailureClass = SafeRedactor.traceLabelOrFallback(failureClass, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        TraceStore.put("promptPose.draft.skipped", true);
        TraceStore.put("promptPose.draft.stage", safeStage);
        TraceStore.put("promptPose.draft.failureClass", safeFailureClass);
        TraceStore.put("promptPose.draft.errorType", errorType);
        LOG.debug("[AWX][prompt][pose] draft trace skipped stage={} failureClass={} errorType={}",
                safeStage,
                safeFailureClass,
                safeErrorType);
    }
}
