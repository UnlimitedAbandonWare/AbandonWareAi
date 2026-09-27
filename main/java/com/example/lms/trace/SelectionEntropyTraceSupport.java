package com.example.lms.trace;

import com.example.lms.infra.selection.ReplaySelectionEntropy;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropyCoherence;
import com.example.lms.infra.selection.SelectionEntropyProjection;
import com.example.lms.infra.selection.SelectionEntropyReason;
import com.example.lms.search.TraceStore;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SelectionEntropyTraceSupport {

    public static final String PREFIX = "selectionEntropy.";

    public static final String SCHEMA = PREFIX + "schema";
    public static final String MODE = PREFIX + "mode";
    public static final String ALGORITHM_VERSION = PREFIX + "algorithmVersion";
    public static final String REPLAY_ACCEPTED = PREFIX + "replayAccepted";
    public static final String COHERENCE_STATUS = PREFIX + "coherenceStatus";
    public static final String SEED_FINGERPRINT = PREFIX + "seedFingerprint";
    public static final String DECISION_DIGEST = PREFIX + "decisionDigest";
    public static final String DECISION_COUNT = PREFIX + "decisionCount";
    public static final String DRAW_COUNT = PREFIX + "drawCount";
    public static final String STABLE_TIE_BREAK_COUNT = PREFIX + "stableTieBreakCount";
    public static final String CANDIDATE_DRIFT_COUNT = PREFIX + "candidateDriftCount";
    public static final String ROUTER_DRAW_COUNT = PREFIX + "routerDrawCount";
    public static final String STRATEGY_DRAW_COUNT = PREFIX + "strategyDrawCount";
    public static final String ENSEMBLE_DRAW_COUNT = PREFIX + "ensembleDrawCount";
    public static final String COMPLETION_ORDER_DETERMINISTIC =
            PREFIX + "completionOrderDeterministic";
    public static final String REASON_CODE = PREFIX + "reasonCode";

    private static final String EXPECTED_SCHEMA = "awx.selection-entropy.v1";
    private static final Set<String> MODES = Set.of("standard", "replay");
    private static final Set<String> COHERENCE_VALUES = Arrays.stream(
                    SelectionEntropyCoherence.values())
            .map(SelectionEntropyCoherence::wireValue)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> REASON_VALUES = Arrays.stream(
                    SelectionEntropyReason.values())
            .map(SelectionEntropyReason::code)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> EXACT_KEYS = Set.copyOf(new LinkedHashSet<>(java.util.List.of(
            SCHEMA,
            MODE,
            ALGORITHM_VERSION,
            REPLAY_ACCEPTED,
            COHERENCE_STATUS,
            SEED_FINGERPRINT,
            DECISION_DIGEST,
            DECISION_COUNT,
            DRAW_COUNT,
            STABLE_TIE_BREAK_COUNT,
            CANDIDATE_DRIFT_COUNT,
            ROUTER_DRAW_COUNT,
            STRATEGY_DRAW_COUNT,
            ENSEMBLE_DRAW_COUNT,
            COMPLETION_ORDER_DETERMINISTIC,
            REASON_CODE)));

    private SelectionEntropyTraceSupport() {
    }

    public static void write(SelectionEntropyProjection value) {
        if (value == null) {
            return;
        }
        TraceStore.put(SCHEMA, value.schema());
        TraceStore.put(MODE, value.mode());
        TraceStore.put(ALGORITHM_VERSION, value.algorithmVersion());
        TraceStore.put(REPLAY_ACCEPTED, value.replayAccepted());
        TraceStore.put(COHERENCE_STATUS, value.coherenceStatus());
        TraceStore.put(SEED_FINGERPRINT, value.seedFingerprint());
        TraceStore.put(DECISION_DIGEST, value.decisionDigest());
        TraceStore.put(DECISION_COUNT, value.decisionCount());
        TraceStore.put(DRAW_COUNT, value.drawCount());
        TraceStore.put(STABLE_TIE_BREAK_COUNT, value.stableTieBreakCount());
        TraceStore.put(CANDIDATE_DRIFT_COUNT, value.candidateDriftCount());
        TraceStore.put(ROUTER_DRAW_COUNT, value.routerDrawCount());
        TraceStore.put(STRATEGY_DRAW_COUNT, value.strategyDrawCount());
        TraceStore.put(ENSEMBLE_DRAW_COUNT, value.ensembleDrawCount());
        TraceStore.put(COMPLETION_ORDER_DETERMINISTIC,
                value.completionOrderDeterministic());
        TraceStore.put(REASON_CODE, value.reasonCode());
    }

    public static Optional<SelectionEntropyProjection> fromTrace(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return Optional.empty();
        }
        boolean hasSelectionField = false;
        for (String key : trace.keySet()) {
            if (key != null && key.startsWith(PREFIX)) {
                hasSelectionField = true;
                if (!EXACT_KEYS.contains(key)) {
                    return Optional.empty();
                }
            }
        }
        if (!hasSelectionField) {
            return Optional.empty();
        }
        try {
            String schema = required(trace, SCHEMA, String.class);
            String mode = required(trace, MODE, String.class);
            String algorithm = required(trace, ALGORITHM_VERSION, String.class);
            Boolean replayAccepted = required(trace, REPLAY_ACCEPTED, Boolean.class);
            String coherence = required(trace, COHERENCE_STATUS, String.class);
            String fingerprint = optional(trace, SEED_FINGERPRINT, String.class);
            String digest = required(trace, DECISION_DIGEST, String.class);
            Integer decisionCount = required(trace, DECISION_COUNT, Integer.class);
            Integer drawCount = required(trace, DRAW_COUNT, Integer.class);
            Integer stableTieCount = required(trace, STABLE_TIE_BREAK_COUNT, Integer.class);
            Integer driftCount = required(trace, CANDIDATE_DRIFT_COUNT, Integer.class);
            Integer routerCount = required(trace, ROUTER_DRAW_COUNT, Integer.class);
            Integer strategyCount = required(trace, STRATEGY_DRAW_COUNT, Integer.class);
            Integer ensembleCount = required(trace, ENSEMBLE_DRAW_COUNT, Integer.class);
            Boolean completionOrder = required(
                    trace, COMPLETION_ORDER_DETERMINISTIC, Boolean.class);
            String reason = required(trace, REASON_CODE, String.class);
            return Optional.of(new SelectionEntropyProjection(
                    schema,
                    mode,
                    algorithm,
                    replayAccepted,
                    coherence,
                    fingerprint,
                    digest,
                    decisionCount,
                    drawCount,
                    stableTieCount,
                    driftCount,
                    routerCount,
                    strategyCount,
                    ensembleCount,
                    completionOrder,
                    reason));
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    public static Object sanitizeExactValue(String key, Object value) {
        if (key == null || !EXACT_KEYS.contains(key)) {
            return null;
        }
        return switch (key) {
            case SCHEMA -> value instanceof String text && EXPECTED_SCHEMA.equals(text)
                    ? text : null;
            case MODE -> value instanceof String text && MODES.contains(text)
                    ? text : null;
            case ALGORITHM_VERSION -> value instanceof String text
                    && ReplaySelectionEntropy.ALGORITHM_VERSION.equals(text) ? text : null;
            case REPLAY_ACCEPTED, COMPLETION_ORDER_DETERMINISTIC ->
                    value instanceof Boolean ? value : null;
            case COHERENCE_STATUS -> value instanceof String text
                    && COHERENCE_VALUES.contains(text) ? text : null;
            case SEED_FINGERPRINT -> value instanceof String text
                    && text.matches("[a-f0-9]{12}") ? text : null;
            case DECISION_DIGEST -> value instanceof String text
                    && text.matches("[a-f0-9]{64}") ? text : null;
            case DECISION_COUNT,
                    DRAW_COUNT,
                    STABLE_TIE_BREAK_COUNT,
                    CANDIDATE_DRIFT_COUNT,
                    ROUTER_DRAW_COUNT,
                    STRATEGY_DRAW_COUNT,
                    ENSEMBLE_DRAW_COUNT -> value instanceof Integer count
                    && count >= 0
                    && count <= SelectionDecisionLedger.MAX_DECISIONS ? count : null;
            case REASON_CODE -> value instanceof String text
                    && REASON_VALUES.contains(text) ? text : null;
            default -> null;
        };
    }

    private static <T> T required(Map<String, Object> trace, String key, Class<T> type) {
        if (!trace.containsKey(key)) {
            throw new IllegalArgumentException("selection_entropy_trace_missing");
        }
        Object safe = sanitizeExactValue(key, trace.get(key));
        if (!type.isInstance(safe)) {
            throw new IllegalArgumentException("selection_entropy_trace_invalid");
        }
        return type.cast(safe);
    }

    private static <T> T optional(Map<String, Object> trace, String key, Class<T> type) {
        if (!trace.containsKey(key)) {
            return null;
        }
        Object safe = sanitizeExactValue(key, trace.get(key));
        if (!type.isInstance(safe)) {
            throw new IllegalArgumentException("selection_entropy_trace_invalid");
        }
        return type.cast(safe);
    }
}
