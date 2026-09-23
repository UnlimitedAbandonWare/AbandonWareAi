package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.ensemble.EnsembleFinalAnswerService;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.learning.gemini.LearningWriteInterceptor;
import com.example.lms.nlp.QueryDomainClassifier;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.RagEvidenceAttributionService;
import com.example.lms.service.rag.detector.UniversalDomainDetector;
import com.example.lms.service.rag.handler.MemoryHandler;
import com.example.lms.service.rag.handler.MemoryWriteInterceptor;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.rag.plan.PlanModelResolver;
import com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor;
import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.strategy.DomainStrategyFactory;
import com.example.lms.service.subject.SubjectAnalysis;
import com.example.lms.service.subject.SubjectCategory;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.service.postprocess.FinalAnswerPostProcessor;
import com.example.lms.service.postprocess.OutputSanitizer;
import com.example.lms.service.verbosity.SectionSpecGenerator;
import com.example.lms.service.verbosity.VerbosityDetector;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatWorkflowFinalVerificationReleaseGateTest {

    @Test
    void verificationNotRequiredPreservesDirectResponse() {
        ChatWorkflow.FinalVerificationReleaseDecision decision = decide(
                "direct answer", false, "not_run", false, false);

        assertEquals("direct answer", decision.content());
        assertEquals("NOT_REQUIRED", decision.releaseStatus());
        assertEquals("verification_not_required", decision.reasonCode());
        assertTrue(decision.releaseAllowed());
    }

    @Test
    void knownAcceptedPassAndCorrectionReleaseVerifiedContent() {
        for (String status : new String[]{"pass", "corrected"}) {
            ChatWorkflow.FinalVerificationReleaseDecision decision = decide(
                    "verified answer", true, status, true, true);

            assertEquals("verified answer", decision.content(), status);
            assertEquals("APPROVE", decision.releaseStatus(), status);
            assertEquals("verification_accepted", decision.reasonCode(), status);
            assertTrue(decision.releaseAllowed(), status);
        }
    }

    @Test
    void unknownOutcomeCannotReleaseOriginalDraft() {
        ChatWorkflow.FinalVerificationReleaseDecision decision = decide(
                "unsupported draft", true, "unknown", false, false);

        assertEquals(
                "evidence_needed: final verification outcome unknown / retry with verifiable evidence",
                decision.content());
        assertFalse(decision.content().contains("unsupported draft"));
        assertEquals("HOLD", decision.releaseStatus());
        assertEquals("verification_outcome_unknown", decision.reasonCode());
        assertFalse(decision.releaseAllowed());
    }

    @Test
    void insufficientEvidenceCannotReleaseOriginalDraft() {
        ChatWorkflow.FinalVerificationReleaseDecision decision = decide(
                "unsupported draft", true, "insufficient", true, false);

        assertEquals(
                "evidence_needed: final verification found insufficient evidence / retry with verifiable evidence",
                decision.content());
        assertFalse(decision.content().contains("unsupported draft"));
        assertEquals("HOLD", decision.releaseStatus());
        assertEquals("verification_insufficient", decision.reasonCode());
        assertFalse(decision.releaseAllowed());
    }

    @Test
    void rejectedVerificationCannotReleaseOriginalDraft() {
        ChatWorkflow.FinalVerificationReleaseDecision decision = decide(
                "unsupported draft", true, "rejected", true, false);

        assertEquals(
                "Information unavailable: final verification rejected the draft.",
                decision.content());
        assertFalse(decision.content().contains("unsupported draft"));
        assertEquals("REJECT", decision.releaseStatus());
        assertEquals("verification_rejected", decision.reasonCode());
        assertFalse(decision.releaseAllowed());
    }

    @Test
    void inconsistentPositiveTelemetryFailsClosed() {
        ChatWorkflow.FinalVerificationReleaseDecision decision = decide(
                "unsupported draft", true, "pass", true, false);

        assertEquals(
                "evidence_needed: final verification state inconsistent / retry with verifiable evidence",
                decision.content());
        assertFalse(decision.content().contains("unsupported draft"));
        assertEquals("HOLD", decision.releaseStatus());
        assertEquals("verification_state_inconsistent", decision.reasonCode());
        assertFalse(decision.releaseAllowed());
    }

    @Test
    void confirmedEmptyWithPositiveLocatorLineageHonorsExplicitDirective() {
        RagEvidenceAttributionService.PromotionResult promotion = completedEmpty(1, 1, 0, 0);
        ChatWorkflow.RetrievalReleaseContract contract = new ChatWorkflow.RetrievalReleaseContract(
                true, true, false, true, false, false);

        ChatWorkflow.EvidenceReleaseState state = ChatWorkflow.deriveEvidenceReleaseState(
                promotion, List.of(), false, contract);
        ChatWorkflow.FinalVerificationReleaseDecision decision = ChatWorkflow.applyEvidenceReleasePolicy(
                decide("unsupported draft", false, "not_run", false, false),
                state,
                true,
                false,
                false);

        assertEquals(ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY, state);
        assertEquals("evidence_needed", decision.content());
        assertEquals("HOLD", decision.releaseStatus());
        assertEquals("evidence_required_empty", decision.reasonCode());
        assertFalse(decision.releaseAllowed());
        assertTrue(decision.evidencePolicyApplied());
    }

    @Test
    void zeroLocatorFailureUnavailableDisabledAndLateEvidenceFailClosedAsIncomplete() {
        ChatWorkflow.RetrievalReleaseContract enabledWeb = new ChatWorkflow.RetrievalReleaseContract(
                true, true, false, true, false, false);
        ChatWorkflow.RetrievalReleaseContract disabledWeb = new ChatWorkflow.RetrievalReleaseContract(
                true, true, false, false, false, false);
        List<ChatWorkflow.EvidenceReleaseState> states = List.of(
                ChatWorkflow.deriveEvidenceReleaseState(completedEmpty(1, 0, 0, 0), List.of(), false, enabledWeb),
                ChatWorkflow.deriveEvidenceReleaseState(failed(), List.of(), false, enabledWeb),
                ChatWorkflow.deriveEvidenceReleaseState(
                        RagEvidenceAttributionService.PromotionResult.unavailable(),
                        List.of(), false, enabledWeb),
                ChatWorkflow.deriveEvidenceReleaseState(completedEmpty(1, 1, 0, 0), List.of(), false, disabledWeb),
                ChatWorkflow.deriveEvidenceReleaseState(completedEmpty(1, 1, 0, 0), List.of(), true, enabledWeb));

        for (ChatWorkflow.EvidenceReleaseState state : states) {
            ChatWorkflow.FinalVerificationReleaseDecision decision = ChatWorkflow.applyEvidenceReleasePolicy(
                    decide("unsupported draft", false, "not_run", false, false),
                    state,
                    false,
                    false,
                    false);
            assertEquals(ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE, state);
            assertEquals("evidence_needed: attribution unavailable / verify retrieval evidence", decision.content());
            assertFalse(decision.content().contains("unsupported draft"));
            assertFalse(decision.releaseAllowed());
            assertTrue(decision.evidencePolicyApplied());
        }
    }

    @Test
    void requestedLanesMustBeRepresentedByFinalTypedEvidence() {
        RagEvidenceMetadata web = evidence("W1", "WEB", "https://example.com/web", null);
        RagEvidenceMetadata vector = evidence("V1", "VECTOR", null, "docs/vector.md");
        RagEvidenceMetadata local = evidence("D1", "LOCAL_DOC", null, "docs/local.md");
        RagEvidenceAttributionService.PromotionResult promotion = promoted(
                List.of(web, vector, local), 1, 1, 1, 1, 1, 1);

        assertEquals(ChatWorkflow.EvidenceReleaseState.EVIDENCE_PRESENT,
                ChatWorkflow.deriveEvidenceReleaseState(
                        promotion,
                        List.of(web),
                        false,
                        new ChatWorkflow.RetrievalReleaseContract(true, true, false, true, false, false)));
        assertEquals(ChatWorkflow.EvidenceReleaseState.EVIDENCE_PRESENT,
                ChatWorkflow.deriveEvidenceReleaseState(
                        promotion,
                        List.of(vector),
                        false,
                        new ChatWorkflow.RetrievalReleaseContract(true, false, true, false, true, false)));
        assertEquals(ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                ChatWorkflow.deriveEvidenceReleaseState(
                        promotion,
                        List.of(local),
                        false,
                        new ChatWorkflow.RetrievalReleaseContract(true, true, false, true, false, false)));
        assertEquals(ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                ChatWorkflow.deriveEvidenceReleaseState(
                        promotion,
                        List.of(web),
                        false,
                        new ChatWorkflow.RetrievalReleaseContract(true, true, true, true, true, false)));
    }

    @Test
    void retrievalContractPreservesRawIntentAndCoversEverySearchMode() {
        ChatRequestDto.RetrievalRequestIntent requestedWeb =
                new ChatRequestDto.RetrievalRequestIntent(true, null);
        ChatWorkflow.RetrievalReleaseContract capped = ChatWorkflow.buildRetrievalReleaseContract(
                ChatRequestDto.builder().searchMode(SearchMode.AUTO).build(),
                requestedWeb,
                false,
                false,
                false);
        assertTrue(capped.retrievalContractRequested());
        assertTrue(capped.webRequested());
        assertFalse(capped.effectiveWeb());

        for (SearchMode forced : List.of(SearchMode.FORCE_LIGHT, SearchMode.FORCE_DEEP)) {
            ChatWorkflow.RetrievalReleaseContract contract = ChatWorkflow.buildRetrievalReleaseContract(
                    ChatRequestDto.builder().searchMode(forced).build(),
                    new ChatRequestDto.RetrievalRequestIntent(false, false),
                    true,
                    false,
                    false);
            assertTrue(contract.retrievalContractRequested(), forced.name());
            assertTrue(contract.webRequested(), forced.name());
            assertFalse(contract.explicitDirectOff(), forced.name());
        }

        ChatWorkflow.RetrievalReleaseContract explicitOff = ChatWorkflow.buildRetrievalReleaseContract(
                ChatRequestDto.builder().searchMode(SearchMode.AUTO).build(),
                new ChatRequestDto.RetrievalRequestIntent(false, false),
                false,
                false,
                true);
        assertTrue(explicitOff.explicitDirectOff());
        assertEquals(ChatWorkflow.EvidenceReleaseState.NOT_APPLICABLE,
                ChatWorkflow.deriveEvidenceReleaseState(
                         completedEmpty(0, 0, 0, 0), List.of(), false, explicitOff));

        ChatWorkflow.RetrievalReleaseContract offModeWithRagRequested =
                ChatWorkflow.buildRetrievalReleaseContract(
                        ChatRequestDto.builder().searchMode(SearchMode.OFF).build(),
                        new ChatRequestDto.RetrievalRequestIntent(null, true),
                        false,
                        true,
                        false);
        assertFalse(offModeWithRagRequested.explicitDirectOff());
        assertTrue(offModeWithRagRequested.retrievalContractRequested());
        assertTrue(offModeWithRagRequested.ragRequested());

        ChatWorkflow.RetrievalReleaseContract offModeWithDefaultedRagDisabled =
                ChatWorkflow.buildRetrievalReleaseContract(
                        ChatRequestDto.builder()
                                .searchMode(SearchMode.OFF)
                                .useWebSearch(false)
                                .useRag(false)
                                .build(),
                        new ChatRequestDto.RetrievalRequestIntent(null, null),
                        false,
                        false,
                        true);
        assertTrue(offModeWithDefaultedRagDisabled.explicitDirectOff());

        ChatWorkflow.RetrievalReleaseContract directCallerFallback =
                ChatWorkflow.buildRetrievalReleaseContract(
                        ChatRequestDto.builder()
                                .searchMode(SearchMode.AUTO)
                                .useWebSearch(true)
                                .useRag(false)
                                .build(),
                        null,
                        true,
                        false,
                        false);
        assertTrue(directCallerFallback.retrievalContractRequested());
        assertTrue(directCallerFallback.webRequested());
        assertFalse(directCallerFallback.ragRequested());
        assertFalse(directCallerFallback.explicitDirectOff());

        ChatWorkflow.RetrievalReleaseContract ordinaryDirect = ChatWorkflow.buildRetrievalReleaseContract(
                ChatRequestDto.builder().searchMode(SearchMode.AUTO).build(),
                new ChatRequestDto.RetrievalRequestIntent(null, null),
                false,
                false,
                false);
        assertEquals(ChatWorkflow.EvidenceReleaseState.NOT_APPLICABLE,
                ChatWorkflow.deriveEvidenceReleaseState(
                        completedEmpty(0, 0, 0, 0), List.of(), false, ordinaryDirect));

        ChatWorkflow.RetrievalReleaseContract directiveButDisabled = ChatWorkflow.buildRetrievalReleaseContract(
                ChatRequestDto.builder().searchMode(SearchMode.AUTO).build(),
                new ChatRequestDto.RetrievalRequestIntent(null, null),
                false,
                false,
                true);
        assertEquals(ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                ChatWorkflow.deriveEvidenceReleaseState(
                        completedEmpty(0, 0, 0, 0), List.of(), false, directiveButDisabled));
    }

    @Test
    void verifierAndPriorFallbackPrecedeEvidenceReplacement() {
        ChatWorkflow.FinalVerificationReleaseDecision verifierDenied = decide(
                "unsupported draft", true, "rejected", true, false);
        ChatWorkflow.FinalVerificationReleaseDecision preservedVerifier = ChatWorkflow.applyEvidenceReleasePolicy(
                verifierDenied,
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                true,
                false,
                false);
        assertEquals(verifierDenied, preservedVerifier);

        ChatWorkflow.FinalVerificationReleaseDecision priorFallback = ChatWorkflow.applyEvidenceReleasePolicy(
                decide("safe fallback", false, "not_run", false, false),
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                true,
                true,
                false);
        assertEquals("safe fallback", priorFallback.content());
        assertEquals("verification_not_required", priorFallback.reasonCode());
        assertFalse(priorFallback.releaseAllowed());
        assertFalse(priorFallback.evidencePolicyApplied());
    }

    @Test
    void lateEvidenceDetectorCoversNormalNullAndExceptionalDetourExits() throws Exception {
        assertFalse(ChatWorkflow.detectLateUnattributedEvidence(2, 2, false));
        assertTrue(ChatWorkflow.detectLateUnattributedEvidence(2, 3, false));
        assertTrue(ChatWorkflow.detectLateUnattributedEvidence(2, 2, true));

        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int detourStart = source.indexOf("int detourEvidenceCountBefore");
        int retry = source.indexOf("DetourRetryResult retryResult = tryDetourCheapRetry", detourStart);
        int returnedFlag = source.indexOf("retryResult.unattributedEvidenceAdded()", retry);
        int contentAdoption = source.indexOf("retryResult.content()", returnedFlag);
        int finallyBlock = source.indexOf("} finally {", contentAdoption);
        int finallyCountCheck = source.indexOf("detectLateUnattributedEvidence(", finallyBlock);
        int verified = source.indexOf("verified = out;", finallyCountCheck);

        assertTrue(detourStart >= 0);
        assertTrue(retry > detourStart);
        assertTrue(returnedFlag > retry);
        assertTrue(contentAdoption > returnedFlag);
        assertTrue(finallyBlock > contentAdoption);
        assertTrue(finallyCountCheck > finallyBlock);
        assertTrue(verified > finallyCountCheck);
        assertTrue(source.substring(finallyCountCheck, verified).contains("false)"));
    }

    @Test
    void evidenceReplacementPrecedesAppendixAndDurableMemoryWriters() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int compose = source.indexOf("applyEvidenceReleasePolicy(");
        int appendix = source.indexOf("appendFinalEvidenceOnce(", compose);
        int memoryWriter = source.indexOf("learningWriteInterceptor.ingest(", compose);

        assertTrue(compose >= 0);
        assertTrue(appendix > compose);
        assertTrue(memoryWriter > appendix);
        assertTrue(source.contains("if (!protectedBaseContent && !releaseDecision.evidencePolicyApplied())"));
        assertTrue(source.contains("finalAnswerMemoryDeniedByPolicy = true;"));
    }

    @Test
    void originalUserQueryOwnsFinalEvidenceAttributionConstraintsAfterRewrite() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("promoteForPromptDetailed(\n                        finalQuery,"));
        assertTrue(source.contains("filterOfficialSourceEvidenceMetadata(\n                        userQuery,"));
        assertTrue(source.contains("attachEnsembleCitationSources(ctxBuilder, userQuery, citableEvidence)"));
        assertTrue(source.contains("vectorDocs,\n                    userQuery,"));
        assertTrue(source.contains("protectedBaseContent ? null : userQuery"));
        assertTrue(source.contains("filterOfficialSourceEvidenceMetadata(userQuery,"));
    }

    @Test
    void postPromotionEarlyReturnsRemainOutsideNormalReleaseComposition() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int promotion = source.indexOf("promoteForPromptDetailed(");
        int release = source.indexOf("releaseDecision = applyEvidenceReleasePolicy(", promotion);

        assertTrue(promotion >= 0);
        assertTrue(release > promotion);
        assertEarlyReturnBeforeRelease(source, promotion, release,
                "boolean agentDebugAnswerRequested",
                "return ChatResult.of(agentDebugDirectAnswer",
                "ChatModel model = modelRouter.route(");
        assertEarlyReturnBeforeRelease(source, promotion, release,
                "shouldUseChatDraftConfigBreakerFallback",
                "return sanitizeFallbackResult(",
                "nightmareBreaker.acquire(");
        assertEarlyReturnBeforeRelease(source, promotion, release,
                "catch (NightmareBreaker.OpenCircuitException",
                "return sanitizeFallbackResult(",
                "long started = System.nanoTime();");
        assertEarlyReturnBeforeRelease(source, promotion, release,
                "finalModelRequestBudget.expired()",
                "return sanitizeFallbackResult(",
                "long started = System.nanoTime();");
        assertEarlyReturnBeforeRelease(source, promotion, release,
                "catch (CancellationException ce)",
                "return ChatResult.of(",
                "catch (Exception e)");
        assertEarlyReturnBeforeRelease(source, promotion, release,
                "LlmConfigurationException cfg = unwrapLlmConfigurationException",
                "return ChatResult.of(userMsg",
                "LlmFastBailoutException fastBail");
        assertEarlyReturnBeforeRelease(source, promotion, release,
                "LlmFastBailoutException fastBail",
                "return sanitizeFallbackResult(",
                "boolean verifierFollowUp");
    }

    @Test
    void evidenceHoldDeniesMemoryAndStillYieldsToApplicableS8Contract() {
        ChatWorkflow.FinalVerificationReleaseDecision exactEvidenceHold =
                ChatWorkflow.applyEvidenceReleasePolicy(
                        decide("unsupported draft", false, "not_run", false, false),
                        ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY,
                        true,
                        false,
                        false);
        FinalAnswerPostProcessor postProcessor =
                new FinalAnswerPostProcessor(new OutputSanitizer());

        FinalAnswerPostProcessor.Result ordinary = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        exactEvidenceHold.content(),
                        exactEvidenceHold.content(),
                        true,
                        true,
                        true,
                        true,
                        false,
                        true,
                        false,
                        "If evidence is missing, return evidence_needed."));
        assertEquals("evidence_needed", ordinary.content());
        assertFalse(ordinary.memorySaveAllowed());
        assertEquals("memory_policy_denied", ordinary.memoryDenyReason());

        String s8Query = "Fictional budgeting scenario. Return exactly two labeled lines. "
                + "OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;"
                + "riskTolerance=low;purchaseCost=high. "
                + "INFERENCE: discretionaryBudget=unknown. Do not invent or repeat any exact financial amount.";
        FinalAnswerPostProcessor.Result s8 = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        exactEvidenceHold.content(),
                        exactEvidenceHold.content(),
                        true,
                        true,
                        true,
                        true,
                        false,
                        true,
                        false,
                        s8Query));
        assertEquals(
                "HOLD\n한계: S8 응답 계약이 완전하지 않아 제약이나 추론을 자동 생성하지 않았습니다.",
                s8.content());
        assertEquals("s8_incomplete_hold", s8.reasonCode());
        assertFalse(s8.memorySaveAllowed());

        String s7Query = "Use Self-Ask and compare A/B/C candidates. Include support, counterexample, "
                + "metric, then a neutral verdict choosing one or HOLD with a limitation.";
        FinalAnswerPostProcessor.Result s7 = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        exactEvidenceHold.content(),
                        exactEvidenceHold.content(),
                        true,
                        true,
                        true,
                        true,
                        false,
                        true,
                        false,
                        s7Query));
        assertTrue(s7.content().startsWith("HOLD\n"));
        assertFalse(s7.content().contains("evidence_needed"));
        assertEquals("incomplete_hold", s7.reasonCode());
        assertFalse(s7.memorySaveAllowed());
    }

    private static void assertEarlyReturnBeforeRelease(
            String source,
            int promotion,
            int release,
            String branchAnchor,
            String returnNeedle,
            String nextAnchor) {
        int branch = source.indexOf(branchAnchor, promotion);
        int returned = source.indexOf(returnNeedle, branch);
        int next = source.indexOf(nextAnchor, branch + branchAnchor.length());
        assertTrue(branch > promotion, branchAnchor);
        assertTrue(returned > branch, returnNeedle);
        assertTrue(next > returned, nextAnchor);
        assertTrue(returned < release, branchAnchor + " must bypass normal release composition");
    }

    @Test
    void evidenceReleaseHoldWithFullMemoryNeverInvokesDurableWriters() {
        MemoryHoldFixture fixture = memoryHoldFixture();
        AttachmentOwnerIdentity owner = AttachmentOwnerIdentity.forAnonymous("release-gate-owner");
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("Give a concise answer. If no reliable evidence is available, reply with evidence_needed.")
                    .model("release-gate-recording-fake")
                    .maxTokens(256)
                    .mode("FACT")
                    .memoryMode("FULL")
                    .searchMode(SearchMode.AUTO)
                    .useWebSearch(false)
                    .useRag(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(null, null))
                    .useVerification(true)
                    .attachmentIds(List.of("release-gate-local"))
                    .build();
            request.bindAttachmentOwnerIdentity(owner);

            ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of());

            assertEquals(
                    "evidence_needed: attribution unavailable / verify retrieval evidence",
                    result.content());
            assertEquals("metadata_incomplete", TraceStore.get("finalAnswer.evidenceReleaseState"));
            assertEquals(true, TraceStore.get("finalAnswer.evidenceReleaseApplied"));
            assertEquals("HOLD", TraceStore.get("finalAnswer.releaseStatus"));
            assertEquals("evidence_release_metadata_incomplete",
                    TraceStore.get("finalAnswer.releaseReason"));
            assertEquals(true, TraceStore.get("finalAnswer.verificationOutcomeKnown"));
            assertEquals(true, TraceStore.get("finalAnswer.verificationAcceptedForMemory"));
            assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
            assertEquals("memory_policy_denied", TraceStore.get("finalAnswer.memoryDenyReason"));

            verify(fixture.verifier()).verifyDetailed(
                    anyString(), nullable(String.class), nullable(String.class),
                    anyString(), anyString(), anyBoolean());
            verify(fixture.attachmentService()).asDocumentsForSession(
                    List.of("release-gate-local"), null, owner);
            verify(fixture.attachmentService(), never()).asDocumentsForSession(
                    anyList(), nullable(String.class));
            verifyNoInteractions(
                    fixture.learningWriteInterceptor(),
                    fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mismatchedWorkflowOwnerProducesNoPromptLocalDocuments() {
        MemoryHoldFixture fixture = memoryHoldFixture();
        AttachmentOwnerIdentity foreign = AttachmentOwnerIdentity.forAnonymous("foreign-release-owner");
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("Use the attached local evidence, with verification.")
                    .model("release-gate-recording-fake")
                    .maxTokens(256)
                    .mode("FACT")
                    .memoryMode("EPHEMERAL")
                    .searchMode(SearchMode.OFF)
                    .useWebSearch(false)
                    .useRag(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false, false))
                    .useVerification(true)
                    .attachmentIds(List.of("release-gate-local"))
                    .build();
            request.bindAttachmentOwnerIdentity(foreign);

            fixture.workflow().continueChat(request, ignored -> List.of());

            verify(fixture.attachmentService()).asDocumentsForSession(
                    List.of("release-gate-local"), null, foreign);
            verify(fixture.attachmentService(), never()).asDocumentsForSession(
                    anyList(), nullable(String.class));
            org.mockito.ArgumentCaptor<List<Document>> localDocs = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(fixture.attribution()).promoteForPromptDetailed(
                    anyString(), nullable(List.class), nullable(List.class),
                    localDocs.capture(), any(), anyBoolean());
            assertTrue(localDocs.getValue().isEmpty());
        } finally {
            clearWorkflowState();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void capturedFinalMessagesKeepBuilderAndUserRolesWithoutRewriting() {
        assertCapturedPromptBoundary(null, List.of(), List.of(), false);
    }

    @Test
    void capturedFinalMessagesRejectPublicLiteralSystemPrompt() {
        assertCapturedPromptBoundary("PUBLIC LITERAL MUST STAY UNTRUSTED", List.of(), List.of(), true);
    }

    @Test
    void capturedFinalMessagesAdmitOnlyResolvedAssetAndTraitBeforeContext() {
        assertCapturedPromptBoundary("recording-approved", List.of("recording-trait"),
                List.of("APPROVED ASSET MARKER", "APPROVED TRAIT MARKER"), false);
    }

    @Test
    void capturedFinalMessagesRejectMissingAssetWithoutUsingItsIdAsSystemText() {
        assertCapturedPromptBoundary("recording-missing", List.of(), List.of(), true);
    }

    private static void assertCapturedPromptBoundary(
            String requestedSystemPrompt, List<String> traits, List<String> expectedExtras, boolean rejected) {
        clearWorkflowState();
        try {
            MemoryHoldFixture fixture = memoryHoldFixture();
            String instruction = "INSTRUCTION MARKER: preserve attribution and explicit negation.";
            String context = "CONTEXT MARKER\nSpeaker A proposed a claim. Speaker B corrected it: not confirmed.";
            String query = "Explain the fictional catalog; the earlier claim was not confirmed.";
            com.example.lms.prompt.PromptBuilder builder = mock(com.example.lms.prompt.PromptBuilder.class);
            when(builder.build(any(com.example.lms.prompt.PromptContext.class))).thenReturn(context);
            when(builder.buildInstructions(any(com.example.lms.prompt.PromptContext.class))).thenReturn(instruction);
            ReflectionTestUtils.setField(fixture.workflow(), "promptBuilder", builder);

            var captured = new java.util.concurrent.CopyOnWriteArrayList<
                    List<dev.langchain4j.data.message.ChatMessage>>();
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenAnswer(invocation -> {
                List<dev.langchain4j.data.message.ChatMessage> messages = invocation.getArgument(0);
                captured.add(List.copyOf(messages));
                return ChatResponse.builder().aiMessage(AiMessage.from("unsupported draft")).build();
            });
            ModelRouter router = (ModelRouter) ReflectionTestUtils.getField(fixture.workflow(), "modelRouter");
            when(router.route(anyString(), nullable(String.class), anyString(), anyInt(), anyString()))
                    .thenReturn(model);
            when(router.resolveModelName(model)).thenReturn("release-gate-recording-fake");

            org.springframework.core.io.ResourceLoader loader = mock(org.springframework.core.io.ResourceLoader.class);
            when(loader.getResource(anyString())).thenAnswer(invocation -> {
                String path = invocation.getArgument(0);
                String text = switch (path) {
                    case "classpath:prompts/system/recording-approved.md" -> "APPROVED ASSET MARKER";
                    case "classpath:prompts/traits/recording-trait.md" -> "APPROVED TRAIT MARKER";
                    default -> "";
                };
                return new org.springframework.core.io.ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
            });
            ReflectionTestUtils.setField(fixture.workflow(), "promptAssetService",
                    new com.example.lms.service.prompt.PromptAssetService(loader));

            ChatRequestDto request = ChatRequestDto.builder()
                    .message(query).model("release-gate-recording-fake").maxTokens(256)
                    .mode("FACT").memoryMode("FULL").searchMode(SearchMode.AUTO)
                    .useWebSearch(false).useRag(false).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(null, null))
                    .systemPrompt(requestedSystemPrompt).traits(traits)
                    .attachmentIds(List.of("release-gate-local")).build();
            request.bindAttachmentOwnerIdentity(AttachmentOwnerIdentity.forAnonymous("release-gate-owner"));
            fixture.workflow().continueChat(request, ignored -> List.of());

            assertEquals(1, captured.size(), "capture must reach the actual normal final model call once");
            List<dev.langchain4j.data.message.ChatMessage> messages = captured.get(0);
            var expectedSystem = new java.util.ArrayList<String>();
            expectedSystem.add(instruction);
            expectedSystem.addAll(expectedExtras);
            expectedSystem.add(context);
            assertEquals(expectedSystem.size() + 1, messages.size());
            for (int i = 0; i < expectedSystem.size(); i++) {
                assertTrue(messages.get(i) instanceof dev.langchain4j.data.message.SystemMessage);
                assertEquals(expectedSystem.get(i),
                        ((dev.langchain4j.data.message.SystemMessage) messages.get(i)).text());
            }
            assertTrue(messages.get(messages.size() - 1) instanceof dev.langchain4j.data.message.UserMessage);
            assertEquals(query, ((dev.langchain4j.data.message.UserMessage) messages.get(messages.size() - 1)).singleText());
            var contextCaptor = org.mockito.ArgumentCaptor.forClass(com.example.lms.prompt.PromptContext.class);
            verify(builder).build(contextCaptor.capture());
            verify(builder).buildInstructions(org.mockito.ArgumentMatchers.same(contextCaptor.getValue()));
            assertEquals(rejected, Boolean.TRUE.equals(TraceStore.get("prompt.systemPrompt.rejected")));
            if (rejected) {
                assertFalse(String.valueOf(TraceStore.getAll()).contains(requestedSystemPrompt));
            }
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
        }
    }

    @Test
    void enforcedImageRequestHasOnePrimaryCallWithoutOptionalOrDurableWork() {
        assertImageWorkflowCounts("enforce", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"enforce", "shadow"})
    void explicitStopSuppressesEnabledOptionalWorkAndMemoryOnlyWhenEnforced(String mode) {
        assertImageWorkflowCounts(mode, true);
    }

    @SuppressWarnings("unchecked")
    private static void assertImageWorkflowCounts(String mode, boolean enabledOptionalWork) {
        MemoryHoldFixture fixture = memoryHoldFixture();
        boolean enforced = "enforce".equals(mode);
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.vision.model", "vision-recording-fake");
        ReflectionTestUtils.setField(fixture.workflow(), "env", env);
        ReflectionTestUtils.setField(fixture.workflow(), "planModelResolver", new PlanModelResolver(env));
        ReflectionTestUtils.setField(fixture.workflow(), "conversationHarmonyMode", mode);
        ReflectionTestUtils.setField(fixture.workflow(), "promptContextRefinerEnabled", enabledOptionalWork);
        EnsembleFinalAnswerService refiner = mock(EnsembleFinalAnswerService.class);
        ReflectionTestUtils.setField(fixture.workflow(), "ensembleFinalAnswerService", refiner);
        var lengthVerifier = (com.example.lms.service.answer.LengthVerifierService)
                ReflectionTestUtils.getField(fixture.workflow(), "lengthVerifier");
        var expander = (com.example.lms.service.answer.AnswerExpanderService)
                ReflectionTestUtils.getField(fixture.workflow(), "answerExpander");
        when(lengthVerifier.isShort(anyString(), anyInt())).thenReturn(true);
        when(expander.expandWithLc(anyString(), any(), any())).thenReturn("synthetic expanded answer");
        UnderstandAndMemorizeInterceptor understanding = mock(UnderstandAndMemorizeInterceptor.class);
        MemoryReinforcementService reinforcement = mock(MemoryReinforcementService.class);
        ReflectionTestUtils.setField(fixture.workflow(), "understandAndMemorizeInterceptor", understanding);
        ReflectionTestUtils.setField(fixture.workflow(), "memorySvc", reinforcement);
        ReflectionTestUtils.setField(fixture.workflow(), "enableAssistantReinforcement", true);
        if (enabledOptionalWork) {
            String verifiedFixtureAnswer = "Synthetic verified fixture response with sufficient detail for the memory policy control.";
            assertFalse(EvidenceAwareGuard.looksWeak(verifiedFixtureAnswer));
            when(fixture.verifier().verifyDetailed(
                    anyString(), nullable(String.class), nullable(String.class),
                    anyString(), anyString(), anyBoolean()))
                    .thenReturn(new FactVerifierService.DetailedVerificationResult(
                            verifiedFixtureAnswer, "pass", true, true));
            var localPromotion = promoted(
                    List.of(evidence("L1", "LOCAL_DOC", "release-gate-local", "docs/fixture.txt")),
                    0, 0, 0, 0, 1, 1);
            when(fixture.attribution().promoteForPromptDetailed(
                    anyString(), nullable(List.class), nullable(List.class), anyList(), any(), anyBoolean()))
                    .thenReturn(localPromotion);
            when(fixture.attribution().appendFinalEvidenceAppendix(anyString(), anyList()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("이제 그만하고 분석을 멈춰 줘")
                    .model("release-gate-recording-fake")
                    .imageBase64("AA==")
                    .imageMediaType("image/png")
                    .maxTokens(256)
                    .mode("FACT")
                    .memoryMode(enabledOptionalWork ? "FULL" : "EPHEMERAL")
                    .searchMode(SearchMode.OFF)
                    .useWebSearch(false)
                    .useRag(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false, false))
                    .useVerification(enabledOptionalWork)
                    .attachmentIds(enabledOptionalWork ? List.of("release-gate-local") : List.of())
                    .build();
            request.bindAttachmentOwnerIdentity(AttachmentOwnerIdentity.forAnonymous("release-gate-owner"));

            fixture.workflow().continueChat(request, ignored -> List.of());

            ArgumentCaptor<String> routeCaptor = ArgumentCaptor.forClass(String.class);
            verify(fixture.modelRouter()).route(
                    anyString(), nullable(String.class), anyString(), anyInt(), routeCaptor.capture());
            assertEquals(enforced ? "vision-recording-fake" : "release-gate-recording-fake",
                    routeCaptor.getValue());
            ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
            verify(fixture.model()).chat(messagesCaptor.capture());
            List<UserMessage> userMessages = messagesCaptor.getValue().stream()
                    .filter(UserMessage.class::isInstance).map(UserMessage.class::cast).toList();
            assertEquals(1, userMessages.size());
            assertEquals(enforced ? 2 : 1, userMessages.get(0).contents().size());
            assertTrue(userMessages.get(0).contents().get(0) instanceof TextContent);
            if (enforced) {
                ImageContent image = (ImageContent) userMessages.get(0).contents().get(1);
                assertEquals("AA==", image.image().base64Data());
                assertEquals("image/png", image.image().mimeType());
                verifyNoInteractions(refiner, lengthVerifier, expander,
                        fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor(),
                        understanding, reinforcement);
                assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
                assertEquals(enabledOptionalWork ? "memory_policy_denied" : "write_disabled",
                        TraceStore.get("finalAnswer.memoryDenyReason"));
            } else {
                verify(refiner).sampleCandidatesForRefinement(any(), nullable(Long.class), any());
                verify(expander).expandWithLc(anyString(), any(), any());
                assertEquals("none", TraceStore.get("finalAnswer.memoryDenyReason"));
                assertEquals(true, TraceStore.get("finalAnswer.memorySaveAllowed"));
                verify(fixture.learningWriteInterceptor()).ingest(anyString(), anyString(), anyString(), any(Double.class));
                verify(fixture.memoryWriteInterceptor()).save(anyString(), anyString(), anyString(), any(Double.class));
                verify(understanding).afterVerified(anyString(), anyString(), anyString(), anyBoolean(), any());
                verify(reinforcement).reinforceWithSnippet(
                        anyString(), anyString(), anyString(), anyString(), any(Double.class), any(), any());
            }
            verify(fixture.verifier(), times(enabledOptionalWork ? 1 : 0)).verifyDetailed(
                    anyString(), nullable(String.class), nullable(String.class),
                    anyString(), anyString(), anyBoolean());
            if (enforced) {
                assertEquals("not_observed", TraceStore.get("conversation.frame.wireAttemptCoverage"));
            }
        } finally {
            clearWorkflowState();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoFailedEvidenceRescuesPreserveFinalReleaseAndNoMemoryBoundary(boolean metadataPromoted) {
        MemoryHoldFixture fixture = memoryHoldFixture();
        ReflectionTestUtils.setField(fixture.workflow(), "disambiguationService",
                mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class));
        var preprocessor = (QueryContextPreprocessor) ReflectionTestUtils.getField(fixture.workflow(), "qcPreprocessor");
        when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
        var rag = mock(com.example.lms.service.rag.LangChainRAGService.class);
        var retriever = mock(dev.langchain4j.rag.content.retriever.ContentRetriever.class);
        when(rag.asContentRetriever(nullable(String.class))).thenReturn(retriever);
        when(retriever.retrieve(any())).thenReturn(List.of(
                dev.langchain4j.rag.content.Content.from("Synthetic retrieved evidence for rescue characterization.")));
        var composer = mock(com.example.lms.service.rag.EvidenceAnswerComposer.class);
        var guard = (EvidenceAwareGuard) ReflectionTestUtils.getField(fixture.workflow(), "evidenceAwareGuard");
        when(composer.compose(anyString(), anyList(), anyBoolean()))
                .thenThrow(new IllegalStateException("synthetic-private-composer-detail"));
        when(guard.degradeToEvidenceList(anyList()))
                .thenThrow(new IllegalStateException("synthetic-private-degrade-detail"));
        when(fixture.model().chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("정보 없음")).build());
        ReflectionTestUtils.setField(fixture.workflow(), "ragSvc", rag);
        ReflectionTestUtils.setField(fixture.workflow(), "evidenceAnswerComposer", composer);
        ReflectionTestUtils.setField(fixture.workflow(), "rescueCount", new java.util.concurrent.atomic.AtomicLong());
        if (metadataPromoted) {
            var promotion = promoted(List.of(evidence("V1", "VECTOR", null, "docs/vector.md")),
                    0, 0, 1, 1, 0, 0);
            when(fixture.attribution().promoteForPromptDetailed(
                    anyString(), nullable(List.class), nullable(List.class), anyList(), any(), anyBoolean()))
                    .thenReturn(promotion);
        }
        when(fixture.attribution().appendFinalEvidenceAppendix(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("Summarize the supplied synthetic record.")
                    .model("release-gate-recording-fake")
                    .maxTokens(256).mode("FACT").memoryMode("EPHEMERAL")
                    .searchMode(SearchMode.AUTO).useWebSearch(false).useRag(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false, true))
                    .useVerification(false).build();
            ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of());
            verify(retriever).retrieve(any());
            verify(composer).compose(anyString(), anyList(), anyBoolean());
            verify(guard).degradeToEvidenceList(anyList());
            assertEquals("definitive_failure_with_evidence", TraceStore.get("nightmare.finalRescue.reason"));
            assertEquals(1, TraceStore.get("nightmare.finalRescue.evidenceCount"));
            assertTrue(result.content() != null && !result.content().isBlank());
            assertFalse(result.content().contains("synthetic-private-composer-detail"));
            assertFalse(result.content().contains("synthetic-private-degrade-detail"));
            assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
            boolean corruptedDisplay = result.content().codePoints().anyMatch(cp ->
                    Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN || cp == 0xfffd);
            System.out.printf("F18_CHAR metadataPromoted=%s contentLength=%d corruptedDisplay=%s releaseStatus=%s releaseReason=%s postprocessReason=%s contentHash=%s%n",
                    metadataPromoted, result.content().length(), corruptedDisplay,
                    TraceStore.get("finalAnswer.releaseStatus"), TraceStore.get("finalAnswer.releaseReason"),
                    TraceStore.get("finalAnswer.postprocess.reason"), TraceStore.get("finalAnswer.postprocess.contentHash"));
        } finally {
            clearWorkflowState();
        }
    }

    @Test
    void responsesTerminalSurvivesFullWorkflowWithoutFallbackOrHealthPromotion() {
        MemoryHoldFixture fixture = memoryHoldFixture();
        clearWorkflowState();
        try {
            var tracker = mock(com.example.lms.llm.ModelRuntimeHealthTracker.class);
            ReflectionTestUtils.setField(fixture.workflow(), "modelRuntimeHealthTracker", tracker);
            var metadata = dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                    .tokenUsage(new dev.langchain4j.model.output.TokenUsage(3, 2, 5))
                    .finishReason(dev.langchain4j.model.output.FinishReason.LENGTH).build();
            var terminal = new com.example.lms.llm.gateway.LlmResponseTerminalException(
                    "output_limit_reached", com.example.lms.llm.gateway.LlmFailureClass.NONE,
                    "partial", metadata, "incomplete", "max_output_tokens", null);
            when(fixture.model().chat(anyList())).thenThrow(terminal);
            var request = ChatRequestDto.builder().message("Explain how rain forms.")
                    .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.OFF)
                    .useWebSearch(false).useRag(false).useVerification(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false, false)).build();
            org.junit.jupiter.api.Assertions.assertSame(terminal,
                    org.junit.jupiter.api.Assertions.assertThrows(
                            com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                            () -> fixture.workflow().continueChat(request, ignored -> List.of())));
            verify(fixture.model(), org.mockito.Mockito.times(1)).chat(anyList());
            verify(tracker, never()).recordCurrentRequestRouteFailure(anyString(), anyString());
            verify(tracker, never()).recordAttemptSuccess(anyString(), any(), any());
        } finally { clearWorkflowState(); }
    }

    private static MemoryHoldFixture memoryHoldFixture() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("unsupported draft"))
                .build());

        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.route(
                anyString(), nullable(String.class), anyString(), anyInt(), anyString()))
                .thenReturn(model);
        when(modelRouter.resolveModelName(model)).thenReturn("release-gate-recording-fake");

        SubjectResolver subjectResolver = mock(SubjectResolver.class);
        when(subjectResolver.analyze(anyString(), anyList(), any())).thenReturn(
                SubjectAnalysis.builder().category(SubjectCategory.GENERAL).build());
        UniversalDomainDetector domainDetector = mock(UniversalDomainDetector.class);
        when(domainDetector.detect(anyString(), any())).thenReturn("GENERAL");
        QueryContextPreprocessor preprocessor = mock(QueryContextPreprocessor.class);
        when(preprocessor.getInteractionRules(anyString())).thenReturn(Map.of());

        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.asDocumentsForSession(
                anyList(),
                nullable(String.class),
                org.mockito.ArgumentMatchers.eq(
                        AttachmentOwnerIdentity.forAnonymous("release-gate-owner"))))
                .thenReturn(List.of(Document.from("trusted local verification context")));

        RagEvidenceAttributionService attribution = mock(RagEvidenceAttributionService.class);
        when(attribution.promoteForPromptDetailed(
                anyString(), nullable(List.class), nullable(List.class), anyList(), any(), anyBoolean()))
                .thenReturn(RagEvidenceAttributionService.PromotionResult.unavailable());

        FactVerifierService verifier = mock(FactVerifierService.class);
        when(verifier.verifyDetailed(
                anyString(), nullable(String.class), nullable(String.class),
                anyString(), anyString(), anyBoolean()))
                .thenReturn(new FactVerifierService.DetailedVerificationResult(
                        "verified draft", "pass", true, true));

        LearningWriteInterceptor learningWriter = mock(LearningWriteInterceptor.class);
        MemoryWriteInterceptor memoryWriter = mock(MemoryWriteInterceptor.class);
        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "interactionPolicyMode", "off");
        ReflectionTestUtils.setField(workflow, "promptContextRefinerEnabled", false);
        ReflectionTestUtils.setField(workflow, "queryDomainClassifier", new QueryDomainClassifier());
        ReflectionTestUtils.setField(workflow, "guardProfileProps", new GuardProfileProps());
        ReflectionTestUtils.setField(workflow, "subjectResolver", subjectResolver);
        ReflectionTestUtils.setField(workflow, "domainDetector", domainDetector);
        ReflectionTestUtils.setField(workflow, "domainStrategyFactory", new DomainStrategyFactory());
        ReflectionTestUtils.setField(workflow, "verbosityDetector", new VerbosityDetector());
        ReflectionTestUtils.setField(workflow, "sectionSpecGenerator", new SectionSpecGenerator());
        ReflectionTestUtils.setField(workflow, "qcPreprocessor", preprocessor);
        ReflectionTestUtils.setField(workflow, "evidenceAwareGuard", mock(EvidenceAwareGuard.class));
        ReflectionTestUtils.setField(workflow, "promptBuilder", new StandardPromptBuilder());
        ReflectionTestUtils.setField(workflow, "modelRouter", modelRouter);
        ReflectionTestUtils.setField(workflow, "lengthVerifier",
                mock(com.example.lms.service.answer.LengthVerifierService.class));
        ReflectionTestUtils.setField(workflow, "answerExpander",
                mock(com.example.lms.service.answer.AnswerExpanderService.class));
        ReflectionTestUtils.setField(workflow, "verifier", verifier);
        ReflectionTestUtils.setField(workflow, "attachmentService", attachmentService);
        ReflectionTestUtils.setField(workflow, "memoryHandler", mock(MemoryHandler.class));
        ReflectionTestUtils.setField(workflow, "ragEvidenceAttributionService", attribution);
        ReflectionTestUtils.setField(workflow, "learningWriteInterceptor", learningWriter);
        ReflectionTestUtils.setField(workflow, "memoryWriteInterceptor", memoryWriter);
        ReflectionTestUtils.setField(workflow, "finalAnswerPostProcessor",
                new FinalAnswerPostProcessor(new OutputSanitizer()));
        ReflectionTestUtils.setField(workflow, "chatUsageLedger", new ChatUsageLedger());
        ReflectionTestUtils.setField(workflow, "llmProvider", "local");
        ReflectionTestUtils.setField(workflow, "defaultModel", "release-gate-recording-fake");
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 0);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
        ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 10);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", false);

        return new MemoryHoldFixture(
                workflow,
                modelRouter,
                model,
                verifier,
                attachmentService,
                attribution,
                learningWriter,
                memoryWriter);
    }

    private static void clearWorkflowState() {
        GuardContextHolder.clear();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    private record MemoryHoldFixture(
            ChatWorkflow workflow,
            ModelRouter modelRouter,
            ChatModel model,
            FactVerifierService verifier,
            AttachmentService attachmentService,
            RagEvidenceAttributionService attribution,
            LearningWriteInterceptor learningWriteInterceptor,
            MemoryWriteInterceptor memoryWriteInterceptor) {
    }

    private static ChatWorkflow.FinalVerificationReleaseDecision decide(
            String candidate,
            boolean verificationRequired,
            String verificationStatus,
            boolean outcomeKnown,
            boolean acceptedForMemory) {
        return ChatWorkflow.applyFinalVerificationReleaseGate(
                candidate,
                verificationRequired,
                verificationStatus,
                outcomeKnown,
                acceptedForMemory);
    }

    private static RagEvidenceAttributionService.PromotionResult completedEmpty(
            int webCandidates,
            int webLocators,
            int vectorCandidates,
            int vectorLocators) {
        return new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.CONFIRMED_EMPTY,
                webCandidates + vectorCandidates == 0
                        ? RagEvidenceAttributionService.PromotionReason.NO_CITABLE_LOCATOR
                        : RagEvidenceAttributionService.PromotionReason.CITATION_GATE_BLOCKED,
                List.of(),
                webCandidates,
                webLocators,
                vectorCandidates,
                vectorLocators,
                0,
                0);
    }

    private static RagEvidenceAttributionService.PromotionResult failed() {
        return new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.FAILED,
                RagEvidenceAttributionService.PromotionReason.GATE_EXCEPTION,
                List.of(),
                1, 1, 0, 0, 0, 0);
    }

    private static RagEvidenceAttributionService.PromotionResult promoted(
            List<RagEvidenceMetadata> evidence,
            int webCandidates,
            int webLocators,
            int vectorCandidates,
            int vectorLocators,
            int localCandidates,
            int localLocators) {
        return new RagEvidenceAttributionService.PromotionResult(
                RagEvidenceAttributionService.PromotionStatus.PROMOTED,
                RagEvidenceAttributionService.PromotionReason.PROMOTED,
                evidence,
                webCandidates,
                webLocators,
                vectorCandidates,
                vectorLocators,
                localCandidates,
                localLocators);
    }

    private static RagEvidenceMetadata evidence(
            String marker,
            String kind,
            String source,
            String filePath) {
        return new RagEvidenceMetadata(
                marker, kind, "title", source, filePath,
                null, null, 1, null, "unavailable");
    }
}
