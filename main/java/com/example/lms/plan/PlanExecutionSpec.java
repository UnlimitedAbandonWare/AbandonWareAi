package com.example.lms.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Additive execution view of the {@code plan.when} / {@code plan.pipeline}
 * sections that the typed {@link PlanHints} projection deliberately does not
 * carry (the projection boundary tests pin those keys as diagnostics only).
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code when.any} is an OR of allowlisted condition expressions.
 *       Tri-state: TRUE if any condition is satisfied, FALSE only when every
 *       condition resolved to false, UNKNOWN when at least one condition is
 *       unresolvable and none is satisfied. An absent {@code when} means the
 *       plan has no gate and evaluates TRUE.</li>
 *   <li>Unobserved metrics are never coerced to zero: a missing metric makes
 *       its condition UNKNOWN, which cannot activate the plan by itself.</li>
 *   <li>{@code pipeline} labels are validated against a stage allowlist and
 *       mapped to observable runtime evidence. A declared stage is never
 *       reported as executed without a real marker; unmapped stages are
 *       reported {@code unavailable} rather than silently claimed.</li>
 * </ul>
 */
public final class PlanExecutionSpec {

    public enum TriState { TRUE, FALSE, UNKNOWN }

    public enum StageStatus {
        EXECUTED, ENABLED, DECLARED,
        SKIPPED_FLAG_OFF, SKIPPED_WHEN_INACTIVE, SKIPPED_DEPENDENCY, SKIPPED_DUPLICATE,
        FAILED, UNAVAILABLE, DELEGATED
    }

    /** Caller-observed request flags so the ledger can distinguish skipped-by-flag. */
    public record StageFlags(boolean selfAskOn,
                             boolean biEncoderOn,
                             boolean onnxOn,
                             boolean diversityOn,
                             boolean expansionEligible) {
    }

    public record Condition(String expression, String kind, String name, String op, String operand) {
    }

    public record ConditionEval(String expression, String result, String detail) {
        Map<String, Object> debugView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("expr", expression);
            out.put("result", result);
            if (detail != null && !detail.isBlank()) {
                out.put("detail", detail);
            }
            return out;
        }
    }

    public record WhenVerdict(TriState state, List<ConditionEval> conditions) {
        public List<Map<String, Object>> debugView() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (ConditionEval c : conditions) {
                out.add(c.debugView());
            }
            return out;
        }
    }

    public record StageEntry(String stage, StageStatus status, String evidence, String detail) {
        public Map<String, Object> debugView() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stage", stage);
            out.put("status", status.name().toLowerCase(Locale.ROOT));
            if (evidence != null && !evidence.isBlank()) {
                out.put("evidence", evidence);
            }
            if (detail != null && !detail.isBlank()) {
                out.put("detail", detail);
            }
            return out;
        }
    }

    private static final Pattern CONDITION_RE = Pattern.compile(
            "^(request\\.header\\.[A-Za-z0-9_-]+|request\\.context\\.[A-Za-z0-9_.-]+"
                    + "|metrics\\.[A-Za-z0-9_.-]+|risk\\.[A-Za-z0-9_.-]+)\\s*"
                    + "(==|!=|<=|>=|<|>)\\s*"
                    + "(\"[^\"]{0,80}\"|[-+]?[0-9]*\\.?[0-9]+|true|false)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STAGE_RE = Pattern.compile(
            "^[a-zA-Z][a-zA-Z0-9]*(\\.[a-zA-Z0-9_]+)+$");

    private static final Set<String> EXPANSION_STAGES = Set.of(
            "analyze.selfask", "expand.queryburst", "expand.extremez", "narrow.overdrive.anchor");

    private enum Binding { SELF_ASK, RETRIEVAL, FUSION, BIENCODER, ONNX, DPP, DELEGATED, NONE }

    private final boolean whenPresent;
    private final List<Condition> whenAny;
    private final int unsupportedWhenKeys;
    private final List<String> pipeline;
    private final List<String> diagnostics;
    private final String emptyReason;

    private PlanExecutionSpec(boolean whenPresent,
                              List<Condition> whenAny,
                              int unsupportedWhenKeys,
                              List<String> pipeline,
                              List<String> diagnostics,
                              String emptyReason) {
        this.whenPresent = whenPresent;
        this.whenAny = List.copyOf(whenAny);
        this.unsupportedWhenKeys = unsupportedWhenKeys;
        this.pipeline = List.copyOf(pipeline);
        this.diagnostics = List.copyOf(diagnostics);
        this.emptyReason = emptyReason;
    }

    public static PlanExecutionSpec empty(String reason) {
        return new PlanExecutionSpec(false, List.of(), 0, List.of(),
                List.of(safeDiag(reason)), safeDiag(reason));
    }

    public boolean isEmpty() {
        return !whenPresent && pipeline.isEmpty();
    }

    public boolean whenPresent() {
        return whenPresent;
    }

    public List<String> pipeline() {
        return pipeline;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public String emptyReason() {
        return emptyReason;
    }

    /** True when the declared pipeline contains plan-gated expansion stages. */
    public boolean declaresExpansion() {
        for (String stage : pipeline) {
            if (EXPANSION_STAGES.contains(stage.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse the {@code plan:} node of a plan YAML document. Anything outside the
     * allowlisted grammar is recorded as a diagnostic and never executed.
     */
    @SuppressWarnings("unchecked")
    public static PlanExecutionSpec parse(Map<String, Object> planNode) {
        if (planNode == null) {
            return empty("missing_plan_node");
        }
        List<Condition> conditions = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        List<String> stages = new ArrayList<>();
        int unsupportedWhenKeys = 0;

        Object whenNode = planNode.get("when");
        boolean whenPresent = whenNode != null;
        if (whenNode instanceof Map<?, ?> whenMap) {
            for (Map.Entry<?, ?> entry : whenMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if ("any".equals(key)) {
                    parseConditionList(entry.getValue(), conditions, diagnostics);
                } else {
                    unsupportedWhenKeys++;
                    diagnostics.add("unsupported_when_key:" + safeDiag(key));
                }
            }
        } else if (whenNode instanceof List<?>) {
            parseConditionList(whenNode, conditions, diagnostics);
        } else if (whenPresent) {
            diagnostics.add("unsupported_when_shape");
        }

        Object pipelineNode = planNode.get("pipeline");
        if (pipelineNode instanceof List<?> list) {
            Set<String> seenStages = new LinkedHashSet<>();
            for (Object item : list) {
                String label = item == null ? "" : String.valueOf(item).trim();
                if (label.isEmpty() || !STAGE_RE.matcher(label).matches()) {
                    diagnostics.add("malformed_stage:" + safeDiag(label));
                    continue;
                }
                if (!seenStages.add(label.toLowerCase(Locale.ROOT))) {
                    diagnostics.add("duplicate_stage:" + safeDiag(label));
                }
                stages.add(label);
            }
        } else if (pipelineNode != null) {
            diagnostics.add("unsupported_pipeline_shape");
        }

        return new PlanExecutionSpec(whenPresent, conditions, unsupportedWhenKeys,
                stages, diagnostics, null);
    }

    private static void parseConditionList(Object value,
                                           List<Condition> conditions,
                                           List<String> diagnostics) {
        if (!(value instanceof List<?> list)) {
            diagnostics.add("unsupported_when_any_shape");
            return;
        }
        for (Object item : list) {
            if (item instanceof Boolean bool) {
                conditions.add(new Condition(String.valueOf(bool), "literal", "", "", String.valueOf(bool)));
                continue;
            }
            String expr = item == null ? "" : String.valueOf(item).trim();
            if ("true".equalsIgnoreCase(expr) || "false".equalsIgnoreCase(expr)) {
                conditions.add(new Condition(expr, "literal", "", "", expr.toLowerCase(Locale.ROOT)));
                continue;
            }
            Matcher matcher = CONDITION_RE.matcher(expr);
            if (!matcher.matches()) {
                diagnostics.add("unsupported_condition:" + safeDiag(expr));
                continue;
            }
            String lhs = matcher.group(1);
            String kind = lhs.toLowerCase(Locale.ROOT).startsWith("request.header.") ? "header"
                    : lhs.toLowerCase(Locale.ROOT).startsWith("request.context.") ? "context"
                    : lhs.toLowerCase(Locale.ROOT).startsWith("metrics.") ? "metric" : "risk";
            conditions.add(new Condition(expr, kind,
                    lhs.toLowerCase(Locale.ROOT), matcher.group(2), unquote(matcher.group(3))));
        }
    }

    /**
     * Evaluate {@code when.any} against caller-supplied scope. Scope keys are
     * the lowercased left-hand-side names (e.g. {@code request.header.x-brave-mode},
     * {@code metrics.result_count}); keys absent from scope make their
     * condition UNKNOWN.
     */
    public WhenVerdict evaluateWhen(Map<String, Object> scope) {
        if (!whenPresent) {
            return new WhenVerdict(TriState.TRUE, List.of());
        }
        List<ConditionEval> evals = new ArrayList<>();
        boolean anyTrue = false;
        boolean anyUnknown = false;
        for (Condition condition : whenAny) {
            ConditionEval eval = evalCondition(condition, scope == null ? Map.of() : scope);
            evals.add(eval);
            if ("true".equals(eval.result())) {
                anyTrue = true;
            } else if ("unknown".equals(eval.result())) {
                anyUnknown = true;
            }
        }
        TriState state = anyTrue ? TriState.TRUE
                : (anyUnknown || whenAny.isEmpty() || unsupportedWhenKeys > 0) ? TriState.UNKNOWN
                : TriState.FALSE;
        return new WhenVerdict(state, List.copyOf(evals));
    }

    private static ConditionEval evalCondition(Condition condition, Map<String, Object> scope) {
        if ("literal".equals(condition.kind())) {
            return new ConditionEval(condition.expression(),
                    Boolean.parseBoolean(condition.operand()) ? "true" : "false", "");
        }
        Object observed = scope.get(condition.name());
        if (observed == null) {
            return new ConditionEval(condition.expression(), "unknown", "unobserved:" + condition.kind());
        }
        return compareObserved(condition, observed);
    }

    private static ConditionEval compareObserved(Condition condition, Object observed) {
        String op = condition.op();
        String operand = condition.operand();
        if ("<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op)) {
            Double left = asDouble(observed);
            Double right = asDouble(operand);
            if (left == null || right == null) {
                return new ConditionEval(condition.expression(), "unknown", "non_numeric");
            }
            boolean result = switch (op) {
                case "<" -> left < right;
                case "<=" -> left <= right;
                case ">" -> left > right;
                default -> left >= right;
            };
            return new ConditionEval(condition.expression(), result ? "true" : "false", "");
        }
        boolean equal;
        Boolean wantBool = asBoolLiteral(operand);
        if (wantBool != null) {
            Boolean got = asBoolValue(observed);
            if (got == null) {
                return new ConditionEval(condition.expression(), "unknown", "non_boolean");
            }
            equal = got == wantBool;
        } else {
            equal = String.valueOf(observed).trim().equalsIgnoreCase(operand.trim());
        }
        boolean result = "!=".equals(op) ? !equal : equal;
        return new ConditionEval(condition.expression(), result ? "true" : "false", "");
    }

    /**
     * Map each declared pipeline stage to observed runtime evidence. Stages
     * without a callable binding are reported unavailable; executed status
     * requires a real evidence marker, never the declaration alone.
     */
    public List<StageEntry> stageLedger(Map<String, Object> evidence, StageFlags flags) {
        List<StageEntry> out = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (String stage : pipeline) {
            String key = stage.toLowerCase(Locale.ROOT);
            if (!emitted.add(key)) {
                out.add(new StageEntry(stage, StageStatus.SKIPPED_DUPLICATE, "", "duplicate_stage"));
                continue;
            }
            Binding binding = bind(key);
            if (binding == Binding.NONE) {
                out.add(new StageEntry(stage, StageStatus.UNAVAILABLE, "", "no_binding"));
                continue;
            }
            if (binding == Binding.DELEGATED) {
                out.add(new StageEntry(stage, StageStatus.DELEGATED, "", "caller_owned"));
                continue;
            }
            boolean expansionClass = EXPANSION_STAGES.contains(key);
            if (expansionClass && !flags.expansionEligible()) {
                out.add(new StageEntry(stage, StageStatus.SKIPPED_WHEN_INACTIVE, "", "when_not_true"));
                continue;
            }
            out.add(resolveStage(stage, binding, evidence, flags));
        }
        return List.copyOf(out);
    }

    private static Binding bind(String stage) {
        return switch (stage) {
            case "analyze.selfask" -> Binding.SELF_ASK;
            case "retrieve.dynamicchain" -> Binding.RETRIEVAL;
            case "fuse.rrf.weighted" -> Binding.FUSION;
            case "rerank.biencoder" -> Binding.BIENCODER;
            case "rerank.crossencoder.onnx" -> Binding.ONNX;
            case "diversity.dpp" -> Binding.DPP;
            case "prompt.build", "answer.generate" -> Binding.DELEGATED;
            default -> Binding.NONE;
        };
    }

    private static StageEntry resolveStage(String stage,
                                           Binding binding,
                                           Map<String, Object> evidence,
                                           StageFlags flags) {
        Map<String, Object> dbg = evidence == null ? Map.of() : evidence;
        return switch (binding) {
            case SELF_ASK -> {
                Object marker = dbg.get("selfAsk");
                if ("enabled".equals(marker)) {
                    yield new StageEntry(stage, StageStatus.ENABLED, "selfAsk", "marker");
                }
                if ("missing_selfAskPlanner".equals(marker)) {
                    yield new StageEntry(stage, StageStatus.UNAVAILABLE, "selfAsk", "planner_absent");
                }
                yield new StageEntry(stage,
                        flags.selfAskOn() ? StageStatus.DECLARED : StageStatus.SKIPPED_FLAG_OFF,
                        "", flags.selfAskOn() ? "no_marker" : "selfask_off");
            }
            case RETRIEVAL -> {
                for (String k : List.of("stage.web", "stage.vector", "stage.kg", "stage.bm25")) {
                    Object v = dbg.get(k);
                    if (v != null && !"disabled".equals(v)) {
                        yield new StageEntry(stage, StageStatus.EXECUTED, k, "");
                    }
                }
                yield new StageEntry(stage, StageStatus.DECLARED, "", "no_retrieval_marker");
            }
            case FUSION -> dbg.get("stage.fuse") != null
                    ? new StageEntry(stage, StageStatus.EXECUTED, "stage.fuse", "")
                    : new StageEntry(stage, StageStatus.DECLARED, "", "no_fusion_marker");
            case BIENCODER -> !flags.biEncoderOn()
                    ? new StageEntry(stage, StageStatus.SKIPPED_FLAG_OFF, "", "biencoder_off")
                    : dbg.get("stage.biencoder") != null
                        ? new StageEntry(stage, StageStatus.EXECUTED, "stage.biencoder", "")
                        : new StageEntry(stage, StageStatus.DECLARED, "", "no_marker");
            case ONNX -> onnxEntry(stage, dbg, flags);
            case DPP -> dppEntry(stage, dbg, flags);
            default -> new StageEntry(stage, StageStatus.UNAVAILABLE, "", "no_binding");
        };
    }

    private static StageEntry onnxEntry(String stage, Map<String, Object> dbg, StageFlags flags) {
        if (!flags.onnxOn()) {
            return new StageEntry(stage, StageStatus.SKIPPED_FLAG_OFF, "", "onnx_off");
        }
        Object marker = dbg.get("stage.onnx");
        if (marker instanceof Number) {
            return new StageEntry(stage, StageStatus.EXECUTED, "stage.onnx", "");
        }
        if (marker instanceof String text) {
            if (text.startsWith("skipped:")) {
                return new StageEntry(stage, StageStatus.SKIPPED_DEPENDENCY, "stage.onnx", text);
            }
            if (text.startsWith("error:")) {
                return new StageEntry(stage, StageStatus.FAILED, "stage.onnx", "stage_error");
            }
        }
        return new StageEntry(stage, StageStatus.DECLARED, "", "no_marker");
    }

    private static StageEntry dppEntry(String stage, Map<String, Object> dbg, StageFlags flags) {
        if (!flags.diversityOn()) {
            return new StageEntry(stage, StageStatus.SKIPPED_FLAG_OFF, "", "diversity_off");
        }
        Object marker = dbg.get("stage.dpp");
        if (marker instanceof Number) {
            return new StageEntry(stage, StageStatus.EXECUTED, "stage.dpp", "");
        }
        if (marker instanceof String text) {
            if (text.startsWith("disabled:") || text.startsWith("skipped:")) {
                return new StageEntry(stage, StageStatus.SKIPPED_DEPENDENCY, "stage.dpp", text);
            }
            if (text.startsWith("error:")) {
                return new StageEntry(stage, StageStatus.FAILED, "stage.dpp", "stage_error");
            }
        }
        return new StageEntry(stage, StageStatus.DECLARED, "", "no_marker");
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean asBoolLiteral(String operand) {
        if ("true".equalsIgnoreCase(operand)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(operand)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Boolean asBoolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return asBoolLiteral(String.valueOf(value).trim());
    }

    private static String unquote(String operand) {
        String out = operand == null ? "" : operand.trim();
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1);
        }
        return out;
    }

    private static String safeDiag(String value) {
        if (value == null) {
            return "unknown";
        }
        String out = value.trim().replaceAll("[^A-Za-z0-9_.:/=-]+", "_");
        return out.length() > 120 ? out.substring(0, 120) : out;
    }
}
