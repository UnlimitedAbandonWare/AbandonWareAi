package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.ensemble.EnsembleFinalAnswerService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.nlp.QueryDomainClassifier;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.postprocess.FinalAnswerPostProcessor;
import com.example.lms.service.postprocess.OutputSanitizer;
import com.example.lms.service.rag.detector.UniversalDomainDetector;
import com.example.lms.service.rag.handler.MemoryHandler;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.strategy.DomainStrategyFactory;
import com.example.lms.service.subject.SubjectAnalysis;
import com.example.lms.service.subject.SubjectCategory;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.service.verbosity.SectionSpecGenerator;
import com.example.lms.service.verbosity.VerbosityDetector;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatWorkflowS7PromptBoundaryContractTest {

    private static final String S7_QUERY = "질문을 먼저 Self-Ask 방식으로 더 명확하게 재작성한 뒤, "
            + "로컬 챗봇의 응답 지연을 줄이기 위한 가능한 답변 후보를 정확히 A, B, C 세 개만 제시해 비교해줘. "
            + "A는 프롬프트 압축, B는 검색 후보 축소, C는 빠른 로컬 모델 라우팅이다. "
            + "각 후보마다 지지 근거, 확인할 반례, 측정 지표를 포함하고, "
            + "마지막에는 긍정 주장과 부정 주장을 중립적으로 심판해 "
            + "최종 선택 하나 또는 HOLD와 한계를 설명해줘.";

    private static final String COMPLIANT_RESPONSE = """
            S7-FAKE-91
            재작성: 동일 부하에서 품질 저하 없이 로컬 챗봇 지연을 가장 크게 줄이는 선택은 무엇인가?
            A. 프롬프트 압축
            - 지지 근거: 입력 처리량을 줄일 수 있다.
            - 반례: 짧은 프롬프트에서는 효과가 없을 수 있다.
            - 측정 지표: 입력 토큰 수와 p95 지연.
            B. 검색 후보 축소
            - 지지 근거: 검색과 재정렬 비용을 줄일 수 있다.
            - 반례: 필요한 근거가 탈락하면 정확도가 낮아질 수 있다.
            - 측정 지표: recall과 p95 지연.
            C. 빠른 로컬 모델 라우팅
            - 지지 근거: 모델 추론 시간을 줄일 수 있다.
            - 반례: 복잡한 질문에서는 품질이 낮아질 수 있다.
            - 측정 지표: 계약 통과율과 p95 지연.
            중립 판정: B
            한계: 동일 seed의 AB/BA 측정 전에는 일반화할 수 없다.
            """;
    private static final String INCOMPLETE_RESPONSE =
            "S7-FAKE-RED 일반적인 지연 개선 방법을 검토하세요. 환경에 따라 결과는 달라질 수 있습니다.";
    private static final String EPHEMERAL_SENTINEL = "SECRET_SENTINEL_13";

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void hybridMemoryRendersWhileSeparateHistoryFieldsRemainContextOnly() {
        Fixture fixture = fixture(COMPLIANT_RESPONSE);
        String memorySentinel = "PM02_MEMORY_SENTINEL_71";
        String historySentinel = "PM02_HISTORY_SENTINEL_72";
        String lastAnswerSentinel = "PM02_LAST_ANSWER_SENTINEL_73";
        List<String> history = List.of("User: " + historySentinel, "Assistant: synthetic-prior-reply");
        when(fixture.chatHistoryService().getFormattedRecentHistory(anyLong(), anyInt())).thenReturn(history);
        when(fixture.chatHistoryService().getLastAssistantMessage(anyLong()))
                .thenReturn(Optional.of(lastAnswerSentinel));
        when(fixture.memoryHandler().loadForSession(any())).thenReturn(memorySentinel);
        ChatRequestDto request = s7Request().toBuilder().sessionId(73L).memoryMode("HYBRID").build();

        clearRequestState();
        try {
            ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of());

            assertEquals(1, fixture.model().calls.get());
            assertEquals(COMPLIANT_RESPONSE, result.content());
            verify(fixture.memoryHandler(), times(1)).loadForSession(73L);
            verify(fixture.chatHistoryService(), org.mockito.Mockito.atLeastOnce()).getLastAssistantMessage(73L);
            org.mockito.ArgumentCaptor<Integer> historyLimits = org.mockito.ArgumentCaptor.forClass(Integer.class);
            verify(fixture.chatHistoryService(), org.mockito.Mockito.atLeastOnce())
                    .getFormattedRecentHistory(eq(73L), historyLimits.capture());
            assertTrue(historyLimits.getAllValues().stream().allMatch(limit -> limit > 0 && limit <= 8));
            PromptContext context = fixture.promptBuilder().lastContext;
            assertEquals(memorySentinel, context.memory());
            assertEquals(String.join("\n", history), context.history());
            assertEquals(lastAnswerSentinel, context.lastAssistantAnswer());
            assertTrue(context.memoryMode().isReadEnabled());
            assertFalse(context.memoryMode().isWriteEnabled());
            List<ChatMessage> messages = fixture.model().lastMessages;
            assertTrue(messages.stream().allMatch(message -> message instanceof SystemMessage
                    || message instanceof UserMessage || message instanceof AiMessage));
            List<String> texts = messages.stream().map(message -> {
                if (message instanceof SystemMessage system) return system.text();
                if (message instanceof UserMessage user) return user.singleText();
                return ((AiMessage) message).text();
            }).toList();
            int memoryOccurrences = texts.stream()
                    .mapToInt(text -> text.split(Pattern.quote(memorySentinel), -1).length - 1).sum();
            int historyOccurrences = texts.stream()
                    .mapToInt(text -> text.split(Pattern.quote(historySentinel), -1).length - 1).sum();
            int lastAnswerOccurrences = texts.stream()
                    .mapToInt(text -> text.split(Pattern.quote(lastAnswerSentinel), -1).length - 1).sum();
            long memorySystemMessages = messages.stream().filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast).filter(message -> message.text().contains(memorySentinel)).count();
            System.out.printf("PM02_CHANNEL_SHAPE memoryOccurrences=%d historyOccurrences=%d "
                            + "lastAnswerOccurrences=%d memorySystemMessages=%d totalMessages=%d contextLength=%d%n",
                    memoryOccurrences, historyOccurrences, lastAnswerOccurrences, memorySystemMessages,
                    messages.size(), fixture.promptBuilder().lastRenderedContext.length());
            assertEquals(1, memoryOccurrences);
            assertEquals(1L, memorySystemMessages);
            assertEquals(0, historyOccurrences, "characterize the separate history field as context-only");
            assertEquals(0, lastAnswerOccurrences, "characterize the separate last-answer field as context-only");
            assertEquals(1, fixture.promptBuilder().lastRenderedContext
                    .split(Pattern.quote(memorySentinel), -1).length - 1);
            assertFalse(fixture.promptBuilder().lastInstructions.contains(memorySentinel));
            assertFalse(fixture.promptBuilder().lastInstructions.contains(historySentinel));
            assertFalse(fixture.promptBuilder().lastInstructions.contains(lastAnswerSentinel));
            int contextIndex = indexOfSystemText(messages, fixture.promptBuilder().lastRenderedContext);
            assertTrue(contextIndex >= 0);
            assertTrue(messages.get(messages.size() - 1) instanceof UserMessage);
            assertEquals(S7_QUERY, ((UserMessage) messages.get(messages.size() - 1)).singleText());
            verifyNoInteractions(fixture.verifier(), fixture.answerExpander(), fixture.ensembleFinalAnswerService());
        } finally {
            clearRequestState();
        }
    }

    @Test
    void ephemeralNormalPromptCannotReadHistoricalSentinel() {
        Fixture fixture = fixture(COMPLIANT_RESPONSE);
        seedHistoricalSentinel(fixture);

        clearRequestState();
        try {
            ChatResult result = fixture.workflow().continueChat(
                    sessionEphemeralRequest(S7_QUERY), ignored -> List.of());

            assertEquals(1, fixture.model().calls.get());
            assertNoHistoricalReads(fixture);
            assertFalse(result.content().contains(EPHEMERAL_SENTINEL));
            assertFalse(fixture.promptBuilder().lastRenderedContext.contains(EPHEMERAL_SENTINEL));
            assertFalse(fixture.promptBuilder().lastContext.history().contains(EPHEMERAL_SENTINEL));
        } finally {
            clearRequestState();
        }
    }

    @Test
    void focusContextReachesActualModelWithRolesWhenWebAndGlobalMemoryAreOff(){
        Fixture fixture=fixture("기억한 이름은 별빛입니다.");
        var memory=new ChatConversationContext(List.of(
                new ChatConversationContext.Turn("최근 질문 하나","최근 답변 하나"),
                new ChatConversationContext.Turn("이름을 정하자","별빛으로 정했습니다.")),
                "요약 표식",List.of(new ChatConversationContext.Turn("과거 질문 표식","과거 답변 표식")));
        clearRequestState();
        try{
            var request=s7Request().toBuilder().message("그 이름을 사용해서 짧은 인사를 작성해 줘").build();
            fixture.workflow().continueChat(request,ignored->{throw new AssertionError("web must remain off");},memory);
            assertEquals(1,fixture.model().calls.get());
            var messages=fixture.model().lastMessages;
            var roles=messages.stream().filter(m->!(m instanceof SystemMessage)).toList();
            assertEquals(5,roles.size());
            assertEquals("최근 질문 하나",((UserMessage)roles.get(0)).singleText());
            assertEquals("최근 답변 하나",((AiMessage)roles.get(1)).text());
            assertEquals("이름을 정하자",((UserMessage)roles.get(2)).singleText());
            assertEquals("별빛으로 정했습니다.",((AiMessage)roles.get(3)).text());
            assertEquals(request.getMessage(),((UserMessage)roles.get(4)).singleText());
            assertTrue(fixture.promptBuilder().lastRenderedContext.contains("요약 표식"));
            assertTrue(fixture.promptBuilder().lastRenderedContext.contains("과거 질문 표식"));
            assertTrue(fixture.promptBuilder().lastContext.history().contains("별빛으로 정했습니다."));
            assertNoHistoricalReads(fixture);
        }finally{clearRequestState();}
    }

    @Test
    void ephemeralAskLaterFallbackCannotReadHistoricalSentinel() {
        assertEphemeralFallbackCannotReadHistory("방금 내가 뭐라고 했지?");
    }

    @Test
    void ephemeralRecentHistoryFallbackCannotReadHistoricalSentinel() {
        assertEphemeralFallbackCannotReadHistory("새로고침 전 내가 뭐라고 했지?");
    }

    @Test
    void ephemeralBudgetFallbackCannotReadHistoricalSentinel() {
        Fixture fixture = fixture(COMPLIANT_RESPONSE);
        seedHistoricalSentinel(fixture);
        TimeBudget exhausted = mock(TimeBudget.class);
        when(exhausted.expired()).thenReturn(true);
        when(exhausted.remainingMillis()).thenReturn(0L);

        clearRequestState();
        TimeBudgetContext.set(exhausted);
        try {
            ChatResult result = fixture.workflow().continueChat(
                    sessionEphemeralRequest("현재 증거만 사용해서 지연 개선 방법을 요약해줘."),
                    ignored -> List.of());

            assertNoHistoricalReads(fixture);
            assertFalse(result.content().contains(EPHEMERAL_SENTINEL));
            assertEquals("request_budget_exhausted", TraceStore.get("llm.final.skipped"));
        } finally {
            clearRequestState();
        }
    }

    @Test
    void fullWorkflowPreservesS7PromptAndCompliantStructuredAnswer() {
        Fixture fixture = fixture(COMPLIANT_RESPONSE);
        RecordingPromptBuilder promptBuilder = fixture.promptBuilder();
        RecordingModel model = fixture.model();
        ModelRouter modelRouter = fixture.modelRouter();
        FactVerifierService verifier = fixture.verifier();
        com.example.lms.service.answer.AnswerExpanderService answerExpander = fixture.answerExpander();
        EnsembleFinalAnswerService ensembleFinalAnswerService = fixture.ensembleFinalAnswerService();

        clearRequestState();
        try {
            ChatResult result = fixture.workflow().continueChat(s7Request(), ignored -> List.of());

            assertEquals(1, model.calls.get(),
                    "direct retrieval-off S7 must reach one primary logical model invocation");
            assertEquals(S7_QUERY, promptBuilder.lastContext.userQuery());
            assertTrue(promptBuilder.lastRenderedContext.contains("Self-Ask 방식으로 더 명확하게 재작성"));
            assertTrue(promptBuilder.lastRenderedContext.contains("정확히 A, B, C 세 개만"));
            assertTrue(promptBuilder.lastRenderedContext.contains("각 후보마다 지지 근거, 확인할 반례, 측정 지표"));
            assertTrue(promptBuilder.lastRenderedContext.contains("최종 선택 하나 또는 HOLD"));

            int instructionsIndex = indexOfSystemText(model.lastMessages, promptBuilder.lastInstructions);
            int contextIndex = indexOfSystemText(model.lastMessages, promptBuilder.lastRenderedContext);
            assertTrue(instructionsIndex >= 0 && instructionsIndex < contextIndex,
                    "buildInstructions must precede the final PromptBuilder context");
            assertTrue(model.lastMessages.get(model.lastMessages.size() - 1) instanceof UserMessage);
            assertEquals(S7_QUERY,
                    ((UserMessage) model.lastMessages.get(model.lastMessages.size() - 1)).singleText());

            assertTrue(satisfiesS7Contract(COMPLIANT_RESPONSE));
            assertEquals(COMPLIANT_RESPONSE, result.content(),
                    "the full workflow must preserve a compliant structured S7 answer");
            verify(modelRouter, times(1)).route(
                    anyString(), nullable(String.class), anyString(), anyInt(), eq("s7-recording-fake"));
            verifyNoInteractions(verifier, answerExpander, ensembleFinalAnswerService);
        } finally {
            clearRequestState();
        }
    }

    @Test
    void incompleteS7AnswerMustBecomeCompleteStructureOrExplicitHold() {
        Fixture fixture = fixture(INCOMPLETE_RESPONSE);

        clearRequestState();
        try {
            ChatResult result = fixture.workflow().continueChat(s7Request(), ignored -> List.of());

            assertTrue(satisfiesS7Contract(result.content()),
                    "S7-FAKE-RED must return rewritten A/B/C support-counterexample-metric blocks "
                            + "and one neutral choice, or an explicit HOLD with limitation");
        } finally {
            clearRequestState();
        }
    }

    @Test
    void explicitHoldWithLimitationSatisfiesTheTerminalS7Contract() {
        assertTrue(satisfiesS7Contract(
                "HOLD\n한계: 현재 증거로 후보 내용을 안전하게 완성할 수 없습니다."));
    }

    @Test
    void boundedNumberOnlyProfileSkipsGpt5AndOSeriesOutputLengthPolicy() {
        for (String resolvedModel : List.of("gpt-5.5", "o3-mini")) {
            Fixture fixture = fixture("3");
            when(fixture.modelRouter().resolveModelName(fixture.model())).thenReturn(resolvedModel);
            ChatRequestDto request = s7Request().toBuilder()
                    .message("다음 문장에 있는 영어 단어 수를 세고 아라비아 숫자만 답해줘: red blue green.")
                    .build();

            clearRequestState();
            try {
                ChatResult result = fixture.workflow().continueChat(request, ignored -> List.of());

                assertTrue(OpenAiTokenParamCompat.usesMaxCompletionTokens(resolvedModel));
                assertEquals(0, fixture.promptBuilder().lastContext.minWordCount());
                assertFalse(fixture.promptBuilder().lastInstructions.contains("### SECTION TEMPLATE"),
                        "bounded output must already omit the builder-level section template");
                assertEquals(1, fixture.model().calls.get());
                assertEquals("3", result.content());
                assertFalse(hasOutputLengthPolicy(fixture.model().lastMessages),
                        () -> resolvedModel + " must not receive a late section-shaped output policy");
            } finally {
                clearRequestState();
            }
        }
    }

    @Test
    void positiveFloorProfileRetainsGptOutputLengthPolicy() {
        Fixture fixture = fixture("서울입니다.");
        when(fixture.modelRouter().resolveModelName(fixture.model())).thenReturn("gpt-5.5");
        ChatRequestDto request = s7Request().toBuilder()
                .message("대한민국 수도의 역사적 배경을 설명해줘.")
                .build();

        clearRequestState();
        try {
            fixture.workflow().continueChat(request, ignored -> List.of());

            assertTrue(fixture.promptBuilder().lastContext.minWordCount() > 0);
            assertTrue(hasOutputLengthPolicy(fixture.model().lastMessages),
                    "positive-floor profiles must retain the GPT/o-series length policy");
        } finally {
            clearRequestState();
        }
    }

    private static Fixture fixture(String response) {
        RecordingPromptBuilder promptBuilder = new RecordingPromptBuilder();
        RecordingModel model = new RecordingModel(response);
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.route(
                anyString(), nullable(String.class), anyString(), anyInt(), anyString()))
                .thenReturn(model);
        when(modelRouter.resolveModelName(model)).thenReturn("s7-recording-fake");

        SubjectResolver subjectResolver = mock(SubjectResolver.class);
        SubjectAnalysis subject = SubjectAnalysis.builder()
                .category(SubjectCategory.GENERAL)
                .build();
        when(subjectResolver.analyze(anyString(), anyList(), any())).thenReturn(subject);
        UniversalDomainDetector domainDetector = mock(UniversalDomainDetector.class);
        when(domainDetector.detect(anyString(), any())).thenReturn("GENERAL");
        QueryContextPreprocessor queryContextPreprocessor = mock(QueryContextPreprocessor.class);
        when(queryContextPreprocessor.getInteractionRules(anyString())).thenReturn(Map.of());
        FactVerifierService verifier = mock(FactVerifierService.class);
        com.example.lms.service.answer.AnswerExpanderService answerExpander =
                mock(com.example.lms.service.answer.AnswerExpanderService.class);
        EnsembleFinalAnswerService ensembleFinalAnswerService = mock(EnsembleFinalAnswerService.class);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        MemoryHandler memoryHandler = mock(MemoryHandler.class);
        VerbosityDetector verbosityDetector = new VerbosityDetector();
        ReflectionTestUtils.setField(verbosityDetector, "minBrief", 120);
        ReflectionTestUtils.setField(verbosityDetector, "minStd", 250);
        ReflectionTestUtils.setField(verbosityDetector, "minDeep", 600);
        ReflectionTestUtils.setField(verbosityDetector, "minUltra", 1_000);
        ReflectionTestUtils.setField(verbosityDetector, "tokBrief", 800);
        ReflectionTestUtils.setField(verbosityDetector, "tokStd", 1_000);
        ReflectionTestUtils.setField(verbosityDetector, "tokDeep", 1_500);
        ReflectionTestUtils.setField(verbosityDetector, "tokUltra", 2_200);

        ChatWorkflow workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "cancelFlags", new java.util.concurrent.ConcurrentHashMap<>());
        ReflectionTestUtils.setField(workflow, "interactionPolicyMode", "off");
        ReflectionTestUtils.setField(workflow, "promptContextRefinerEnabled", false);
        ReflectionTestUtils.setField(workflow, "queryDomainClassifier", new QueryDomainClassifier());
        ReflectionTestUtils.setField(workflow, "guardProfileProps", new GuardProfileProps());
        ReflectionTestUtils.setField(workflow, "subjectResolver", subjectResolver);
        ReflectionTestUtils.setField(workflow, "domainDetector", domainDetector);
        ReflectionTestUtils.setField(workflow, "domainStrategyFactory", new DomainStrategyFactory());
        ReflectionTestUtils.setField(workflow, "verbosityDetector", verbosityDetector);
        ReflectionTestUtils.setField(workflow, "sectionSpecGenerator", new SectionSpecGenerator());
        ReflectionTestUtils.setField(workflow, "qcPreprocessor", queryContextPreprocessor);
        ReflectionTestUtils.setField(workflow, "evidenceAwareGuard", mock(EvidenceAwareGuard.class));
        ReflectionTestUtils.setField(workflow, "promptBuilder", promptBuilder);
        ReflectionTestUtils.setField(workflow, "modelRouter", modelRouter);
        ReflectionTestUtils.setField(workflow, "lengthVerifier",
                mock(com.example.lms.service.answer.LengthVerifierService.class));
        ReflectionTestUtils.setField(workflow, "verifier", verifier);
        ReflectionTestUtils.setField(workflow, "answerExpander", answerExpander);
        ReflectionTestUtils.setField(workflow, "ensembleFinalAnswerService", ensembleFinalAnswerService);
        ReflectionTestUtils.setField(workflow, "chatHistoryService", chatHistoryService);
        ReflectionTestUtils.setField(workflow, "memoryHandler", memoryHandler);
        ReflectionTestUtils.setField(workflow, "finalAnswerPostProcessor",
                new FinalAnswerPostProcessor(new OutputSanitizer()));
        ReflectionTestUtils.setField(workflow, "chatUsageLedger", new ChatUsageLedger());
        ReflectionTestUtils.setField(workflow, "llmProvider", "local");
        ReflectionTestUtils.setField(workflow, "defaultModel", "s7-recording-fake");
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 0);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5_000L);
        ReflectionTestUtils.setField(workflow, "llmFastBailoutMinTimeoutHitsWithEvidence", 10);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", false);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", false);

        return new Fixture(
                workflow,
                promptBuilder,
                model,
                modelRouter,
                verifier,
                answerExpander,
                ensembleFinalAnswerService,
                chatHistoryService,
                memoryHandler);
    }

    private static ChatRequestDto s7Request() {
        return ChatRequestDto.builder()
                .message(S7_QUERY)
                .model("s7-recording-fake")
                .maxTokens(512)
                .mode("FACT")
                .memoryMode("EPHEMERAL")
                .searchMode(SearchMode.OFF)
                .useWebSearch(false)
                .useRag(false)
                .useVerification(false)
                .build();
    }

    private static ChatRequestDto sessionEphemeralRequest(String message) {
        return s7Request().toBuilder()
                .sessionId(13L)
                .message(message)
                .build();
    }

    private static void seedHistoricalSentinel(Fixture fixture) {
        when(fixture.chatHistoryService().getFormattedRecentHistory(anyLong(), anyInt()))
                .thenReturn(List.of(
                        "User: " + EPHEMERAL_SENTINEL,
                        "Assistant: remembered response"));
        when(fixture.chatHistoryService().getLastAssistantMessage(anyLong()))
                .thenReturn(Optional.of(EPHEMERAL_SENTINEL));
        when(fixture.memoryHandler().loadForSession(any()))
                .thenReturn(EPHEMERAL_SENTINEL);
    }

    private static void assertEphemeralFallbackCannotReadHistory(String query) {
        Fixture fixture = fixture(COMPLIANT_RESPONSE);
        seedHistoricalSentinel(fixture);

        clearRequestState();
        try {
            ChatResult result = fixture.workflow().continueChat(
                    sessionEphemeralRequest(query), ignored -> List.of());

            assertEquals(1, fixture.model().calls.get(),
                    "EPHEMERAL history questions must not short-circuit from stored history");
            assertNoHistoricalReads(fixture);
            assertFalse(result.content().contains(EPHEMERAL_SENTINEL));
            assertFalse(fixture.promptBuilder().lastRenderedContext.contains(EPHEMERAL_SENTINEL));
        } finally {
            clearRequestState();
        }
    }

    private static void assertNoHistoricalReads(Fixture fixture) {
        verify(fixture.chatHistoryService(), never())
                .getFormattedRecentHistory(anyLong(), anyInt());
        verify(fixture.chatHistoryService(), never()).getLastAssistantMessage(anyLong());
        verify(fixture.memoryHandler(), never()).loadForSession(any());
    }

    private static void clearRequestState() {
        GuardContextHolder.clear();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    private static boolean satisfiesS7Contract(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        if (Pattern.compile("(?is)^\\s*HOLD\\b.*(?:한계|limitation)\\s*[:：]\\s*\\S.*$")
                .matcher(answer).matches()) {
            return true;
        }
        String normalized = answer.replace("\r\n", "\n");
        if (!Pattern.compile("(?im)^\\s*(?:재작성|rewritten\\s+question)\\s*[:：]")
                .matcher(normalized).find()) {
            return false;
        }

        int a = singleHeaderIndex(normalized, "A");
        int b = singleHeaderIndex(normalized, "B");
        int c = singleHeaderIndex(normalized, "C");
        if (!(a >= 0 && a < b && b < c) || singleHeaderIndex(normalized, "D") >= 0) {
            return false;
        }
        if (!candidateBlockComplete(normalized.substring(a, b))
                || !candidateBlockComplete(normalized.substring(b, c))) {
            return false;
        }

        Matcher verdict = Pattern.compile(
                "(?im)^\\s*(?:중립\\s*판정|최종\\s*(?:선택|판정)|neutral\\s*verdict)\\s*[:：]\\s*(A|B|C|HOLD)\\b")
                .matcher(normalized);
        int verdictCount = 0;
        String decision = "";
        int verdictIndex = -1;
        while (verdict.find()) {
            verdictCount++;
            decision = verdict.group(1).toUpperCase(Locale.ROOT);
            verdictIndex = verdict.start();
        }
        if (verdictCount != 1 || verdictIndex <= c
                || !candidateBlockComplete(normalized.substring(c, verdictIndex))) {
            return false;
        }
        return !"HOLD".equals(decision)
                || Pattern.compile("(?i)(한계|limitation)").matcher(normalized.substring(verdictIndex)).find();
    }

    private static int singleHeaderIndex(String answer, String label) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + label + "\\s*[.)：:]\\s+").matcher(answer);
        if (!matcher.find()) {
            return -1;
        }
        int index = matcher.start();
        return matcher.find() ? -1 : index;
    }

    private static boolean candidateBlockComplete(String block) {
        return Pattern.compile("(?i)(지지\\s*근거|support)").matcher(block).find()
                && Pattern.compile("(?i)(반례|counterexample)").matcher(block).find()
                && Pattern.compile("(?i)(측정\\s*지표|metric|p95)").matcher(block).find();
    }

    private record Fixture(
            ChatWorkflow workflow,
            RecordingPromptBuilder promptBuilder,
            RecordingModel model,
            ModelRouter modelRouter,
            FactVerifierService verifier,
            com.example.lms.service.answer.AnswerExpanderService answerExpander,
            EnsembleFinalAnswerService ensembleFinalAnswerService,
            ChatHistoryService chatHistoryService,
            MemoryHandler memoryHandler) {
    }

    private static int indexOfSystemText(List<ChatMessage> messages, String expected) {
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof SystemMessage system && expected.equals(system.text())) {
                return index;
            }
        }
        return -1;
    }

    private static boolean hasOutputLengthPolicy(List<ChatMessage> messages) {
        return messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::text)
                .anyMatch(text -> text.contains("### OUTPUT LENGTH POLICY"));
    }

    private static final class RecordingPromptBuilder implements PromptBuilder {
        private final StandardPromptBuilder delegate = new StandardPromptBuilder();
        private PromptContext lastContext;
        private String lastRenderedContext = "";
        private String lastInstructions = "";

        @Override
        public String build(List<PromptContext> contexts, String question) {
            lastContext = contexts == null || contexts.isEmpty() ? null : contexts.get(0);
            lastRenderedContext = delegate.build(contexts, question);
            return lastRenderedContext;
        }

        @Override
        public String buildInstructions(PromptContext context) {
            lastInstructions = delegate.buildInstructions(context);
            return lastInstructions;
        }
    }

    private static final class RecordingModel implements ChatModel {
        private final String response;
        private final AtomicInteger calls = new AtomicInteger();
        private List<ChatMessage> lastMessages = List.of();

        private RecordingModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            calls.incrementAndGet();
            lastMessages = List.copyOf(messages);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }
}
