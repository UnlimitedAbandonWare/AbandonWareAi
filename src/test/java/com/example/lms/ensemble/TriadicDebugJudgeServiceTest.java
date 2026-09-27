package com.example.lms.ensemble;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriadicDebugJudgeServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void neutralJudgeAcceptsOnlyOwnedEvidenceIdsAndUsesCanonicalPromptBuilder() {
        PromptContext context = contextWithPatchCandidate();
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(context.evidence());
        String patchCandidateId = matrix.rows().stream()
                .filter(row -> row.safeMarker().startsWith("P"))
                .map(EnsembleEvidenceMatrix.Row::evidenceId)
                .findFirst()
                .orElseThrow();
        String fingerprintId = matrix.rows().stream()
                .filter(row -> row.safeMarker().startsWith("D"))
                .map(EnsembleEvidenceMatrix.Row::evidenceId)
                .findFirst()
                .orElseThrow();
        RecordingFactory factory = new RecordingFactory("""
                ROLE: HOLD
                DECISION: APPLY
                CONFIDENCE: HIGH
                DECISIVE EVIDENCE IDS: %s,%s
                REASON CODE: support_grounded
                """.formatted(patchCandidateId, fingerprintId));
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        var vote = service.judgeDebugPatch(threeRoleSet(), context, "rid");

        assertEquals(EnsembleJudgeService.DebugPatchDecision.APPLY, vote.decision());
        assertEquals(EnsembleJudgeService.DebugPatchConfidence.HIGH, vote.confidence());
        assertEquals(2, vote.decisiveEvidenceCount());
        assertEquals("support_grounded", vote.reasonCode());
        assertEquals(1, vote.modelCallCount());
        assertEquals(1, factory.prompts.size());
        assertTrue(factory.prompts.get(0).contains("Evidence-Grounded Triadic Debug Adjudicator"));
        assertTrue(factory.prompts.get(0).contains("ROLE: HOLD"));
        assertTrue(factory.prompts.get(0).contains("exactly three candidate dossiers"));
        assertTrue(factory.prompts.get(0).contains("support, support_alternative, and falsify"));
        assertTrue(factory.prompts.get(0).contains(
                "SUPPORT and SUPPORT_ALTERNATIVE are independent hypotheses, not two votes."));
        assertTrue(factory.prompts.get(0).contains(
                "Never decide by candidate count"));
        assertFalse(factory.prompts.get(0).contains("two candidate dossiers"));
        assertTrue(factory.prompts.get(0).contains("safeMarker=P1"));
    }

    @Test
    void contradictoryDecisionAndReasonFailSoftToHold() {
        PromptContext context = contextWithPatchCandidate();
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(context.evidence());
        String patchCandidateId = matrix.rows().stream()
                .filter(row -> row.safeMarker().startsWith("P"))
                .map(EnsembleEvidenceMatrix.Row::evidenceId)
                .findFirst()
                .orElseThrow();
        String fingerprintId = matrix.rows().stream()
                .filter(row -> row.safeMarker().startsWith("D"))
                .map(EnsembleEvidenceMatrix.Row::evidenceId)
                .findFirst()
                .orElseThrow();
        RecordingFactory factory = new RecordingFactory("""
                ROLE: HOLD
                DECISION: APPLY
                CONFIDENCE: HIGH
                DECISIVE EVIDENCE IDS: %s,%s
                REASON CODE: falsify_grounded
                """.formatted(patchCandidateId, fingerprintId));
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        var vote = service.judgeDebugPatch(threeRoleSet(), context, "rid");

        assertEquals(EnsembleJudgeService.DebugPatchDecision.HOLD, vote.decision());
        assertEquals(EnsembleJudgeService.DebugPatchConfidence.LOW, vote.confidence());
        assertEquals("judge_contract_mismatch", vote.reasonCode());
        assertEquals(1, vote.modelCallCount());
    }

    @Test
    void malformedOrUnknownEvidenceVoteFailsSoftToHoldWithoutLeakingOutput() {
        String rawSecret = "sk-" + "1234567890abcdef1234";
        RecordingFactory factory = new RecordingFactory("""
                ROLE: HOLD
                DECISION: APPLY
                CONFIDENCE: HIGH
                DECISIVE EVIDENCE IDS: ev1:000000000000
                REASON CODE: %s
                """.formatted(rawSecret));
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        var vote = service.judgeDebugPatch(threeRoleSet(), context(), "rid");

        assertEquals(EnsembleJudgeService.DebugPatchDecision.HOLD, vote.decision());
        assertEquals(EnsembleJudgeService.DebugPatchConfidence.LOW, vote.confidence());
        assertEquals("judge_contract_mismatch", vote.reasonCode());
        assertEquals(1, vote.modelCallCount());
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawSecret));
    }

    @Test
    void applyVoteWithoutPatchCandidateEvidenceFailsClosedToHold() {
        PromptContext context = contextWithPatchCandidate();
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(context.evidence());
        String fingerprintId = matrix.rows().stream()
                .filter(row -> row.safeMarker().startsWith("D"))
                .map(EnsembleEvidenceMatrix.Row::evidenceId)
                .findFirst()
                .orElseThrow();
        RecordingFactory factory = new RecordingFactory("""
                ROLE: HOLD
                DECISION: APPLY
                CONFIDENCE: HIGH
                DECISIVE EVIDENCE IDS: %s
                REASON CODE: support_grounded
                """.formatted(fingerprintId));
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        var vote = service.judgeDebugPatch(threeRoleSet(), context, "rid");

        assertEquals(EnsembleJudgeService.DebugPatchDecision.HOLD, vote.decision());
        assertEquals(EnsembleJudgeService.DebugPatchConfidence.LOW, vote.confidence());
        assertEquals("judge_contract_mismatch", vote.reasonCode());
        assertEquals(1, vote.modelCallCount());
    }

    @Test
    void duplicateDossiersHoldWithoutNeutralModelCall() {
        RecordingFactory factory = new RecordingFactory("unused");
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());
        List<SampledCandidate> duplicates = List.of(
                candidate(SampledCandidate.HypothesisDirection.SUPPORT, 0.84d, "same dossier"),
                candidate(SampledCandidate.HypothesisDirection.FALSIFY, 0.61d, "same dossier"));

        var vote = service.judgeDebugPatch(duplicates, context(), "rid");

        assertEquals(EnsembleJudgeService.DebugPatchDecision.HOLD, vote.decision());
        assertEquals("incomplete_role_set", vote.reasonCode());
        assertEquals(0, vote.modelCallCount());
        assertEquals(0, factory.prompts.size());
    }

    @Test
    void impostorRoleNodeIdHoldsWithoutNeutralModelCall() {
        RecordingFactory factory = new RecordingFactory("unused");
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());
        List<SampledCandidate> impostorRoles = List.of(
                candidate("support", SampledCandidate.HypothesisDirection.SUPPORT, 0.84d,
                        "primary support dossier"),
                candidate("second_support", SampledCandidate.HypothesisDirection.SUPPORT, 0.78d,
                        "alternative support dossier"),
                candidate("falsify", SampledCandidate.HypothesisDirection.FALSIFY, 0.61d,
                        "falsify dossier"));

        var vote = service.judgeDebugPatch(impostorRoles, context(), "rid");

        assertEquals(EnsembleJudgeService.DebugPatchDecision.HOLD, vote.decision());
        assertEquals("incomplete_role_set", vote.reasonCode());
        assertEquals(0, vote.modelCallCount());
        assertEquals(0, factory.prompts.size());
    }

    @Test
    void missingNeutralModelReportsZeroCalls() {
        DynamicChatModelFactory factory = new DynamicChatModelFactory(null, null) {
            @Override
            public ChatModel lcWithTimeout(
                    String modelName,
                    Double temperature,
                    Double topP,
                    Double frequencyPenalty,
                    Double presencePenalty,
                    Integer maxTokens,
                    int timeoutSeconds) {
                return null;
            }
        };
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        var vote = service.judgeDebugPatch(threeRoleSet(), context(), "rid");

        assertEquals(EnsembleJudgeService.DebugPatchDecision.HOLD, vote.decision());
        assertEquals("model_unavailable", vote.reasonCode());
        assertEquals(0, vote.modelCallCount());
    }

    @Test
    void wrappedNeutralModelCancellationIsNotConvertedToHold() {
        DynamicChatModelFactory factory = new DynamicChatModelFactory(null, null) {
            @Override
            public ChatModel lcWithTimeout(
                    String modelName,
                    Double temperature,
                    Double topP,
                    Double frequencyPenalty,
                    Double presencePenalty,
                    Integer maxTokens,
                    int timeoutSeconds) {
                return new ChatModel() {
                    @Override
                    public ChatResponse chat(List<ChatMessage> messages) {
                        throw new CompletionException(new CancellationException("cancelled"));
                    }
                };
            }
        };
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        assertThrows(CancellationException.class,
                () -> service.judgeDebugPatch(threeRoleSet(), context(), "rid"));
    }

    private static PromptContext context() {
        return PromptContext.builder()
                .userQuery("adjudicate safe debug patch candidate")
                .evidence(List.of(
                        new RagEvidenceMetadata("D1", "LOCAL_DOC", "failure fingerprint one", null,
                                "debug/fingerprints/one.trace", 1, 1, 1, 1.0d, "debug_event_count"),
                        new RagEvidenceMetadata("D2", "LOCAL_DOC", "failure fingerprint two", null,
                                "debug/fingerprints/two.trace", 1, 1, 2, 1.0d, "debug_event_count")))
                .build();
    }

    private static PromptContext contextWithPatchCandidate() {
        return PromptContext.builder()
                .userQuery("adjudicate bound safe debug patch candidate")
                .evidence(List.of(
                        new RagEvidenceMetadata("P1", "LOCAL_DOC", "patch candidate", null,
                                "debug/patch-candidates/candidate.patch", 1, 1, 0, 1.0d,
                                "admin_patch_candidate"),
                        new RagEvidenceMetadata("D1", "LOCAL_DOC", "failure fingerprint one", null,
                                "debug/fingerprints/one.trace", 1, 1, 1, 1.0d, "debug_event_count"),
                        new RagEvidenceMetadata("D2", "LOCAL_DOC", "failure fingerprint two", null,
                                "debug/fingerprints/two.trace", 1, 1, 2, 1.0d, "debug_event_count")))
                .build();
    }

    private static List<SampledCandidate> threeRoleSet() {
        return List.of(
                candidate("support", SampledCandidate.HypothesisDirection.SUPPORT, 0.84d,
                        "bounded support dossier"),
                candidate("support_alternative", SampledCandidate.HypothesisDirection.SUPPORT, 0.78d,
                        "independent alternative support dossier"),
                candidate("falsify", SampledCandidate.HypothesisDirection.FALSIFY, 0.61d,
                        "bounded falsify dossier"));
    }

    private static SampledCandidate candidate(
            SampledCandidate.HypothesisDirection direction,
            double groundingScore) {
        return candidate(direction, groundingScore, "bounded " + direction.name().toLowerCase() + " dossier");
    }

    private static SampledCandidate candidate(
            SampledCandidate.HypothesisDirection direction,
            double groundingScore,
            String dossier) {
        return candidate(direction.name().toLowerCase(), direction, groundingScore, dossier);
    }

    private static SampledCandidate candidate(
            String nodeId,
            SampledCandidate.HypothesisDirection direction,
            double groundingScore,
            String dossier) {
        return new SampledCandidate(
                nodeId,
                dossier,
                0.2d,
                0.4d,
                groundingScore,
                0.1d,
                FinalSigmoidGate.GateResult.PASS,
                direction,
                SampledCandidate.EvidenceStatus.SUFFICIENT,
                1.0d,
                1.0d,
                0.0d,
                groundingScore);
    }

    private static final class RecordingFactory extends DynamicChatModelFactory {
        private final String responseText;
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        private RecordingFactory(String responseText) {
            super(null, null);
            this.responseText = responseText;
        }

        @Override
        public ChatModel lcWithTimeout(
                String modelName,
                Double temperature,
                Double topP,
                Double frequencyPenalty,
                Double presencePenalty,
                Integer maxTokens,
                int timeoutSeconds) {
            return new RecordingModel(responseText, prompts);
        }
    }

    private record RecordingModel(String responseText, List<String> prompts) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            prompts.add(messages.stream()
                    .map(ChatMessage::toString)
                    .reduce("", (left, right) -> left + right));
            return ChatResponse.builder().aiMessage(AiMessage.from(responseText)).build();
        }
    }
}
