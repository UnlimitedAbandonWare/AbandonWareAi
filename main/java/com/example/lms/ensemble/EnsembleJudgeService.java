package com.example.lms.ensemble;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnsembleJudgeService {

    public enum DebugPatchDecision {
        APPLY,
        HOLD,
        REJECT
    }

    public enum DebugPatchConfidence {
        LOW,
        MEDIUM,
        HIGH
    }

    public record DebugPatchVote(
            DebugPatchDecision decision,
            DebugPatchConfidence confidence,
            int decisiveEvidenceCount,
            String reasonCode,
            int modelCallCount) {

        public DebugPatchVote {
            decision = decision == null ? DebugPatchDecision.HOLD : decision;
            confidence = confidence == null ? DebugPatchConfidence.LOW : confidence;
            decisiveEvidenceCount = Math.max(0, decisiveEvidenceCount);
            reasonCode = safeDebugReasonCode(reasonCode);
            modelCallCount = Math.max(0, modelCallCount);
        }

        public static DebugPatchVote hold(String reasonCode) {
            return hold(reasonCode, 0);
        }

        public static DebugPatchVote hold(String reasonCode, int modelCallCount) {
            return new DebugPatchVote(
                    DebugPatchDecision.HOLD,
                    DebugPatchConfidence.LOW,
                    0,
                    reasonCode,
                    modelCallCount);
        }
    }

    private static final double MAX_JUDGE_TEMPERATURE = 0.30d;
    private static final double DEFAULT_JUDGE_TEMPERATURE = 0.20d;
    private static final double MAX_JUDGE_TOP_P = 0.50d;
    private static final double DEFAULT_JUDGE_TOP_P = 0.40d;
    private static final int MIN_JUDGE_MAX_TOKENS = 256;
    private static final int MAX_JUDGE_MAX_TOKENS = 2_000;
    private static final int DEFAULT_JUDGE_MAX_TOKENS = 1_200;
    private static final String SAFE_EVIDENCE_HOLD = """
            DECISION: HOLD
            CONFIDENCE: LOW
            COOPERATIVE: POSSIBLE - UNCONFIRMED
            BASE_RATE: POSSIBLE - UNCONFIRMED
            OPPORTUNISTIC: POSSIBLE - UNCONFIRMED
            POSSIBILITY RANGES: COOPERATIVE 0-100%; BASE_RATE 0-100%; OPPORTUNISTIC 0-100%
            DECISIVE EVIDENCE IDS: NONE
            EVIDENCE ASSESSMENT: CURRENT SOURCES DO NOT DISTINGUISH INTENT
            UNRESOLVED CONFLICTS: EVIDENCE CONFLICTS UNRESOLVED
            DISCRIMINATING EVIDENCE: ADDITIONAL INDEPENDENT RECORDS REQUIRED
            PROCEDURAL OPTIONS: PRESERVE_RECORDS; DOCUMENT_TIMELINE; REQUEST_WRITTEN_CLARIFICATION; FOLLOW_ESTABLISHED_PROCESS
            """.strip();
    private static final String EVIDENCE_JUDGE_CONTRACT = """
            You are a low-variance evidence judge. The COOPERATIVE, BASE_RATE, and OPPORTUNISTIC candidates
            are hypothesis-generation perspectives, not independent evidence and not facts.
            Compare them against source authority, provenance, time, independence, hard constraints, and base rates.
            Do not average the candidates or state hidden intent as fact. Preserve the original claim, negations,
            speaker attribution, and unresolved conflicts. If evidence cannot distinguish intent, say UNDERDETERMINED
            or HOLD and give calibrated possibility ranges rather than a categorical accusation or exoneration.
            A *_SUPPORTED decision requires MEDIUM or HIGH confidence, direct and independent evidence that
            distinguishes that stance, a selected possibility interval above every alternative interval, and
            UNRESOLVED CONFLICTS: NONE. Otherwise use UNDERDETERMINED or HOLD with LOW or MEDIUM confidence.
            For UNDERDETERMINED or HOLD, every stance line must include only the exact value POSSIBLE - UNCONFIRMED and
            EVIDENCE ASSESSMENT must be exactly CURRENT SOURCES DO NOT DISTINGUISH INTENT. Do not add narrative
            to those verdict-bearing fields. UNRESOLVED CONFLICTS must be exactly EVIDENCE CONFLICTS UNRESOLVED,
            DISCRIMINATING EVIDENCE must be exactly ADDITIONAL INDEPENDENT RECORDS REQUIRED, and no stance range
            may be strictly above both alternatives.
            Only the selected stance may use SUPPORTS, DIRECT=YES, INDEPENDENT=YES, or BASIS in a *_SUPPORTED result;
            every alternative stance must state its evidence limits without an affirmative support claim.
            Use only evidence IDs present in the EVIDENCE MATRIX below. If supportEligible=false, or any time,
            directness, relation, or independence field is UNKNOWN/UNVERIFIED, *_SUPPORTED is forbidden.
            UNDERDETERMINED and HOLD must return DECISIVE EVIDENCE IDS: NONE.
            The complete output is an ASCII-only machine contract; translate source meaning without copying
            non-ASCII narrative into verdict-bearing fields.
            For *_SUPPORTED, format the selected stance value as POSSIBLE - SUPPORTS=<same stance>;
            DIRECT=YES; INDEPENDENT=YES; BASIS=<same stance>|<affirmative evidence basis>.
            Identify the additional discriminating evidence that would change the decision. Finish with low-risk
            procedural options centered on evidence preservation, written clarification, and established process.
            Return exactly one non-empty line per field, in this order, with no other text:
            DECISION: <one of UNDERDETERMINED, HOLD, COOPERATIVE_SUPPORTED, BASE_RATE_SUPPORTED, OPPORTUNISTIC_SUPPORTED>
            CONFIDENCE: <one of LOW, MEDIUM, HIGH>
            COOPERATIVE: <POSSIBLE - UNCONFIRMED for unresolved; otherwise POSSIBLE - evidence fit and limits>
            BASE_RATE: <POSSIBLE - UNCONFIRMED for unresolved; otherwise POSSIBLE - evidence fit and limits>
            OPPORTUNISTIC: <POSSIBLE - UNCONFIRMED for unresolved; otherwise POSSIBLE - evidence fit and limits>
            POSSIBILITY RANGES: COOPERATIVE <0-100>-<0-100>%; BASE_RATE <0-100>-<0-100>%; OPPORTUNISTIC <0-100>-<0-100>%
            DECISIVE EVIDENCE IDS: <NONE or at least two comma-separated ev1 IDs from the matrix>
            EVIDENCE ASSESSMENT: <for unresolved exactly CURRENT SOURCES DO NOT DISTINGUISH INTENT; for supported use
            SUPPORTS=<selected stance>; DIRECT=YES; INDEPENDENT=YES; DISTINGUISHES=YES;
            BASIS=<selected stance>|<affirmative evidence basis>>
            UNRESOLVED CONFLICTS: <for unresolved exactly EVIDENCE CONFLICTS UNRESOLVED; for supported exactly NONE>
            DISCRIMINATING EVIDENCE: <for unresolved exactly ADDITIONAL INDEPENDENT RECORDS REQUIRED;
            for supported state the additional evidence that could falsify the selected stance>
            PROCEDURAL OPTIONS: <semicolon-separated allowed actions only; must include PRESERVE_RECORDS>
            Allowed actions are PRESERVE_RECORDS, DOCUMENT_TIMELINE, REQUEST_WRITTEN_CLARIFICATION,
            FOLLOW_ESTABLISHED_PROCESS, and SEEK_QUALIFIED_COUNSEL.
            """;
    private static final String TRIADIC_DEBUG_JUDGE_CONTRACT = """
            SUPER_TITLE: Evidence-Grounded Triadic Debug Adjudicator
            You are the neutral HOLD role. SUPPORT and FALSIFY are bounded diagnostic hypotheses,
            not facts and not independent evidence. Inspect only the supplied immutable evidence matrix
            and exactly three candidate dossiers: support, support_alternative, and falsify.
            SUPPORT and SUPPORT_ALTERNATIVE are independent hypotheses, not two votes.
            Never decide by candidate count; compare grounding, contradictions, and decisive evidence IDs.
            Never invent an evidence ID, score, patch, command, or source.
            APPLY means the SUPPORT dossier is sufficiently grounded for a human-reviewed minimal patch.
            REJECT means the FALSIFY dossier is sufficiently grounded against that patch candidate.
            HOLD means the evidence is incomplete, conflicted, close, unsafe, or underdetermined.
            Your vote is advisory input only; deterministic code owns the final decision.
            Return exactly five non-empty ASCII lines in this order and no other text:
            ROLE: HOLD
            DECISION: <one of APPLY, HOLD, REJECT>
            CONFIDENCE: <one of LOW, MEDIUM, HIGH>
            DECISIVE EVIDENCE IDS: <NONE or comma-separated ev1 IDs from the matrix>
            REASON CODE: <one of support_grounded, falsify_grounded, evidence_conflicted,
            evidence_incomplete, underdetermined, unsafe_patch, neutral_hold>
            HOLD must use DECISIVE EVIDENCE IDS: NONE.
            APPLY or REJECT must cite both the P1 patch-candidate descriptor and at least one D* failure
            fingerprint ID owned by the matrix. The P1 descriptor binds the vote to the submitted patch but is
            not independent evidence that the patch is correct.
            """;
    private static final Pattern DEBUG_REASON_CODE = Pattern.compile("^[a-z0-9_]{1,64}$");
    private static final Pattern DEBUG_EVIDENCE_ID = Pattern.compile("^ev1:[0-9a-f]{12}$");
    private static final Set<String> DEBUG_REASON_CODES = Set.of(
            "support_grounded",
            "falsify_grounded",
            "evidence_conflicted",
            "evidence_incomplete",
            "underdetermined",
            "unsafe_patch",
            "neutral_hold");

    private final DynamicChatModelFactory modelFactory;
    private final PromptBuilder promptBuilder;

    @Value("${ensemble.sampling.judge-model:${llm.judge.model:${llm.high.model:${llm.chat-model:gemma4:26b}}}}")
    private String judgeModel = "gemma4:26b";

    @Value("${ensemble.judge.temperature:0.3}")
    private double judgeTemperature = 0.3d;

    @Value("${ensemble.judge.top-p:0.5}")
    private double judgeTopP = 0.5d;

    @Value("${ensemble.judge.max-tokens:2000}")
    private int judgeMaxTokens = 2000;

    @Value("${ensemble.judge.timeout-seconds:${llm.judge.timeout-seconds:6}}")
    private int judgeTimeoutSeconds = 6;

    @Value("${ensemble.judge.require-support-eligible:false}")
    private boolean requireSupportEligible;

    public String judge(List<SampledCandidate> candidates, PromptContext ctx, String rid) {
        beginJudgeAttempt("ensemble.judge.");
        if (candidates == null || candidates.isEmpty()) {
            TraceStore.put("ensemble.judge.skipped", "no_candidates");
            return null;
        }
        if (!EnsembleEvidenceContract.hasCompleteHypothesisSet(candidates)) {
            TraceStore.put("ensemble.judge.skipped", "incomplete_hypothesis_set");
            return null;
        }
        if (!EnsembleEvidenceContract.hasValidHypothesisDossiers(candidates)) {
            TraceStore.put("ensemble.judge.skipped", "invalid_hypothesis_dossier");
            return null;
        }
        if (!EnsembleEvidenceContract.hasDistinctHypothesisDossiers(candidates)) {
            TraceStore.put("ensemble.judge.skipped", "duplicate_hypothesis_dossier");
            return null;
        }

        try {
            EnsembleEvidenceMatrix evidenceMatrix = EnsembleEvidenceMatrix.from(
                    ctx == null ? List.of() : ctx.evidence());
            traceEvidenceMatrix(evidenceMatrix);
            boolean evidenceWasSupplied = ctx != null && ctx.evidence() != null && !ctx.evidence().isEmpty();
            if (evidenceWasSupplied && !evidenceMatrix.supportEligible()) {
                TraceStore.put("ensemble.judge.supportPromotionDisabled", "matrix_not_support_eligible");
                if (requireSupportEligible) {
                    TraceStore.put("ensemble.judge.skipped", "matrix_not_support_eligible");
                    return failClosedToEvidenceHold();
                }
            }
            PromptContext judgeContext = (ctx == null ? PromptContext.builder() : ctx.toBuilder())
                    .ensembleCandidates(candidates)
                    .ensembleJudgeMode(true)
                    .build();
            String judgePrompt = buildJudgePrompt(judgeContext, evidenceMatrix);
            double effectiveJudgeTemperature = boundedJudgeTemperature(judgeTemperature);
            double effectiveJudgeTopP = boundedJudgeTopP(judgeTopP);
            int effectiveJudgeMaxTokens = boundedJudgeMaxTokens(judgeMaxTokens);
            TraceStore.put("ensemble.judge.temperature", effectiveJudgeTemperature);
            TraceStore.put("ensemble.judge.topP", effectiveJudgeTopP);
            TraceStore.put("ensemble.judge.maxTokens", effectiveJudgeMaxTokens);
            int effectiveJudgeTimeoutSeconds = Math.max(1, judgeTimeoutSeconds);
            TraceStore.put("ensemble.judge.timeoutSeconds", effectiveJudgeTimeoutSeconds);
            var model = modelFactory.lcWithTimeout(
                    judgeModel, effectiveJudgeTemperature, effectiveJudgeTopP, null, null,
                    effectiveJudgeMaxTokens, effectiveJudgeTimeoutSeconds);
            ChatResponse response = model == null ? null : chatWithLatency(
                    model, List.of(UserMessage.from(judgePrompt)), "ensemble.judge.");
            traceTokenUsage("ensemble.judge.", response);
            var aiMessage = response == null ? null : response.aiMessage();
            String result = aiMessage == null ? null : aiMessage.text();

            TraceStore.put("ensemble.judge.modelHash", SafeRedactor.hashValue(judgeModel));
            TraceStore.put("ensemble.judge.modelLength", judgeModel == null ? 0 : judgeModel.length());
            TraceStore.put("ensemble.judge.candidateCount", candidates.size());
            TraceStore.put("ensemble.judge.resultLen", result == null ? 0 : result.length());
            if (response == null || aiMessage == null) {
                TraceStore.put("ensemble.judge.fail", "malformed_model_response");
                return failClosedToEvidenceHold();
            }
            if (result == null || result.isBlank()) {
                TraceStore.put("ensemble.judge.empty", "blank_judge_output");
                return failClosedToEvidenceHold();
            }
            if (!EnsembleEvidenceContract.isStructurallyValidJudgeResult(result)) {
                TraceStore.put("ensemble.judge.invalid", "judge_contract_mismatch");
                return failClosedToEvidenceHold();
            }
            if (!EnsembleEvidenceContract.isValidJudgeResult(result, evidenceMatrix)) {
                TraceStore.put("ensemble.judge.invalid", "unverified_evidence_matrix");
                return failClosedToEvidenceHold();
            }
            return result;
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            TraceStore.put("ensemble.judge.fail", "ensemble_judge_failed");
            log.warn("[ensemble][judge] fail rid={} type={}",
                    SafeRedactor.hashValue(rid),
                    "ensemble_judge_failed");
            return failClosedToEvidenceHold();
        }
    }

    /**
     * Runs the neutral HOLD role for the demand-driven debug adjudication path.
     * The returned vote cannot apply a patch by itself; callers must enforce the
     * deterministic grounding and safety gates.
     */
    public DebugPatchVote judgeDebugPatch(
            List<SampledCandidate> candidates,
            PromptContext ctx,
            String rid) {
        beginJudgeAttempt("debug.triadic.judge.");
        if (!hasCompleteDistinctThreeRoleSet(candidates)) {
            TraceStore.put("debug.triadic.judge.skipped", "incomplete_role_set");
            return DebugPatchVote.hold("incomplete_role_set");
        }

        EnsembleEvidenceMatrix evidenceMatrix = EnsembleEvidenceMatrix.from(
                ctx == null ? List.of() : ctx.evidence());
        if (evidenceMatrix.rows().size() < 2) {
            TraceStore.put("debug.triadic.judge.skipped", "insufficient_evidence_rows");
            return DebugPatchVote.hold("insufficient_evidence_rows");
        }

        boolean modelCallAttempted = false;
        try {
            PromptContext judgeContext = (ctx == null ? PromptContext.builder() : ctx.toBuilder())
                    .ensembleCandidates(candidates)
                    .ensembleJudgeMode(true)
                    .build();
            String debugJudgePrompt = buildDebugJudgePrompt(judgeContext, evidenceMatrix);
            int effectiveTimeoutSeconds = Math.max(1, judgeTimeoutSeconds);
            var model = modelFactory.lcWithTimeout(
                    judgeModel,
                    boundedJudgeTemperature(judgeTemperature),
                    boundedJudgeTopP(judgeTopP),
                    null,
                    null,
                    boundedJudgeMaxTokens(judgeMaxTokens),
                    effectiveTimeoutSeconds);
            if (model == null) {
                TraceStore.put("debug.triadic.judge.callCount", 0);
                TraceStore.put("debug.triadic.judge.fail", "model_unavailable");
                return DebugPatchVote.hold("model_unavailable");
            }
            modelCallAttempted = true;
            ChatResponse response = chatWithLatency(
                    model, List.of(UserMessage.from(debugJudgePrompt)), "debug.triadic.judge.");
            traceTokenUsage("debug.triadic.judge.", response);
            var aiMessage = response == null ? null : response.aiMessage();
            String result = aiMessage == null ? null : aiMessage.text();

            TraceStore.put("debug.triadic.judge.callCount", 1);
            TraceStore.put("debug.triadic.judge.modelHash", SafeRedactor.hashValue(judgeModel));
            TraceStore.put("debug.triadic.judge.resultLen", result == null ? 0 : result.length());
            if (response == null || aiMessage == null) {
                TraceStore.put("debug.triadic.judge.fail", "malformed_model_response");
                return DebugPatchVote.hold("malformed_model_response", 1);
            }
            DebugPatchVote vote = parseDebugPatchVote(result, evidenceMatrix);
            if (vote == null) {
                TraceStore.put("debug.triadic.judge.invalid", "judge_contract_mismatch");
                return DebugPatchVote.hold("judge_contract_mismatch", 1);
            }
            TraceStore.put("debug.triadic.judge.decision", vote.decision().name());
            TraceStore.put("debug.triadic.judge.confidence", vote.confidence().name());
            TraceStore.put("debug.triadic.judge.decisiveEvidenceCount", vote.decisiveEvidenceCount());
            TraceStore.put("debug.triadic.judge.resultHash12", SafeRedactor.hash12(result));
            return vote;
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (RuntimeException failure) {
            CancellationException cancellation = cancellationFrom(failure);
            if (cancellation != null) {
                throw cancellation;
            }
            TraceStore.put("debug.triadic.judge.fail", "debug_judge_failed");
            log.warn("[ensemble][debug-judge] fail rid={} type={}",
                    SafeRedactor.hashValue(rid),
                    "debug_judge_failed");
            return DebugPatchVote.hold("debug_judge_failed", modelCallAttempted ? 1 : 0);
        }
    }

    private static CancellationException cancellationFrom(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CancellationException cancellation) {
                return cancellation;
            }
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                CancellationException cancellation = new CancellationException("triadic_judge_interrupted");
                cancellation.initCause(failure);
                return cancellation;
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        if (Thread.currentThread().isInterrupted()) {
            CancellationException cancellation = new CancellationException("triadic_judge_interrupted");
            cancellation.initCause(failure);
            return cancellation;
        }
        return null;
    }

    private static ChatResponse chatWithLatency(
            dev.langchain4j.model.chat.ChatModel model,
            List<ChatMessage> messages,
            String tracePrefix) {
        long startedNanos = System.nanoTime();
        try {
            return model.chat(messages);
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, System.nanoTime() - startedNanos));
            TraceStore.put(tracePrefix + "modelCallElapsedMs", elapsedMs);
        }
    }

    private static void traceTokenUsage(String tracePrefix, ChatResponse response) {
        TokenUsage usage = response == null ? null : response.tokenUsage();
        Integer inputTokens = usage == null ? null : usage.inputTokenCount();
        Integer outputTokens = usage == null ? null : usage.outputTokenCount();
        Integer totalTokens = usage == null ? null : usage.totalTokenCount();
        if (inputTokens == null || outputTokens == null || totalTokens == null
                || inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            markUsageUnavailable(tracePrefix);
            return;
        }
        TraceStore.put(tracePrefix + "tokenUsageObserved", true);
        TraceStore.put(tracePrefix + "tokenUsageReason", null);
        TraceStore.put(tracePrefix + "inputTokens", inputTokens);
        TraceStore.put(tracePrefix + "outputTokens", outputTokens);
        TraceStore.put(tracePrefix + "totalTokens", totalTokens);
    }

    private static void beginJudgeAttempt(String tracePrefix) {
        markUsageUnavailable(tracePrefix);
        TraceStore.put(tracePrefix + "modelCallElapsedMs", null);
    }

    private static void markUsageUnavailable(String tracePrefix) {
        TraceStore.put(tracePrefix + "tokenUsageObserved", false);
        TraceStore.put(tracePrefix + "tokenUsageReason", "provider_usage_unavailable");
        TraceStore.put(tracePrefix + "inputTokens", null);
        TraceStore.put(tracePrefix + "outputTokens", null);
        TraceStore.put(tracePrefix + "totalTokens", null);
    }

    private String buildJudgePrompt(
            PromptContext judgeContext,
            EnsembleEvidenceMatrix evidenceMatrix) {
        String dynamicEvidenceContract = EVIDENCE_JUDGE_CONTRACT
                + "\n\n"
                + evidenceMatrix.renderForJudge();
        PromptContext evidenceJudgeContext = judgeContext.toBuilder()
                .userQuery(EnsembleEvidenceContract.stageQuestion(
                        dynamicEvidenceContract,
                        judgeContext.userQuery()))
                .build();
        return promptBuilder.build(evidenceJudgeContext);
    }

    private String buildDebugJudgePrompt(
            PromptContext judgeContext,
            EnsembleEvidenceMatrix evidenceMatrix) {
        String contract = TRIADIC_DEBUG_JUDGE_CONTRACT
                + "\n\n"
                + evidenceMatrix.renderForJudge();
        PromptContext debugJudgeContext = judgeContext.toBuilder()
                .userQuery(EnsembleEvidenceContract.stageQuestion(
                        contract,
                        judgeContext.userQuery()))
                .build();
        return promptBuilder.build(debugJudgeContext);
    }

    private static boolean hasCompleteDistinctThreeRoleSet(List<SampledCandidate> candidates) {
        if (candidates == null || candidates.size() != 3) {
            return false;
        }
        List<SampledCandidate> complete = candidates.stream()
                .filter(candidate -> candidate != null
                        && candidate.nodeId() != null
                        && !candidate.nodeId().isBlank()
                        && candidate.text() != null
                        && !candidate.text().isBlank())
                .toList();
        long supportCount = complete.stream()
                .filter(candidate -> candidate.hypothesisDirection()
                        == SampledCandidate.HypothesisDirection.SUPPORT)
                .count();
        long falsifyCount = complete.stream()
                .filter(candidate -> candidate.hypothesisDirection()
                        == SampledCandidate.HypothesisDirection.FALSIFY)
                .count();
        if (complete.size() != 3 || supportCount != 2L || falsifyCount != 1L) {
            return false;
        }
        java.util.Map<String, SampledCandidate> byNodeId = complete.stream()
                .collect(java.util.stream.Collectors.toMap(
                        SampledCandidate::nodeId,
                        candidate -> candidate,
                        (left, right) -> left));
        if (!byNodeId.keySet().equals(java.util.Set.of("support", "support_alternative", "falsify"))) {
            return false;
        }
        if (byNodeId.get("support").hypothesisDirection() != SampledCandidate.HypothesisDirection.SUPPORT
                || byNodeId.get("support_alternative").hypothesisDirection()
                        != SampledCandidate.HypothesisDirection.SUPPORT
                || byNodeId.get("falsify").hypothesisDirection()
                        != SampledCandidate.HypothesisDirection.FALSIFY) {
            return false;
        }
        return complete.stream().map(candidate -> candidate.text().strip()).distinct().count() == 3L;
    }

    private static DebugPatchVote parseDebugPatchVote(
            String result,
            EnsembleEvidenceMatrix evidenceMatrix) {
        if (result == null || result.isBlank() || result.length() > 1_000 || !isBoundedAscii(result)) {
            return null;
        }
        List<String> lines = result.replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.size() != 5 || !"ROLE: HOLD".equals(lines.get(0))) {
            return null;
        }
        String decisionValue = fieldValue(lines.get(1), "DECISION: ");
        String confidenceValue = fieldValue(lines.get(2), "CONFIDENCE: ");
        String idsValue = fieldValue(lines.get(3), "DECISIVE EVIDENCE IDS: ");
        String reasonCode = fieldValue(lines.get(4), "REASON CODE: ");
        if (decisionValue == null || confidenceValue == null || idsValue == null || reasonCode == null
                || !DEBUG_REASON_CODE.matcher(reasonCode).matches()
                || !DEBUG_REASON_CODES.contains(reasonCode)) {
            return null;
        }

        DebugPatchDecision decision;
        DebugPatchConfidence confidence;
        try {
            decision = DebugPatchDecision.valueOf(decisionValue);
            confidence = DebugPatchConfidence.valueOf(confidenceValue);
        } catch (IllegalArgumentException invalidEnum) {
            return null;
        }
        if (!isDebugDecisionReasonCoherent(decision, reasonCode)) {
            return null;
        }

        List<String> ids;
        if ("NONE".equals(idsValue)) {
            ids = List.of();
        } else {
            ids = java.util.Arrays.stream(idsValue.split(","))
                    .map(String::strip)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .toList();
            if (ids.isEmpty()
                    || ids.stream().anyMatch(id -> !DEBUG_EVIDENCE_ID.matcher(id).matches())
                    || !evidenceMatrix.containsAllEvidenceIds(ids)) {
                return null;
            }
        }
        if (decision == DebugPatchDecision.HOLD && !ids.isEmpty()) {
            return null;
        }
        if (decision != DebugPatchDecision.HOLD && ids.isEmpty()) {
            return null;
        }
        if (decision != DebugPatchDecision.HOLD && !evidenceMatrix.supportsDebugPatchDecisionIds(ids)) {
            return null;
        }
        return new DebugPatchVote(decision, confidence, ids.size(), reasonCode, 1);
    }

    static boolean isDebugDecisionReasonCoherent(
            DebugPatchDecision decision,
            String reasonCode) {
        return switch (decision) {
            case APPLY -> "support_grounded".equals(reasonCode);
            case REJECT -> "falsify_grounded".equals(reasonCode);
            case HOLD -> Set.of(
                    "evidence_conflicted",
                    "evidence_incomplete",
                    "underdetermined",
                    "unsafe_patch",
                    "neutral_hold").contains(reasonCode);
        };
    }

    private static String fieldValue(String line, String prefix) {
        if (line == null || !line.startsWith(prefix) || line.length() <= prefix.length()) {
            return null;
        }
        return line.substring(prefix.length()).strip();
    }

    private static boolean isBoundedAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current > 0x7e
                    || (current < 0x20 && current != '\r' && current != '\n' && current != '\t')) {
                return false;
            }
        }
        return true;
    }

    private static String safeDebugReasonCode(String value) {
        String normalized = value == null ? "underdetermined" : value.strip().toLowerCase(Locale.ROOT);
        return DEBUG_REASON_CODE.matcher(normalized).matches() ? normalized : "underdetermined";
    }

    private static void traceEvidenceMatrix(EnsembleEvidenceMatrix matrix) {
        TraceStore.put("ensemble.evidenceMatrix.schemaVersion", matrix.schemaVersion());
        TraceStore.put("ensemble.evidenceMatrix.matrixId", matrix.matrixId());
        TraceStore.put("ensemble.evidenceMatrix.rowCount", matrix.rows().size());
        TraceStore.put("ensemble.evidenceMatrix.provenanceGroupCount", matrix.provenanceGroupCount());
        TraceStore.put("ensemble.evidenceMatrix.droppedMissingLocatorCount", matrix.droppedMissingLocatorCount());
        TraceStore.put("ensemble.evidenceMatrix.droppedRowLimitCount", matrix.droppedRowLimitCount());
        TraceStore.put("ensemble.evidenceMatrix.blockerCount", matrix.blockers().size());
        TraceStore.put("ensemble.evidenceMatrix.supportEligible", matrix.supportEligible());
    }

    private static double boundedJudgeTemperature(double requested) {
        if (!Double.isFinite(requested)) {
            return DEFAULT_JUDGE_TEMPERATURE;
        }
        return Math.max(0.0d, Math.min(MAX_JUDGE_TEMPERATURE, requested));
    }

    private static double boundedJudgeTopP(double requested) {
        if (!Double.isFinite(requested)) {
            return DEFAULT_JUDGE_TOP_P;
        }
        return Math.max(0.0d, Math.min(MAX_JUDGE_TOP_P, requested));
    }

    private static int boundedJudgeMaxTokens(int requested) {
        if (requested <= 0) {
            return DEFAULT_JUDGE_MAX_TOKENS;
        }
        return Math.max(MIN_JUDGE_MAX_TOKENS, Math.min(MAX_JUDGE_MAX_TOKENS, requested));
    }

    private static String failClosedToEvidenceHold() {
        TraceStore.put("ensemble.judge.fallback", "evidence_hold");
        return SAFE_EVIDENCE_HOLD;
    }
}
