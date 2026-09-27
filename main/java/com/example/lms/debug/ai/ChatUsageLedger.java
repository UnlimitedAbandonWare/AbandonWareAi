package com.example.lms.debug.ai;

import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-local, payload-free counters for answer-generation model calls.
 *
 * <p>The ledger intentionally records configured token caps and provider-reported
 * counts only. It does not record prompts, answers, queries, model/session IDs,
 * errors, or transport-level retry claims.</p>
 */
@Component
public final class ChatUsageLedger {

    public enum CapState {
        EXPLICIT("explicit"),
        OMITTED("omitted"),
        PROVIDER_DEFAULT_UNKNOWN("provider_default_unknown");

        private final String wireValue;

        CapState(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum ParameterKind {
        MAX_TOKENS("max_tokens"),
        MAX_COMPLETION_TOKENS("max_completion_tokens"),
        MAX_OUTPUT_TOKENS("max_output_tokens"),
        NUM_PREDICT("num_predict"),
        OMITTED("omitted"),
        UNKNOWN("unknown");

        private final String wireValue;

        ParameterKind(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum CapSource {
        NORMALIZED_REQUEST,
        PROFILE_TARGET,
        ROUTER_MODEL,
        BEAN_DEFAULT,
        SELF_HEAL,
        COMPLETIONS_FALLBACK,
        RESPONSES_FALLBACK,
        UNKNOWN
    }

    public enum ModelPurpose {
        PRIMARY,
        RETRY,
        SELF_HEAL,
        MODEL_REQUIRED_HEAL,
        COMPLETIONS_FALLBACK,
        RESPONSES_FALLBACK,
        EXPANSION
    }

    public record ConfiguredCap(
            Integer profileTarget,
            Integer normalizedRequestCap,
            Integer configuredCap,
            CapState state,
            ParameterKind parameterKind,
            CapSource source) {

        public ConfiguredCap {
            state = state == null ? CapState.PROVIDER_DEFAULT_UNKNOWN : state;
            parameterKind = parameterKind == null ? ParameterKind.UNKNOWN : parameterKind;
            source = source == null ? CapSource.UNKNOWN : source;
        }

        public static ConfiguredCap explicit(
                Integer profileTarget,
                Integer normalizedRequestCap,
                Integer configuredCap,
                ParameterKind parameterKind,
                CapSource source) {
            return new ConfiguredCap(
                    profileTarget,
                    normalizedRequestCap,
                    configuredCap,
                    CapState.EXPLICIT,
                    parameterKind,
                    source);
        }

        public static ConfiguredCap omitted(
                Integer profileTarget,
                Integer normalizedRequestCap,
                CapSource source) {
            return new ConfiguredCap(
                    profileTarget,
                    normalizedRequestCap,
                    null,
                    CapState.OMITTED,
                    ParameterKind.OMITTED,
                    source);
        }

        public static ConfiguredCap providerDefaultUnknown(
                Integer profileTarget,
                Integer normalizedRequestCap) {
            return new ConfiguredCap(
                    profileTarget,
                    normalizedRequestCap,
                    null,
                    CapState.PROVIDER_DEFAULT_UNKNOWN,
                    ParameterKind.UNKNOWN,
                    CapSource.UNKNOWN);
        }

        public ConfiguredCap withRequestContext(
                Integer observedProfileTarget,
                Integer observedNormalizedRequestCap,
                CapSource observedSource) {
            return new ConfiguredCap(
                    observedProfileTarget,
                    observedNormalizedRequestCap,
                    configuredCap,
                    state,
                    parameterKind,
                    observedSource == null ? source : observedSource);
        }
    }

    private enum ModelTerminal {
        OPEN,
        RESPONSE_RECEIVED,
        FAILED_BEFORE_RESPONSE,
        TIMED_OUT,
        CANCELLED
    }

    private enum ExpansionState {
        BEFORE_MODEL,
        MODEL_STARTED,
        TERMINAL
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final long counterEpochStartedAtMs = System.currentTimeMillis();
    private boolean overflowed;

    private long modelAttempts;
    private long modelInFlight;
    private long modelResponseReceived;
    private long modelFailedBeforeResponse;
    private long modelTimedOut;
    private long modelCancelled;
    private long profileTargetObservedCount;
    private Integer latestProfileTarget;
    private long normalizedRequestCapObservedCount;
    private Integer latestNormalizedRequestCap;
    private long configuredCapKnownAttemptCount;
    private long configuredCapUnknownAttemptCount;
    private long configuredCapSum;
    private Integer configuredCapMin;
    private Integer configuredCapMax;
    private String lastSuccessfulCapState;
    private String lastSuccessfulParameterKind;
    private Integer lastSuccessfulConfiguredCap;
    private long providerUsageObservedAttemptCount;
    private long providerUsageMissingAttemptCount;
    private long providerInputTokensObservedAttemptCount;
    private long providerOutputTokensObservedAttemptCount;
    private long providerTotalTokensObservedAttemptCount;
    private long providerInputTokens;
    private long providerOutputTokens;
    private long providerTotalTokens;
    private final long[] modelPurposeCounts = new long[ModelPurpose.values().length];
    private final long[] capStateCounts = new long[CapState.values().length];
    private final long[] parameterKindCounts = new long[ParameterKind.values().length];

    private long expansionInvocations;
    private long expansionInFlightBeforeModel;
    private long expansionInFlightAfterModel;
    private long expansionSkippedBeforeModel;
    private long expansionFailedBeforeModel;
    private long expansionModelInvocations;
    private long expansionAccepted;
    private long expansionRejectedNumeric;
    private long expansionRejectedNoEvidence;
    private long expansionRejectedTooShort;
    private long expansionRejectedEmpty;
    private long expansionFailedAfterModel;
    private long expansionTimedOut;
    private long expansionCancelled;
    private long expansionProviderUsageObservedAttemptCount;
    private long expansionProviderUsageMissingAttemptCount;
    private long expansionProviderOutputTokensObservedAttemptCount;
    private long expansionRejectedProviderOutputTokensObservedAttemptCount;
    private long expansionProviderOutputTokens;
    private long expansionRejectedProviderOutputTokens;
    private long expansionRequestedTokenBudgetUpperBoundSum;
    private long expansionUnknownBudgetAttemptCount;
    private String s8IntegrityCorrelationHash = "";
    private final Map<String, Map<String, Object>> s8IntegrityStages = new LinkedHashMap<>();

    public ModelAttempt beginModelInvocation(ModelPurpose purpose, ConfiguredCap configuredCap) {
        ConfiguredCap safeCap = configuredCap == null
                ? ConfiguredCap.providerDefaultUnknown(null, null)
                : configuredCap;
        lock.lock();
        try {
            modelAttempts = add(modelAttempts, 1L);
            modelInFlight = add(modelInFlight, 1L);
            ModelPurpose safePurpose = purpose == null ? ModelPurpose.PRIMARY : purpose;
            modelPurposeCounts[safePurpose.ordinal()] = add(modelPurposeCounts[safePurpose.ordinal()], 1L);
            recordCapObservation(safeCap);
        } finally {
            lock.unlock();
        }
        return new ModelAttempt(safeCap);
    }

    public ExpansionAttempt beginExpansion() {
        lock.lock();
        try {
            expansionInFlightBeforeModel = add(expansionInFlightBeforeModel, 1L);
        } finally {
            lock.unlock();
        }
        return new ExpansionAttempt();
    }

    public void recordS8Integrity(
            String stage,
            String correlationHash,
            int requiredObservationCount,
            int preservedObservationCount,
            int requiredInferenceCount,
            int preservedInferenceCount,
            boolean orderedTwoLineContract,
            boolean complete,
            String contentHash) {
        String safeStage = switch (stage == null ? "" : stage) {
            case "primary" -> "primary";
            case "preExpansion" -> "preExpansion";
            case "postExpansion" -> "postExpansion";
            default -> "";
        };
        String safeCorrelationHash = safeSha256(correlationHash);
        if (safeStage.isBlank() || safeCorrelationHash.isBlank()) {
            return;
        }
        int requiredObservations = Math.max(0, requiredObservationCount);
        int preservedObservations = Math.min(requiredObservations, Math.max(0, preservedObservationCount));
        int requiredInferences = Math.max(0, requiredInferenceCount);
        int preservedInferences = Math.min(requiredInferences, Math.max(0, preservedInferenceCount));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("requiredObservationCount", requiredObservations);
        row.put("preservedObservationCount", preservedObservations);
        row.put("droppedObservationCount", requiredObservations - preservedObservations);
        row.put("requiredInferenceCount", requiredInferences);
        row.put("preservedInferenceCount", preservedInferences);
        row.put("droppedInferenceCount", requiredInferences - preservedInferences);
        row.put("orderedTwoLineContract", orderedTwoLineContract);
        row.put("complete", complete);
        row.put("contentHash", safeSha256(contentHash));
        lock.lock();
        try {
            if ("primary".equals(safeStage)
                    || !safeCorrelationHash.equals(s8IntegrityCorrelationHash)) {
                s8IntegrityStages.clear();
                s8IntegrityCorrelationHash = safeCorrelationHash;
            }
            s8IntegrityStages.put(safeStage, Map.copyOf(row));
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> snapshot() {
        lock.lock();
        try {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("attempts", modelAttempts);
            model.put("inFlight", modelInFlight);
            model.put("responseReceived", modelResponseReceived);
            model.put("failedBeforeResponse", modelFailedBeforeResponse);
            model.put("timedOut", modelTimedOut);
            model.put("cancelled", modelCancelled);
            model.put("profileTargetObservedCount", profileTargetObservedCount);
            model.put("latestProfileTarget", latestProfileTarget);
            model.put("normalizedRequestCapObservedCount", normalizedRequestCapObservedCount);
            model.put("latestNormalizedRequestCap", latestNormalizedRequestCap);
            model.put("configuredCapKnownAttemptCount", configuredCapKnownAttemptCount);
            model.put("unknownAttemptCount", configuredCapUnknownAttemptCount);
            model.put("configuredCapSum", configuredCapSum);
            model.put("configuredCapMin", configuredCapMin);
            model.put("configuredCapMax", configuredCapMax);
            model.put("lastSuccessfulCapState", lastSuccessfulCapState);
            model.put("lastSuccessfulParameterKind", lastSuccessfulParameterKind);
            model.put("lastSuccessfulConfiguredCap", lastSuccessfulConfiguredCap);
            model.put("providerUsageObservedAttemptCount", providerUsageObservedAttemptCount);
            model.put("providerUsageMissingAttemptCount", providerUsageMissingAttemptCount);
            model.put("providerInputTokensObservedAttemptCount", providerInputTokensObservedAttemptCount);
            model.put("providerOutputTokensObservedAttemptCount", providerOutputTokensObservedAttemptCount);
            model.put("providerTotalTokensObservedAttemptCount", providerTotalTokensObservedAttemptCount);
            model.put("providerInputTokens", providerInputTokens);
            model.put("providerOutputTokens", providerOutputTokens);
            model.put("providerTotalTokens", providerTotalTokens);
            long terminalModelAttempts = addWithoutOverflowFlag(
                    modelResponseReceived,
                    addWithoutOverflowFlag(
                            modelFailedBeforeResponse,
                            addWithoutOverflowFlag(modelTimedOut, modelCancelled)));
            model.put("providerInputTokensComplete",
                    terminalModelAttempts > 0L && providerInputTokensObservedAttemptCount == terminalModelAttempts);
            model.put("providerOutputTokensComplete",
                    terminalModelAttempts > 0L && providerOutputTokensObservedAttemptCount == terminalModelAttempts);
            model.put("providerTotalTokensComplete",
                    terminalModelAttempts > 0L && providerTotalTokensObservedAttemptCount == terminalModelAttempts);
            model.put("byPurpose", enumCounts(ModelPurpose.values(), modelPurposeCounts));
            model.put("capStateCounts", wireCounts(CapState.values(), capStateCounts));
            model.put("parameterKindCounts", wireCounts(ParameterKind.values(), parameterKindCounts));

            long rejectionDenominator = addWithoutOverflowFlag(
                    expansionAccepted,
                    addWithoutOverflowFlag(
                            expansionRejectedNumeric,
                            addWithoutOverflowFlag(
                                    expansionRejectedNoEvidence,
                                    addWithoutOverflowFlag(expansionRejectedTooShort, expansionRejectedEmpty))));
            Map<String, Object> expansion = new LinkedHashMap<>();
            expansion.put("invocations", expansionInvocations);
            expansion.put("inFlightBeforeModel", expansionInFlightBeforeModel);
            expansion.put("inFlightAfterModel", expansionInFlightAfterModel);
            expansion.put("skippedBeforeModel", expansionSkippedBeforeModel);
            expansion.put("failedBeforeModel", expansionFailedBeforeModel);
            expansion.put("modelInvocations", expansionModelInvocations);
            expansion.put("accepted", expansionAccepted);
            expansion.put("rejectedNumeric", expansionRejectedNumeric);
            expansion.put("rejectedNoEvidence", expansionRejectedNoEvidence);
            expansion.put("rejectedTooShort", expansionRejectedTooShort);
            expansion.put("rejectedEmpty", expansionRejectedEmpty);
            expansion.put("failedAfterModel", expansionFailedAfterModel);
            expansion.put("timedOut", expansionTimedOut);
            expansion.put("cancelled", expansionCancelled);
            expansion.put("providerUsageObservedAttemptCount", expansionProviderUsageObservedAttemptCount);
            expansion.put("providerUsageMissingAttemptCount", expansionProviderUsageMissingAttemptCount);
            expansion.put("providerOutputTokensObservedAttemptCount",
                    expansionProviderOutputTokensObservedAttemptCount);
            expansion.put("providerOutputTokens", expansionProviderOutputTokens);
            expansion.put("rejectedProviderOutputTokens", expansionRejectedProviderOutputTokens);
            long rejectedTotal = addWithoutOverflowFlag(
                    expansionRejectedNumeric,
                    addWithoutOverflowFlag(
                            expansionRejectedNoEvidence,
                            addWithoutOverflowFlag(expansionRejectedTooShort, expansionRejectedEmpty)));
            expansion.put("providerOutputTokensComplete",
                    expansionModelInvocations > 0L
                            && expansionProviderOutputTokensObservedAttemptCount == expansionModelInvocations);
            expansion.put("rejectedProviderOutputTokensComplete",
                    rejectedTotal > 0L
                            && expansionRejectedProviderOutputTokensObservedAttemptCount == rejectedTotal);
            expansion.put("requestedTokenBudgetUpperBoundSum", expansionRequestedTokenBudgetUpperBoundSum);
            expansion.put("unknownBudgetAttemptCount", expansionUnknownBudgetAttemptCount);
            expansion.put("rejectionRate", rejectionDenominator == 0L
                    ? null
                    : ((double) addWithoutOverflowFlag(
                            expansionRejectedNumeric,
                            addWithoutOverflowFlag(
                                    expansionRejectedNoEvidence,
                                    addWithoutOverflowFlag(expansionRejectedTooShort, expansionRejectedEmpty))))
                             / (double) rejectionDenominator);
            expansion.put("rejectionRateDenominator", rejectionDenominator);

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schemaVersion", "awx.chat-usage.v1");
            snapshot.put("captureEnabled", true);
            snapshot.put("observed", modelAttempts > 0L
                    || expansionInvocations > 0L
                    || expansionInFlightBeforeModel > 0L
                    || expansionInFlightAfterModel > 0L);
            snapshot.put("counterScope", "process_lifetime");
            snapshot.put("restartDurable", false);
            snapshot.put("counterEpochStartedAtMs", counterEpochStartedAtMs);
            snapshot.put("overflowed", overflowed);
            snapshot.put("wireAttemptCoverage", "not_observed");
            snapshot.put("modelInvocations", model);
            snapshot.put("answerExpansion", expansion);
            Map<String, Object> s8Integrity = new LinkedHashMap<>();
            s8Integrity.put("correlationScope", "query_hash_latest_stage");
            s8Integrity.put("correlationHash", s8IntegrityCorrelationHash);
            s8Integrity.putAll(s8IntegrityStages);
            snapshot.put("s8Integrity", s8Integrity);
            return snapshot;
        } finally {
            lock.unlock();
        }
    }

    private void recordCapObservation(ConfiguredCap configuredCap) {
        capStateCounts[configuredCap.state().ordinal()] = add(
                capStateCounts[configuredCap.state().ordinal()], 1L);
        parameterKindCounts[configuredCap.parameterKind().ordinal()] = add(
                parameterKindCounts[configuredCap.parameterKind().ordinal()], 1L);
        if (positive(configuredCap.profileTarget())) {
            profileTargetObservedCount = add(profileTargetObservedCount, 1L);
            latestProfileTarget = configuredCap.profileTarget();
        }
        if (positive(configuredCap.normalizedRequestCap())) {
            normalizedRequestCapObservedCount = add(normalizedRequestCapObservedCount, 1L);
            latestNormalizedRequestCap = configuredCap.normalizedRequestCap();
        }
        if (configuredCap.state() == CapState.EXPLICIT && positive(configuredCap.configuredCap())) {
            configuredCapKnownAttemptCount = add(configuredCapKnownAttemptCount, 1L);
            configuredCapSum = add(configuredCapSum, configuredCap.configuredCap().longValue());
            configuredCapMin = configuredCapMin == null
                    ? configuredCap.configuredCap()
                    : Math.min(configuredCapMin, configuredCap.configuredCap());
            configuredCapMax = configuredCapMax == null
                    ? configuredCap.configuredCap()
                    : Math.max(configuredCapMax, configuredCap.configuredCap());
        } else {
            configuredCapUnknownAttemptCount = add(configuredCapUnknownAttemptCount, 1L);
        }
    }

    private static String safeSha256(String value) {
        if (value == null || value.length() != 64) {
            return "";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return "";
            }
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private void finishModel(ModelAttempt attempt, ModelTerminal terminal, TokenUsage usage) {
        lock.lock();
        try {
            modelInFlight = Math.max(0L, modelInFlight - 1L);
            switch (terminal) {
                case RESPONSE_RECEIVED -> {
                    modelResponseReceived = add(modelResponseReceived, 1L);
                    attempt.usage = usageData(usage);
                    if (attempt.usage.observed) {
                        providerUsageObservedAttemptCount = add(providerUsageObservedAttemptCount, 1L);
                        if (attempt.usage.inputObserved) {
                            providerInputTokensObservedAttemptCount = add(
                                    providerInputTokensObservedAttemptCount, 1L);
                            providerInputTokens = add(providerInputTokens, attempt.usage.inputTokens);
                        }
                        if (attempt.usage.outputObserved) {
                            providerOutputTokensObservedAttemptCount = add(
                                    providerOutputTokensObservedAttemptCount, 1L);
                            providerOutputTokens = add(providerOutputTokens, attempt.usage.outputTokens);
                        }
                        if (attempt.usage.totalObserved) {
                            providerTotalTokensObservedAttemptCount = add(
                                    providerTotalTokensObservedAttemptCount, 1L);
                            providerTotalTokens = add(providerTotalTokens, attempt.usage.totalTokens);
                        }
                    } else {
                        providerUsageMissingAttemptCount = add(providerUsageMissingAttemptCount, 1L);
                    }
                }
                case FAILED_BEFORE_RESPONSE -> {
                    modelFailedBeforeResponse = add(modelFailedBeforeResponse, 1L);
                    providerUsageMissingAttemptCount = add(providerUsageMissingAttemptCount, 1L);
                }
                case TIMED_OUT -> {
                    modelTimedOut = add(modelTimedOut, 1L);
                    providerUsageMissingAttemptCount = add(providerUsageMissingAttemptCount, 1L);
                }
                case CANCELLED -> {
                    modelCancelled = add(modelCancelled, 1L);
                    providerUsageMissingAttemptCount = add(providerUsageMissingAttemptCount, 1L);
                }
                case OPEN -> throw new IllegalArgumentException("OPEN is not terminal");
            }
        } finally {
            lock.unlock();
        }
    }

    private void recordExpansionBudget(ConfiguredCap configuredCap) {
        lock.lock();
        try {
            if (configuredCap != null
                    && configuredCap.state() == CapState.EXPLICIT
                    && positive(configuredCap.configuredCap())) {
                expansionRequestedTokenBudgetUpperBoundSum = add(
                        expansionRequestedTokenBudgetUpperBoundSum,
                        configuredCap.configuredCap().longValue());
            } else {
                expansionUnknownBudgetAttemptCount = add(expansionUnknownBudgetAttemptCount, 1L);
            }
        } finally {
            lock.unlock();
        }
    }

    private void recordExpansionModelStarted() {
        lock.lock();
        try {
            expansionInFlightBeforeModel = Math.max(0L, expansionInFlightBeforeModel - 1L);
            expansionInFlightAfterModel = add(expansionInFlightAfterModel, 1L);
        } finally {
            lock.unlock();
        }
    }

    private void recordModelSuccess(ModelAttempt attempt) {
        lock.lock();
        try {
            lastSuccessfulCapState = attempt.configuredCap.state().wireValue();
            lastSuccessfulParameterKind = attempt.configuredCap.parameterKind().wireValue();
            lastSuccessfulConfiguredCap = attempt.configuredCap.state() == CapState.EXPLICIT
                    && positive(attempt.configuredCap.configuredCap())
                    ? attempt.configuredCap.configuredCap()
                    : null;
        } finally {
            lock.unlock();
        }
    }

    private void recordExpansionTerminal(ExpansionTerminal terminal, ModelAttempt modelAttempt) {
        lock.lock();
        try {
            if (modelAttempt == null) {
                expansionInFlightBeforeModel = Math.max(0L, expansionInFlightBeforeModel - 1L);
            } else {
                expansionInFlightAfterModel = Math.max(0L, expansionInFlightAfterModel - 1L);
            }
            expansionInvocations = add(expansionInvocations, 1L);
            if (modelAttempt != null) {
                expansionModelInvocations = add(expansionModelInvocations, 1L);
            }
            switch (terminal) {
                case SKIPPED_BEFORE_MODEL -> expansionSkippedBeforeModel = add(expansionSkippedBeforeModel, 1L);
                case FAILED_BEFORE_MODEL -> expansionFailedBeforeModel = add(expansionFailedBeforeModel, 1L);
                case ACCEPTED -> expansionAccepted = add(expansionAccepted, 1L);
                case REJECTED_NUMERIC -> expansionRejectedNumeric = add(expansionRejectedNumeric, 1L);
                case REJECTED_NO_EVIDENCE -> expansionRejectedNoEvidence = add(expansionRejectedNoEvidence, 1L);
                case REJECTED_TOO_SHORT -> expansionRejectedTooShort = add(expansionRejectedTooShort, 1L);
                case REJECTED_EMPTY -> expansionRejectedEmpty = add(expansionRejectedEmpty, 1L);
                case FAILED_AFTER_MODEL -> expansionFailedAfterModel = add(expansionFailedAfterModel, 1L);
                case TIMED_OUT -> expansionTimedOut = add(expansionTimedOut, 1L);
                case CANCELLED -> expansionCancelled = add(expansionCancelled, 1L);
            }
            if (modelAttempt != null) {
                UsageData usage = modelAttempt.usage;
                if (usage.observed) {
                    expansionProviderUsageObservedAttemptCount = add(
                            expansionProviderUsageObservedAttemptCount, 1L);
                    if (usage.outputObserved) {
                        expansionProviderOutputTokensObservedAttemptCount = add(
                                expansionProviderOutputTokensObservedAttemptCount, 1L);
                        expansionProviderOutputTokens = add(
                                expansionProviderOutputTokens, usage.outputTokens);
                    }
                    if (terminal.isRejected() && usage.outputObserved) {
                        expansionRejectedProviderOutputTokensObservedAttemptCount = add(
                                expansionRejectedProviderOutputTokensObservedAttemptCount, 1L);
                        expansionRejectedProviderOutputTokens = add(
                                expansionRejectedProviderOutputTokens, usage.outputTokens);
                    }
                } else {
                    expansionProviderUsageMissingAttemptCount = add(
                            expansionProviderUsageMissingAttemptCount, 1L);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private long add(long current, long delta) {
        if (delta <= 0L) {
            return current;
        }
        if (current > Long.MAX_VALUE - delta) {
            overflowed = true;
            return Long.MAX_VALUE;
        }
        return current + delta;
    }

    private static long addWithoutOverflowFlag(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private Map<String, Object> enumCounts(ModelPurpose[] values, long[] counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ModelPurpose value : values) {
            out.put(value.name().toLowerCase(Locale.ROOT), counts[value.ordinal()]);
        }
        return out;
    }

    private Map<String, Object> wireCounts(CapState[] values, long[] counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (CapState value : values) {
            out.put(value.wireValue(), counts[value.ordinal()]);
        }
        return out;
    }

    private Map<String, Object> wireCounts(ParameterKind[] values, long[] counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ParameterKind value : values) {
            out.put(value.wireValue(), counts[value.ordinal()]);
        }
        return out;
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static UsageData usageData(TokenUsage usage) {
        if (usage == null) {
            return UsageData.MISSING;
        }
        Integer input = usage.inputTokenCount();
        Integer output = usage.outputTokenCount();
        Integer total = usage.totalTokenCount();
        if ((input == null || input < 0)
                && (output == null || output < 0)
                && (total == null || total < 0)) {
            return UsageData.MISSING;
        }
        boolean inputObserved = input != null && input >= 0;
        boolean outputObserved = output != null && output >= 0;
        boolean totalObserved = total != null && total >= 0;
        return new UsageData(
                true,
                inputObserved,
                outputObserved,
                totalObserved,
                inputObserved ? input.longValue() : 0L,
                outputObserved ? output.longValue() : 0L,
                totalObserved ? total.longValue() : 0L);
    }

    private record UsageData(
            boolean observed,
            boolean inputObserved,
            boolean outputObserved,
            boolean totalObserved,
            long inputTokens,
            long outputTokens,
            long totalTokens) {
        private static final UsageData MISSING = new UsageData(false, false, false, false, 0L, 0L, 0L);
    }

    private enum ExpansionTerminal {
        SKIPPED_BEFORE_MODEL(false),
        FAILED_BEFORE_MODEL(false),
        ACCEPTED(false),
        REJECTED_NUMERIC(true),
        REJECTED_NO_EVIDENCE(true),
        REJECTED_TOO_SHORT(true),
        REJECTED_EMPTY(true),
        FAILED_AFTER_MODEL(false),
        TIMED_OUT(false),
        CANCELLED(false);

        private final boolean rejected;

        ExpansionTerminal(boolean rejected) {
            this.rejected = rejected;
        }

        boolean isRejected() {
            return rejected;
        }
    }

    public final class ModelAttempt {
        private final ConfiguredCap configuredCap;
        private final AtomicReference<ModelTerminal> terminal = new AtomicReference<>(ModelTerminal.OPEN);
        private final AtomicBoolean successful = new AtomicBoolean(false);
        private volatile UsageData usage = UsageData.MISSING;

        private ModelAttempt(ConfiguredCap configuredCap) {
            this.configuredCap = configuredCap;
        }

        public boolean responseReceived(TokenUsage usage) {
            return finish(ModelTerminal.RESPONSE_RECEIVED, usage);
        }

        public boolean failedBeforeResponse() {
            return finish(ModelTerminal.FAILED_BEFORE_RESPONSE, null);
        }

        public boolean timedOut() {
            return finish(ModelTerminal.TIMED_OUT, null);
        }

        public boolean cancelled() {
            return finish(ModelTerminal.CANCELLED, null);
        }

        public boolean markSuccessful() {
            if (terminal.get() != ModelTerminal.RESPONSE_RECEIVED
                    || !successful.compareAndSet(false, true)) {
                return false;
            }
            recordModelSuccess(this);
            return true;
        }

        private boolean finish(ModelTerminal target, TokenUsage usage) {
            if (!terminal.compareAndSet(ModelTerminal.OPEN, target)) {
                return false;
            }
            finishModel(this, target, usage);
            return true;
        }
    }

    public final class ExpansionAttempt {
        private final AtomicReference<ExpansionState> state = new AtomicReference<>(ExpansionState.BEFORE_MODEL);
        private volatile ModelAttempt modelAttempt;

        private ExpansionAttempt() {
        }

        public ModelAttempt beginModelInvocation(ConfiguredCap configuredCap) {
            if (!state.compareAndSet(ExpansionState.BEFORE_MODEL, ExpansionState.MODEL_STARTED)) {
                throw new IllegalStateException("expansion model invocation already started or terminal");
            }
            ConfiguredCap safeCap = configuredCap == null
                    ? ConfiguredCap.providerDefaultUnknown(null, null)
                    : configuredCap;
            recordExpansionModelStarted();
            recordExpansionBudget(safeCap);
            modelAttempt = ChatUsageLedger.this.beginModelInvocation(ModelPurpose.EXPANSION, safeCap);
            return modelAttempt;
        }

        public boolean skippedBeforeModel() {
            return finishBeforeModel(ExpansionTerminal.SKIPPED_BEFORE_MODEL);
        }

        public boolean failedBeforeModel() {
            return finishBeforeModel(ExpansionTerminal.FAILED_BEFORE_MODEL);
        }

        public boolean accepted() {
            return finishAfterModel(ExpansionTerminal.ACCEPTED);
        }

        public boolean rejectedNumeric() {
            return finishAfterModel(ExpansionTerminal.REJECTED_NUMERIC);
        }

        public boolean rejectedNoEvidence() {
            return finishAfterModel(ExpansionTerminal.REJECTED_NO_EVIDENCE);
        }

        public boolean rejectedTooShort() {
            return finishAfterModel(ExpansionTerminal.REJECTED_TOO_SHORT);
        }

        public boolean rejectedEmpty() {
            return finishAfterModel(ExpansionTerminal.REJECTED_EMPTY);
        }

        public boolean failedAfterModel() {
            return finishAfterModel(ExpansionTerminal.FAILED_AFTER_MODEL);
        }

        public boolean timedOut() {
            return finishAfterModel(ExpansionTerminal.TIMED_OUT);
        }

        public boolean cancelled() {
            return finishAfterModel(ExpansionTerminal.CANCELLED);
        }

        private boolean finishBeforeModel(ExpansionTerminal terminal) {
            if (!state.compareAndSet(ExpansionState.BEFORE_MODEL, ExpansionState.TERMINAL)) {
                return false;
            }
            recordExpansionTerminal(terminal, null);
            return true;
        }

        private boolean finishAfterModel(ExpansionTerminal terminal) {
            if (!state.compareAndSet(ExpansionState.MODEL_STARTED, ExpansionState.TERMINAL)) {
                return false;
            }
            recordExpansionTerminal(terminal, modelAttempt);
            return true;
        }
    }
}
