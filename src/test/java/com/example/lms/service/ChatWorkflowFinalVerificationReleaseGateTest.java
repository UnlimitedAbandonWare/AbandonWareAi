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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatWorkflowFinalVerificationReleaseGateTest {

    @ParameterizedTest
    @ValueSource(strings = {"concept", "concept-reference", "force-light", "force-deep", "evidence-only", "attachment",
            "current", "medical", "medication", "scope-no-search", "evidence-needed", "constitutional"})
    void explicitGeneralConceptWithoutExternalSearchPreservesDraftAndSkipsUnrelatedEvidence(String variant) {
        MemoryHoldFixture fixture = memoryHoldFixture();
        ReflectionTestUtils.setField(fixture.workflow(), "hybridTopK", 5);
        for (String field : List.of("keepNBrief", "keepNStd", "keepNDeep", "keepNUltra"))
            ReflectionTestUtils.setField(fixture.workflow(), field, 5);
        var plate = mock(com.example.lms.artplate.ArtPlateSpec.class);
        when(plate.webTopK()).thenReturn(5);
        when(plate.vecTopK()).thenReturn(5);
        when(plate.webBudgetMs()).thenReturn(2_000);
        var plateGate = mock(com.example.lms.artplate.NineArtPlateGate.class);
        when(plateGate.decide(any())).thenReturn(plate);
        ReflectionTestUtils.setField(fixture.workflow(), "nineArtPlateGate", plateGate);
        ReflectionTestUtils.setField(fixture.workflow(), "rescueCount", new java.util.concurrent.atomic.AtomicLong());
        ReflectionTestUtils.setField(fixture.workflow(), "env", new MockEnvironment());
        ReflectionTestUtils.setField(fixture.workflow(), "evidenceAwareGuard", new EvidenceAwareGuard());
        ReflectionTestUtils.setField(fixture.workflow(), "disambiguationService",
                mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class));
        var preprocessor = (QueryContextPreprocessor) ReflectionTestUtils.getField(fixture.workflow(), "qcPreprocessor");
        when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
        var rag = mock(com.example.lms.service.rag.LangChainRAGService.class);
        var retriever = mock(dev.langchain4j.rag.content.retriever.ContentRetriever.class);
        when(rag.asContentRetriever(nullable(String.class))).thenReturn(retriever);
        var unrelated = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> dev.langchain4j.rag.content.Content.from(
                        dev.langchain4j.data.segment.TextSegment.from(
                                "unrelated election votes and candidates " + index,
                                dev.langchain4j.data.document.Metadata.from("url", "https://example.test/votes/" + index))))
                .toList();
        when(retriever.retrieve(any())).thenReturn(unrelated);
        var hybrid = mock(com.example.lms.service.rag.HybridRetriever.class);
        when(hybrid.retrieveAll(anyList(), anyInt(), nullable(Long.class), anyMap())).thenReturn(unrelated);
        ReflectionTestUtils.setField(fixture.workflow(), "hybridRetriever", hybrid);
        ReflectionTestUtils.setField(fixture.workflow(), "ragSvc", rag);
        String answer = "키워드 검색은 표현의 일치를 찾습니다. 벡터 검색은 의미의 유사성을 찾습니다. RAG에서는 둘을 함께 사용할 수 있습니다.";
        when(fixture.model().chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(answer)).build());
        when(fixture.attribution().appendFinalEvidenceAppendix(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("RAG에서 키워드 검색과 벡터 검색의 차이를 일반적인 개념으로 3문장만 설명해줘. 외부 검색이 필요한 주제는 아니야.")
                    .model("release-gate-recording-fake").maxTokens(256)
                    .mode("FACT").memoryMode("FULL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true).build();
            switch (variant) {
                case "concept-reference" -> request.setMessage("RAG에서 키워드 검색과 벡터 검색의 차이를 일반 개념으로 세 문장 정도 설명해줘. 최신 정보나 외부 검색이 꼭 필요한 질문은 아니야.");
                case "force-light" -> request.setSearchMode(SearchMode.FORCE_LIGHT);
                case "force-deep" -> request.setSearchMode(SearchMode.FORCE_DEEP);
                case "evidence-only" -> request.setRagAnswerPolicy("evidence_only");
                case "attachment" -> {
                    request.setAttachmentIds(List.of("release-gate-local"));
                    request.bindAttachmentOwnerIdentity(AttachmentOwnerIdentity.forAnonymous("release-gate-owner"));
                }
                case "scope-no-search" -> request.setMessage("추가 검색 없이 앞에서 확인된 원신 출처만으로 그 대상의 오늘 상태까지 확정할 수 있을까? 기존 근거가 보장하는 범위와 새 확인이 필요한 부분을 구분해줘.");
                case "current" -> request.setMessage("현재 공식 출처를 확인해줘. " + request.getMessage());
                case "medical" -> request.setMessage("의료 진단의 차이를 " + request.getMessage());
                case "evidence-needed" -> request.setMessage("evidence_needed: " + request.getMessage());
                case "medication" -> request.setMessage("아스피린과 와파린을 함께 복용해도 되는지를 일반적인 개념으로 3문장만 설명해줘. 외부 검색이 필요한 주제는 아니야.");
                case "constitutional" -> {
                    var domainClassifier = mock(QueryDomainClassifier.class);
                    when(domainClassifier.classify(anyString())).thenAnswer(invocation -> {
                        TraceStore.put("blackbox.risk.routingDecision", "BLOCK");
                        return com.example.lms.rag.model.QueryDomain.GENERAL;
                    });
                    ReflectionTestUtils.setField(fixture.workflow(), "queryDomainClassifier", domainClassifier);
                }
                default -> { }
            }
            ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of());
            if ("scope-no-search".equals(variant)) {
                verify(hybrid, never()).retrieveAll(anyList(), anyInt(), nullable(Long.class), anyMap());
                verify(retriever, never()).retrieve(any());
            }
            if (!"concept".equals(variant) && !"concept-reference".equals(variant)) {
                assertFalse("retrieval_off_direct".equals(TraceStore.get("chat.disambiguation.skipReason")),
                        "a protected request must not enter the general-concept direct route: " + variant);
                return;
            }
            assertEquals(answer, result.content());
            verify(hybrid, never()).retrieveAll(anyList(), anyInt(), nullable(Long.class), anyMap());
            verify(retriever, never()).retrieve(any());
            verifyNoInteractions(fixture.verifier(), fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
            assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
        } finally {
            clearWorkflowState();
        }
    }


    @ParameterizedTest
    @ValueSource(strings = {"unresolved", "resolved", "dictionary-seed", "unchanged-high"})
    void unresolvedPriorComparisonAsksBeforeFreshRetrieval(String variant) {
        boolean resolved = "resolved".equals(variant);
        MemoryHoldFixture fixture = memoryHoldFixture();
        ReflectionTestUtils.setField(fixture.workflow(), "hybridTopK", 5);
        for (String field : List.of("keepNBrief", "keepNStd", "keepNDeep", "keepNUltra"))
            ReflectionTestUtils.setField(fixture.workflow(), field, 5);
        var plate = mock(com.example.lms.artplate.ArtPlateSpec.class);
        when(plate.webTopK()).thenReturn(5);
        when(plate.vecTopK()).thenReturn(5);
        when(plate.webBudgetMs()).thenReturn(2_000);
        var plateGate = mock(com.example.lms.artplate.NineArtPlateGate.class);
        when(plateGate.decide(any())).thenReturn(plate);
        ReflectionTestUtils.setField(fixture.workflow(), "nineArtPlateGate", plateGate);
        ReflectionTestUtils.setField(fixture.workflow(), "rescueCount", new java.util.concurrent.atomic.AtomicLong());
        ReflectionTestUtils.setField(fixture.workflow(), "env", new MockEnvironment());
        var clarification = new com.example.lms.service.disambiguation.DisambiguationResult();
        clarification.setScore(resolved ? 0.9 : 0.0);
        clarification.setRewrittenQuery(resolved ? "원신 자료 A와 자료 B의 확인 가능한 갱신일 비교" : null);
        if ("dictionary-seed".equals(variant) || "unchanged-high".equals(variant)) {
            clarification.setScore(1.0);
            clarification.setDetectedCategory("dictionary-seed".equals(variant) ? "DICTIONARY_TERM" : "GENERAL");
            clarification.setRewrittenQuery("앞에서 확인한 원신 자료 두 개를 비교해줘. 어느 두 자료인지 또는 비교 기준이 불명확하면 먼저 확인 질문을 해줘.");
        }
        var disambiguation = mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class);
        when(disambiguation.clarify(anyString(), anyList())).thenReturn(clarification);
        ReflectionTestUtils.setField(fixture.workflow(), "disambiguationService", disambiguation);
        var preprocessor = (QueryContextPreprocessor) ReflectionTestUtils.getField(fixture.workflow(), "qcPreprocessor");
        when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
        var unrelated = List.of(dev.langchain4j.rag.content.Content.from(
                dev.langchain4j.data.segment.TextSegment.from("unrelated election candidates",
                        dev.langchain4j.data.document.Metadata.from("url", "https://example.test/votes"))));
        var hybrid = mock(com.example.lms.service.rag.HybridRetriever.class);
        when(hybrid.retrieveAll(anyList(), anyInt(), nullable(Long.class), anyMap())).thenReturn(unrelated);
        var rag = mock(com.example.lms.service.rag.LangChainRAGService.class);
        var retriever = mock(dev.langchain4j.rag.content.retriever.ContentRetriever.class);
        when(rag.asContentRetriever(nullable(String.class))).thenReturn(retriever);
        when(retriever.retrieve(any())).thenReturn(unrelated);
        ReflectionTestUtils.setField(fixture.workflow(), "hybridRetriever", hybrid);
        ReflectionTestUtils.setField(fixture.workflow(), "ragSvc", rag);
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("앞에서 확인한 원신 자료 두 개를 비교해줘. 어느 두 자료인지 또는 비교 기준이 불명확하면 먼저 확인 질문을 해줘.")
                    .model("release-gate-recording-fake").maxTokens(256)
                    .mode("FACT").memoryMode("FULL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true).build();
            var context = new ChatConversationContext(List.of(new ChatConversationContext.Turn(
                    "비교 기준은 확인 가능한 갱신일",
                    resolved ? "자료 A https://example.org/a 자료 B https://example.org/b"
                            : "자료 A https://example.org/a")), "", List.of());
            ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of(), context);
            verify(disambiguation).clarify(eq(request.getMessage()), eq(context.interpretationHistory()));
            if (resolved) {
                verify(hybrid, atLeastOnce()).retrieveAll(anyList(), anyInt(), nullable(Long.class), anyMap());
                assertFalse("clarification:prior-comparison".equals(result.modelUsed()));
                return;
            }
            verify(hybrid, never()).retrieveAll(anyList(), anyInt(), nullable(Long.class), anyMap());
            verify(retriever, never()).retrieve(any());
            verifyNoInteractions(fixture.model(), fixture.verifier(), fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
            assertTrue(result.content().contains("두 자료") && result.content().contains("?") && result.content().contains("비교 기준"));
            assertEquals("clarification:prior-comparison", result.modelUsed());
            assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
            assertEquals("prior_comparison_unresolved", TraceStore.get("chat.disambiguation.reasonCode"));
        } finally {
            clearWorkflowState();
        }
    }

    @Test
    void verificationNotRequiredPreservesDirectResponse() {
        ChatWorkflow.FinalVerificationReleaseDecision decision = decide(
                "direct answer", false, "not_run", false, false);

        assertEquals("direct answer", decision.content());
        assertEquals("NOT_REQUIRED", decision.releaseStatus());
        assertEquals("verification_not_required", decision.reasonCode());
        assertTrue(decision.releaseAllowed());
        assertTrue(decision.knowledgeWriteAllowed());
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

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "fail_soft"})
    void unavailableVerdictPreservesOrdinaryDraftAndDeniesMemory(String status) {
        ChatWorkflow.FinalVerificationReleaseDecision decision = ChatWorkflow.applyFinalVerificationReleaseGate(
                "ordinary draft", true, status, false, false, true);

        assertEquals("ordinary draft", decision.content());
        assertEquals("UNVERIFIED", decision.releaseStatus());
        assertEquals("verification_unknown_release", decision.reasonCode());
        assertTrue(decision.releaseAllowed());
        assertFalse(decision.knowledgeWriteAllowed());
    }

    @Test
    void failSoftTelemetryCannotBecomeInconsistentPositiveVerdict() {
        ChatWorkflow.FinalVerificationReleaseDecision decision = ChatWorkflow.applyFinalVerificationReleaseGate(
                "ordinary draft", true, "fail_soft", true, false, true);
        assertEquals("ordinary draft", decision.content());
        assertEquals("verification_unknown_release", decision.reasonCode());
        assertTrue(decision.releaseAllowed());
        assertFalse(decision.knowledgeWriteAllowed());
    }

    @Test
    void emptyEvidencePreservesUnknownVerdictReleaseReasonAndMemoryDenial() {
        for (var state : new ChatWorkflow.EvidenceReleaseState[]{
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY}) {
            var base = new ChatWorkflow.FinalVerificationReleaseDecision(
                    "ordinary draft", "UNVERIFIED", "verification_unknown_release", true, false, false);
            var decision = ChatWorkflow.applyEvidenceReleasePolicy(base, state, false, false);
            assertEquals("ordinary draft", decision.content());
            assertEquals("verification_unknown_release", decision.reasonCode());
            assertTrue(decision.releaseAllowed());
            assertFalse(decision.knowledgeWriteAllowed());
        }
    }

    @Test
    void explicitEvidenceAndScopedUnknownVerdictsKeepTheirHold() {
        var decision = ChatWorkflow.applyFinalVerificationReleaseGate(
                "scoped draft", true, "unknown", false, false, false);
        assertEquals("HOLD", decision.releaseStatus());
        assertFalse(decision.releaseAllowed());
        assertFalse(decision.knowledgeWriteAllowed());
        var ordinary = ChatWorkflow.applyFinalVerificationReleaseGate(
                "ordinary draft", true, "unknown", false, false, true);
        var explicit = ChatWorkflow.applyEvidenceReleasePolicy(ordinary,
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE, true, false);
        assertFalse(explicit.releaseAllowed());
        assertEquals("evidence_release_metadata_incomplete", explicit.reasonCode());
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
    void ordinaryInsufficientEvidenceGetsBoundedGuidanceWithoutTheUnsupportedDraft() {
        var base = decide("Invented signature weapon: SECRET_UNSUPPORTED", true, "insufficient", true, false);
        var decision = recoverInsufficient(base,
                "그럼 합성 캐릭터의 전무가 뭐냐?", List.of(), List.of(), false, false);
        assertTrue(decision.releaseAllowed());
        assertEquals("UNVERIFIED", decision.releaseStatus());
        assertEquals("verification_insufficient_guidance", decision.reasonCode());
        assertFalse(decision.knowledgeWriteAllowed());
        assertFalse(decision.content().contains("SECRET_UNSUPPORTED"));
        assertTrue(decision.content().contains("출처"));
        assertTrue(decision.releasedEvidence().isEmpty());
    }

    @Test
    void insufficientRecoveryPreservesRejectedExplicitEvidenceAndOwnerScope() {
        for (String status : List.of("rejected", "unknown", "inconsistent")) {
            var base = decide("unsupported draft", true, status, true, false);
            assertSame(base, recoverInsufficient(base,
                    "ordinary question", List.of(), List.of(), false, false));
        }
        var held = decide("unsupported draft", true, "insufficient", true, false);
        assertSame(held, recoverInsufficient(held,
                "ordinary question", List.of(), List.of(), true, false));
        assertSame(held, recoverInsufficient(held,
                "ordinary question", List.of(), List.of(), false, true));
    }

    private static ChatWorkflow.FinalVerificationReleaseDecision recoverInsufficient(
            ChatWorkflow.FinalVerificationReleaseDecision base, String query,
            List<dev.langchain4j.rag.content.Content> raw, List<RagEvidenceMetadata> evidence,
            boolean required, boolean scoped) {
        return ReflectionTestUtils.invokeMethod(ChatWorkflow.class, "applyInsufficientVerificationRelease",
                base, query, raw, evidence, required, scoped);
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
                false);

        assertEquals(ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY, state);
        assertEquals("evidence_needed", decision.content());
        assertEquals("HOLD", decision.releaseStatus());
        assertEquals("evidence_required_empty", decision.reasonCode());
        assertFalse(decision.releaseAllowed());
        assertTrue(decision.evidencePolicyApplied());
    }

    @Test
    void zeroLocatorFailureUnavailableDisabledAndLateEvidenceReleaseUnlessRequired() {
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
            assertEquals(ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE, state);

            ChatWorkflow.FinalVerificationReleaseDecision released = ChatWorkflow.applyEvidenceReleasePolicy(
                    decide("unsupported draft", false, "not_run", false, false),
                    state,
                    false,
                    false);
            assertEquals("unsupported draft", released.content());
            assertEquals("NOT_REQUIRED", released.releaseStatus());
            assertEquals("evidence_unverified_release", released.reasonCode());
            assertTrue(released.releaseAllowed());
            assertFalse(released.evidencePolicyApplied());
            assertFalse(released.knowledgeWriteAllowed());

            ChatWorkflow.FinalVerificationReleaseDecision held = ChatWorkflow.applyEvidenceReleasePolicy(
                    decide("unsupported draft", false, "not_run", false, false),
                    state,
                    true,
                    false);
            assertEquals("evidence_needed: attribution unavailable / verify retrieval evidence", held.content());
            assertFalse(held.content().contains("unsupported draft"));
            assertEquals("HOLD", held.releaseStatus());
            assertEquals("evidence_release_metadata_incomplete", held.reasonCode());
            assertFalse(held.releaseAllowed());
            assertTrue(held.evidencePolicyApplied());
            assertFalse(held.knowledgeWriteAllowed());
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
                false);
        assertEquals(verifierDenied, preservedVerifier);

        ChatWorkflow.FinalVerificationReleaseDecision fallbackHold = ChatWorkflow.applyEvidenceReleasePolicy(
                decide("safe fallback", false, "not_run", false, false),
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                true,
                false);
        assertEquals("evidence_needed: attribution unavailable / verify retrieval evidence",
                fallbackHold.content());
        assertEquals("evidence_release_metadata_incomplete", fallbackHold.reasonCode());
        assertFalse(fallbackHold.releaseAllowed());
        assertTrue(fallbackHold.evidencePolicyApplied());
        assertFalse(fallbackHold.knowledgeWriteAllowed());

        ChatWorkflow.FinalVerificationReleaseDecision releasedFallback =
                ChatWorkflow.applyEvidenceReleasePolicy(
                        new ChatWorkflow.FinalVerificationReleaseDecision(
                                "safe fallback",
                                "NOT_REQUIRED",
                                "verification_not_required",
                                true,
                                false,
                                false),
                        ChatWorkflow.EvidenceReleaseState.NOT_APPLICABLE,
                        false,
                        false);
        assertEquals("safe fallback", releasedFallback.content());
        assertTrue(releasedFallback.releaseAllowed());
        assertFalse(releasedFallback.knowledgeWriteAllowed());
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
                "return finishEarlyResult(ChatResult.of(agentDebugDirectAnswer",
                "String draft;");
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
                "return finishEarlyResult(ChatResult.of(",
                "catch (Exception e)");
        assertEarlyReturnBeforeRelease(source, promotion, release,
                "LlmConfigurationException cfg = unwrapLlmConfigurationException",
                "return finishEarlyResult(ChatResult.of(userMsg",
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
                    List.of("release-gate-local"), null, owner, request.getMessage());
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
    void finalVerifierReceivesTheSameSessionFactsUsedByGeneration() {
        MemoryHoldFixture fixture = memoryHoldFixture();
        MemoryHandler memory = (MemoryHandler) ReflectionTestUtils.getField(fixture.workflow(), "memoryHandler");
        String facts = "코드워드 바람; 가운데 단계 검토; 제한 2개; 형식 짧은 표.";
        when(memory.loadForSession(nullable(Long.class))).thenReturn(facts);
        when(fixture.model().chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(facts)).build());
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("이 대화의 최신 설정 네 가지를 표로 알려주세요.")
                    .model("release-gate-recording-fake")
                    .maxTokens(256).mode("FACT").memoryMode("SESSION")
                    .searchMode(SearchMode.AUTO).useWebSearch(false).useRag(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(null, true))
                    .useVerification(true).attachmentIds(List.of("release-gate-local")).build();
            request.bindAttachmentOwnerIdentity(AttachmentOwnerIdentity.forAnonymous("release-gate-owner"));

            fixture.workflow().continueChat(request, ignored -> List.of());

            ArgumentCaptor<List<ChatMessage>> generation = ArgumentCaptor.forClass(List.class);
            verify(fixture.model(), atLeastOnce()).chat(generation.capture());
            assertTrue(generation.getAllValues().stream().flatMap(List::stream)
                    .map(message -> message instanceof UserMessage user ? user.singleText()
                            : message instanceof dev.langchain4j.data.message.SystemMessage system ? system.text() : "")
                    .anyMatch(text -> text.contains(facts)));
            ArgumentCaptor<String> verifiedMemory = ArgumentCaptor.forClass(String.class);
            verify(fixture.verifier()).verifyDetailed(anyString(), nullable(String.class),
                    verifiedMemory.capture(), anyString(), anyString(), eq(false));
            assertEquals(facts, verifiedMemory.getValue(),
                    "final verification must retain the session facts already used to generate its draft");
        } finally {
            clearWorkflowState();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Blue.", "안녕하세요."})
    void shortNonblankAnswerSurvivesEmptyRagWithoutDurableMemory(String answer) {
        MemoryHoldFixture fixture = memoryHoldFixture();
        ReflectionTestUtils.setField(fixture.workflow(), "disambiguationService",
                mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class));
        var preprocessor = (QueryContextPreprocessor) ReflectionTestUtils.getField(fixture.workflow(), "qcPreprocessor");
        when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
        var rag = mock(com.example.lms.service.rag.LangChainRAGService.class);
        var retriever = mock(dev.langchain4j.rag.content.retriever.ContentRetriever.class);
        when(rag.asContentRetriever(nullable(String.class))).thenReturn(retriever);
        when(retriever.retrieve(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(fixture.workflow(), "ragSvc", rag);
        when(fixture.model().chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(answer)).build());
        when(fixture.attribution().appendFinalEvidenceAppendix(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message(answer.equals("Blue.") ? "Name a primary color."
                            : "[codex-test] 안녕? 한국어로 한 문장 인사해 줘.")
                    .model("release-gate-recording-fake").maxTokens(256)
                    .mode("FACT").memoryMode("FULL").searchMode(SearchMode.AUTO)
                    .useWebSearch(false).useRag(true).useVerification(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false, true))
                    .build();
            ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of());
            assertEquals(answer, result.content());
            verify(retriever).retrieve(any());
            assertEquals("evidence_unverified_release", TraceStore.get("finalAnswer.releaseReason"));
            assertEquals(true, TraceStore.get("finalAnswer.releaseAllowed"));
            assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
        }
    }

    @Test
    void evidenceZeroWithoutDirectivePublishesDraftButSkipsDurableMemory() {
        MemoryHoldFixture fixture = memoryHoldFixture();
        AttachmentOwnerIdentity owner = AttachmentOwnerIdentity.forAnonymous("release-gate-owner");
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("Explain the Heisenberg uncertainty principle in one sentence.")
                    .model("release-gate-recording-fake")
                    .maxTokens(256)
                    .mode("FACT")
                    .memoryMode("FULL")
                    .searchMode(SearchMode.AUTO)
                    .useWebSearch(false)
                    .useRag(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(null, true))
                    .useVerification(true)
                    .attachmentIds(List.of("release-gate-local"))
                    .build();
            request.bindAttachmentOwnerIdentity(owner);

            ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of());

            assertTrue(result.content().contains("verified draft"));
            assertFalse(result.content().contains("evidence_needed"));
            assertEquals("metadata_incomplete", TraceStore.get("finalAnswer.evidenceReleaseState"));
            assertEquals("APPROVE", TraceStore.get("finalAnswer.releaseStatus"));
            assertEquals("evidence_unverified_release", TraceStore.get("finalAnswer.releaseReason"));
            assertEquals(true, TraceStore.get("finalAnswer.releaseAllowed"));
            assertEquals(false, TraceStore.get("finalAnswer.evidenceReleaseApplied"));
            assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
            assertEquals("memory_policy_denied", TraceStore.get("finalAnswer.memoryDenyReason"));

            verify(fixture.verifier()).verifyDetailed(
                    anyString(), nullable(String.class), nullable(String.class),
                    anyString(), anyString(), anyBoolean());
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
                    List.of("release-gate-local"), null, foreign, request.getMessage());
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
            when(builder.buildUserPreferences(any(com.example.lms.prompt.PromptContext.class))).thenReturn("");
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
            when(router.routeMain(anyString(), nullable(String.class), nullable(String.class), anyInt(),
                    nullable(String.class), anyString(), anyBoolean()))
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
            verify(fixture.modelRouter()).routeMain(
                    anyString(), nullable(String.class), anyString(), anyInt(), routeCaptor.capture(),
                    anyString(), anyBoolean());
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
                verify(understanding).prepare(anyString(), anyString(), anyBoolean());
                verify(understanding).commitPrepared(anyString(), anyString(),
                        nullable(com.example.lms.dto.answer.AnswerUnderstanding.class), any());
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

    @Test
    void understandingPreparationDoesNotHoldRunGateOrCommitAfterCancellation() throws Exception {
        MemoryHoldFixture fixture = memoryHoldFixture();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var run = registry.beginOrJoin(9101L).context();
        var summaryService = mock(com.example.lms.service.understanding.AnswerUnderstandingService.class,
                invocation -> {
                    if (!invocation.getMethod().getName().equals("understand")) return null;
                    entered.countDown();
                    assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    return new com.example.lms.dto.answer.AnswerUnderstanding(
                            "synthetic summary", List.of(), List.of(), List.of(), List.of(),
                            List.of(), List.of(), List.of(), List.of(), 0.9);
                });
        var memory = mock(MemoryReinforcementService.class);
        var emitter = mock(com.example.lms.service.chat.ChatStreamEmitter.class);
        var history = mock(ChatHistoryService.class);
        ReflectionTestUtils.setField(fixture.workflow(), "chatHistoryService", mock(ChatHistoryService.class));
        var interceptor = new UnderstandAndMemorizeInterceptor(summaryService, memory, emitter, history);
        ReflectionTestUtils.setField(interceptor, "globalEnabled", true);
        ReflectionTestUtils.setField(fixture.workflow(), "understandAndMemorizeInterceptor", interceptor);
        String answer = "Synthetic verified fixture response with sufficient detail for the memory policy control.";
        when(fixture.verifier().verifyDetailed(anyString(), nullable(String.class), nullable(String.class),
                anyString(), anyString(), anyBoolean()))
                .thenReturn(new FactVerifierService.DetailedVerificationResult(answer, "pass", true, true));
        when(fixture.attribution().promoteForPromptDetailed(
                anyString(), nullable(List.class), nullable(List.class), anyList(), any(), anyBoolean()))
                .thenReturn(promoted(List.of(evidence("L1", "LOCAL_DOC", "release-gate-local", "docs/fixture.txt")),
                        0, 0, 0, 0, 1, 1));
        when(fixture.attribution().appendFinalEvidenceAppendix(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var request = ChatRequestDto.builder().sessionId(9101L).message("Explain the synthetic fixture.")
                .model("release-gate-recording-fake").maxTokens(256).mode("FACT").memoryMode("FULL")
                .searchMode(SearchMode.OFF).useWebSearch(false).useRag(false).useVerification(true)
                .understandingEnabled(true).attachmentIds(List.of("release-gate-local"))
                .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false, false)).build();
        request.bindAttachmentOwnerIdentity(AttachmentOwnerIdentity.forAnonymous("release-gate-owner"));
        try {
            var result = executor.submit(() -> {
                try (var scope = com.example.lms.service.chat.ChatRunExecutionContext.bind(run)) {
                    return fixture.workflow().continueChat(request, ignored -> List.of());
                } finally { clearWorkflowState(); }
            });
            if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                result.get(1, java.util.concurrent.TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.fail("summary preparation must run");
            }
            var cancelled = executor.submit(() -> registry.cancelExact(9101L, run.clientToken()));
            assertTrue(org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> cancelled.get(1, java.util.concurrent.TimeUnit.SECONDS),
                    "cancellation must finish while summary preparation is still blocked"));
            assertEquals(1L, release.getCount());
            release.countDown();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof java.util.concurrent.CancellationException);
            verifyNoInteractions(memory, history, emitter,
                    fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            ReflectionTestUtils.invokeMethod(registry, "shutdown");
            clearWorkflowState();
        }
    }

    @Test
    void completedAnswerSurvivesFinalPostprocessFailureWithoutKnowledgeWrites() {
        MemoryHoldFixture fixture = memoryHoldFixture();
        clearWorkflowState();
        try {
            String answer = "Position and momentum cannot both have arbitrarily small uncertainty.";
            when(fixture.model().chat(anyList())).thenReturn(ChatResponse.builder()
                    .aiMessage(AiMessage.from(answer)).build());
            FinalAnswerPostProcessor processor = mock(FinalAnswerPostProcessor.class);
            when(processor.process(any())).thenAnswer(invocation -> {
                FinalAnswerPostProcessor.Request input = invocation.getArgument(0);
                assertTrue(input.candidate().contains(answer));
                verify(fixture.model(), org.mockito.Mockito.atLeastOnce()).chat(anyList());
                throw new java.util.concurrent.CompletionException(
                        new IllegalStateException("synthetic-private-postprocess-message"));
            });
            ReflectionTestUtils.setField(fixture.workflow(), "finalAnswerPostProcessor", processor);
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("Explain the uncertainty principle in one sentence.")
                    .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                    .memoryMode("FULL").searchMode(SearchMode.OFF)
                    .useWebSearch(false).useRag(false).useVerification(false).build();

            ChatResult result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> fixture.workflow().continueChat(request, ignored -> List.of()));

            assertTrue(result.content().startsWith("[품질 저하]"));
            assertTrue(result.content().endsWith(answer));
            assertFalse(result.content().contains("synthetic-private-postprocess-message"));
            assertEquals("postprocess_failed", TraceStore.get("finalAnswer.postprocess.reason"));
            assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
            assertEquals("postprocess_failed", TraceStore.get("finalAnswer.memoryDenyReason"));
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
            verify(fixture.model(), org.mockito.Mockito.atLeastOnce()).chat(anyList());
        } finally {
            clearWorkflowState();
        }
    }

    @Test
    void postprocessFailurePreservesEvidenceHoldInsteadOfPublishingDraft() {
        MemoryHoldFixture fixture = memoryHoldFixture();
        clearWorkflowState();
        try {
            FinalAnswerPostProcessor processor = mock(FinalAnswerPostProcessor.class);
            when(processor.process(any())).thenThrow(new IllegalStateException("synthetic failure"));
            ReflectionTestUtils.setField(fixture.workflow(), "finalAnswerPostProcessor", processor);
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("If no reliable evidence is available, reply with evidence_needed.")
                    .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                    .memoryMode("FULL").searchMode(SearchMode.AUTO)
                    .useWebSearch(false).useRag(false).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(null, null))
                    .attachmentIds(List.of("release-gate-local")).build();
            request.bindAttachmentOwnerIdentity(AttachmentOwnerIdentity.forAnonymous("release-gate-owner"));
            ChatResult result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> fixture.workflow().continueChat(request, ignored -> List.of()));
            assertTrue(result.content().contains("evidence_needed"));
            assertFalse(result.content().contains("verified draft"));
            assertEquals(false, TraceStore.get("finalAnswer.releaseAllowed"));
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
        }
    }

    @Test
    void finalPostprocessSalvageNeverConsumesWrappedControlExceptions() {
        var terminal = new com.example.lms.llm.gateway.LlmResponseTerminalException(
                "content_filter", com.example.lms.llm.gateway.LlmFailureClass.NONE,
                null, null, "incomplete", "content_filter", null);
        for (RuntimeException control : List.of(new java.util.concurrent.CancellationException(),
                new ChatHistoryService.SessionQuotaExceededException(), terminal)) {
            MemoryHoldFixture fixture = memoryHoldFixture();
            clearWorkflowState();
            try {
                var wrapped = new java.util.concurrent.CompletionException(control);
                FinalAnswerPostProcessor processor = mock(FinalAnswerPostProcessor.class);
                when(processor.process(any())).thenThrow(wrapped);
                ReflectionTestUtils.setField(fixture.workflow(), "finalAnswerPostProcessor", processor);
                ChatRequestDto request = ChatRequestDto.builder().message("Explain photosynthesis.")
                        .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                        .memoryMode("FULL").searchMode(SearchMode.OFF)
                        .useWebSearch(false).useRag(false).useVerification(false).build();
                RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                        () -> fixture.workflow().continueChat(request, ignored -> List.of()));
                org.junit.jupiter.api.Assertions.assertSame(wrapped, failure);
                verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
            } finally {
                clearWorkflowState();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "rejected"})
    void chosenReleaseSurvivesPostprocessFailureWithoutMemoryWrites(String status) {
        MemoryHoldFixture fixture = memoryHoldFixture();
        clearWorkflowState();
        try {
            String draft = "Synthetic ordinary model draft.";
            String expected = "unknown".equals(status)
                    ? draft
                    : "Information unavailable: final verification rejected the draft.";
            when(fixture.model().chat(anyList())).thenReturn(ChatResponse.builder()
                    .aiMessage(AiMessage.from(draft)).build());
            when(fixture.verifier().verifyDetailed(anyString(), nullable(String.class),
                    nullable(String.class), anyString(), anyString(), anyBoolean()))
                    .thenReturn(new FactVerifierService.DetailedVerificationResult(
                            draft, status, !"unknown".equals(status), false));
            when(fixture.attribution().promoteForPromptDetailed(anyString(), nullable(List.class),
                    nullable(List.class), anyList(), any(), anyBoolean()))
                    .thenReturn(promoted(List.of(evidence("L1", "LOCAL_DOC", "release-gate-local",
                            "docs/fixture.txt")), 0, 0, 0, 0, 1, 1));
            when(fixture.attribution().appendFinalEvidenceAppendix(anyString(), anyList()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            FinalAnswerPostProcessor processor = mock(FinalAnswerPostProcessor.class);
            when(processor.process(any())).thenAnswer(invocation -> {
                FinalAnswerPostProcessor.Request input = invocation.getArgument(0);
                assertEquals(expected, input.candidate());
                verify(fixture.model(), org.mockito.Mockito.atLeastOnce()).chat(anyList());
                throw new java.util.concurrent.CompletionException(
                        new IllegalStateException("synthetic-private-postprocess-message"));
            });
            ReflectionTestUtils.setField(fixture.workflow(), "finalAnswerPostProcessor", processor);
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("Explain the synthetic fixture.")
                    .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                    .memoryMode("FULL").searchMode(SearchMode.OFF)
                    .useWebSearch(false).useRag(false).useVerification(true)
                    .attachmentIds(List.of("release-gate-local"))
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false, false)).build();
            request.bindAttachmentOwnerIdentity(AttachmentOwnerIdentity.forAnonymous("release-gate-owner"));

            ChatResult result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> fixture.workflow().continueChat(request, ignored -> List.of()));

            if ("unknown".equals(status)) {
                assertTrue(result.content().endsWith(expected));
                assertTrue(result.content().contains("[품질 저하]"));
            } else {
                assertEquals(expected, result.content());
            }
            assertEquals("unknown".equals(status), result.content().contains(draft));
            assertFalse(result.content().contains("synthetic-private-postprocess-message"));
            assertEquals("unknown".equals(status) ? "UNVERIFIED" : "REJECT",
                    TraceStore.get("finalAnswer.releaseStatus"));
            assertEquals("unknown".equals(status) ? "verification_unknown_release" : "verification_rejected",
                    TraceStore.get("finalAnswer.releaseReason"));
            assertEquals("unknown".equals(status), TraceStore.get("finalAnswer.releaseAllowed"));
            assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
            assertEquals("postprocess_failed", TraceStore.get("finalAnswer.memoryDenyReason"));
            verify(fixture.verifier()).verifyDetailed(anyString(), nullable(String.class),
                    nullable(String.class), anyString(), anyString(), anyBoolean());
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor(),
                    ReflectionTestUtils.getField(fixture.workflow(), "understandAndMemorizeInterceptor"));
        } finally {
            clearWorkflowState();
        }
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
        when(modelRouter.routeMain(
                anyString(), nullable(String.class), nullable(String.class), anyInt(),
                nullable(String.class), anyString(), anyBoolean()))
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
                        AttachmentOwnerIdentity.forAnonymous("release-gate-owner")),
                anyString()))
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
        ReflectionTestUtils.setField(workflow, "understandAndMemorizeInterceptor", mock(UnderstandAndMemorizeInterceptor.class));
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"pass,false,false", "corrected,false,false",
            "insufficient,false,false", "rejected,false,false", "unknown,false,false",
            "pass,true,false", "pass,false,true"})
    void sourceSupportedComparisonMustReachSoleVerifierWithItsOwnVerdict(
            String subsetStatus, boolean blankDraft, boolean middleBody) {
        MemoryHoldFixture fixture = memoryHoldFixture();
        ReflectionTestUtils.setField(fixture.workflow(), "hybridTopK", 2);
        for (String field : List.of("keepNBrief", "keepNStd", "keepNDeep", "keepNUltra"))
            ReflectionTestUtils.setField(fixture.workflow(), field, 2);
        var plate = mock(com.example.lms.artplate.ArtPlateSpec.class);
        when(plate.webTopK()).thenReturn(2);
        when(plate.webBudgetMs()).thenReturn(2_000);
        var plateGate = mock(com.example.lms.artplate.NineArtPlateGate.class);
        when(plateGate.decide(any())).thenReturn(plate);
        ReflectionTestUtils.setField(fixture.workflow(), "nineArtPlateGate", plateGate);
        ReflectionTestUtils.setField(fixture.workflow(), "rescueCount", new java.util.concurrent.atomic.AtomicLong());
        var composer = mock(com.example.lms.service.rag.EvidenceAnswerComposer.class);
        when(composer.compose(anyString(), anyList(), anyBoolean()))
                .thenThrow(new IllegalStateException("synthetic composer unavailable"));
        ReflectionTestUtils.setField(fixture.workflow(), "evidenceAnswerComposer", composer);
        var guard = (EvidenceAwareGuard) ReflectionTestUtils.getField(fixture.workflow(), "evidenceAwareGuard");
        when(guard.degradeToEvidenceList(anyList()))
                .thenThrow(new IllegalStateException("synthetic degradation unavailable"));
        String query = "원신에서 루미단이 쎄냐?하늘꽃이 쎄냐?";
        String originalDraft = blankDraft ? " " : "정보 없음: 루미단이 항상 더 강하다는 주장을 확인할 수 없습니다.";
        String left = "루미단은 원신 버전 9.9에서 기본 무기와 단독 파티 조건의 근접 공격을 사용한다.";
        String right = "하늘꽃은 원신 버전 9.9에서 기본 무기와 단독 파티 조건의 원거리 공격을 사용한다.";
        var rawWeb = List.of(
                dev.langchain4j.rag.content.Content.from(dev.langchain4j.data.segment.TextSegment.from(
                        middleBody ? "Left navigation. ".repeat(210) + "\n" + left + "\n" + "Left footer. ".repeat(260) : left,
                        dev.langchain4j.data.document.Metadata.from(Map.of("url", "https://example.org/left", "kind", "WEB")))),
                dev.langchain4j.rag.content.Content.from(dev.langchain4j.data.segment.TextSegment.from(
                        middleBody ? "Right menu. ".repeat(260) + "\n" + right + "\n" + "Right footer. ".repeat(260) : right,
                        dev.langchain4j.data.document.Metadata.from(Map.of("url", "https://example.org/right", "kind", "WEB")))));
        var evidence = List.of(evidence("W1", "WEB", "https://example.org/left", null),
                evidence("W2", "WEB", "https://example.org/right", null));
        String supported = com.example.lms.service.rag.EvidenceAnswerComposer
                .supportedDescriptionExcerpt(query, rawWeb, evidence).orElseThrow().content();
        when(fixture.model().chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(originalDraft)).build());
        ReflectionTestUtils.setField(fixture.workflow(), "disambiguationService",
                mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class));
        var preprocessor = (QueryContextPreprocessor) ReflectionTestUtils.getField(fixture.workflow(), "qcPreprocessor");
        when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
        when(fixture.attribution().promoteForPromptDetailed(anyString(), nullable(List.class),
                nullable(List.class), anyList(), any(), anyBoolean()))
                .thenReturn(promoted(evidence, 2, 2, 0, 0, 0, 0));
        when(fixture.attribution().appendFinalEvidenceAppendix(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.verifier().verifyDetailed(anyString(), nullable(String.class), nullable(String.class),
                anyString(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            String answer = invocation.getArgument(3);
            if ("corrected".equals(subsetStatus)) answer = answer.replace(
                    "> " + left + "\n\n[W1](https://example.org/left)\n", "");
            return new FactVerifierService.DetailedVerificationResult(answer, subsetStatus,
                    !"unknown".equals(subsetStatus), "pass".equals(subsetStatus) || "corrected".equals(subsetStatus), false);
        });
        clearWorkflowState();
        try {
            var request = ChatRequestDto.builder().message(query).model("release-gate-recording-fake")
                    .maxTokens(256).mode("FACT").memoryMode("FULL").polish(false)
                    .searchMode(SearchMode.AUTO).useWebSearch(true).useRag(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, false))
                    .useVerification(true).build();
            ChatResult result = fixture.workflow().continueChat(request,
                    (ChatWorkflow.WebEvidenceSupplier) ignored -> rawWeb);
            if (blankDraft) {
                // Blank model output is rejected at the generation boundary before final verification.
                verify(fixture.verifier(), never()).verifyDetailed(anyString(), nullable(String.class),
                        nullable(String.class), anyString(), anyString(), anyBoolean());
                assertFalse(result.content().isBlank());
                assertFalse(result.content().contains("항상 더 강"));
                verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
                assertEquals(com.example.lms.service.verification.FactVerificationStatus.INSUFFICIENT,
                        new com.example.lms.service.verification.FactStatusClassifier(null)
                                .classify(query, left + right, originalDraft, "synthetic"));
                return;
            }
            ArgumentCaptor<String> candidate = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> verifierContext = ArgumentCaptor.forClass(String.class);
            verify(fixture.verifier(), times(1)).verifyDetailed(anyString(), verifierContext.capture(),
                    nullable(String.class), candidate.capture(), anyString(), anyBoolean());
            assertEquals(supported, candidate.getValue(), "supported features must be verified separately from the unsupported winner");
            if (!blankDraft) assertFalse(candidate.getValue().contains(originalDraft));
            assertTrue(verifierContext.getValue().contains(left));
            assertTrue(verifierContext.getValue().contains(right));
            assertTrue(verifierContext.getValue().contains("https://example.org/left"));
            assertTrue(verifierContext.getValue().contains("https://example.org/right"));
            assertTrue(verifierContext.getValue().length() <= 8_000);
            assertFalse(verifierContext.getValue().contains("정보 없음"));
            assertFalse(verifierContext.getValue().contains("우열은 확인되지"));
            assertEquals("heuristic_insufficient", TraceStore.get("finalAnswer.originalDraftStatus"));
            assertEquals(false, TraceStore.get("finalAnswer.memorySaveAllowed"));
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
            if ("pass".equals(subsetStatus)) {
                assertTrue(result.content().contains(left)); assertTrue(result.content().contains(right));
                assertTrue(result.content().contains("[W1](https://example.org/left)"));
                assertTrue(result.content().contains("[W2](https://example.org/right)"));
                assertTrue(result.content().contains("9.9"));
            } else if ("corrected".equals(subsetStatus)) {
                assertFalse(result.content().contains(left), "final verifier correction must not be undone by prior-fallback restoration");
                assertTrue(result.content().contains(right));
                assertEquals(List.of("W2"), result.evidenceMetadata().stream().map(RagEvidenceMetadata::marker).toList());
            } else if ("unknown".equals(subsetStatus)) {
                assertEquals(true, TraceStore.get("finalAnswer.releaseAllowed"));
                assertEquals("verification_unknown_release", TraceStore.get("finalAnswer.releaseReason"));
                assertTrue(result.content().contains(left)); assertTrue(result.content().contains(right));
                assertFalse(result.content().contains(originalDraft));
            } else if ("insufficient".equals(subsetStatus)) {
                assertEquals(true, TraceStore.get("finalAnswer.releaseAllowed"));
                assertEquals("insufficient", TraceStore.get("finalAnswer.verificationStatus"));
                assertEquals("verification_insufficient_excerpt", TraceStore.get("finalAnswer.releaseReason"));
                assertTrue(result.content().contains(left)); assertTrue(result.content().contains(right));
                assertEquals(List.of("W1", "W2"), result.evidenceMetadata().stream().map(RagEvidenceMetadata::marker).toList());
                assertFalse(result.content().contains(originalDraft));
            } else {
                assertEquals(false, TraceStore.get("finalAnswer.releaseAllowed"));
                assertFalse(result.content().contains(left)); assertFalse(result.content().contains(right));
                assertFalse(String.valueOf(TraceStore.get("finalAnswer.releaseReason")).startsWith("verification_unavailable"));
            }
        } finally { clearWorkflowState(); }
    }

    @Test
    void twoTurnEvidenceIdentitySurvivesFinalFit() throws Exception {
        MemoryHoldFixture fixture = memoryHoldFixture();
        ReflectionTestUtils.setField(fixture.workflow(), "hybridTopK", 2);
        for (String field : List.of("keepNBrief", "keepNStd", "keepNDeep", "keepNUltra"))
            ReflectionTestUtils.setField(fixture.workflow(), field, 2);
        var plate = mock(com.example.lms.artplate.ArtPlateSpec.class);
        when(plate.webTopK()).thenReturn(2);
        when(plate.webBudgetMs()).thenReturn(2_000);
        var plateGate = mock(com.example.lms.artplate.NineArtPlateGate.class);
        when(plateGate.decide(any())).thenReturn(plate);
        ReflectionTestUtils.setField(fixture.workflow(), "nineArtPlateGate", plateGate);
        ReflectionTestUtils.setField(fixture.workflow(), "disambiguationService", mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class));
        ReflectionTestUtils.setField(fixture.workflow(), "cancelFlags", new java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>());
        ReflectionTestUtils.setField(fixture.workflow(), "chatHistoryService", mock(ChatHistoryService.class));
        ReflectionTestUtils.setField(fixture.workflow(), "rescueCount", new java.util.concurrent.atomic.AtomicLong());
        ReflectionTestUtils.setField(fixture.workflow(), "env", new MockEnvironment());
        String bodyA = "Synthetic Alpha is the fixture character.";
        String bodyB = "Synthetic Bravo is the recommended fixture weapon.";
        String locatorA = "https://example.org/identity-alpha";
        String locatorB = "https://example.org/weapon-bravo";
        var draftInputs = new java.util.ArrayList<List<dev.langchain4j.data.message.ChatMessage>>();
        var verifierInputs = new java.util.ArrayList<List<String>>();
        var appendixInputs = new java.util.ArrayList<List<RagEvidenceMetadata>>();
        when(fixture.model().chat(anyList())).thenAnswer(invocation -> {
            draftInputs.add(List.copyOf(invocation.getArgument(0)));
            return ChatResponse.builder().aiMessage(AiMessage.from(
                    (draftInputs.size() == 1 ? bodyA : bodyB) + " [W1]")).build();
        });
        when(fixture.verifier().verifyDetailed(anyString(), nullable(String.class), nullable(String.class),
                anyString(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            verifierInputs.add(List.of(invocation.getArgument(0), invocation.getArgument(1),
                    java.util.Objects.toString(invocation.getArgument(2), ""), invocation.getArgument(3)));
            return new FactVerifierService.DetailedVerificationResult(invocation.getArgument(3), "pass", true, true);
        });
        when(fixture.attribution().appendFinalEvidenceAppendix(anyString(), anyList())).thenAnswer(invocation -> {
            List<RagEvidenceMetadata> evidence = List.copyOf(invocation.getArgument(1));
            appendixInputs.add(evidence);
            return invocation.<String>getArgument(0) + "\n[W1](" + evidence.get(0).source() + ")";
        });
        var preprocessor = (QueryContextPreprocessor) ReflectionTestUtils.getField(fixture.workflow(), "qcPreprocessor");
        when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
        var specs = mock(com.example.lms.llm.spec.ModelSpecRegistry.class);
        ReflectionTestUtils.setField(fixture.workflow(), "focusModelSpecs", specs);
        var results = new java.util.ArrayList<ChatResult>();
        clearWorkflowState();
        try {
            for (int turn = 0; turn < 2; turn++) {
                String body = turn == 0 ? bodyA : bodyB;
                String locator = turn == 0 ? locatorA : locatorB;
                var currentEvidence = List.of(evidence("W1", "WEB", locator, null));
                when(fixture.attribution().promoteForPromptDetailed(anyString(), nullable(List.class),
                        nullable(List.class), anyList(), any(), anyBoolean()))
                        .thenReturn(promoted(currentEvidence, 1, 1, 0, 0, 0, 0));
                var rawWeb = List.of(dev.langchain4j.rag.content.Content.from(
                        dev.langchain4j.data.segment.TextSegment.from(body,
                                dev.langchain4j.data.document.Metadata.from(Map.of("url", locator, "kind", "WEB")))));
                var request = ChatRequestDto.builder().sessionId(910392L)
                        .message(turn == 0 ? "Describe Synthetic Alpha." : "Which fixture weapon is recommended?")
                        .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                        .memoryMode("FULL").polish(false).searchMode(SearchMode.AUTO)
                        .useWebSearch(true).useRag(false).useVerification(true).build();
                ChatConversationContext history = turn == 0 ? ChatConversationContext.empty()
                        : new ChatConversationContext(List.of(new ChatConversationContext.Turn(
                                "Describe Synthetic Alpha.", bodyA)), "old summary ".repeat(49), List.of());
                if (turn == 1) {
                    int cap = Math.toIntExact(ChatConversationContext.conservativeInput(draftInputs.get(0))) + 768;
                    when(specs.snapshots()).thenReturn(List.of(com.example.lms.llm.spec.ModelSpecSnapshot.of(
                            "synthetic", "release-gate-recording-fake", "example.org", cap, null, List.of(), Map.of())));
                }
                results.add(fixture.workflow().continueChat(request, (ChatWorkflow.WebEvidenceSupplier) ignored -> rawWeb, history));
            }
            assertEquals(2, draftInputs.size(), "both turns must reach the actual draft model");
            assertEquals(2, verifierInputs.size(), "both turns must reach the sole verifier");
            assertEquals(2, appendixInputs.size(), "both turns must bind their own citations");
            String promptA = draftInputs.get(0).stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n"));
            String promptB = draftInputs.get(1).stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(promptA.contains(bodyA) && promptA.contains(locatorA));
            assertTrue(promptB.contains(bodyB) && promptB.contains(locatorB), "B must survive final context fitting");
            assertFalse(promptB.contains("old summary"), "the fixture must exercise actual fitting");
            assertNotNull(TraceStore.get("focus.context.modelCap"));
            assertTrue(verifierInputs.get(1).get(1).contains(bodyB));
            assertFalse(verifierInputs.get(1).get(1).contains(bodyA), "history A cannot become current verifier evidence");
            assertEquals("Which fixture weapon is recommended?", verifierInputs.get(1).get(0));
            assertTrue(verifierInputs.get(1).get(3).contains(bodyB), "the judge must receive the same current draft");
            assertEquals(locatorB, appendixInputs.get(1).get(0).source());
            assertEquals(List.of(locatorB), results.get(1).evidenceMetadata().stream().map(RagEvidenceMetadata::source).toList());
            assertTrue(results.get(1).content().contains("[W1](" + locatorB + ")"));
            assertFalse(results.get(1).content().contains(locatorA));

            // Consume those actual workflow results at the existing controller persistence seam.
            var persisted = mock(ChatHistoryService.class);
            var chat = mock(ChatService.class);
            var settings = mock(SettingsService.class);
            var owners = mock(com.example.lms.web.ClientOwnerKeyResolver.class);
            var runs = new com.example.lms.service.chat.ChatRunRegistry();
            var renderer = mock(com.example.lms.service.trace.TraceHtmlBuilder.class);
            var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            beans.registerSingleton("traceHtmlBuilder", renderer);
            var snapshots = new com.example.lms.trace.TraceSnapshotStore(
                    beans.getBeanProvider(com.example.lms.service.trace.TraceHtmlBuilder.class));
            for (var entry : Map.<String, Object>of("enabled", true, "htmlEnabled", true, "maxSize", 5,
                    "maxValueLen", 1000, "maxEntries", 100, "maxPerTrace", 10, "captureSample", 1.0d).entrySet())
                ReflectionTestUtils.setField(snapshots, entry.getKey(), entry.getValue());
            ReflectionTestUtils.setField(snapshots, "budgetWindowMs", 600000L);
            for (String name : List.of("allowReasonsCsv", "denyReasonsCsv", "allowKeysCsv", "denyKeysCsv"))
                ReflectionTestUtils.setField(snapshots, name, "");
            ReflectionTestUtils.setField(snapshots, "allowKeysMode", "any");
            ReflectionTestUtils.setField(runs, "replayCapacity", 32);
            ReflectionTestUtils.setField(runs, "ttlSeconds", 60);
            var controller = new com.example.lms.api.ChatApiController(persisted, chat, null, settings, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    new com.fasterxml.jackson.databind.ObjectMapper(), null, runs, owners);
            ReflectionTestUtils.setField(controller, "traceHtmlBuilder", renderer);
            ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshots);
            var session = new com.example.lms.domain.ChatSession("synthetic", "synthetic-owner", "ANON");
            session.setId(910392L);
            when(persisted.getSessionForRequest(910392L)).thenReturn(session);
            when(persisted.getSessionWithMessages(910392L, 1)).thenReturn(session);
            when(settings.getAllSettings()).thenReturn(Map.of());
            when(owners.ownerKey()).thenReturn("synthetic-owner");
            when(persisted.appendMessageReturningId(eq(910392L), eq("user"), anyString())).thenReturn(39201L, 39204L);
            when(persisted.appendMessageReturningId(eq(910392L), eq("assistant"), anyString())).thenReturn(39202L, 39205L);
            when(persisted.appendMessageReturningId(eq(910392L), eq("system"), anyString())).thenReturn(39203L, 39206L);
            var persistedTurn = new java.util.concurrent.atomic.AtomicInteger();
            when(chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                int turn = persistedTurn.getAndIncrement();
                ChatRequestDto intercepted = invocation.getArgument(0);
                assertEquals(turn == 0 ? "Describe Synthetic Alpha." : "Which fixture weapon is recommended?",
                        intercepted.getMessage(), "the persisted result must belong to this exact request");
                assertEquals(910392L, intercepted.getSessionId());
                TraceStore.put("rag.evidence.public", List.of(Map.of("source", turn == 0 ? locatorA : locatorB,
                        "snippet", turn == 0 ? bodyA : bodyB)));
                return results.get(turn);
            });
            try {
                for (int turn = 0; turn < 2; turn++) {
                    var request = ChatRequestDto.builder().message(turn == 0 ? "Describe Synthetic Alpha." :
                            "Which fixture weapon is recommended?").sessionId(910392L).useRag(false).useWebSearch(false).build();
                    var response = controller.chatSync(request, null, new org.springframework.mock.web.MockHttpServletRequest());
                    assertNotNull(response.getBody());
                    assertEquals(results.get(turn).content(), response.getBody().getContent());
                    assertEquals(List.of(turn == 0 ? locatorA : locatorB),
                            response.getBody().getEvidence().stream().map(RagEvidenceMetadata::source).toList());
                }
                var texts = org.mockito.ArgumentCaptor.forClass(String.class);
                verify(persisted, org.mockito.Mockito.times(2)).appendMessageReturningId(eq(910392L), eq("assistant"), texts.capture());
                assertEquals(results.stream().map(ChatResult::content).toList(), texts.getAllValues());
                var pointers = org.mockito.ArgumentCaptor.forClass(String.class);
                verify(persisted, org.mockito.Mockito.times(2)).appendMessageReturningId(eq(910392L), eq("system"), pointers.capture());
                var restorer = Class.forName("com.example.lms.api.ChatTraceMetaMessageRestorer");
                var ids = new java.util.HashSet<String>();
                for (int turn = 0; turn < 2; turn++) {
                    java.util.Optional<?> parsed = ReflectionTestUtils.invokeMethod(restorer, "parseSnapshotPointer",
                            pointers.getAllValues().get(turn), turn == 0 ? 39203L : 39206L);
                    assertNotNull(parsed);
                    Object pointer = parsed.orElseThrow();
                    assertEquals(turn == 0 ? 39202L : 39205L,
                            ReflectionTestUtils.<Long>invokeMethod(pointer, "assistantMessageId"));
                    String id = ReflectionTestUtils.invokeMethod(pointer, "snapshotId");
                    assertTrue(ids.add(id), "each assistant must own a different snapshot");
                    assertTrue(snapshots.get(id).isPresent());
                    Map<?, ?> diagnostics = ReflectionTestUtils.invokeMethod(pointer, "diagnostics");
                    assertNotNull(diagnostics);
                    assertFalse(diagnostics.containsKey("rag.evidence.public"));
                    String decoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(diagnostics);
                    for (String rawIdentity : List.of(bodyA, bodyB, locatorA, locatorB))
                        assertFalse(decoded.contains(rawIdentity), "typed durable diagnostics must omit raw evidence identity");
                }
                verifyNoInteractions(renderer);
            } finally {
                ReflectionTestUtils.invokeMethod(runs, "shutdown");
                com.example.lms.trace.TraceContext.cleanupCurrentThread();
            }
        } finally {
            clearWorkflowState();
        }
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

    // ---------- m21222ain: scope-bound release + exact-output expansion guard ----------

    private static ChatWorkflow.FinalVerificationReleaseDecision releasedBase() {
        return new ChatWorkflow.FinalVerificationReleaseDecision(
                "\uc77c\ubc18 \uc9c0\uc2dd \ucd08\uc548", "PASS", "verified", true, false, true);
    }

    private static ChatWorkflow.RetrievalReleaseContract ragOnlyContract() {
        return new ChatWorkflow.RetrievalReleaseContract(true, false, true, false, true, false);
    }

    @Test
    void scopeBoundExecutedEmptyHoldsWithScopeEmptyNotice() {
        var d = ChatWorkflow.applyEvidenceReleasePolicy(
                releasedBase(), ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY,
                false, false, true, completedEmpty(0, 0, 0, 0), ragOnlyContract());
        assertEquals("HOLD", d.releaseStatus());
        assertEquals("evidence_scope_empty", d.reasonCode());
        assertFalse(d.releaseAllowed());
        assertFalse(d.knowledgeWriteAllowed());
        assertTrue(d.content().contains("\uadfc\uac70\ub97c \ucc3e\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4"));
    }

    @Test
    void scopeBoundGateRejectedIsNotReportedAsEmpty() {
        var d = ChatWorkflow.applyEvidenceReleasePolicy(
                releasedBase(), ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY,
                false, false, true, completedEmpty(3, 0, 2, 0), ragOnlyContract());
        assertEquals("HOLD", d.releaseStatus());
        assertEquals("evidence_scope_unverified", d.reasonCode());
        assertFalse(d.releaseAllowed());
        assertFalse(d.knowledgeWriteAllowed());
    }

    @Test
    void scopeBoundFailedRetrievalHoldsWithUnavailableNotice() {
        var d = ChatWorkflow.applyEvidenceReleasePolicy(
                releasedBase(), ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                false, false, true, failed(), ragOnlyContract());
        assertEquals("HOLD", d.releaseStatus());
        assertEquals("retrieval_scope_unavailable", d.reasonCode());
        assertFalse(d.releaseAllowed());
        assertFalse(d.knowledgeWriteAllowed());
    }

    @Test
    void scopeBoundEvidencePresentStillReleases() {
        var promotion = promoted(
                List.of(evidence("L1", "LOCAL_DOC", "src", "docs/a.txt")), 0, 0, 0, 0, 1, 1);
        var state = ChatWorkflow.deriveEvidenceReleaseState(
                promotion, promotion.evidence(), false, ragOnlyContract());
        assertEquals(ChatWorkflow.EvidenceReleaseState.EVIDENCE_PRESENT, state);
        var d = ChatWorkflow.applyEvidenceReleasePolicy(
                releasedBase(), state,
                false, false, true, promotion, ragOnlyContract());
        assertTrue(d.releaseAllowed());
        assertEquals("\uc77c\ubc18 \uc9c0\uc2dd \ucd08\uc548", d.content());
    }

    @Test
    void generalScopeEmptyStillReleasesAdaptiveDraft() {
        var d = ChatWorkflow.applyEvidenceReleasePolicy(
                releasedBase(), ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY,
                false, false, false, completedEmpty(0, 0, 0, 0), ragOnlyContract());
        assertTrue(d.releaseAllowed());
        assertEquals("\uc77c\ubc18 \uc9c0\uc2dd \ucd08\uc548", d.content());
    }

    @Test
    void explicitEvidenceRequiredWinsOverScopeBound() {
        var d = ChatWorkflow.applyEvidenceReleasePolicy(
                releasedBase(), ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY,
                true, false, true, completedEmpty(0, 0, 0, 0), ragOnlyContract());
        assertEquals("evidence_needed", d.content());
        assertEquals("evidence_required_empty", d.reasonCode());
        assertFalse(d.releaseAllowed());
    }

    @Test
    void legacyFourArgPolicyKeepsAdaptiveRelease() {
        var d = ChatWorkflow.applyEvidenceReleasePolicy(
                releasedBase(), ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY,
                false, false);
        assertTrue(d.releaseAllowed());
        assertEquals("\uc77c\ubc18 \uc9c0\uc2dd \ucd08\uc548", d.content());
    }

    @Test
    void retrievalExecutionClassifiesGateRejectSeparatelyFromEmpty() {
        var contract = ragOnlyContract();
        assertEquals(ChatWorkflow.RetrievalExecution.EXECUTED_EMPTY,
                ChatWorkflow.classifyRetrievalExecution(completedEmpty(0, 0, 0, 0), contract));
        assertEquals(ChatWorkflow.RetrievalExecution.GATE_REJECTED,
                ChatWorkflow.classifyRetrievalExecution(completedEmpty(2, 0, 0, 0), contract));
        assertEquals(ChatWorkflow.RetrievalExecution.EXECUTION_FAILED,
                ChatWorkflow.classifyRetrievalExecution(failed(), contract));
        var offContract = new ChatWorkflow.RetrievalReleaseContract(
                false, false, false, false, false, true);
        assertEquals(ChatWorkflow.RetrievalExecution.NOT_REQUESTED,
                ChatWorkflow.classifyRetrievalExecution(null, offContract));
        var disabledContract = new ChatWorkflow.RetrievalReleaseContract(
                true, true, true, false, true, false);
        assertEquals(ChatWorkflow.RetrievalExecution.EXECUTION_FAILED,
                ChatWorkflow.classifyRetrievalExecution(null, disabledContract));
    }

    @Test
    void documentScopePolicyDetectsBoundAndGeneralQueries() {
        assertTrue(DocumentScopeQuestionPolicy.isDocumentScopeBound(
                "\ucca8\ubd80 \ubb38\uc11c\ub9cc \uadfc\uac70\ub85c \ub2f5\ud574\uc918", 0));
        assertTrue(DocumentScopeQuestionPolicy.isDocumentScopeBound(
                "\uc774 \ubb38\uc11c\ub97c \uc694\uc57d\ud574\uc918", 1));
        assertFalse(DocumentScopeQuestionPolicy.isDocumentScopeBound(
                "\uc774 \ubb38\uc11c\ub97c \uc694\uc57d\ud574\uc918", 0));
        assertTrue(DocumentScopeQuestionPolicy.isDocumentScopeBound(
                "based only on the attached document, what is the total?", 0));
        assertFalse(DocumentScopeQuestionPolicy.isDocumentScopeBound(
                "\ube44\uac00 \uc624\ub294 \uc774\uc720\ub97c \uc124\uba85\ud574\uc918", 0));
        assertFalse(DocumentScopeQuestionPolicy.isDocumentScopeBound(
                "\ubb38\uc11c \uc791\uc131\ubc95\uc744 \uc54c\ub824\uc918", 0));
    }

    @Test
    void exactNumericContractSkipsExpansionAndKeepsDraft() {
        MemoryHoldFixture fixture = memoryHoldFixture();
        var lengthVerifier = (com.example.lms.service.answer.LengthVerifierService)
                ReflectionTestUtils.getField(fixture.workflow(), "lengthVerifier");
        var expander = (com.example.lms.service.answer.AnswerExpanderService)
                ReflectionTestUtils.getField(fixture.workflow(), "answerExpander");
        when(lengthVerifier.isShort(anyString(), anyInt())).thenReturn(true);
        when(expander.expandWithLc(anyString(), any(), any()))
                .thenReturn("\ud3b8\uc9d1\ud560 \uc6d0\ubb38\uc774 \ubd80\uc871\ud569\ub2c8\ub2e4.");
        when(fixture.model().chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("7")).build());
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("3 + 4\uc758 \ub2f5\uc744 \uc22b\uc790 \ud558\ub098\ub85c\ub9cc \ub2f5\ud574\uc918")
                    .model("release-gate-recording-fake").maxTokens(256)
                    .mode("FACT").memoryMode("EPHEMERAL").searchMode(SearchMode.OFF)
                    .useWebSearch(false).useRag(false).useVerification(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false, false))
                    .build();
            ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of());
            verify(expander, never()).expandWithLc(anyString(), any(), any());
            assertEquals("exact_output_contract", TraceStore.get("answer.expansion.skipped"));
            assertNotNull(result.content());
            assertTrue(result.content().contains("7"));
            assertFalse(result.content().contains("\ubd80\uc871\ud569\ub2c8\ub2e4"));
        } finally {
            clearWorkflowState();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void polishToggleControlsExpansionCall(boolean polishEnabled) {
        MemoryHoldFixture fixture = memoryHoldFixture();
        var lengthVerifier = (com.example.lms.service.answer.LengthVerifierService)
                ReflectionTestUtils.getField(fixture.workflow(), "lengthVerifier");
        var expander = (com.example.lms.service.answer.AnswerExpanderService)
                ReflectionTestUtils.getField(fixture.workflow(), "answerExpander");
        when(lengthVerifier.isShort(anyString(), anyInt())).thenReturn(true);
        when(expander.expandWithLc(anyString(), any(), any()))
                .thenReturn("synthetic expanded answer");
        clearWorkflowState();
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("\ube44\uac00 \uc624\ub294 \uc774\uc720\ub97c \uc124\uba85\ud574\uc918")
                    .model("release-gate-recording-fake").maxTokens(256)
                    .mode("FACT").memoryMode("EPHEMERAL").searchMode(SearchMode.OFF)
                    .useWebSearch(false).useRag(false).useVerification(false)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false, false))
                    .polish(polishEnabled)
                    .build();
            ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of());
            if (polishEnabled) {
                verify(expander).expandWithLc(anyString(), any(), any());
            } else {
                verify(expander, never()).expandWithLc(anyString(), any(), any());
                assertEquals("polish_disabled", TraceStore.get("answer.expansion.skipped"));
                assertFalse(result.content() != null
                        && result.content().contains("synthetic expanded answer"));
            }
        } finally {
            clearWorkflowState();
        }
    }

    @Test
    void expansionRefusalMarkerKeepsOriginalDraft() {
        clearWorkflowState();
        try {
            var expander = new com.example.lms.service.answer.AnswerExpanderService(
                    new com.example.lms.prompt.StandardPromptBuilder());
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                    .aiMessage(AiMessage.from("\ud3b8\uc9d1\ud560 \uc6d0\ubb38\uc774 \ubd80\uc871\ud569\ub2c8\ub2e4.")).build());
            var vp = new com.example.lms.service.verbosity.VerbosityProfile(
                    "standard", 20, 256, "enduser", "inline", List.of());
            // 숫자 없는 초안으로 refusal 표식 분기만 검증한다(숫자 초안은 draft_number_dropped가 먼저 잡는다).
            assertNull(expander.expandWithLc("간단한 초안입니다", vp, model));
            assertEquals("expansion_refusal_marker", TraceStore.get("answer.expansion.rejected"));
        } finally {
            clearWorkflowState();
        }
    }

    @Test
    void expansionThatDropsDraftNumberIsRejected() {
        clearWorkflowState();
        try {
            var expander = new com.example.lms.service.answer.AnswerExpanderService(
                    new com.example.lms.prompt.StandardPromptBuilder());
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                    .aiMessage(AiMessage.from("\uc815\ub2f5\uc740 \uc77c\uacf1\uc774\uba70 \uac04\ub2e8\ud55c \ub367\uc148 \uacb0\uacfc\uc785\ub2c8\ub2e4.")).build());
            var vp = new com.example.lms.service.verbosity.VerbosityProfile(
                    "standard", 20, 256, "enduser", "inline", List.of());
            assertNull(expander.expandWithLc("7", vp, model));
            assertEquals("draft_number_dropped", TraceStore.get("answer.expansion.rejected"));
        } finally {
            clearWorkflowState();
        }
    }
}
