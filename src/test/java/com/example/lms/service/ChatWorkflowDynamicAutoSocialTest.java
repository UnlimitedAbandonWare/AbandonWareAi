package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.domain.enums.ExecutionMode;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;

/** Reuses the existing release-gate boundary fixture without altering its contracts. */
class ChatWorkflowDynamicAutoSocialTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"안녕?,llmrouter.auto", "고마워,llmrouter.auto", "안녕?,chatgpt-oauth:gpt-5.6-luna", "고마워,chatgpt-oauth:gpt-5.6-luna"})
    void pureSocialAutoReturnsBeforeGenerationRetrievalOrDurableMemory(String message, String requestedModel) {
        Object fixture = ReflectionTestUtils.invokeMethod(
                ChatWorkflowFinalVerificationReleaseGateTest.class, "memoryHoldFixture");
        ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(fixture, "workflow");
        ChatModel model = ReflectionTestUtils.invokeMethod(fixture, "model");
        installLocalCatalog(workflow);
        var classifier = mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class);
        doThrow(new AssertionError("pure social unexpectedly attempted auxiliary classification"))
                .when(classifier).clarify(anyString(), anyList());
        ReflectionTestUtils.setField(workflow, "disambiguationService", classifier);
        AtomicInteger webAttempts = new AtomicInteger();
        clearState();
        try {
            ChatRequestDto request = ChatRequestDto.builder().message(message)
                    .model(requestedModel).modelSelectionMode("preferred").executionMode(ExecutionMode.AUTO)
                    .searchMode(com.example.lms.gptsearch.dto.SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).memoryMode("OFF").build();
            request.bindVerifiedRequestOwner(AttachmentOwnerIdentity.forAnonymous("synthetic-owner"));
            ChatResult result = workflow.continueChat(request, query -> {
                webAttempts.incrementAndGet();
                return List.of();
            });
            assertNotNull(result);
            assertFalse(result.content().isBlank());
            assertFalse(result.content().contains("모델") || result.content().contains("HOLD"));
            assertEquals("local:social", result.modelUsed(), "local reply must not claim a selected model ran");
            assertFalse(result.ragUsed());
            assertTrue(result.evidence().isEmpty());
            assertEquals(Boolean.FALSE, TraceStore.get("finalAnswer.memorySaveAllowed"),
                    "local social reply must not be promoted as verified knowledge");
            assertEquals(0, webAttempts.get());
            verifyNoInteractions(model);
            verifyNoInteractions(classifier);
            for (String boundary : List.of("modelRouter", "qcPreprocessor", "subjectResolver", "domainDetector",
                    "verifier", "learningWriteInterceptor", "memoryWriteInterceptor",
                    "understandAndMemorizeInterceptor", "attachmentService")) {
                Object dependency = ReflectionTestUtils.getField(workflow, boundary);
                if (dependency != null) verifyNoInteractions(dependency);
            }
        } finally {
            clearState();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uC548\uB155?", "\uACE0\uB9C8\uC6CC"})
    void cancelledSocialCannotPublishAnAnswerOrReachAuxiliaryModels(String message) {
        Object fixture = ReflectionTestUtils.invokeMethod(
                ChatWorkflowFinalVerificationReleaseGateTest.class, "memoryHoldFixture");
        ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(fixture, "workflow");
        ChatModel model = ReflectionTestUtils.invokeMethod(fixture, "model");
        installLocalCatalog(workflow);
        var classifier = mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class);
        doThrow(new AssertionError("cancelled social reached auxiliary classification"))
                .when(classifier).clarify(anyString(), anyList());
        ReflectionTestUtils.setField(workflow, "disambiguationService", classifier);
        clearState();
        try {
            ReflectionTestUtils.setField(workflow, "cancelFlags",
                    new java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>());
            workflow.cancelSession(712L);
            ChatRequestDto request = ChatRequestDto.builder().message(message).sessionId(712L)
                    .model("llmrouter.auto").executionMode(ExecutionMode.AUTO)
                    .searchMode(com.example.lms.gptsearch.dto.SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).memoryMode("OFF").build();
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> workflow.continueChat(request, ignored -> {
                        throw new AssertionError("cancelled social reached web retrieval");
                    }));
            verifyNoInteractions(model, classifier);
        } finally { clearState(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"안녕, 오늘 원신 소식 알려줘", "고마워, 오늘 주가는?", "그럼?", "아까 색은?", "hi-fi", "thanks for the report"})
    void mixedAndContextualRequestsKeepTheNormalWorkflow(String message) {
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(
                ChatRequestDto.builder().message(message).executionMode(ExecutionMode.AUTO).build()));
    }

    @org.junit.jupiter.api.Test
    void explicitModelEvidenceAssetsAndExecutionRequestsKeepTheirContracts() {
        var request = ChatRequestDto.builder().message("안녕?").executionMode(ExecutionMode.AUTO).build();
        assertTrue(NoEvidenceChatFallback.isLocalSocialReplyRequest(request));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().strictModelSelection(true).build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().modelSelectionMode("strict").build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().model("chatgpt-oauth:gpt-5.6-luna").modelSelectionMode("strict").build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().searchMode(com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT).build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().searchMode(com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP).build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().ragAnswerPolicy("evidence_only").build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().attachmentIds(List.of("owned-file")).build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().imageBase64("synthetic-image").build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().inputType("image").build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().executionMode(ExecutionMode.STRIKE).build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().useAdaptive(true).build()));
        assertFalse(NoEvidenceChatFallback.isLocalSocialReplyRequest(request.toBuilder().autoTranslate(true).build()));
        var off = request.toBuilder().searchMode(com.example.lms.gptsearch.dto.SearchMode.OFF).useWebSearch(false).useRag(false).build();
        assertTrue(NoEvidenceChatFallback.isLocalSocialReplyRequest(off));
        assertFalse(off.isUseWebSearch());
        assertFalse(off.isUseRag());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"안녕?,llmrouter.auto", "고마워,llmrouter.auto", "안녕?,chatgpt-oauth:gpt-5.6-luna", "고마워,chatgpt-oauth:gpt-5.6-luna"})
    void joinedStreamKeepsTranscriptAndExactAckWithoutAnyProviderOrKnowledgePromotion(String message, String requestedModel) throws Exception {
        Object fixture = ReflectionTestUtils.invokeMethod(
                ChatWorkflowFinalVerificationReleaseGateTest.class, "memoryHoldFixture");
        ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(fixture, "workflow");
        ChatModel model = ReflectionTestUtils.invokeMethod(fixture, "model");
        installLocalCatalog(workflow);
        ReflectionTestUtils.setField(workflow, "cancelFlags", new java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>());
        var history = mock(ChatHistoryService.class);
        var settings = mock(SettingsService.class);
        var owner = mock(com.example.lms.web.ClientOwnerKeyResolver.class);
        var web = mock(com.example.lms.search.provider.WebSearchProvider.class);
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var session = new com.example.lms.domain.ChatSession("social integration", "synthetic-owner", "ANON");
        session.setId(501L);
        org.mockito.Mockito.when(settings.getAllSettings()).thenReturn(java.util.Map.of());
        org.mockito.Mockito.when(owner.ownerKey()).thenReturn("synthetic-owner");
        org.mockito.Mockito.when(history.getSessionForRequest(501L)).thenReturn(session);
        org.mockito.Mockito.when(history.getSessionWithMessages(501L)).thenReturn(session);
        var messageIds = new java.util.concurrent.atomic.AtomicLong(700L);
        org.mockito.Mockito.when(history.appendMessageReturningId(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString()))
                .thenAnswer(ignored -> messageIds.incrementAndGet());
        com.example.lms.api.ChatApiController controller = ReflectionTestUtils.invokeMethod(
                Class.forName("com.example.lms.api.ChatApiControllerInputGuardTest"), "controller",
                history, new ChatService(workflow), settings, owner, registry, web, null);
        assertNotNull(controller);
        var guardedBoundaries = new java.util.ArrayList<Object>();
        guardedBoundaries.add(web);
        for (String name : List.of("chainRunner", "workflowOrchestrator", "planHintApplier", "searchDecisionService",
                "memoryReinforcementService", "generalGraphCapture")) {
            var field = org.springframework.util.ReflectionUtils.findField(controller.getClass(), name);
            assertNotNull(field, "existing controller boundary missing: " + name);
            Object boundary = mock(field.getType());
            ReflectionTestUtils.setField(controller, name, boundary);
            guardedBoundaries.add(boundary);
        }
        clearState();
        try {
            var request = ChatRequestDto.builder().message(message).sessionId(501L).model(requestedModel)
                    .modelSelectionMode("preferred").executionMode(ExecutionMode.AUTO)
                    .searchMode(com.example.lms.gptsearch.dto.SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).memoryMode("FULL").build();
            var servlet = new org.springframework.mock.web.MockHttpServletRequest();
            servlet.addHeader("X-Request-Id", "synthetic-social-integration");
            var stream = controller.chatStream(request, false, false, null, servlet);
            String token = registry.currentRunToken(501L).orElseThrow();
            var received = controller.acknowledgeRun(null, java.util.Map.of("sessionId", 501L, "runToken", token), null, null);
            assertEquals(true, received.getBody().get("acknowledged"));
            var events = stream.collectList().block(java.time.Duration.ofSeconds(8));
            assertNotNull(events);
            var finals = events.stream().map(org.springframework.http.codec.ServerSentEvent::data)
                    .filter(e -> e != null && "final".equals(e.type())).toList();
            assertEquals(1, finals.size(), "social reply must use the normal terminal final path");
            var finalEvent = finals.get(0);
            assertEquals("local:social", finalEvent.modelUsed());
            assertFalse(finalEvent.data().isBlank());
            assertFalse(finalEvent.data().contains("HOLD") || finalEvent.data().contains("backend_unavailable"));
            assertTrue(finalEvent.evidence() == null || finalEvent.evidence().isEmpty());
            org.mockito.Mockito.verify(history).appendMessageReturningId(501L, "user", message);
            org.mockito.Mockito.verify(history).appendMessageReturningId(501L, "assistant", finalEvent.data());
            org.mockito.Mockito.verify(history, org.mockito.Mockito.never()).updateRollingSummary(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
            var delivered = controller.acknowledgeRun(null, java.util.Map.of("sessionId", 501L, "runToken", token, "phase", "final"), null, null);
            assertEquals(true, delivered.getBody().get("acknowledged"));
            assertTrue(registry.describeExact(501L, token).orElseThrow().outcome().finalDeliveryAccepted());
            verifyNoInteractions(model);
            guardedBoundaries.forEach(org.mockito.Mockito::verifyNoInteractions);
        } finally { clearState(); }
    }

    @org.junit.jupiter.api.Test
    void tenTurnsKeepOneOwnerAndExposeEveryProviderCounter() throws Exception {
        runTenTurnFixture(java.util.Set.of(0,1,2,3,4,5,6,7,8,9));
    }

    @org.junit.jupiter.api.Test
    void dynamicAutoMemoryAndStableConceptKeepSelectedGenerationWithoutRetrieval() throws Exception {
        runTenTurnFixture(java.util.Set.of(2,3,4));
    }

    @org.junit.jupiter.api.Test
    void dynamicAutoMixedExplanationRetainsExternalFactRetrieval() throws Exception {
        runTenTurnFixture(java.util.Set.of(4), true);
    }

    private static void runTenTurnFixture(java.util.Set<Integer> checkedTurns) throws Exception {
        runTenTurnFixture(checkedTurns, false);
    }

    private static void runTenTurnFixture(java.util.Set<Integer> checkedTurns, boolean mixedExplanation) throws Exception {
        Object fixture = ReflectionTestUtils.invokeMethod(ChatWorkflowFinalVerificationReleaseGateTest.class, "memoryHoldFixture");
        ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(fixture, "workflow");
        ChatModel model = ReflectionTestUtils.invokeMethod(fixture, "model");
        installLocalCatalog(workflow);
        ReflectionTestUtils.invokeMethod(ChatWorkflowPromptMessageRoleTest.class, "configureRetrieval", workflow, 3);
        // The reused role fixture contains unrelated WEB evidence; it is not this batch's source.
        var hybrid = (com.example.lms.service.rag.HybridRetriever) ReflectionTestUtils.getField(workflow, "hybridRetriever");
        org.mockito.Mockito.when(hybrid.retrieveAll(anyList(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        ReflectionTestUtils.setField(workflow, "cancelFlags", new java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>());
        ReflectionTestUtils.setField(workflow, "freeIdeaCount", new java.util.concurrent.atomic.AtomicLong());
        String luna = "chatgpt-oauth:gpt-5.6-luna";
        var factory = mock(com.example.lms.llm.DynamicChatModelFactory.class, call -> {
            if (call.getMethod().getName().equals("lcWithTimeout")) {
                assertEquals(luna, call.getArgument(0), "fixture must retain exact selected OAuth binding");
                return model;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
        var router = (com.example.lms.service.routing.ModelRouter) ReflectionTestUtils.getField(workflow, "modelRouter");
        org.mockito.Mockito.when(router.resolveModelName(model)).thenReturn(luna);
        var preprocessor = (com.example.lms.service.rag.pre.QueryContextPreprocessor) ReflectionTestUtils.getField(workflow, "qcPreprocessor");
        org.mockito.Mockito.when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
        var current = new AtomicInteger();
        var generations = new AtomicInteger();
        var braveCalls = new AtomicInteger();
        var naverCalls = new AtomicInteger();
        var vectorCalls = new AtomicInteger();
        var rescueCalls = new AtomicInteger();
        var finalMessages = new java.util.concurrent.atomic.AtomicReference<List<dev.langchain4j.data.message.ChatMessage>>(List.of());
        String[] questions = {"안녕", "고마워", "기억해: 프로젝트는 해솔-42, 색은 청록.",
                "색은 남색으로 바꿔. 아까 프로젝트는 뭐였지?", "RAG와 미세조정 차이를 간단히 설명해줘.",
                "안녕. 오늘 발표된 원신 신규 캐릭터 공식 내용을 알려줘.",
                "앞의 두 공식 자료를 같은 조건에서 비교하고 가정도 밝혀줘.",
                "오늘 공개된 보디냐챠의 공식 키와 능력은?", "검색 끄고, 오늘 새 캐릭터의 공식 키를 알려줘.",
                "앞에서 정한 프로젝트와 최종 색은? 확인한 사실과 일반 설명도 구분해줘."};
        if (mixedExplanation) questions[4] = "RAG와 미세조정의 개념 차이와 2026년 10월 6일 서울의 강수량을 설명해줘.";
        String[] replies = {"", "", "이 대화의 프로젝트는 해솔-42, 색은 청록입니다.",
                "프로젝트는 해솔-42이고 최종 색은 남색입니다.",
                "일반 설명: RAG는 검색한 문서를 문맥으로 사용하고, 미세조정은 학습으로 모델 가중치를 바꿉니다.",
                "자료 A의 공식 발표 범위와 자료 B의 업데이트 시점이 다릅니다. [W1] [W2]",
                "같은 버전과 발표 시점이라는 가정 아래 자료 A와 B를 비교합니다. [W1] [W2]",
                "보디냐챠의 공식 키와 능력을 확인할 근거가 부족합니다. 어떤 발표를 뜻하는지 알려주세요.",
                "검색을 끈 상태에서 오늘의 공식 값을 확인할 수 없습니다. 공식 발표를 확인할 수 있습니다.",
                "대화에서 정한 프로젝트는 해솔-42, 최종 색은 남색입니다. 외부 검증한 사실과는 구분됩니다."};
        org.mockito.Mockito.when(model.chat(anyList())).thenAnswer(call -> {
            generations.incrementAndGet(); finalMessages.set(List.copyOf(call.getArgument(0)));
            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(replies[current.get()])).build();
        });
        var history = mock(ChatHistoryService.class);
        var turns = new java.util.ArrayList<String>();
        org.mockito.Mockito.when(history.getFormattedRecentHistory(org.mockito.ArgumentMatchers.eq(601L), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> List.copyOf(turns.subList(Math.max(0, turns.size() - call.getArgument(1, Integer.class)), turns.size())));
        org.mockito.Mockito.when(history.getLastAssistantMessage(601L)).thenAnswer(call ->
                turns.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(turns.get(turns.size()-1)));
        ReflectionTestUtils.setField(workflow, "chatHistoryService", history);
        var memory = (com.example.lms.service.rag.handler.MemoryHandler) ReflectionTestUtils.getField(workflow, "memoryHandler");
        org.mockito.Mockito.when(memory.loadForSession(601L)).thenAnswer(call -> String.join("\n", turns));
        var rag = (com.example.lms.service.rag.LangChainRAGService) ReflectionTestUtils.getField(workflow, "ragSvc");
        org.mockito.Mockito.when(rag.asContentRetriever(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(query -> {
            vectorCalls.incrementAndGet(); return List.of();
        });
        var brave = mock(com.example.lms.service.web.BraveSearchService.class);
        var naver = mock(NaverSearchService.class);
        org.mockito.Mockito.when(brave.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(naver.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(brave.searchWithMeta(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call -> {
            braveCalls.incrementAndGet(); return com.example.lms.service.web.BraveSearchResult.ok(List.of(), 1);
        });
        org.mockito.Mockito.when(naver.searchWithTraceSync(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call -> {
            naverCalls.incrementAndGet(); var trace = new NaverSearchService.SearchTrace();
            trace.outcomeClass = current.get()==5 ? "NONE" : "TRUE_ZERO";
            return new NaverSearchService.SearchResult(current.get()==5 ? List.of("fixture-A", "fixture-B") : List.of(), trace);
        });
        var web = new com.example.lms.search.provider.HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(web, "boundedFallbackEnabled", true);
        ReflectionTestUtils.setField(web, "primary", "BRAVE"); ReflectionTestUtils.setField(web, "timeoutSec", 3);
        String bodyA = "자료 A: 원신 합성 캐릭터의 발표 범위는 시험 버전 A입니다.";
        String bodyB = "자료 B: 원신 합성 캐릭터 업데이트는 시험 버전 B입니다.";
        List<dev.langchain4j.rag.content.Content> docs = List.of(
                dev.langchain4j.rag.content.Content.from(dev.langchain4j.data.segment.TextSegment.from(bodyA,
                        dev.langchain4j.data.document.Metadata.from(java.util.Map.of("url", "https://example.test/official-A", "kind", "WEB")))),
                dev.langchain4j.rag.content.Content.from(dev.langchain4j.data.segment.TextSegment.from(bodyB,
                        dev.langchain4j.data.document.Metadata.from(java.util.Map.of("url", "https://example.test/official-B", "kind", "WEB")))));
        var profiles = new com.example.lms.guard.GuardProfileProps(); profiles.setProfile("PROFILE_MEMORY");
        var evidenceGate = new com.example.lms.service.rag.guard.EvidenceGate(.05, .02, .6, .8, false);
        ReflectionTestUtils.setField(evidenceGate, "guardProfileProps", profiles);
        ReflectionTestUtils.setField(workflow, "ragEvidenceAttributionService", new com.example.lms.service.rag.RagEvidenceAttributionService(
                evidenceGate, new com.example.lms.service.guard.CitationGate(), null));
        var projectionFixture = Class.forName("com.example.lms.learning.gemini.GeminiSearchRescueProjectionTest").getDeclaredConstructor();
        projectionFixture.setAccessible(true); Object nativeFixture = projectionFixture.newInstance();
        String valid = (String) ReflectionTestUtils.getField(nativeFixture, "VALID");
        org.springframework.web.reactive.function.client.ExchangeFunction exchange = request -> {
            rescueCalls.incrementAndGet(); return reactor.core.publisher.Mono.just(
                    org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                            .header("Content-Type", "application/json").body(valid).build());
        };
        Object gateway = ReflectionTestUtils.invokeMethod(nativeFixture, "gateway", exchange);
        ReflectionTestUtils.setField(workflow, "geminiGateway", gateway);
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        String ownerHash = AttachmentOwnerIdentity.forAnonymous("synthetic-owner").hash();
        var rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        try {
            for (int turn=0; turn<10; turn++) {
                current.set(turn); clearState(); com.example.lms.service.rag.SelfAskSearchBudget.beginRequest(ExecutionMode.AUTO); finalMessages.set(List.of());
                int g=generations.get(), b=braveCalls.get(), n=naverCalls.get(), v=vectorCalls.get(), r=rescueCalls.get();
                var request = ChatRequestDto.builder().message(questions[turn]).sessionId(601L).model(luna)
                        .modelSelectionMode("preferred").executionMode(ExecutionMode.AUTO).maxTokens(512)
                        .searchMode(turn>=8 ? com.example.lms.gptsearch.dto.SearchMode.OFF : com.example.lms.gptsearch.dto.SearchMode.AUTO)
                        .useWebSearch(turn<8).useRag(turn<8).googleSearchRescueEnabled(turn>=7)
                        .memoryMode("HYBRID").useVerification(false).build();
                request.bindVerifiedRequestOwner(AttachmentOwnerIdentity.forAnonymous("synthetic-owner"));
                request.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(java.util.Map.of(), java.util.Map.of(),
                        java.util.Map.of("model", luna, "searchMode", request.getSearchMode().name(),
                                "useWebSearch", request.isUseWebSearch(), "useRag", request.isUseRag(),
                                "googleSearchRescueEnabled", request.isGoogleSearchRescueEnabled())));
                ChatWorkflow.WebEvidenceSupplier supplier = query -> {
                    List<String> found = web.search(query, 3);
                    return found.isEmpty() ? List.of() : docs;
                };
                var run = registry.beginOrJoin(601L).context();
                var prior = registry.priorWebEvidence(run, ownerHash);
                if (ChatWorkflow.isPriorWebComparisonRequest(request) && !prior.isEmpty())
                    supplier = ChatWorkflow.priorWebEvidenceSupplier(prior);
                ChatResult result = null;
                String failureCode = "none";
                try { result = workflow.continueChat(request, supplier); }
                catch (com.example.lms.llm.ModelSelectionException failed) { failureCode = failed.code(); }
                if (result != null) {
                    assertTrue(registry.stageWebEvidence(run, ownerHash, result.retainedWebEvidence()));
                    run.markGenerationSucceeded();
                    assertTrue(run.tryBeginTranscriptCommit());
                    assertTrue(run.markPersisted());
                    assertTrue(run.claimTerminalEvent("delivery_pending"));
                    assertTrue(run.recordFinalEmit("ok", false));
                    assertTrue(registry.markDone(run));
                    assertTrue(registry.acknowledgeFinalDeliveryExact(601L, run.clientToken()));
                } else registry.markDone(run);
                String prompt = finalMessages.get().stream().map(m -> m instanceof dev.langchain4j.data.message.SystemMessage a ? a.text()
                        : m instanceof dev.langchain4j.data.message.UserMessage a ? a.singleText()
                        : m instanceof dev.langchain4j.data.message.AiMessage a ? a.text() : "").collect(java.util.stream.Collectors.joining("\n"));
                var row = new java.util.LinkedHashMap<String, Object>(); String id=String.format("T%02d",turn+1);
                row.put("suppressedDraftType", String.valueOf(TraceStore.get("chat.workflow.suppressed.llm.chatDraft.errorType")));
                row.put("turn",id); row.put("failureCode",failureCode); row.put("generation",generations.get()-g); row.put("brave",braveCalls.get()-b);
                row.put("naver",naverCalls.get()-n); row.put("vector",vectorCalls.get()-v); row.put("nativeRescue",rescueCalls.get()-r);
                row.put("citableEvidence",result==null ? 0 : result.evidenceMetadata().size()); row.put("sourceKinds",result==null ? java.util.Set.of() : result.evidence()); row.put("projectInPrompt",prompt.contains("해솔-42"));
                row.put("correctedColorInPrompt",prompt.contains("남색")); row.put("bodyAInPrompt",prompt.contains(bodyA));
                row.put("bodyBInPrompt",prompt.contains(bodyB)); row.put("rescueInPrompt",prompt.contains("서울🙂"));
                row.put("localSocial", result!=null && "local:social".equals(result.modelUsed())); row.put("offPreserved", turn<8 || !request.isUseWebSearch() && !request.isUseRag());
                row.put("originalWebReason", java.util.Objects.toString(TraceStore.get("web.boundedRoute.terminalReason"), "not_observed"));
                row.put("rescueReason",result==null || result.googleSearchRescue()==null ? "not_attempted" : result.googleSearchRescue().reasonCode());
                rows.add(row);
                int t=turn; if (checkedTurns.contains(t)) checks.add(() -> {
                    assertEquals("none", row.get("failureCode"), id+" failure");
                    assertEquals(t<2 ? 0 : 1, row.get("generation"), id+" generation");
                    assertEquals(t==5 || t==7 || t==4 && mixedExplanation ? 1 : 0, row.get("brave"),id+" Brave");
                    assertEquals(t==5 || t==7 || t==4 && mixedExplanation ? 1 : 0, row.get("naver"),id+" Naver");
                    if(t!=5 && t!=7) assertEquals(t==4 && mixedExplanation ? 1 : 0,row.get("vector"),id+" vector");
                    assertEquals(t==7 ? 1 : 0,row.get("nativeRescue"),id+" typed native rescue");
                    assertEquals(false,row.get("rescueInPrompt"),id+" auxiliary result reinjected");
                    if(t==3 || t==9) assertEquals(true,row.get("projectInPrompt"),id+" project context");
                    if(t==9) assertEquals(true,row.get("correctedColorInPrompt"),id+" corrected color");
                    if(t!=5 && t!=6) assertEquals(0,row.get("citableEvidence"),id+" invented citation");
                    if(t==5) { assertEquals(true,row.get("bodyAInPrompt"),id+" bodyA"); assertEquals(true,row.get("bodyBInPrompt"),id+" bodyB"); assertEquals(2,row.get("citableEvidence"),id+" citation promotion"); }
                    if(t==6) { assertEquals(2,row.get("citableEvidence"),id+" existing citation reuse");
                        assertEquals(true,row.get("bodyAInPrompt"),id+" retained bodyA");
                        assertEquals(true,row.get("bodyBInPrompt"),id+" retained bodyB"); }
                });
                turns.add("User: "+questions[turn]); if(result!=null) turns.add("Assistant: "+result.content());
            }
            var diagnostics = new java.util.LinkedHashMap<String,Object>();
            if (rows.stream().anyMatch(row -> !"none".equals(row.get("failureCode")))) {
                var probe = ChatRequestDto.builder().message("synthetic generation probe").model(luna).maxTokens(512).build();
                try {
                    ReflectionTestUtils.invokeMethod(workflow, "callWithRetryReportingSuccessCore", model,
                            List.of(dev.langchain4j.data.message.UserMessage.from("synthetic generation probe")), probe,
                            (java.util.function.Consumer<Object>) ignored -> {}, true, 512, null, false, false);
                } catch (RuntimeException | Error failed) {
                    diagnostics.put("originalFailureClass",failed.getClass().getName());
                    diagnostics.put("firstBoundary",failed.getStackTrace().length==0 ? "none" : failed.getStackTrace()[0].toString());
                }
                diagnostics.put("probeCount",1);
            }
            java.nio.file.Path card = java.nio.file.Path.of("build/desktop/dynamic-auto-ten-turn-counter.json");
            java.nio.file.Files.createDirectories(card.getParent());
            java.nio.file.Files.writeString(card,new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(java.util.Map.of("scope","synthetic Workflow fixture; one server-bound owner/session",
                            "liveProviderProof",false,"rawPromptStored",false,"rawResponseStored",false,"turns",rows,"diagnostics",diagnostics)));
            org.mockito.Mockito.verifyNoInteractions(ReflectionTestUtils.getField(workflow,"learningWriteInterceptor"),
                    ReflectionTestUtils.getField(workflow,"memoryWriteInterceptor"));
            assertAll("ten turn contract; retain all adverse counters", checks);
        } finally { ReflectionTestUtils.invokeMethod(registry, "shutdown"); clearState(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"안녕?", "고마워"})
    void selectableMetadataAllowsPreferredSocialWithoutFailoverOwnership(String message) {
        Object fixture = ReflectionTestUtils.invokeMethod(
                ChatWorkflowFinalVerificationReleaseGateTest.class, "memoryHoldFixture");
        ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(fixture, "workflow");
        ChatModel model = ReflectionTestUtils.invokeMethod(fixture, "model");
        installLocalCatalog(workflow);
        var catalog = (ChatModelCatalogService) ReflectionTestUtils.getField(workflow, "chatModelCatalogService");
        var registration = (com.example.lms.llm.ChatGptOAuthRegistration) ReflectionTestUtils.getField(catalog, "chatGptOAuth");
        String owner = AttachmentOwnerIdentity.forAnonymous("synthetic-owner").hash();
        org.mockito.Mockito.when(registration.isRegisteredOwner(owner)).thenReturn(false);
        clearState();
        try {
            var request = ChatRequestDto.builder().message(message).model("chatgpt-oauth:gpt-5.6-luna")
                    .modelSelectionMode("preferred").executionMode(ExecutionMode.AUTO)
                    .searchMode(com.example.lms.gptsearch.dto.SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).memoryMode("OFF").build();
            request.bindVerifiedRequestOwner(AttachmentOwnerIdentity.forAnonymous("synthetic-owner"));
            var result = workflow.continueChat(request, ignored -> { throw new AssertionError("social web call"); });
            assertEquals("local:social", result.modelUsed());
            assertFalse(result.content().isBlank());
            assertFalse(result.content().contains("HOLD") || result.content().contains("backend_unavailable"));
            assertTrue(result.evidence().isEmpty());
            assertEquals(Boolean.FALSE, TraceStore.get("finalAnswer.memorySaveAllowed"));
            verifyNoInteractions(model);
            for (String boundary : List.of("modelRouter", "qcPreprocessor", "subjectResolver", "domainDetector",
                    "verifier", "learningWriteInterceptor", "memoryWriteInterceptor",
                    "understandAndMemorizeInterceptor", "attachmentService")) {
                Object dependency = ReflectionTestUtils.getField(workflow, boundary);
                if (dependency != null) verifyNoInteractions(dependency);
            }
        } finally { clearState(); }
    }

    @org.junit.jupiter.api.Test
    void metadataAdmissionDoesNotWidenFirstDefaultOrAdmitMissingModels() {
        Object fixture = ReflectionTestUtils.invokeMethod(
                ChatWorkflowFinalVerificationReleaseGateTest.class, "memoryHoldFixture");
        ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(fixture, "workflow");
        installLocalCatalog(workflow);
        var catalog = (ChatModelCatalogService) ReflectionTestUtils.getField(workflow, "chatModelCatalogService");
        var registration = (com.example.lms.llm.ChatGptOAuthRegistration) ReflectionTestUtils.getField(catalog, "chatGptOAuth");
        String owner = AttachmentOwnerIdentity.forAnonymous("synthetic-owner").hash();
        org.mockito.Mockito.when(registration.isRegisteredOwner(owner)).thenReturn(false);
        var factory = java.util.Map.<String,Object>of("modelSelectionMode", "preferred", "model", "llmrouter.auto");
        assertEquals(factory, catalog.firstSessionDefaults(factory, owner));
        org.mockito.Mockito.when(registration.models(owner)).thenReturn(List.of());
        assertFalse(catalog.isRegisteredOAuthModel("chatgpt-oauth:gpt-5.6-luna", owner));
        clearState();
        try {
            var request = ChatRequestDto.builder().message("안녕?").model("chatgpt-oauth:gpt-5.6-luna")
                    .modelSelectionMode("preferred").executionMode(ExecutionMode.AUTO).build();
            request.bindVerifiedRequestOwner(AttachmentOwnerIdentity.forAnonymous("synthetic-owner"));
            assertThrows(com.example.lms.llm.ModelSelectionException.class,
                    () -> workflow.continueChat(request, ignored -> { throw new AssertionError("missing model web call"); }));
            ChatModel model = ReflectionTestUtils.invokeMethod(fixture, "model");
            verifyNoInteractions(model);
        } finally { clearState(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"delivered", "pending", "recovered", "foreign", "cancelled", "expired", "late"})
    void priorWebCarryRequiresExactNormalDeliveryOwnerAndLiveTtl(String state) {
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        String owner = "a".repeat(64);
        var time = new java.util.concurrent.atomic.AtomicLong(1000);
        ReflectionTestUtils.setField(registry, "clock", (java.util.function.LongSupplier) time::get);
        try {
            var first = registry.beginOrJoin(901L).context();
            var docs = List.of(dev.langchain4j.rag.content.Content.from(dev.langchain4j.data.segment.TextSegment.from(
                    "private synthetic passage", dev.langchain4j.data.document.Metadata.from(java.util.Map.of("url", "https://example.test/a", "kind", "WEB")))));
            var joined = registry.beginOrJoin(901L).context();
            assertTrue(registry.priorWebEvidence(joined, "b".repeat(64)).isEmpty());
            assertFalse(registry.stageWebEvidence(joined, "b".repeat(64), docs));
            assertTrue(registry.priorWebEvidence(first, owner).isEmpty());
            assertTrue(registry.stageWebEvidence(first, owner, docs));
            assertFalse(registry.stageWebEvidence(first, "b".repeat(64), docs));
            first.markGenerationSucceeded();
            if (state.equals("cancelled")) assertTrue(registry.cancelExact(901L, first.clientToken()));
            else {
                first.tryBeginTranscriptCommit(); first.markPersisted();
                first.claimTerminalEvent("delivery_pending"); first.recordFinalEmit("ok", false);
                registry.markDone(first);
            }
            if (state.equals("recovered")) registry.acknowledgeRecoveredDeliveryExact(901L, first.clientToken());
            else if (!state.equals("pending") && !state.equals("late"))
                registry.acknowledgeFinalDeliveryExact(901L, first.clientToken());
            if (state.equals("expired")) time.set(62000);
            String nextOwner = state.equals("foreign") ? "b".repeat(64) : owner;
            var next = registry.beginOrJoin(901L).context();
            if (state.equals("late")) registry.acknowledgeFinalDeliveryExact(901L, first.clientToken());
            assertEquals(state.equals("delivered") ? 1 : 0, registry.priorWebEvidence(next, nextOwner).size());
            assertTrue(registry.priorWebEvidence(next, "c".repeat(64)).isEmpty());
            assertTrue(registry.priorWebEvidence(registry.beginOrJoin(902L).context(), owner).isEmpty());
            assertFalse(registry.stageWebEvidence(first, owner, docs), "retained old owner cannot stage into replacement");
            assertTrue(registry.priorWebEvidence(first, owner).isEmpty());
            var publicResult = new ChatResult("public answer", "fixture", true, java.util.Set.of(), List.of(), null, null, docs);
            String serialized;
            try { serialized = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(publicResult); }
            catch (Exception bad) { throw new AssertionError(bad); }
            assertFalse(serialized.contains("private synthetic passage") || serialized.contains("retainedWebEvidence"));
        } finally { ReflectionTestUtils.invokeMethod(registry, "shutdown"); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"앞의 두 공식 자료를 비교해줘. 그리고 오늘 서울 날씨도 알려줘.",
            "앞의 두 자료를 비교해줘. 오늘 새 자료를 검색해줘.", "안녕, 앞의 두 자료를 비교해줘."})
    void mixedPriorRequestsCannotSuppressFreshFactRetrieval(String message) {
        var request = ChatRequestDto.builder().message(message).executionMode(ExecutionMode.AUTO)
                .searchMode(com.example.lms.gptsearch.dto.SearchMode.AUTO).useWebSearch(true).build();
        assertFalse(ChatWorkflow.isPriorWebComparisonRequest(request));
    }

    @org.junit.jupiter.api.Test
    void priorComparisonKeepsOffForceAndAssetContracts() {
        var request = ChatRequestDto.builder().message("앞의 두 공식 자료를 같은 조건에서 비교하고 가정도 밝혀줘.")
                .executionMode(ExecutionMode.AUTO).searchMode(com.example.lms.gptsearch.dto.SearchMode.AUTO).useWebSearch(true).build();
        assertTrue(ChatWorkflow.isPriorWebComparisonRequest(request));
        assertFalse(ChatWorkflow.isPriorWebComparisonRequest(request.toBuilder().searchMode(com.example.lms.gptsearch.dto.SearchMode.OFF).build()));
        assertFalse(ChatWorkflow.isPriorWebComparisonRequest(request.toBuilder().useWebSearch(false).build()));
        assertFalse(ChatWorkflow.isPriorWebComparisonRequest(request.toBuilder().searchMode(com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT).build()));
        assertFalse(ChatWorkflow.isPriorWebComparisonRequest(request.toBuilder().searchMode(com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP).build()));
        assertFalse(ChatWorkflow.isPriorWebComparisonRequest(request.toBuilder().attachmentIds(List.of("file")).build()));
        assertFalse(ChatWorkflow.isPriorWebComparisonRequest(request.toBuilder().imageBase64("image").build()));
        assertFalse(ChatWorkflow.isPriorWebComparisonRequest(request.toBuilder().ragAnswerPolicy("evidence_only").build()));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "false,true", "false,false"})
    void controllerPriorSupplierKeepsSelectedPlanCaps(boolean webAllowed, boolean headerSelected) throws Exception {
        var history = mock(ChatHistoryService.class);
        var settings = mock(SettingsService.class);
        var owner = mock(com.example.lms.web.ClientOwnerKeyResolver.class);
        var web = mock(com.example.lms.search.provider.WebSearchProvider.class);
        var service = mock(ChatService.class);
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        String ownerHash = AttachmentOwnerIdentity.forAnonymous("synthetic-owner").hash();
        var docs = List.of(dev.langchain4j.rag.content.Content.from(dev.langchain4j.data.segment.TextSegment.from(
                "private plan passage", dev.langchain4j.data.document.Metadata.from(java.util.Map.of("url", "https://example.test/plan", "kind", "WEB")))));
        var previous = registry.beginOrJoin(951L).context();
        registry.priorWebEvidence(previous, ownerHash);
        registry.stageWebEvidence(previous, ownerHash, docs);
        previous.markGenerationSucceeded(); previous.tryBeginTranscriptCommit(); previous.markPersisted();
        previous.claimTerminalEvent("delivery_pending"); previous.recordFinalEmit("ok", false);
        registry.markDone(previous); registry.acknowledgeFinalDeliveryExact(951L, previous.clientToken());
        var session = new com.example.lms.domain.ChatSession("prior plan", "synthetic-owner", "ANON"); session.setId(951L);
        org.mockito.Mockito.when(settings.getAllSettings()).thenReturn(java.util.Map.of());
        org.mockito.Mockito.when(owner.ownerKey()).thenReturn("synthetic-owner");
        org.mockito.Mockito.when(history.getSessionForRequest(951L)).thenReturn(session);
        org.mockito.Mockito.when(history.getSessionWithMessages(951L)).thenReturn(session);
        var ids = new java.util.concurrent.atomic.AtomicLong(1000);
        org.mockito.Mockito.when(history.appendMessageReturningId(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString()))
                .thenAnswer(ignored -> ids.incrementAndGet());
        com.example.lms.api.ChatApiController controller = ReflectionTestUtils.invokeMethod(
                Class.forName("com.example.lms.api.ChatApiControllerInputGuardTest"), "controller",
                history, service, settings, owner, registry, web, null);
        var hints = mock(com.example.lms.plan.PlanHints.class);
        org.mockito.Mockito.when(hints.allowWeb()).thenReturn(webAllowed);
        org.mockito.Mockito.when(hints.allowRag()).thenReturn(false);
        var applier = mock(com.example.lms.plan.PlanHintApplier.class);
        org.mockito.Mockito.when(applier.load("prior-plan")).thenReturn(hints);
        ReflectionTestUtils.setField(controller, "planHintApplier", applier);
        var selector = mock(com.example.lms.orchestration.WorkflowOrchestrator.class);
        org.mockito.Mockito.when(selector.ensurePlanSelected(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(call -> {
                    com.example.lms.service.guard.GuardContext context = call.getArgument(0);
                    context.setPlanId("prior-plan"); return "prior-plan";
                });
        ReflectionTestUtils.setField(controller, "workflowOrchestrator", selector);
        var capturedWeb = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var capturedPrior = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        org.mockito.Mockito.when(service.continueChat(org.mockito.ArgumentMatchers.any(ChatRequestDto.class), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    ChatRequestDto request = call.getArgument(0);
                    java.util.function.Function<String,List<String>> supplier = call.getArgument(1);
                    capturedWeb.set(request.isUseWebSearch());
                    capturedPrior.set(ChatWorkflow.isPriorWebComparisonRequest(request)
                            && supplier instanceof ChatWorkflow.WebEvidenceSupplier typed
                            && typed.priorTurnEvidence() && typed.evidence(request.getMessage()).size() == 1);
                    return ChatResult.of("synthetic comparison", "fixture", false);
                });
        clearState();
        try {
            var request = ChatRequestDto.builder().sessionId(951L).message("앞의 두 공식 자료를 같은 조건에서 비교하고 가정도 밝혀줘.")
                    .model("llmrouter.auto").modelSelectionMode("preferred").executionMode(ExecutionMode.AUTO)
                    .searchMode(com.example.lms.gptsearch.dto.SearchMode.AUTO).useWebSearch(true).useRag(true).memoryMode("OFF").build();
            var servlet = new org.springframework.mock.web.MockHttpServletRequest();
            if (headerSelected) servlet.addHeader("X-Jammini-Mode", "prior-plan");
            var stream = controller.chatStream(request, false, false, null, servlet);
            String token = registry.currentRunToken(951L).orElseThrow();
            controller.acknowledgeRun(null, java.util.Map.of("sessionId",951L,"runToken",token),null,null);
            var events = stream.collectList().block(java.time.Duration.ofSeconds(8));
            assertNotNull(events);
            assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())));
            assertEquals(webAllowed, capturedWeb.get(), "selected plan cap must reach the Workflow DTO");
            assertEquals(webAllowed, capturedPrior.get(), "disabled web cannot promote a retained source");
            org.mockito.Mockito.verify(applier, org.mockito.Mockito.atLeastOnce()).load("prior-plan");
            verifyNoInteractions(web);
        } finally { ReflectionTestUtils.invokeMethod(registry, "shutdown"); clearState(); }
    }

    private static void installLocalCatalog(ChatWorkflow workflow) {
        var cloud = mock(com.example.lms.llm.gateway.CloudModelRouteClassifier.class);
        var registration = mock(com.example.lms.llm.ChatGptOAuthRegistration.class);
        String owner = AttachmentOwnerIdentity.forAnonymous("synthetic-owner").hash();
        org.mockito.Mockito.when(registration.isRegisteredOwner(owner)).thenReturn(true);
        org.mockito.Mockito.when(registration.models(owner)).thenReturn(List.of("gpt-5.6-luna"));
        var catalog = new ChatModelCatalogService(cloud, null,
                new org.springframework.boot.web.client.RestTemplateBuilder(), "https://fixture.invalid", false);
        ReflectionTestUtils.setField(catalog, "chatGptOAuth", registration);
        ReflectionTestUtils.setField(workflow, "chatModelCatalogService", catalog);
    }

    private static void clearState() {
        GuardContextHolder.clear();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }
}
