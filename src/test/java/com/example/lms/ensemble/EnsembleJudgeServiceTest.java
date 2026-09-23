package com.example.lms.ensemble;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnsembleJudgeServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void emptyCandidatesSkipJudge() {
        EnsembleJudgeService service = new EnsembleJudgeService(new StubFactory("unused"), builder());

        assertNull(service.judge(List.of(), PromptContext.builder().userQuery("q").build(), "rid"));

        assertEquals("no_candidates", TraceStore.get("ensemble.judge.skipped"));
    }

    @Test
    void blankJudgeOutputFailsClosedInsteadOfPromotingOneHypothesis() {
        AtomicBoolean sawJudgeMode = new AtomicBoolean(false);
        AtomicInteger builderCalls = new AtomicInteger();
        EnsembleJudgeService service = new EnsembleJudgeService(new StubFactory(""), (contexts, question) -> {
            builderCalls.incrementAndGet();
            PromptContext ctx = contexts.get(0);
            sawJudgeMode.set(ctx.ensembleJudgeMode() && ctx.ensembleCandidates().size() == 3);
            return "judge prompt";
        });

        String result = service.judge(triad(), PromptContext.builder().userQuery("q").build(), "rid");

        assertEvidenceHold(result);
        assertTrue(sawJudgeMode.get());
        assertEquals(1, builderCalls.get());
        assertEquals("gemma4:26b".length(), TraceStore.get("ensemble.judge.modelLength"));
        assertNull(TraceStore.get("ensemble.judge.model"));
        assertEquals(3, TraceStore.get("ensemble.judge.candidateCount"));
        assertEquals(0, TraceStore.get("ensemble.judge.resultLen"));
        assertEquals("evidence_hold", TraceStore.get("ensemble.judge.fallback"));
    }

    @Test
    void blankJudgeNeverReturnsUnverifiedCandidateText() {
        EnsembleJudgeService service = new EnsembleJudgeService(new StubFactory(""), builder());
        String rawKey = "sk-" + "1234567890abcdef1234";
        List<SampledCandidate> candidates = List.of(
                candidate("cooperative", "cooperative saw " + rawKey, 0.90d),
                candidate("base_rate", "base rate saw " + rawKey, 0.90d),
                candidate("opportunistic", "opportunistic saw " + rawKey, 0.90d));

        String result = service.judge(candidates, PromptContext.builder().userQuery("q").build(), "rid");

        assertEvidenceHold(result);
        assertFalse(result.contains(rawKey));
        assertEquals("evidence_hold", TraceStore.get("ensemble.judge.fallback"));
    }

    @Test
    void judgeFailureRecordsStableTraceLabel() {
        EnsembleJudgeService service = new EnsembleJudgeService(new ThrowingFactory(), builder());

        String result = service.judge(triad(), PromptContext.builder().userQuery("q").build(), "rid");

        assertEvidenceHold(result);
        assertEquals("ensemble_judge_failed", TraceStore.get("ensemble.judge.fail"));
        assertFalse(String.valueOf(TraceStore.get("ensemble.judge.fail")).contains("IllegalStateException"));
        assertEquals("evidence_hold", TraceStore.get("ensemble.judge.fallback"));
    }

    @Test
    void judgeUsesEvidenceCenteredContractAtBoundedTemperature() {
        StubFactory factory = new StubFactory(validJudgeResult());
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());
        ReflectionTestUtils.setField(service, "judgeTemperature", 0.95d);
        ReflectionTestUtils.setField(service, "judgeTopP", 0.95d);
        ReflectionTestUtils.setField(service, "judgeMaxTokens", 10_000);

        String result = service.judge(List.of(
                        candidate("cooperative", "cooperative candidate", 0.70d),
                        candidate("base_rate", "base-rate candidate", 0.80d),
                        candidate("opportunistic", "opportunistic candidate", 0.75d)),
                PromptContext.builder().userQuery("ambiguous conduct").build(),
                "judge-rid");

        assertEquals(validJudgeResult(), result);
        assertEquals(List.of(0.30d), factory.temperatures);
        assertEquals(List.of(0.50d), factory.topPs);
        assertEquals(List.of(2_000), factory.maxTokensValues);
        assertEquals(1, factory.prompts.size());
        String prompt = factory.prompts.get(0);
        assertTrue(prompt.contains("low-variance evidence judge"));
        assertTrue(prompt.contains("COOPERATIVE, BASE_RATE, and OPPORTUNISTIC"));
        assertTrue(prompt.contains("hidden intent as fact"));
        assertTrue(prompt.contains("unresolved conflicts"));
        assertTrue(prompt.contains("POSSIBILITY RANGES:"));
        assertTrue(prompt.contains("additional discriminating evidence"));
        assertTrue(prompt.contains("evidence preservation"));
        assertTrue(prompt.contains("SUPPORTS=<selected stance>"));
        assertTrue(prompt.contains("For UNDERDETERMINED or HOLD, every stance line must include"));
        assertTrue(prompt.contains("POSSIBLE - UNCONFIRMED"));
        assertTrue(prompt.contains("CURRENT SOURCES DO NOT DISTINGUISH INTENT"));
        assertTrue(prompt.contains("EVIDENCE CONFLICTS UNRESOLVED"));
        assertTrue(prompt.contains("ADDITIONAL INDEPENDENT RECORDS REQUIRED"));
        assertTrue(prompt.contains("ASCII-only machine contract"));
        assertTrue(prompt.contains("Only the selected stance may use SUPPORTS"));
        assertTrue(prompt.contains("DECISION: <one of"));
        assertEquals(0.30d, TraceStore.get("ensemble.judge.temperature"));
        assertEquals(0.50d, TraceStore.get("ensemble.judge.topP"));
        assertEquals(2_000, TraceStore.get("ensemble.judge.maxTokens"));
        assertEquals(6, TraceStore.get("ensemble.judge.timeoutSeconds"));
        assertEquals(List.of(6), factory.timeoutSecondsValues);
        assertEquals(3, TraceStore.get("prompt.ensembleCandidatesRenderedCount"));
    }

    @Test
    void judgeProjectsCompleteNeutralProviderUsageAndModelCallLatency() {
        UsageRecordingFactory factory = new UsageRecordingFactory(
                validJudgeResult(), new TokenUsage(11, 7, 18));
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        String result = service.judge(
                triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "judge-rid");

        assertEquals(validJudgeResult(), result);
        assertEquals(1, factory.chatCalls.get());
        assertEquals(1, factory.prompts.size());
        assertEquals(Boolean.TRUE, TraceStore.get("ensemble.judge.tokenUsageObserved"));
        assertEquals(11, TraceStore.get("ensemble.judge.inputTokens"));
        assertEquals(7, TraceStore.get("ensemble.judge.outputTokens"));
        assertEquals(18, TraceStore.get("ensemble.judge.totalTokens"));
        assertNull(TraceStore.get("ensemble.judge.tokenUsageReason"));
        Object elapsedMs = TraceStore.get("ensemble.judge.modelCallElapsedMs");
        assertTrue(elapsedMs instanceof Number);
        assertTrue(((Number) elapsedMs).longValue() >= 0L);
        assertTrue(TraceStore.getByPrefix("ensemble.node.").isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailableTokenUsages")
    void judgeDoesNotProjectNullPartialOrNegativeProviderUsage(String ignored, TokenUsage usage) {
        UsageRecordingFactory factory = new UsageRecordingFactory(validJudgeResult(), usage);
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        String result = service.judge(
                triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "judge-rid");

        assertEquals(validJudgeResult(), result);
        assertEquals(1, factory.chatCalls.get());
        assertEquals(Boolean.FALSE, TraceStore.get("ensemble.judge.tokenUsageObserved"));
        assertEquals("provider_usage_unavailable", TraceStore.get("ensemble.judge.tokenUsageReason"));
        assertNull(TraceStore.get("ensemble.judge.inputTokens"));
        assertNull(TraceStore.get("ensemble.judge.outputTokens"));
        assertNull(TraceStore.get("ensemble.judge.totalTokens"));
    }

    @Test
    void judgeClearsObservedUsageBeforeThrowingChatCallInSameContext() {
        SequenceFactory factory = new SequenceFactory(
                responseModel(validJudgeResult(), new TokenUsage(11, 7, 18)),
                new ThrowingChatModel(new IllegalStateException("judge model down")));
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        assertEquals(validJudgeResult(), service.judge(
                triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "judge-rid"));
        String result = service.judge(
                triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "judge-rid");

        assertEvidenceHold(result);
        assertEquals("ensemble_judge_failed", TraceStore.get("ensemble.judge.fail"));
        assertUnavailableUsage("ensemble.judge.");
        assertNonNegativeLatency("ensemble.judge.");
    }

    @Test
    void judgeClearsObservedUsageBeforeCancelledChatCallInSameContext() {
        SequenceFactory factory = new SequenceFactory(
                responseModel(validJudgeResult(), new TokenUsage(11, 7, 18)),
                new ThrowingChatModel(new CancellationException("cancelled")));
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        assertEquals(validJudgeResult(), service.judge(
                triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "judge-rid"));

        assertThrows(CancellationException.class, () -> service.judge(
                triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "judge-rid"));

        assertNull(TraceStore.get("ensemble.judge.fallback"));
        assertUnavailableUsage("ensemble.judge.");
        assertNonNegativeLatency("ensemble.judge.");
    }

    @Test
    void debugJudgeClearsObservedUsageWhenTheNextModelIsUnavailable() {
        SequenceFactory factory = new SequenceFactory(
                responseModel(validDebugJudgeResult(), new TokenUsage(11, 7, 18)),
                null);
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        assertEquals(EnsembleJudgeService.DebugPatchDecision.HOLD,
                service.judgeDebugPatch(debugTriad(), debugContext(), "judge-rid").decision());
        assertEquals(Boolean.TRUE, TraceStore.get("debug.triadic.judge.tokenUsageObserved"));

        EnsembleJudgeService.DebugPatchVote result = service.judgeDebugPatch(
                debugTriad(), debugContext(), "judge-rid");

        assertEquals(EnsembleJudgeService.DebugPatchDecision.HOLD, result.decision());
        assertEquals("model_unavailable", result.reasonCode());
        assertUnavailableUsage("debug.triadic.judge.");
        assertNull(TraceStore.get("debug.triadic.judge.modelCallElapsedMs"));
    }

    @Test
    void judgeTreatsCompleteZeroProviderUsageAsObserved() {
        UsageRecordingFactory factory = new UsageRecordingFactory(
                validJudgeResult(), new TokenUsage(0, 0, 0));
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        assertEquals(validJudgeResult(), service.judge(
                triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "judge-rid"));

        assertEquals(Boolean.TRUE, TraceStore.get("ensemble.judge.tokenUsageObserved"));
        assertEquals(0, TraceStore.get("ensemble.judge.inputTokens"));
        assertEquals(0, TraceStore.get("ensemble.judge.outputTokens"));
        assertEquals(0, TraceStore.get("ensemble.judge.totalTokens"));
        assertNull(TraceStore.get("ensemble.judge.tokenUsageReason"));
    }

    private static Stream<Arguments> unavailableTokenUsages() {
        return Stream.of(
                Arguments.of("null usage", (TokenUsage) null),
                Arguments.of("all-null usage", new TokenUsage(null, null, null)),
                Arguments.of("input only", new TokenUsage(11, null, null)),
                Arguments.of("output only", new TokenUsage(null, 7, null)),
                Arguments.of("total only", new TokenUsage(null, null, 18)),
                Arguments.of("input and output", new TokenUsage(11, 7, null)),
                Arguments.of("input and total", new TokenUsage(11, null, 18)),
                Arguments.of("output and total", new TokenUsage(null, 7, 18)),
                Arguments.of("negative input", new TokenUsage(-1, 7, 18)),
                Arguments.of("negative output", new TokenUsage(11, -1, 18)),
                Arguments.of("negative total", new TokenUsage(11, 7, -1)));
    }

    private static void assertUnavailableUsage(String tracePrefix) {
        assertEquals(Boolean.FALSE, TraceStore.get(tracePrefix + "tokenUsageObserved"));
        assertEquals("provider_usage_unavailable", TraceStore.get(tracePrefix + "tokenUsageReason"));
        assertNull(TraceStore.get(tracePrefix + "inputTokens"));
        assertNull(TraceStore.get(tracePrefix + "outputTokens"));
        assertNull(TraceStore.get(tracePrefix + "totalTokens"));
    }

    private static void assertNonNegativeLatency(String tracePrefix) {
        Object elapsedMs = TraceStore.get(tracePrefix + "modelCallElapsedMs");
        assertTrue(elapsedMs instanceof Number);
        assertTrue(((Number) elapsedMs).longValue() >= 0L);
    }

    private static List<SampledCandidate> debugTriad() {
        return List.of(
                debugCandidate("support", SampledCandidate.HypothesisDirection.SUPPORT, 0.84d),
                debugCandidate("support_alternative", SampledCandidate.HypothesisDirection.SUPPORT, 0.78d),
                debugCandidate("falsify", SampledCandidate.HypothesisDirection.FALSIFY, 0.61d));
    }

    private static SampledCandidate debugCandidate(
            String nodeId, SampledCandidate.HypothesisDirection direction, double groundingScore) {
        return new SampledCandidate(
                nodeId,
                "bounded " + nodeId + " dossier",
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

    private static PromptContext debugContext() {
        return PromptContext.builder()
                .userQuery("adjudicate safe debug patch candidate")
                .evidence(List.of(
                        new RagEvidenceMetadata("D1", "LOCAL_DOC", "failure fingerprint one", null,
                                "debug/fingerprints/one.trace", 1, 1, 1, 1.0d, "debug_event_count"),
                        new RagEvidenceMetadata("D2", "LOCAL_DOC", "failure fingerprint two", null,
                                "debug/fingerprints/two.trace", 1, 1, 2, 1.0d, "debug_event_count")))
                .build();
    }

    private static String validDebugJudgeResult() {
        return """
                ROLE: HOLD
                DECISION: HOLD
                CONFIDENCE: LOW
                DECISIVE EVIDENCE IDS: NONE
                REASON CODE: neutral_hold
                """;
    }

    @Test
    void incompleteHypothesisSetSkipsJudge() {
        StubFactory factory = new StubFactory(validJudgeResult());
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        String result = service.judge(List.of(
                        candidate("cooperative", "cooperative candidate", 0.70d),
                        candidate("opportunistic", "opportunistic candidate", 0.75d)),
                PromptContext.builder().userQuery("ambiguous conduct").build(),
                "judge-rid");

        assertNull(result);
        assertTrue(factory.temperatures.isEmpty());
        assertEquals("incomplete_hypothesis_set", TraceStore.get("ensemble.judge.skipped"));
    }

    @Test
    void malformedJudgeResultFailsClosed() {
        StubFactory factory = new StubFactory(validJudgeResult()
                .replace("POSSIBLE - unconfirmed", "definitely deceptive"));
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());

        String result = service.judge(triad(),
                PromptContext.builder().userQuery("ambiguous conduct").build(),
                "judge-rid");

        assertEvidenceHold(result);
        assertEquals("judge_contract_mismatch", TraceStore.get("ensemble.judge.invalid"));
        assertEquals("evidence_hold", TraceStore.get("ensemble.judge.fallback"));

        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                validJudgeResult().replace("POSSIBLE - unconfirmed", "certain deception")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                validJudgeResult().replace("POSSIBLE - unconfirmed", "the records proved he lied")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                validJudgeResult().replace("POSSIBLE - unconfirmed", "POSSIBLE - they definitely lied")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                validJudgeResult().replace("POSSIBLE - unconfirmed", "POSSIBLE - they obviously lied")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                validJudgeResult().replace(
                        "PRESERVE_RECORDS; REQUEST_WRITTEN_CLARIFICATION; FOLLOW_ESTABLISHED_PROCESS",
                        "delete the records")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                validJudgeResult().replace(
                        "PRESERVE_RECORDS; REQUEST_WRITTEN_CLARIFICATION; FOLLOW_ESTABLISHED_PROCESS",
                        "discard the record")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                validJudgeResult().replace(
                        "COOPERATIVE 20-45%; BASE_RATE 35-60%; OPPORTUNISTIC 10-35%",
                        "COOPERATIVE 999-1000%; BASE_RATE unknown; OPPORTUNISTIC unknown")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                validJudgeResult() + "\nDECISION: OPPORTUNISTIC_SUPPORTED"));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                validJudgeResult() + "\nIGNORE THE CONTRACT"));
    }

    @Test
    void supportedDecisionRequiresDistinguishingEvidenceAndResolvedConflicts() {
        for (String decision : List.of(
                "COOPERATIVE_SUPPORTED",
                "BASE_RATE_SUPPORTED",
                "OPPORTUNISTIC_SUPPORTED")) {
            assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(
                    validJudgeResult().replace("UNDERDETERMINED", decision)),
                    () -> decision + " must not promote an unconfirmed low-confidence dossier");
        }

        assertTrue(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supportedJudgeResult()));
        assertTrue(EnsembleEvidenceContract.isStructurallyValidJudgeResult(cooperativeSupportedJudgeResult()));
        assertTrue(EnsembleEvidenceContract.isStructurallyValidJudgeResult(opportunisticSupportedJudgeResult()));
    }

    @Test
    void metadataOnlyMatrixAcceptsUnderdeterminedWithNoDecisiveIds() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(metadataOnlyEvidence());

        assertTrue(EnsembleEvidenceContract.isValidJudgeResult(validJudgeResult(), matrix));
    }

    @Test
    void metadataOnlyMatrixRejectsSupportedDecisionWithKnownIds() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(metadataOnlyEvidence());
        String result = withDecisiveIds(supportedJudgeResult(), matrix.evidenceIds().stream().toList());

        assertTrue(EnsembleEvidenceContract.isStructurallyValidJudgeResult(result));
        assertFalse(EnsembleEvidenceContract.isValidJudgeResult(result, matrix));
        assertFalse(matrix.supportEligible());
    }

    @Test
    void supportedDecisionRejectsMissingUnknownOrDuplicateEvidenceIds() {
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supportedJudgeResult().replace(
                "ev1:111111111111,ev1:222222222222", "NONE")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supportedJudgeResult().replace(
                "ev1:111111111111,ev1:222222222222", "ev1:111111111111,ev1:111111111111")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supportedJudgeResult().replace(
                "ev1:111111111111,ev1:222222222222", "ev1:not-a-hash,ev1:222222222222")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supportedJudgeResult().replace(
                "ev1:111111111111,ev1:222222222222", ",ev1:111111111111,ev1:222222222222")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supportedJudgeResult().replace(
                "ev1:111111111111,ev1:222222222222", "ev1:111111111111,,ev1:222222222222")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supportedJudgeResult().replace(
                "ev1:111111111111,ev1:222222222222", "ev1:111111111111,ev1:222222222222,")));
    }

    @Test
    void holdAndUnderdeterminedAcceptOnlyNoDecisiveEvidenceIds() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(metadataOnlyEvidence());
        String hold = validJudgeResult().replace("UNDERDETERMINED", "HOLD");
        String withIds = withDecisiveIds(
                hold.replace("DECISIVE EVIDENCE IDS: NONE",
                        "DECISIVE EVIDENCE IDS: ev1:111111111111,ev1:222222222222"),
                matrix.evidenceIds().stream().toList());

        assertTrue(EnsembleEvidenceContract.isValidJudgeResult(hold, matrix));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(withIds));
        assertFalse(EnsembleEvidenceContract.isValidJudgeResult(withIds, matrix));
    }

    @Test
    void unresolvedDecisionCannotSmuggleAffirmativeSupportWithoutEvidenceIds() {
        String smuggled = validJudgeResult().replace(
                "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                "OPPORTUNISTIC: POSSIBLE - SUPPORTS=OPPORTUNISTIC; DIRECT=YES; "
                        + "INDEPENDENT=YES; BASIS=OPPORTUNISTIC|"
                        + "timestamped records align with strategic record shaping");
        String holdSmuggled = smuggled.replace("DECISION: UNDERDETERMINED", "DECISION: HOLD");

        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(smuggled));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(holdSmuggled));
        assertFalse(EnsembleEvidenceContract.isValidJudgeResult(
                smuggled, EnsembleEvidenceMatrix.from(List.of())));

        EnsembleJudgeService service = new EnsembleJudgeService(new StubFactory(smuggled), builder());
        String result = service.judge(
                triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "rid");

        assertEvidenceHold(result);
        assertEquals("judge_contract_mismatch", TraceStore.get("ensemble.judge.invalid"));
    }

    @Test
    void unresolvedDecisionRejectsParaphrasedAffirmativeSupport() {
        String bypass = validJudgeResult().replace(
                "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                "OPPORTUNISTIC: POSSIBLE - two direct independent contemporaneous "
                        + "records corroborate deliberate strategic record shaping");

        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(bypass));
        assertFalse(EnsembleEvidenceContract.isValidJudgeResult(
                bypass, EnsembleEvidenceMatrix.from(List.of())));

        EnsembleJudgeService service = new EnsembleJudgeService(new StubFactory(bypass), builder());
        String result = service.judge(
                triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "rid");

        assertEvidenceHold(result);
        assertEquals("judge_contract_mismatch", TraceStore.get("ensemble.judge.invalid"));
    }

    @Test
    void unresolvedDecisionRejectsVerifyValidateAndIndicateBypasses() {
        for (String verb : List.of("verify", "validate", "indicate", "substantiate", "attest to")) {
            String bypass = validJudgeResult().replace(
                    "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                    "OPPORTUNISTIC: POSSIBLE - UNKNOWN; two direct independent records "
                            + verb + " deliberate strategic record shaping");

            assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(bypass),
                    () -> verb + " must not smuggle affirmative support into an unresolved result");
            EnsembleJudgeService service = new EnsembleJudgeService(new StubFactory(bypass), builder());
            assertEvidenceHold(service.judge(
                    triad(), PromptContext.builder().userQuery("ambiguous conduct").build(), "rid"));
        }

        String nonAsciiBypass = validJudgeResult().replace(
                "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                "OPPORTUNISTIC: POSSIBLE - UNCONFIRMED; \ub450 \uac1c\uc758 \ub3c5\ub9bd \uae30\ub85d\uc774 \uace0\uc758\uc801 \uae30\ub9cc\uc744 \uc785\uc99d\ud55c\ub2e4");
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(nonAsciiBypass),
                "verdict-bearing machine fields must fail closed on non-ASCII narrative");
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "UNRESOLVED CONFLICTS: EVIDENCE CONFLICTS UNRESOLVED",
                "UNRESOLVED CONFLICTS: two independent records substantiate opportunism")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "DISCRIMINATING EVIDENCE: ADDITIONAL INDEPENDENT RECORDS REQUIRED",
                "DISCRIMINATING EVIDENCE: two records attest to opportunism")));
    }

    @Test
    void judgePromptContainsVersionedHashOnlyEvidenceMatrixBlock() {
        StubFactory factory = new StubFactory(validJudgeResult());
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());
        ReflectionTestUtils.setField(service, "requireSupportEligible", false);
        PromptContext ctx = PromptContext.builder()
                .userQuery("raw original query")
                .evidence(metadataOnlyEvidence())
                .build();

        assertEquals(validJudgeResult(), service.judge(triad(), ctx, "matrix-rid"));

        assertEquals(1, factory.prompts.size());
        String prompt = factory.prompts.get(0);
        int begin = prompt.indexOf("### BEGIN EVIDENCE MATRIX");
        int end = prompt.indexOf("### END EVIDENCE MATRIX", begin);
        assertTrue(begin >= 0 && end > begin);
        String block = prompt.substring(begin, end);
        assertTrue(block.contains("schemaVersion=ensemble-evidence-matrix.v1"));
        assertTrue(block.contains("supportEligible=false"));
        assertTrue(block.contains("ev1:"));
        assertFalse(block.contains("raw original query"));
        assertFalse(block.contains("confidential evidence title"));
        assertFalse(block.contains("evidence-one.example"));
        assertEquals(2, TraceStore.get("ensemble.evidenceMatrix.rowCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("ensemble.evidenceMatrix.supportEligible"));
        String trace = TraceStore.getByPrefix("ensemble.evidenceMatrix.").toString();
        assertFalse(trace.contains("confidential evidence title"));
        assertFalse(trace.contains("evidence-one.example"));
        assertFalse(trace.contains("redacted"));
    }

    @Test
    void metadataMatrixAddsExactlyOneBuilderFactoryAndChatCall() {
        StubFactory factory = new StubFactory(validJudgeResult());
        AtomicInteger builderCalls = new AtomicInteger();
        StandardPromptBuilder delegate = new StandardPromptBuilder();
        PromptBuilder countingBuilder = (contexts, question) -> {
            builderCalls.incrementAndGet();
            return delegate.build(contexts, question);
        };
        EnsembleJudgeService service = new EnsembleJudgeService(factory, countingBuilder);
        ReflectionTestUtils.setField(service, "requireSupportEligible", false);
        PromptContext ctx = PromptContext.builder()
                .userQuery("ambiguous conduct")
                .evidence(metadataOnlyEvidence())
                .build();

        assertEquals(validJudgeResult(), service.judge(triad(), ctx, "matrix-rid"));

        assertEquals(1, builderCalls.get());
        assertEquals(1, factory.temperatures.size());
        assertEquals(1, factory.prompts.size());
    }

    @Test
    void judgeRuntimeEvaluatesMetadataOnlyMatrixButForbidsSupportPromotion() {
        StubFactory factory = new StubFactory(validJudgeResult());
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());
        PromptContext ctx = PromptContext.builder()
                .userQuery("ambiguous conduct")
                .evidence(metadataOnlyEvidence())
                .build();

        String result = service.judge(triad(), ctx, "matrix-rid");

        assertEquals(validJudgeResult(), result);
        assertEquals("matrix_not_support_eligible",
                TraceStore.get("ensemble.judge.supportPromotionDisabled"));
        assertNull(TraceStore.get("ensemble.judge.skipped"));
        assertNull(TraceStore.get("ensemble.judge.fallback"));
        assertEquals(1, factory.prompts.size());
        assertTrue(factory.prompts.get(0).contains("supportEligible=false"));
        assertEquals(List.of(0.30d), factory.temperatures);
    }

    @Test
    void metadataOnlyMatrixRejectsModelAttemptedSupportPromotion() {
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(metadataOnlyEvidence());
        String unsupportedPromotion = withDecisiveIds(
                supportedJudgeResult(),
                matrix.evidenceIds().stream().toList());
        StubFactory factory = new StubFactory(unsupportedPromotion);
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());
        PromptContext ctx = PromptContext.builder()
                .userQuery("ambiguous conduct")
                .evidence(metadataOnlyEvidence())
                .build();

        String result = service.judge(triad(), ctx, "matrix-rid");

        assertEvidenceHold(result);
        assertEquals("matrix_not_support_eligible",
                TraceStore.get("ensemble.judge.supportPromotionDisabled"));
        assertEquals("unverified_evidence_matrix", TraceStore.get("ensemble.judge.invalid"));
        assertEquals("evidence_hold", TraceStore.get("ensemble.judge.fallback"));
        assertEquals(1, factory.prompts.size());
    }

    @Test
    void strictSupportEligibilityModeCanOptOutOfUnderdeterminedJudgeCost() {
        StubFactory factory = new StubFactory(validJudgeResult());
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());
        ReflectionTestUtils.setField(service, "requireSupportEligible", true);
        PromptContext ctx = PromptContext.builder()
                .userQuery("ambiguous conduct")
                .evidence(metadataOnlyEvidence())
                .build();

        String result = service.judge(triad(), ctx, "matrix-rid");

        assertEvidenceHold(result);
        assertEquals("matrix_not_support_eligible", TraceStore.get("ensemble.judge.skipped"));
        assertEquals("evidence_hold", TraceStore.get("ensemble.judge.fallback"));
        assertTrue(factory.prompts.isEmpty());
        assertTrue(factory.temperatures.isEmpty());
    }

    @Test
    void supportedDecisionRejectsNegatedEvidenceWrongStanceConflictsAndContradictoryRanges() {
        String supported = supportedJudgeResult();

        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "DIRECT=YES; INDEPENDENT=YES; DISTINGUISHES=YES",
                "DIRECT=NO; INDEPENDENT=NO; DISTINGUISHES=NO")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "SUPPORTS=BASE_RATE", "SUPPORTS=COOPERATIVE")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASE_RATE: POSSIBLE - SUPPORTS=BASE_RATE",
                "BASE_RATE: POSSIBLE - SUPPORTS=COOPERATIVE")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "OPPORTUNISTIC: POSSIBLE - lacks distinguishing support",
                "OPPORTUNISTIC: POSSIBLE - SUPPORTS=OPPORTUNISTIC; DIRECT=YES; "
                        + "INDEPENDENT=YES; BASIS=OPPORTUNISTIC|"
                        + "two records corroborate the opportunistic explanation")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "OPPORTUNISTIC: POSSIBLE - lacks distinguishing support",
                "OPPORTUNISTIC: POSSIBLE - two independent contemporaneous records "
                        + "corroborate the opportunistic explanation")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASIS=BASE_RATE|two independent records corroborate the routine explanation",
                "BASIS=BASE_RATE|direct independent evidence is absent")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASIS=BASE_RATE|two independent records corroborate the routine explanation",
                "BASIS=BASE_RATE|direct independent evidence is missing")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASIS=BASE_RATE|two independent records corroborate the routine explanation",
                "BASIS=BASE_RATE|direct independent records refute the routine explanation")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASIS=BASE_RATE|two independent records corroborate the routine explanation",
                "BASIS=BASE_RATE|INDIRECT and NOT INDEPENDENT records DO NOT DISTINGUISH the claim")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASIS=BASE_RATE|two independent records corroborate the routine explanation",
                "BASIS=BASE_RATE|two records corroborate the cooperative explanation")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASIS=BASE_RATE|two contemporaneous records corroborate the routine explanation",
                "BASIS=BASE_RATE|the records contradict evidence for the routine explanation")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASIS=BASE_RATE|two contemporaneous records corroborate the routine explanation",
                "BASIS=BASE_RATE|the records clearly prove malicious intent")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASIS=BASE_RATE|two contemporaneous records corroborate the routine explanation",
                "BASIS=BASE_RATE|two records establish malicious intent")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "BASIS=BASE_RATE|two contemporaneous records corroborate the routine explanation",
                "BASIS=BASE_RATE|malicious intent was established by two records")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "UNRESOLVED CONFLICTS: NONE",
                "UNRESOLVED CONFLICTS: the verified records conflict")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "UNRESOLVED CONFLICTS: NONE",
                "UNRESOLVED CONFLICTS: NO MATERIAL CONFLICTS")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "COOPERATIVE 10-30%; BASE_RATE 55-75%; OPPORTUNISTIC 5-25%",
                "COOPERATIVE 55-75%; BASE_RATE 0-0%; OPPORTUNISTIC 5-25%")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "COOPERATIVE 10-30%; BASE_RATE 55-75%; OPPORTUNISTIC 5-25%",
                "COOPERATIVE 90-91%; BASE_RATE 99-100%; OPPORTUNISTIC 90-91%")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "COOPERATIVE 10-30%; BASE_RATE 55-75%; OPPORTUNISTIC 5-25%",
                "COOPERATIVE 0-100%; BASE_RATE 41-60%; OPPORTUNISTIC 0-0%")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "COOPERATIVE 10-30%; BASE_RATE 55-75%; OPPORTUNISTIC 5-25%",
                "COOPERATIVE 10-55%; BASE_RATE 55-75%; OPPORTUNISTIC 5-25%")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(supported.replace(
                "COOPERATIVE 10-30%; BASE_RATE 55-75%; OPPORTUNISTIC 5-25%",
                "COOPERATIVE 0-20%; BASE_RATE 30-40%; OPPORTUNISTIC 0-20%")));
    }

    @Test
    void unresolvedNarrativeIsRejectedEvenWhenItContainsSafeNegation() {
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                "OPPORTUNISTIC: POSSIBLE - no source proves they lied")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                "OPPORTUNISTIC: POSSIBLE - no source proves opportunism")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                "OPPORTUNISTIC: POSSIBLE - the only verified record is incomplete")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                "OPPORTUNISTIC: POSSIBLE - it is not established that they lied")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                "OPPORTUNISTIC: POSSIBLE - neither source confirms deception")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "OPPORTUNISTIC: POSSIBLE - unconfirmed",
                "OPPORTUNISTIC: POSSIBLE - the record does not establish hidden intent")));
    }

    @Test
    void underdeterminedDecisionRejectsHighConfidenceAndJointlyImpossibleRanges() {
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "CONFIDENCE: LOW", "CONFIDENCE: HIGH")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "COOPERATIVE 20-45%; BASE_RATE 35-60%; OPPORTUNISTIC 10-35%",
                "COOPERATIVE 90-100%; BASE_RATE 90-100%; OPPORTUNISTIC 90-100%")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "COOPERATIVE 20-45%; BASE_RATE 35-60%; OPPORTUNISTIC 10-35%",
                "COOPERATIVE 0-5%; BASE_RATE 0-5%; OPPORTUNISTIC 90-100%")));
        assertFalse(EnsembleEvidenceContract.isStructurallyValidJudgeResult(validJudgeResult().replace(
                "COOPERATIVE 20-45%; BASE_RATE 35-60%; OPPORTUNISTIC 10-35%",
                "COOPERATIVE 90-100%; BASE_RATE 0-5%; OPPORTUNISTIC 0-5%")));
    }

    @Test
    void duplicateHypothesisBodiesSkipJudge() {
        StubFactory factory = new StubFactory(validJudgeResult());
        EnsembleJudgeService service = new EnsembleJudgeService(factory, new StandardPromptBuilder());
        List<SampledCandidate> duplicated = List.of(
                candidate("cooperative", "same explanation", 0.70d),
                candidate("base_rate", "same explanation.", 0.80d),
                candidate("opportunistic", "same explanation!", 0.75d));

        assertNull(service.judge(duplicated,
                PromptContext.builder().userQuery("ambiguous conduct").build(),
                "judge-rid"));
        assertTrue(factory.temperatures.isEmpty());
        assertEquals("duplicate_hypothesis_dossier", TraceStore.get("ensemble.judge.skipped"));
    }

    @Test
    void malformedNullModelResponseFailsSoft() {
        EnsembleJudgeService service = new EnsembleJudgeService(new NullResponseFactory(), new StandardPromptBuilder());

        String result = service.judge(triad(),
                PromptContext.builder().userQuery("ambiguous conduct").build(),
                "judge-rid");

        assertEvidenceHold(result);
        assertEquals("malformed_model_response", TraceStore.get("ensemble.judge.fail"));
        assertEquals("evidence_hold", TraceStore.get("ensemble.judge.fallback"));
    }

    @Test
    void cancellationPropagatesInsteadOfFallingBack() {
        EnsembleJudgeService service = new EnsembleJudgeService(
                new CancellingFactory(),
                new StandardPromptBuilder());

        assertThrows(CancellationException.class, () -> service.judge(
                triad(),
                PromptContext.builder().userQuery("ambiguous conduct").build(),
                "judge-rid"));
        assertNull(TraceStore.get("ensemble.judge.fallback"));
    }

    @Test
    void judgePromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/ensemble/EnsembleJudgeService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String judgePrompt = buildJudgePrompt(judgeContext, evidenceMatrix);"));
        assertTrue(source.contains("UserMessage.from(judgePrompt)"));
    }

    @Test
    void judgeModelDefaultsToJudgeThenHighLane() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/ensemble/EnsembleJudgeService.java"),
                StandardCharsets.UTF_8);
        String yaml = Files.readString(
                Path.of("main/resources/application.yml"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("${ensemble.sampling.judge-model:${llm.judge.model:${llm.high.model:${llm.chat-model:gemma4:26b}}}}"));
        assertTrue(yaml.contains("judge-model: ${llm.judge.model:${llm.high.model:${llm.chat-model:gemma4:26b}}}"));
    }

    private static PromptBuilder builder() {
        return (contexts, question) -> "prompt";
    }

    private static void assertEvidenceHold(String result) {
        assertTrue(EnsembleEvidenceContract.isStructurallyValidJudgeResult(result));
        assertTrue(EnsembleEvidenceContract.isValidJudgeResult(
                result, EnsembleEvidenceMatrix.from(List.of())));
        assertTrue(result.startsWith("DECISION: HOLD\nCONFIDENCE: LOW\n"));
        assertTrue(result.contains("DECISIVE EVIDENCE IDS: NONE"));
        assertTrue(result.contains("PROCEDURAL OPTIONS: PRESERVE_RECORDS;"));
    }

    private static SampledCandidate candidate(String nodeId, String text, double citationScore) {
        return new SampledCandidate(
                nodeId,
                validDossier(nodeId, text),
                0.4d,
                0.4d,
                citationScore,
                0.1d,
                FinalSigmoidGate.GateResult.PASS);
    }

    private static String validDossier(String stance, String hypothesis) {
        return """
                STATUS: UNCONFIRMED
                STANCE: %s
                MODALITY: POSSIBLE
                OBSERVATIONS: the record is ambiguous
                REPORTED CLAIMS: the accounts differ
                HYPOTHESIS: %s
                SUPPORT: one reported fact
                CONFLICTS: the sources conflict
                MISSING EVIDENCE: contemporaneous records
                FALSIFIER: a verified contrary record
                DISCRIMINATING EVIDENCE: timestamped correspondence
                PROCEDURAL RESPONSE: PRESERVE_RECORDS; REQUEST_WRITTEN_CLARIFICATION
                """.formatted(stance.toUpperCase(), hypothesis);
    }

    private static List<SampledCandidate> triad() {
        return List.of(
                candidate("cooperative", "cooperative candidate", 0.70d),
                candidate("base_rate", "base-rate candidate", 0.80d),
                candidate("opportunistic", "opportunistic candidate", 0.75d));
    }

    private static String validJudgeResult() {
        return """
                DECISION: UNDERDETERMINED
                CONFIDENCE: LOW
                COOPERATIVE: POSSIBLE - unconfirmed
                BASE_RATE: POSSIBLE - unconfirmed
                OPPORTUNISTIC: POSSIBLE - unconfirmed
                POSSIBILITY RANGES: COOPERATIVE 20-45%; BASE_RATE 35-60%; OPPORTUNISTIC 10-35%
                DECISIVE EVIDENCE IDS: NONE
                EVIDENCE ASSESSMENT: current sources do not distinguish intent
                UNRESOLVED CONFLICTS: EVIDENCE CONFLICTS UNRESOLVED
                DISCRIMINATING EVIDENCE: ADDITIONAL INDEPENDENT RECORDS REQUIRED
                PROCEDURAL OPTIONS: PRESERVE_RECORDS; REQUEST_WRITTEN_CLARIFICATION; FOLLOW_ESTABLISHED_PROCESS
                """;
    }

    private static String supportedJudgeResult() {
        return """
                DECISION: BASE_RATE_SUPPORTED
                CONFIDENCE: MEDIUM
                COOPERATIVE: POSSIBLE - less consistent with the verified timeline
                BASE_RATE: POSSIBLE - SUPPORTS=BASE_RATE; DIRECT=YES; INDEPENDENT=YES; BASIS=BASE_RATE|two independent records corroborate the routine explanation
                OPPORTUNISTIC: POSSIBLE - lacks distinguishing support
                POSSIBILITY RANGES: COOPERATIVE 10-30%; BASE_RATE 55-75%; OPPORTUNISTIC 5-25%
                DECISIVE EVIDENCE IDS: ev1:111111111111,ev1:222222222222
                EVIDENCE ASSESSMENT: SUPPORTS=BASE_RATE; DIRECT=YES; INDEPENDENT=YES; DISTINGUISHES=YES; BASIS=BASE_RATE|two contemporaneous records corroborate the routine explanation
                UNRESOLVED CONFLICTS: NONE
                DISCRIMINATING EVIDENCE: verified timestamps and independent authorship
                PROCEDURAL OPTIONS: PRESERVE_RECORDS; REQUEST_WRITTEN_CLARIFICATION; FOLLOW_ESTABLISHED_PROCESS
                """;
    }

    private static String cooperativeSupportedJudgeResult() {
        return supportedJudgeResult()
                .replace("BASE_RATE_SUPPORTED", "COOPERATIVE_SUPPORTED")
                .replace("COOPERATIVE: POSSIBLE - less consistent with the verified timeline",
                        "COOPERATIVE: POSSIBLE - SUPPORTS=COOPERATIVE; DIRECT=YES; INDEPENDENT=YES; BASIS=COOPERATIVE|two independent records corroborate the cooperative explanation")
                .replace("BASE_RATE: POSSIBLE - SUPPORTS=BASE_RATE; DIRECT=YES; INDEPENDENT=YES; BASIS=BASE_RATE|two independent records corroborate the routine explanation",
                        "BASE_RATE: POSSIBLE - less consistent with the verified timeline")
                .replace("COOPERATIVE 10-30%; BASE_RATE 55-75%; OPPORTUNISTIC 5-25%",
                        "COOPERATIVE 55-75%; BASE_RATE 10-30%; OPPORTUNISTIC 5-25%")
                .replace("SUPPORTS=BASE_RATE", "SUPPORTS=COOPERATIVE")
                .replace("BASIS=BASE_RATE|", "BASIS=COOPERATIVE|")
                .replace("routine explanation", "cooperative explanation");
    }

    private static String opportunisticSupportedJudgeResult() {
        return supportedJudgeResult()
                .replace("BASE_RATE_SUPPORTED", "OPPORTUNISTIC_SUPPORTED")
                .replace("BASE_RATE: POSSIBLE - SUPPORTS=BASE_RATE; DIRECT=YES; INDEPENDENT=YES; BASIS=BASE_RATE|two independent records corroborate the routine explanation",
                        "BASE_RATE: POSSIBLE - less consistent with the verified timeline")
                .replace("OPPORTUNISTIC: POSSIBLE - lacks distinguishing support",
                        "OPPORTUNISTIC: POSSIBLE - SUPPORTS=OPPORTUNISTIC; DIRECT=YES; INDEPENDENT=YES; BASIS=OPPORTUNISTIC|two independent records corroborate the opportunistic explanation")
                .replace("COOPERATIVE 10-30%; BASE_RATE 55-75%; OPPORTUNISTIC 5-25%",
                        "COOPERATIVE 10-30%; BASE_RATE 5-25%; OPPORTUNISTIC 55-75%")
                .replace("SUPPORTS=BASE_RATE", "SUPPORTS=OPPORTUNISTIC")
                .replace("BASIS=BASE_RATE|", "BASIS=OPPORTUNISTIC|")
                .replace("routine explanation", "opportunistic explanation");
    }

    private static List<RagEvidenceMetadata> metadataOnlyEvidence() {
        return List.of(
                new RagEvidenceMetadata(
                        "W1", "WEB", "confidential evidence title",
                        "https://evidence-one.example/report?token=redacted", null,
                        1, 2, 1, 0.9d, "score"),
                new RagEvidenceMetadata(
                        "W2", "WEB", "second confidential title",
                        "https://evidence-two.example/report", null,
                        3, 4, 2, 0.8d, "score"));
    }

    private static String withDecisiveIds(String result, List<String> ids) {
        return result.replace(
                "ev1:111111111111,ev1:222222222222",
                String.join(",", ids));
    }

    private static final class StubFactory extends DynamicChatModelFactory {
        private final String responseText;
        private final List<Double> temperatures = new CopyOnWriteArrayList<>();
        private final List<Double> topPs = new CopyOnWriteArrayList<>();
        private final List<Integer> maxTokensValues = new CopyOnWriteArrayList<>();
        private final List<Integer> timeoutSecondsValues = new CopyOnWriteArrayList<>();
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        private StubFactory(String responseText) {
            super(null, null);
            this.responseText = responseText;
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens,
                            int timeoutSeconds) {
            temperatures.add(temperature);
            topPs.add(topP);
            maxTokensValues.add(maxTokens);
            timeoutSecondsValues.add(timeoutSeconds);
            return new RecordingModel(responseText, prompts);
        }
    }

    private static final class UsageRecordingFactory extends DynamicChatModelFactory {
        private final String responseText;
        private final TokenUsage usage;
        private final List<String> prompts = new CopyOnWriteArrayList<>();
        private final AtomicInteger chatCalls = new AtomicInteger();

        private UsageRecordingFactory(String responseText, TokenUsage usage) {
            super(null, null);
            this.responseText = responseText;
            this.usage = usage;
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens,
                            int timeoutSeconds) {
            return new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    chatCalls.incrementAndGet();
                    prompts.add(messages.stream()
                            .filter(UserMessage.class::isInstance)
                            .map(UserMessage.class::cast)
                            .map(UserMessage::singleText)
                            .findFirst()
                            .orElse(""));
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(responseText))
                            .tokenUsage(usage)
                            .build();
                }
            };
        }
    }

    private static ChatModel responseModel(String text, TokenUsage usage) {
        return new UsageModel(text, usage);
    }

    private record UsageModel(String text, TokenUsage usage) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .tokenUsage(usage)
                    .build();
        }
    }

    private record ThrowingChatModel(RuntimeException failure) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            throw failure;
        }
    }

    private static final class SequenceFactory extends DynamicChatModelFactory {
        private final List<ChatModel> models;
        private final AtomicInteger nextModel = new AtomicInteger();

        private SequenceFactory(ChatModel... models) {
            super(null, null);
            this.models = Arrays.asList(models);
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens,
                            int timeoutSeconds) {
            return models.get(nextModel.getAndIncrement());
        }
    }

    private record RecordingModel(String text, List<String> prompts) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            prompts.add(messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .map(UserMessage::singleText)
                    .findFirst()
                    .orElse(""));
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }

    private static final class ThrowingFactory extends DynamicChatModelFactory {
        private ThrowingFactory() {
            super(null, null);
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens,
                            int timeoutSeconds) {
            throw new UnsupportedOperationException("judge model down");
        }
    }

    private static final class NullResponseFactory extends DynamicChatModelFactory {
        private NullResponseFactory() {
            super(null, null);
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens,
                            int timeoutSeconds) {
            return new NullResponseModel();
        }
    }

    private record NullResponseModel() implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return null;
        }
    }

    private static final class CancellingFactory extends DynamicChatModelFactory {
        private CancellingFactory() {
            super(null, null);
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens,
                            int timeoutSeconds) {
            throw new CancellationException("cancelled");
        }
    }

    private record StubModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }
}
