package com.example.lms.orchestration.control;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts current runtime facts into the bounded seven-stage PGPC contract. */
@Component
public final class RagControlRuntimeAdapter {

    static final String PRESENTATION_INPUT_TRACE_KEY = "ragControl.presentation.runtimeInput";
    private static final Set<String> VERIFIER_TERMINAL_STAGES = Set.of(
            "not_run", "draft_presence", "entity_validation", "context_presence", "claim_verification",
            "source_credibility", "meta_check", "fact_classification", "evidence_coverage", "correction");
    private static final Set<String> VERIFIER_TERMINAL_REASONS = Set.of(
            "pass", "corrected", "insufficient", "rejected", "unknown");

    public static void capturePresentationInput(RuntimeInput input) {
        TraceStore.putInternal(PRESENTATION_INPUT_TRACE_KEY, input);
    }

    public static RuntimeInput currentPresentationInput() {
        Object value = TraceStore.get(PRESENTATION_INPUT_TRACE_KEY);
        return value instanceof RuntimeInput input ? input : null;
    }

    public List<RagControlFinding> collect(
            RuntimeInput input,
            ModelRuntimeHealthTracker runtimeHealthTracker) {
        if (input == null || !input.ragRequested()) {
            return List.of();
        }
        Object rawTimelineId = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
        String timelineId = rawTimelineId == null ? null : String.valueOf(rawTimelineId);
        List<Map<String, Object>> timeline = runtimeHealthTracker == null
                ? List.of()
                : runtimeHealthTracker.redactedRequestTimeline(timelineId);
        List<Map<String, Object>> attempts = runtimeHealthTracker == null
                ? List.of()
                : runtimeHealthTracker.redactedRequestAttemptLedger(timelineId);
        return collect(input, timelineId, timeline, attempts);
    }

    List<RagControlFinding> collect(
            RuntimeInput input,
            String timelineId,
            List<Map<String, Object>> timeline,
            List<Map<String, Object>> attempts) {
        if (input == null || !input.ragRequested()) {
            return List.of();
        }
        if (!input.finalBoundaryReached()) {
            return unfinishedFindings(input.hardGuardHeld());
        }
        if (input.hasNonModelRelease()
                && (!input.nonModelReleaseEligible()
                || !nonModelMetadataConsistent(input, timelineId, timeline, attempts))) {
            input = input.withHardGuardHeld();
        }
        boolean nonModelRelease = input.hasNonModelRelease();
        LineageProof proof = nonModelRelease
                ? missingLineage(timelineId)
                : lineageProof(timelineId, timeline, attempts);
        ArrayList<RagControlFinding> findings = new ArrayList<>(7);
        findings.add(finding(
                RagControlFinding.Stage.REQUEST,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.DIAGNOSTIC,
                RagActionPlan.Action.CONTINUE,
                "rag_requested",
                proof,
                Map.of("ragRequested", true)));
        findings.add(finding(
                RagControlFinding.Stage.ORCHESTRATION,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.DIAGNOSTIC,
                RagActionPlan.Action.CONTINUE,
                "workflow_final_boundary_reached",
                proof,
                Map.of("finalBoundaryReached", true)));
        findings.add(retrievalFinding(input, proof));
        findings.add(promptEvidenceFinding(input, proof));
        findings.add(nonModelRelease
                ? nonModelFinding(RagControlFinding.Stage.LLM, input, proof)
                : llmFinding(input, proof));
        findings.add(nonModelRelease
                ? nonModelFinding(RagControlFinding.Stage.VERIFICATION, input, proof)
                : verificationFinding(input, proof));
        findings.add(finalFinding(input, proof));
        return List.copyOf(findings);
    }

    private RagControlFinding nonModelFinding(
            RagControlFinding.Stage stage, RuntimeInput input, LineageProof proof) {
        return finding(stage, RagControlFinding.FailureClass.OBSERVABILITY_GAP,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.VERIFICATION, RagActionPlan.Action.DEGRADE,
                input.nonModelRelease().reasonCode(), proof,
                Map.of("nonModelReleaseKind", input.nonModelRelease().name(),
                        "nonModelReleaseBodyHash", input.nonModelBodyHash(),
                        "nonModelReleaseRequestHash", SafeRedactor.hashValue(input.nonModelTimelineId()),
                        "nonModelReleaseBound", true));
    }

    /** Missing observations do not contradict a non-model body; supplied contradictions do. */
    private boolean nonModelMetadataConsistent(
            RuntimeInput input, String timelineId,
            List<Map<String, Object>> timeline, List<Map<String, Object>> attempts) {
        if (timelineId == null || !timelineId.equals(input.nonModelTimelineId())) {
            return false;
        }
        List<Map<String, Object>> safeTimeline = timeline == null ? List.of() : timeline;
        List<Map<String, Object>> safeAttempts = attempts == null ? List.of() : attempts;
        Set<String> requestHashes = new LinkedHashSet<>();
        int finalCount = 0;
        for (Map<String, Object> row : safeTimeline) {
            if (row == null || !isProofHash(row.get("requestHash"))) return false;
            requestHashes.add(proofHash(row.get("requestHash")));
            if (!timelineId.equals(row.get("timelineId"))) return false;
            if (row.containsKey("requestExecutionHash")
                    && !SafeRedactor.hashValue(timelineId).equals(row.get("requestExecutionHash"))) return false;
            boolean finalBoundary = "final_boundary".equals(row.get("phase"));
            if (finalBoundary && ++finalCount > 1) return false;
            if ((finalBoundary || row.containsKey("finalHash"))
                    && !input.nonModelBodyHash().equals(proofHash(row.get("finalHash")))) return false;
        }
        Set<String> ordinalPairs = new LinkedHashSet<>();
        for (Map<String, Object> row : safeAttempts) {
            if (row == null || !isProofHash(row.get("requestHash"))) return false;
            requestHashes.add(proofHash(row.get("requestHash")));
            if (!timelineId.equals(row.get("timelineId"))) return false;
            if (row.containsKey("requestExecutionHash")
                    && !SafeRedactor.hashValue(timelineId).equals(row.get("requestExecutionHash"))) return false;
            int logical = positiveInt(row.get("logicalCallOrdinal"));
            int attempt = positiveInt(row.get("attemptOrdinal"));
            if (logical <= 0 || attempt <= 0
                    || positiveInt(row.get("attemptTotal")) != safeAttempts.size()
                    || nonNegativeInt(row.get("attemptDropped")) != 0
                    || !ordinalPairs.add(logical + ":" + attempt)) return false;
            boolean receipt = Boolean.TRUE.equals(row.get("providerReceiptObserved"));
            boolean provider = Boolean.TRUE.equals(row.get("providerAttemptObserved"));
            boolean wire = Boolean.TRUE.equals(row.get("wireAttemptObserved"));
            if ((receipt || provider || wire
                    || "provider_receive".equals(row.get("evidenceBoundary"))
                    || "controlled_http_server".equals(row.get("providerReceiptSource")))
                    && !(receipt && provider && wire
                    && "controlled_http_server".equals(row.get("providerReceiptSource"))
                    && "provider_receive".equals(row.get("evidenceBoundary"))
                    && Boolean.TRUE.equals(row.get("modelAdapterAttemptObserved"))
                    && Boolean.TRUE.equals(row.get("clientHttpExchangeObserved"))
                    && Boolean.TRUE.equals(row.get("clientHttpResponseObserved"))
                    && Boolean.TRUE.equals(row.get("responseObserved"))
                    && isProofHash(row.get("promptHash"))
                    && isProofHash(row.get("optionsHash"))
                    && isProofHash(row.get("responseHash"))
                    && isExactSha256(row.get("httpRequestBodyHash"))
                    && positiveInt(row.get("httpRequestBodyUtf8ByteCount")) > 0
                    && isExactSha256(row.get("httpResponseBodyHash"))
                    && positiveInt(row.get("httpResponseBodyUtf8ByteCount")) > 0)) return false;
        }
        return requestHashes.size() <= 1;
    }

    private List<RagControlFinding> unfinishedFindings(boolean hardGuardHeld) {
        return java.util.Arrays.stream(RagControlFinding.Stage.values())
                .map(stage -> hardGuardHeld && stage == RagControlFinding.Stage.PROMPT_EVIDENCE
                        ? new RagControlFinding(
                                "existing-release-guard",
                                stage,
                                RagControlFinding.FailureClass.POLICY_DENIED,
                                RagControlFinding.EvidenceStatus.VERIFIED,
                                RagControlFinding.Authority.HARD_GUARD,
                                RagActionPlan.Action.HOLD,
                                "existing_release_guard_hold",
                                null,
                                RagControlFinding.LineageStatus.MISSING,
                                releaseGuardEvidence())
                        : RagControlFinding.observabilityGap(stage))
                .toList();
    }

    private RagControlFinding retrievalFinding(RuntimeInput input, LineageProof proof) {
        if (input.retrievedCount() == 0) {
            return finding(
                    RagControlFinding.Stage.RETRIEVAL,
                    RagControlFinding.FailureClass.ZERO_RESULT,
                    RagControlFinding.EvidenceStatus.OBSERVED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.DEGRADE,
                    "zero_result",
                    proof,
                    Map.of("returnedCount", 0, "afterFilterCount", 0));
        }
        if (input.citableCount() == 0) {
            return finding(
                    RagControlFinding.Stage.RETRIEVAL,
                    RagControlFinding.FailureClass.AFTER_FILTER_STARVATION,
                    RagControlFinding.EvidenceStatus.OBSERVED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.DEGRADE,
                    "after_filter_starvation",
                    proof,
                    Map.of("returnedCount", input.retrievedCount(), "afterFilterCount", 0));
        }
        return finding(
                RagControlFinding.Stage.RETRIEVAL,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.CONTINUE,
                "retrieval_evidence_observed",
                proof,
                Map.of("returnedCount", input.retrievedCount(), "afterFilterCount", input.citableCount()));
    }

    private static Map<String, Object> releaseGuardEvidence() {
        Object reason = TraceStore.get("finalAnswer.releaseReason");
        if (reason instanceof String value && java.util.Set.of(
                "verification_insufficient", "verification_rejected",
                "verification_state_inconsistent", "verification_outcome_unknown").contains(value)) {
            return Map.of("releaseGuardHeld", true, "releaseReason", value);
        }
        return Map.of("releaseGuardHeld", true);
    }

    private RagControlFinding promptEvidenceFinding(RuntimeInput input, LineageProof proof) {
        if (input.hardGuardHeld()) {
            return finding(
                    RagControlFinding.Stage.PROMPT_EVIDENCE,
                    RagControlFinding.FailureClass.POLICY_DENIED,
                    RagControlFinding.EvidenceStatus.VERIFIED,
                    RagControlFinding.Authority.HARD_GUARD,
                    RagActionPlan.Action.HOLD,
                    "existing_release_guard_hold",
                    proof,
                    releaseGuardEvidence());
        }
        if (input.citableCount() == 0) {
            return finding(
                    RagControlFinding.Stage.PROMPT_EVIDENCE,
                    RagControlFinding.FailureClass.CITATION_MISS,
                    RagControlFinding.EvidenceStatus.OBSERVED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.ISOLATE_EVIDENCE,
                    "citation_miss",
                    proof,
                    Map.of("promotedCount", 0));
        }
        return finding(
                RagControlFinding.Stage.PROMPT_EVIDENCE,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.CONTINUE,
                "citable_evidence_observed",
                proof,
                Map.of("promotedCount", input.citableCount()));
    }

    private RagControlFinding llmFinding(RuntimeInput input, LineageProof proof) {
        if (input.answerBlank()) {
            return finding(
                    RagControlFinding.Stage.LLM,
                    RagControlFinding.FailureClass.MODEL_BLANK,
                    RagControlFinding.EvidenceStatus.VERIFIED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.HOLD,
                    "model_blank",
                    proof,
                    Map.of("responseObserved", false));
        }
        if (!proof.complete()) {
            // 검증 필요 응답은 lineage 누락을 보류 사유로 유지하고(UAW 게이트의
            // shadow 판정도 이 HOLD finding을 근거로 한다), 검증 불필요 응답은
            // 비종결 관측으로만 남긴다.
            return finding(
                    RagControlFinding.Stage.LLM,
                    RagControlFinding.FailureClass.OBSERVABILITY_GAP,
                    RagControlFinding.EvidenceStatus.EVIDENCE_NEEDED,
                    RagControlFinding.Authority.VERIFICATION,
                    input.verificationRequired()
                            ? RagActionPlan.Action.HOLD
                            : RagActionPlan.Action.DEGRADE,
                    "runtime_lineage_missing",
                    proof,
                    Map.of("responseObserved", true, "lineageComplete", false));
        }
        return finding(
                RagControlFinding.Stage.LLM,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.CONTINUE,
                "runtime_lineage_verified",
                proof,
                Map.of("responseObserved", true, "lineageComplete", true));
    }

    private RagControlFinding verificationFinding(RuntimeInput input, LineageProof proof) {
        if (!input.verificationRequired()) {
            return finding(
                    RagControlFinding.Stage.VERIFICATION,
                    RagControlFinding.FailureClass.NONE,
                    RagControlFinding.EvidenceStatus.OBSERVED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.CONTINUE,
                    "verification_not_required",
                    proof,
                    Map.of("verificationRequired", false));
        }
        if (input.verificationUnknownReleaseAllowed() && !input.verificationAccepted()
                && !input.hardGuardHeld() && !input.answerBlank() && proof.complete()) {
            return finding(
                    RagControlFinding.Stage.VERIFICATION,
                    RagControlFinding.FailureClass.OBSERVABILITY_GAP,
                    RagControlFinding.EvidenceStatus.OBSERVED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.DEGRADE,
                    "verification_unknown_release",
                    proof,
                    Map.of("verificationRequired", true, "verificationKnown", false));
        }
        if (!input.verificationKnown()) {
            return finding(
                    RagControlFinding.Stage.VERIFICATION,
                    RagControlFinding.FailureClass.OBSERVABILITY_GAP,
                    RagControlFinding.EvidenceStatus.EVIDENCE_NEEDED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.HOLD,
                    "verification_outcome_missing",
                    proof,
                    Map.of("verificationKnown", false));
        }
        if (!input.verificationAccepted()) {
            return finding(
                    RagControlFinding.Stage.VERIFICATION,
                    RagControlFinding.FailureClass.CITATION_MISS,
                    RagControlFinding.EvidenceStatus.VERIFIED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.HOLD,
                    "verification_rejected",
                    proof,
                    Map.of("verificationKnown", true, "verificationAccepted", false));
        }
        return finding(
                RagControlFinding.Stage.VERIFICATION,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.CONTINUE,
                "verification_accepted",
                proof,
                Map.of("verificationKnown", true, "verificationAccepted", true));
    }

    private RagControlFinding finalFinding(RuntimeInput input, LineageProof proof) {
        if (input.answerBlank()) {
            return finding(
                    RagControlFinding.Stage.FINAL,
                    RagControlFinding.FailureClass.SILENT_FAILURE,
                    RagControlFinding.EvidenceStatus.VERIFIED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.HOLD,
                    "silent_failure",
                    proof,
                    Map.of("selectedCount", 0));
        }
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("selectedCount", 1);
        if (proof.complete()) {
            evidence.put("evidenceBoundary", EvidenceBoundary.PRESENTATION_FINAL);
            evidence.put("finalHash", proof.finalHash());
            evidence.put("logicalCallOrdinal", proof.logicalCallOrdinal());
            evidence.put("attemptOrdinal", proof.attemptOrdinal());
        }
        return finding(
                RagControlFinding.Stage.FINAL,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.CONTINUE,
                "final_answer_observed",
                proof,
                Map.copyOf(evidence));
    }

    private static Map<String, Object> verificationEvidence(Map<String, Object> evidence) {
        Map<String, Object> result = new LinkedHashMap<>(evidence);
        String terminalStage = diagnosticLabel(TraceStore.get("factVerifier.terminalStage"), VERIFIER_TERMINAL_STAGES);
        String terminalReason = diagnosticLabel(TraceStore.get("factVerifier.terminalReason"), VERIFIER_TERMINAL_REASONS);
        if (terminalStage != null) result.put("terminalStage", terminalStage);
        if (terminalReason != null) result.put("terminalReason", terminalReason);
        Object judgeUnavailable = TraceStore.get("factVerifier.judgeUnavailable");
        if (judgeUnavailable instanceof Boolean) result.put("judgeUnavailable", judgeUnavailable);
        return Map.copyOf(result);
    }

    private static String diagnosticLabel(Object value, Set<String> allowed) {
        return value instanceof String label && allowed.contains(label) ? SafeRedactor.traceLabel(label) : null;
    }

    private RagControlFinding finding(
            RagControlFinding.Stage stage,
            RagControlFinding.FailureClass failureClass,
            RagControlFinding.EvidenceStatus evidenceStatus,
            RagControlFinding.Authority authority,
            RagActionPlan.Action action,
            String reasonCode,
            LineageProof proof,
            Map<String, Object> evidence) {
        return new RagControlFinding(
                "chat-workflow-runtime",
                stage,
                failureClass,
                evidenceStatus,
                authority,
                action,
                reasonCode,
                proof.lineageKey(),
                proof.complete()
                        ? RagControlFinding.LineageStatus.COMPLETE
                        : RagControlFinding.LineageStatus.MISSING,
                stage == RagControlFinding.Stage.VERIFICATION ? verificationEvidence(evidence) : evidence);
    }

    private LineageProof lineageProof(
            String timelineId,
            List<Map<String, Object>> timeline,
            List<Map<String, Object>> attempts) {
        Set<String> timelineRequestHashes = proofHashes(timeline, "requestHash");
        String expectedRequestHash = timelineRequestHashes.size() == 1
                ? timelineRequestHashes.iterator().next()
                : null;
        if (expectedRequestHash == null || timeline == null || timeline.isEmpty()) {
            return missingLineage(timelineId);
        }

        LinkedHashSet<String> phases = new LinkedHashSet<>();
        String finalHash = null;
        int finalBoundaryCount = 0;
        for (Map<String, Object> row : timeline) {
            if (row == null
                    || !expectedRequestHash.equalsIgnoreCase(String.valueOf(row.get("requestHash")))) {
                return missingLineage(timelineId);
            }
            String phase = String.valueOf(row.get("phase")).toLowerCase(Locale.ROOT);
            phases.add(phase);
            if ("final_boundary".equals(phase)) {
                finalBoundaryCount++;
                Object candidate = row.get("finalHash");
                if (!isProofHash(candidate)) {
                    return missingLineage(timelineId);
                }
                finalHash = proofHash(candidate);
            }
        }
        if (!phases.containsAll(Set.of("dispatch", "pending", "final_boundary"))
                || finalBoundaryCount != 1
                || attempts == null
                || attempts.isEmpty()) {
            return missingLineage(timelineId);
        }

        int expectedAttemptTotal = attempts.size();
        LinkedHashSet<String> ordinalPairs = new LinkedHashSet<>();
        ArrayList<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> row : attempts) {
            if (row == null
                    || !expectedRequestHash.equalsIgnoreCase(String.valueOf(row.get("requestHash")))) {
                return missingLineage(timelineId);
            }
            int logicalCallOrdinal = positiveInt(row.get("logicalCallOrdinal"));
            int attemptOrdinal = positiveInt(row.get("attemptOrdinal"));
            int attemptTotal = positiveInt(row.get("attemptTotal"));
            int attemptDropped = nonNegativeInt(row.get("attemptDropped"));
            if (logicalCallOrdinal <= 0
                    || attemptOrdinal <= 0
                    || attemptTotal != expectedAttemptTotal
                    || attemptDropped != 0
                    || !ordinalPairs.add(logicalCallOrdinal + ":" + attemptOrdinal)) {
                return missingLineage(timelineId);
            }
            boolean receiptObserved = Boolean.TRUE.equals(row.get("providerReceiptObserved"));
            boolean providerObserved = Boolean.TRUE.equals(row.get("providerAttemptObserved"));
            boolean wireObserved = Boolean.TRUE.equals(row.get("wireAttemptObserved"));
            if ((receiptObserved || providerObserved || wireObserved)
                    && !(receiptObserved && providerObserved && wireObserved
                    && "controlled_http_server".equals(row.get("providerReceiptSource"))
                    && "provider_receive".equals(row.get("evidenceBoundary")))) {
                return missingLineage(timelineId);
            }
            if (isSelectedReceiptBackedSuccess(row, finalHash)) {
                selected.add(row);
            }
        }
        if (selected.size() != 1) {
            return missingLineage(timelineId);
        }

        Map<String, Object> chosen = selected.get(0);
        int logicalCallOrdinal = positiveInt(chosen.get("logicalCallOrdinal"));
        int attemptOrdinal = positiveInt(chosen.get("attemptOrdinal"));
        String tuple = String.join("|",
                expectedRequestHash,
                String.valueOf(logicalCallOrdinal),
                String.valueOf(attemptOrdinal),
                "provider_receive",
                proofHash(chosen.get("promptHash")),
                proofHash(chosen.get("optionsHash")),
                String.valueOf(chosen.get("httpRequestBodyHash")).toLowerCase(Locale.ROOT),
                String.valueOf(chosen.get("httpResponseBodyHash")).toLowerCase(Locale.ROOT),
                proofHash(chosen.get("responseHash")),
                "presentation_final",
                finalHash);
        String lineageKey = SafeRedactor.hashValue(tuple);
        return new LineageProof(
                lineageKey != null,
                lineageKey,
                logicalCallOrdinal,
                attemptOrdinal,
                "presentation_final",
                finalHash);
    }

    private boolean isSelectedReceiptBackedSuccess(Map<String, Object> row, String finalHash) {
        return "success".equals(row.get("outcome"))
                && "success".equals(row.get("terminalClass"))
                && "provider_receive".equals(row.get("evidenceBoundary"))
                && "controlled_http_server".equals(row.get("providerReceiptSource"))
                && Boolean.TRUE.equals(row.get("modelAdapterAttemptObserved"))
                && Boolean.TRUE.equals(row.get("clientHttpExchangeObserved"))
                && Boolean.TRUE.equals(row.get("clientHttpResponseObserved"))
                && Boolean.TRUE.equals(row.get("providerReceiptObserved"))
                && Boolean.TRUE.equals(row.get("providerAttemptObserved"))
                && Boolean.TRUE.equals(row.get("wireAttemptObserved"))
                && Boolean.TRUE.equals(row.get("responseObserved"))
                && isProofHash(row.get("promptHash"))
                && isProofHash(row.get("optionsHash"))
                && isProofHash(row.get("responseHash"))
                && proofHash(row.get("responseHash")).equals(finalHash)
                && isExactSha256(row.get("httpRequestBodyHash"))
                && positiveInt(row.get("httpRequestBodyUtf8ByteCount")) > 0
                && isExactSha256(row.get("httpResponseBodyHash"))
                && positiveInt(row.get("httpResponseBodyUtf8ByteCount")) > 0;
    }

    private LineageProof missingLineage(String timelineId) {
        return new LineageProof(
                false,
                SafeRedactor.hashValue(timelineId),
                0,
                0,
                "evidence_needed",
                "hash:unknown");
    }

    private boolean isExactSha256(Object value) {
        return value != null && String.valueOf(value).matches("(?i)sha256:[a-f0-9]{64}");
    }

    private int positiveInt(Object value) {
        int parsed = nonNegativeInt(value);
        return parsed > 0 ? parsed : 0;
    }

    private int nonNegativeInt(Object value) {
        try {
            int parsed = value instanceof Number number
                    ? new java.math.BigDecimal(number.toString()).intValueExact()
                    : Integer.parseInt(String.valueOf(value));
            return parsed >= 0 ? parsed : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private Set<String> proofHashes(List<Map<String, Object>> rows, String key) {
        Set<String> hashes = new LinkedHashSet<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Object value = row == null ? null : row.get(key);
                if (isProofHash(value)) {
                    hashes.add(proofHash(value));
                }
            }
        }
        return hashes;
    }

    private String proofHash(Object value) {
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private boolean isProofHash(Object value) {
        return value != null && String.valueOf(value).matches("(?i)hash:[a-f0-9]{12,64}");
    }

    public enum NonModelRelease {
        NONE(""),
        SUPPORTED_EXCERPT("verification_unavailable_excerpt"),
        VERIFICATION_GUIDANCE("verification_unavailable_guidance"),
        INSUFFICIENT_EXCERPT("verification_insufficient_excerpt"),
        INSUFFICIENT_GUIDANCE("verification_insufficient_guidance");

        private final String reasonCode;
        NonModelRelease(String reasonCode) { this.reasonCode = reasonCode; }
        public String reasonCode() { return reasonCode; }
    }

    private static String boundBodyHash(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "hash:" + java.util.HexFormat.of().formatHex(digest, 0, 6);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    public record RuntimeInput(
            boolean ragRequested,
            int retrievedCount,
            int citableCount,
            boolean answerBlank,
            boolean verificationKnown,
            boolean verificationAccepted,
            boolean hardGuardHeld,
            boolean finalBoundaryReached,
            boolean verificationRequired,
            NonModelRelease nonModelRelease,
            String nonModelBodyHash,
            String nonModelTimelineId,
            boolean verificationUnknownReleaseAllowed) {

        public RuntimeInput(
                boolean ragRequested, int retrievedCount, int citableCount,
                boolean answerBlank, boolean verificationKnown, boolean verificationAccepted,
                boolean hardGuardHeld, boolean finalBoundaryReached, boolean verificationRequired,
                NonModelRelease nonModelRelease, String nonModelBodyHash, String nonModelTimelineId) {
            this(ragRequested, retrievedCount, citableCount, answerBlank, verificationKnown,
                    verificationAccepted, hardGuardHeld, finalBoundaryReached, verificationRequired,
                    nonModelRelease, nonModelBodyHash, nonModelTimelineId, false);
        }

        public RuntimeInput(
                boolean ragRequested, int retrievedCount, int citableCount,
                boolean answerBlank, boolean verificationKnown, boolean verificationAccepted,
                boolean hardGuardHeld, boolean finalBoundaryReached, boolean verificationRequired) {
            this(ragRequested, retrievedCount, citableCount, answerBlank, verificationKnown,
                    verificationAccepted, hardGuardHeld, finalBoundaryReached, verificationRequired,
                    NonModelRelease.NONE, null, null);
        }

        public RuntimeInput withNonModelRelease(String reason, String finalBody) {
            NonModelRelease kind = "verification_unavailable_excerpt".equals(reason)
                    ? NonModelRelease.SUPPORTED_EXCERPT
                    : "verification_unavailable_guidance".equals(reason)
                    ? NonModelRelease.VERIFICATION_GUIDANCE
                    : "verification_insufficient_excerpt".equals(reason)
                    ? NonModelRelease.INSUFFICIENT_EXCERPT
                    : "verification_insufficient_guidance".equals(reason)
                    ? NonModelRelease.INSUFFICIENT_GUIDANCE : NonModelRelease.NONE;
            if (kind == NonModelRelease.NONE) {
                return new RuntimeInput(ragRequested, retrievedCount, citableCount, answerBlank,
                        verificationKnown, verificationAccepted, hardGuardHeld,
                        finalBoundaryReached, verificationRequired);
            }
            Object timeline = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
            return new RuntimeInput(ragRequested, retrievedCount, citableCount, answerBlank,
                    verificationKnown, verificationAccepted, hardGuardHeld, finalBoundaryReached,
                    verificationRequired, kind, boundBodyHash(finalBody),
                    timeline == null ? null : String.valueOf(timeline));
        }

        public RuntimeInput withVerificationUnknownRelease(boolean allowed) {
            return new RuntimeInput(ragRequested, retrievedCount, citableCount, answerBlank,
                    verificationKnown, verificationAccepted, hardGuardHeld, finalBoundaryReached,
                    verificationRequired, nonModelRelease, nonModelBodyHash, nonModelTimelineId,
                    allowed && verificationRequired && !verificationAccepted && !hardGuardHeld
                            && finalBoundaryReached && !answerBlank && nonModelRelease == NonModelRelease.NONE);
        }

        boolean hasNonModelRelease() { return nonModelRelease != NonModelRelease.NONE; }

        boolean nonModelReleaseEligible() {
            boolean insufficient = nonModelRelease == NonModelRelease.INSUFFICIENT_EXCERPT
                    || nonModelRelease == NonModelRelease.INSUFFICIENT_GUIDANCE;
            boolean verdictEligible = insufficient
                    ? verificationKnown && "insufficient".equals(TraceStore.get("finalAnswer.verificationStatus"))
                    : ragRequested && !verificationKnown;
            return verificationRequired && verdictEligible && !verificationAccepted
                    && !hardGuardHeld && finalBoundaryReached && !answerBlank
                    && nonModelBodyHash != null && nonModelTimelineId != null
                    && !nonModelTimelineId.isBlank();
        }

        boolean matchesNonModelBody(String body) {
            return nonModelBodyHash != null && nonModelBodyHash.equals(boundBodyHash(body));
        }

        RuntimeInput withHardGuardHeld() {
            return new RuntimeInput(ragRequested, retrievedCount, citableCount, answerBlank,
                    verificationKnown, verificationAccepted, true, finalBoundaryReached,
                    verificationRequired);
        }

        public RuntimeInput(
                boolean ragRequested,
                int retrievedCount,
                int citableCount,
                boolean answerBlank,
                boolean verificationKnown,
                boolean verificationAccepted,
                boolean hardGuardHeld) {
            this(
                    ragRequested,
                    retrievedCount,
                    citableCount,
                    answerBlank,
                    verificationKnown,
                    verificationAccepted,
                    hardGuardHeld,
                    true);
        }

        public RuntimeInput(
                boolean ragRequested,
                int retrievedCount,
                int citableCount,
                boolean answerBlank,
                boolean verificationKnown,
                boolean verificationAccepted,
                boolean hardGuardHeld,
                boolean finalBoundaryReached) {
            this(
                    ragRequested,
                    retrievedCount,
                    citableCount,
                    answerBlank,
                    verificationKnown,
                    verificationAccepted,
                    hardGuardHeld,
                    finalBoundaryReached,
                    true);
        }

        public RuntimeInput {
            nonModelRelease = nonModelRelease == null ? NonModelRelease.NONE : nonModelRelease;
            if (nonModelRelease == NonModelRelease.NONE) {
                nonModelBodyHash = null;
                nonModelTimelineId = null;
            }
            retrievedCount = Math.max(0, retrievedCount);
            citableCount = Math.max(0, citableCount);
        }

        public static RuntimeInput evidenceNeeded(boolean ragRequested) {
            return new RuntimeInput(
                    ragRequested,
                    0,
                    0,
                    true,
                    false,
                    false,
                    false,
                    false,
                    true);
        }

        /** 입력 자체가 수집되지 않은 제어-계층 갭 — 근거 부족 상태로 위장하지 않는다. */
        public static RuntimeInput controlGap() {
            return new RuntimeInput(
                    true,
                    0,
                    0,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false);
        }
    }

    private record LineageProof(
            boolean complete,
            String lineageKey,
            int logicalCallOrdinal,
            int attemptOrdinal,
            String evidenceBoundary,
            String finalHash) {
    }

    private enum EvidenceBoundary {
        PRESENTATION_FINAL
    }
}
