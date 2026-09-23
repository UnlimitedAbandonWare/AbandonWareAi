package com.example.lms.service.postprocess;

import java.util.Objects;

/**
 * Deterministic final-answer cleanup boundary.
 *
 * <p>The immutable request/result contract keeps orchestration metadata out of
 * the visible answer while allowing later pipeline stages to depend on one
 * stable post-processing entry point.</p>
 */
public final class FinalAnswerPostProcessor {

    private final OutputSanitizer outputSanitizer;
    private final S7AnswerContractPolicy s7AnswerContractPolicy = new S7AnswerContractPolicy();
    private final S8AnswerContractPolicy s8AnswerContractPolicy = new S8AnswerContractPolicy();

    public FinalAnswerPostProcessor(OutputSanitizer outputSanitizer) {
        this.outputSanitizer = Objects.requireNonNull(outputSanitizer, "outputSanitizer");
    }

    public Result process(Request request) {
        Objects.requireNonNull(request, "request");
        OutputSanitizer.Result sanitized = outputSanitizer.sanitize(request.candidate());
        OutputSanitizer.Result memorySanitized =
                outputSanitizer.sanitize(request.memoryCandidate());
        S7AnswerContractPolicy.Result answerContract =
                s7AnswerContractPolicy.evaluate(request.query(), sanitized.content());
        S8AnswerContractPolicy.Result s8AnswerContract =
                s8AnswerContractPolicy.evaluate(request.query(), answerContract.content());
        boolean answerContractHold = answerContract.changed() || s8AnswerContract.changed();
        String memoryDenyReason = answerContractHold
                ? "answer_contract_hold"
                : memoryDenyReason(request, sanitized, memorySanitized);
        boolean memorySaveAllowed = "none".equals(memoryDenyReason);
        return new Result(
                s8AnswerContract.content(),
                sanitized.changed() || answerContractHold,
                s8AnswerContract.changed()
                        ? s8AnswerContract.reasonCode()
                        : answerContract.changed() ? answerContract.reasonCode() : sanitized.reasonCode(),
                sanitized.marker(),
                sanitized.removedChars(),
                sanitized.removedHash(),
                memorySaveAllowed,
                memorySaveAllowed ? memorySanitized.content() : null,
                memoryDenyReason);
    }

    public S8Integrity inspectS8Integrity(String query, String candidate) {
        S8AnswerContractPolicy.Integrity integrity = s8AnswerContractPolicy.inspect(query, candidate);
        return new S8Integrity(
                integrity.applicable(),
                integrity.requiredObservationCount(),
                integrity.preservedObservationCount(),
                integrity.requiredInferenceCount(),
                integrity.preservedInferenceCount(),
                integrity.orderedTwoLineContract(),
                integrity.complete());
    }

    private static String memoryDenyReason(
            Request request,
            OutputSanitizer.Result sanitized,
            OutputSanitizer.Result memorySanitized) {
        if (!request.memoryWriteEnabled()) {
            return "write_disabled";
        }
        if (!request.verificationOutcomeKnown()) {
            return "verification_outcome_unknown";
        }
        if (!request.verificationAcceptedForMemory()) {
            return "verification_not_accepted";
        }
        if (request.memoryDeniedByPolicy()) {
            return "memory_policy_denied";
        }
        if (request.creativeApplied()) {
            return "creative_result";
        }
        if (request.fallbackApplied()) {
            return "fallback_result";
        }
        if (request.weakResult()) {
            return "weak_result";
        }
        if ("blank_content".equals(sanitized.reasonCode())
                || "diagnostics_removed_empty".equals(sanitized.reasonCode())
                || "blank_content".equals(memorySanitized.reasonCode())
                || "diagnostics_removed_empty".equals(memorySanitized.reasonCode())) {
            return "sanitizer_fallback";
        }
        return "none";
    }

    public record Request(
            String candidate,
            String memoryCandidate,
            boolean verificationOutcomeKnown,
            boolean verificationAcceptedForMemory,
            boolean memoryWriteEnabled,
            boolean memoryDeniedByPolicy,
            boolean creativeApplied,
            boolean fallbackApplied,
            boolean weakResult,
            String query) {

        public Request(String candidate) {
            this(candidate, candidate, false, false, false, false, false, false, false, null);
        }

        public Request(
                String candidate,
                boolean memoryWriteEnabled,
                boolean creativeApplied,
                boolean fallbackApplied,
                boolean weakResult) {
            this(candidate, candidate, false, false, memoryWriteEnabled, false,
                    creativeApplied, fallbackApplied, weakResult, null);
        }

        public Request(
                String candidate,
                boolean memoryWriteEnabled,
                boolean memoryDeniedByPolicy,
                boolean creativeApplied,
                boolean fallbackApplied,
                boolean weakResult) {
            this(candidate, candidate, false, false, memoryWriteEnabled, memoryDeniedByPolicy,
                    creativeApplied, fallbackApplied, weakResult, null);
        }

        public Request(
                String candidate,
                String memoryCandidate,
                boolean verificationOutcomeKnown,
                boolean verificationAcceptedForMemory,
                boolean memoryWriteEnabled,
                boolean memoryDeniedByPolicy,
                boolean creativeApplied,
                boolean fallbackApplied,
                boolean weakResult) {
            this(candidate, memoryCandidate, verificationOutcomeKnown, verificationAcceptedForMemory,
                    memoryWriteEnabled, memoryDeniedByPolicy, creativeApplied, fallbackApplied, weakResult, null);
        }
    }

    public record Result(
            String content,
            boolean changed,
            String reasonCode,
            String marker,
            long removedChars,
            String removedHash,
            boolean memorySaveAllowed,
            String memoryContent,
            String memoryDenyReason) {
    }

    public record S8Integrity(
            boolean applicable,
            int requiredObservationCount,
            int preservedObservationCount,
            int requiredInferenceCount,
            int preservedInferenceCount,
            boolean orderedTwoLineContract,
            boolean complete) {
    }
}
