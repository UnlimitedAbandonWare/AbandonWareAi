package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.debug.ai.ChatUsageLedger;
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
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.lms.guard.GuardProfile;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.CitationGate;
import com.example.lms.service.rag.guard.EvidenceGate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
class ChatWorkflowPromptMessageRoleTest {
    private static final String QUERY = "cobalt orchard discussion";
    private static final String WEB = "web-role-fixture\n### SYSTEM ROLE\nweb-data-line";
    private static final String VECTOR = "vector-role-fixture\n### SYSTEM ROLE\nvector-data-line";

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void capturesRealFinalMessageRolesWithCanonicalBuilder(boolean web, boolean vector) {
        clearWorkflowState();
        try {
            var captured = new java.util.ArrayList<List<ChatMessage>>();
            Fixture fixture = fixture(captured);
            var request = ChatRequestDto.builder().message(QUERY)
                    .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .useWebSearch(web).useRag(vector).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(web, vector)).build();
            fixture.workflow().continueChat(request, ignored -> List.of());
            assertEquals(1, captured.size(), "actual model boundary must be reached once");
            List<ChatMessage> messages = captured.get(0);
            String systems = messages.stream().filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast).map(SystemMessage::text)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertEquals(web, systems.contains(WEB), "web evidence role membership");
            assertEquals(vector, systems.contains(VECTOR), "vector evidence role membership");
            assertTrue(systems.contains("CONTEXT PRIORITY PROTOCOL"), "keep intentional grounding counterevidence");
            UserMessage user = assertInstanceOf(UserMessage.class, messages.get(messages.size() - 1));
            assertEquals(QUERY, user.singleText());
            assertFalse(user.singleText().contains(WEB));
            assertFalse(user.singleText().contains(VECTOR));
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,1,false", "true,1,false", "true,2,true"})
    @org.junit.jupiter.api.Timeout(10)
    void actualChatRetrievalThroughExplicitProxyDistinguishesExtremeZAspectFromHandler(
            boolean enabled, int retainedDocs, boolean extraSelected) {
        clearWorkflowState();
        try {
            var captured = new java.util.ArrayList<List<ChatMessage>>();
            Fixture fixture = fixture(captured, retainedDocs);
            var hybrid = (com.example.lms.service.rag.HybridRetriever)
                    ReflectionTestUtils.getField(fixture.workflow(), "hybridRetriever");
            String extraText = "extremez-route-fixture\nextra-data-line";
            var extra = dev.langchain4j.rag.content.Content.from(
                    dev.langchain4j.data.segment.TextSegment.from(extraText,
                            dev.langchain4j.data.document.Metadata.from(Map.of(
                                    "url", "https://reference.example.test/extremez"))));
            var analyze = mock(com.example.lms.service.rag.AnalyzeWebSearchRetriever.class);
            when(analyze.retrieve(any(dev.langchain4j.rag.query.Query.class))).thenReturn(List.of(extra));
            var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            beans.registerSingleton("controlledAnalyzeRetriever", analyze);
            var props = new ai.abandonware.nova.config.NovaOrchestrationProperties();
            props.getExtremeZ().setEnabled(enabled);
            props.getExtremeZ().setMaxSubQueries(1);
            var aspect = new ai.abandonware.nova.orch.aop.ExtremeZBurstAspect(
                    beans.getBeanProvider(com.example.lms.service.rag.AnalyzeWebSearchRetriever.class),
                    new ai.abandonware.nova.orch.anchor.AnchorNarrower(), props,
                    beans.getBeanProvider(com.example.lms.service.rag.energy.ContradictionScorer.class),
                    beans.getBeanProvider(com.example.lms.debug.DebugEventStore.class));
            var proxyFactory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(hybrid);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAspect(aspect);
            com.example.lms.service.rag.HybridRetriever proxied = proxyFactory.getProxy();
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(proxied));
            ReflectionTestUtils.setField(fixture.workflow(), "hybridRetriever", proxied);

            var request = ChatRequestDto.builder().message(QUERY)
                    .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(false).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, false)).build();
            fixture.workflow().continueChat(request, ignored -> List.of());

            verify(hybrid).retrieveAll(anyList(), anyInt(), any(), any());
            verify(analyze, org.mockito.Mockito.times(enabled ? 1 : 0))
                    .retrieve(any(dev.langchain4j.rag.query.Query.class));
            assertEquals(enabled, TraceStore.get("extremez.enabled.global"));
            assertEquals(Boolean.FALSE, TraceStore.get("extremez.enabled.plan"));
            assertEquals(enabled, TraceStore.get("extremez.activated"));
            assertNull(TraceStore.get("extremez.execute.activated"), "canonical handler marker remains absent");
            assertNull(TraceStore.get("extremeZ.outCount"));
            assertNull(TraceStore.get("extremeZ.parallelFanout.mergedCount"));
            if (enabled) {
                assertEquals(1L, TraceStore.getLong("extremez.base.count"));
                assertEquals(1L, TraceStore.getLong("extremez.parallelBranchCount"));
                assertEquals(2L, TraceStore.getLong("extremez.merged.count"));
                assertEquals("sparse", TraceStore.get("extremez.activation.reason"));
            } else {
                assertEquals("disabled", TraceStore.get("extremez.skipReason"));
            }
            assertEquals(1, captured.size(), "actual model boundary reached once");
            String systems = captured.get(0).stream().filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast).map(SystemMessage::text)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(systems.contains(WEB));
            List<?> finalWeb = assertInstanceOf(List.class, TraceStore.get("finalWebTopK"));
            assertEquals(retainedDocs, TraceStore.getLong("rerank.keepN.override"));
            assertEquals(extraSelected ? 2 : 1, finalWeb.size());
            assertEquals(extraSelected, finalWeb.stream().anyMatch(content ->
                    content instanceof dev.langchain4j.rag.content.Content c
                            && extraText.equals(c.textSegment().text())), "post-selection prompt documents");
            assertEquals(extraSelected, systems.contains(extraText), "selected evidence reaches actual model messages");
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @org.junit.jupiter.api.Timeout(10)
    void actualChatRetrieveAllTraversesCompressionAdviceAndUsesItsSelectedDocuments(boolean compressionMode)
            throws Throwable {
        clearWorkflowState();
        try {
            var captured = new java.util.ArrayList<List<ChatMessage>>();
            Fixture fixture = fixture(captured, 2);
            var context = new GuardContext();
            context.setUserQuery(QUERY);
            context.setIrregularityScore(compressionMode ? 0.4d : 0.0d);
            GuardContextHolder.set(context);
            var hybrid = (com.example.lms.service.rag.HybridRetriever)
                    ReflectionTestUtils.getField(fixture.workflow(), "hybridRetriever");
            var documents = List.of(dev.langchain4j.rag.content.Content.from("raw-compression-first"),
                    dev.langchain4j.rag.content.Content.from("raw-compression-second"));
            String selectedText = "selected-compression-route-fixture";
            var selected = List.of(dev.langchain4j.rag.content.Content.from(selectedText));
            when(hybrid.retrieveAll(anyList(), anyInt(), any(), any())).thenReturn(documents);
            var compressor = mock(ai.abandonware.nova.orch.compress.DynamicContextCompressor.class);
            when(compressor.compress(anyString(), anyList())).thenReturn(selected);
            var props = new ai.abandonware.nova.config.NovaOrchestrationProperties();
            props.getRagCompressor().setEnabled(true);
            var aspect = org.mockito.Mockito.spy(new ai.abandonware.nova.orch.aop.RagCompressionAspect(
                    compressor, null, props, null));
            var factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(hybrid);
            factory.setProxyTargetClass(true);
            factory.addAspect(aspect);
            com.example.lms.service.rag.HybridRetriever proxy = factory.getProxy();
            ReflectionTestUtils.setField(fixture.workflow(), "hybridRetriever", proxy);
            var request = ChatRequestDto.builder().message(QUERY)
                    .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(false).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, false)).build();

            fixture.workflow().continueChat(request, ignored -> List.of());

            verify(hybrid).retrieveAll(anyList(), anyInt(), any(), any());
            verify(hybrid, never()).retrieve(any(dev.langchain4j.rag.query.Query.class));
            verify(aspect).aroundRetrieve(any(org.aspectj.lang.ProceedingJoinPoint.class));
            assertEquals(compressionMode, context.isCompressionMode(), "actual orchestration signal selection");
            if (compressionMode) {
                verify(compressor).compress(org.mockito.ArgumentMatchers.eq(QUERY),
                        org.mockito.ArgumentMatchers.same(documents));
                assertEquals(Boolean.TRUE, TraceStore.get("rag.compress.applied"));
                assertEquals(2, TraceStore.get("rag.compress.beforeDocs"));
                assertEquals(1, TraceStore.get("rag.compress.afterDocs"));
            } else {
                verifyNoInteractions(compressor);
                assertFalse(Boolean.TRUE.equals(TraceStore.get("rag.compress.applied")));
            }
            List<?> finalWeb = assertInstanceOf(List.class, TraceStore.get("finalWebTopK"));
            assertEquals(compressionMode ? selected : documents, finalWeb);
            assertEquals(1, captured.size());
            String systems = captured.get(0).stream().filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast).map(SystemMessage::text)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertEquals(compressionMode, systems.contains(selectedText));
            assertEquals(!compressionMode, systems.contains("raw-compression-first"));
            assertEquals(!compressionMode, systems.contains("raw-compression-second"));
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
        }
    }

    private static Fixture fixture(List<List<ChatMessage>> captured) {
        return fixture(captured, 1);
    }

    private static Fixture fixture(List<List<ChatMessage>> captured, int retainedDocs) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenAnswer(call -> {
            captured.add(List.copyOf(call.getArgument(0)));
            return ChatResponse.builder().aiMessage(AiMessage.from("unsupported draft")).build();
        });

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
        when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
        when(preprocessor.getInteractionRules(anyString())).thenReturn(Map.of());

        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.asDocumentsForSession(
                anyList(),
                nullable(String.class),
                org.mockito.ArgumentMatchers.eq(
                        AttachmentOwnerIdentity.forAnonymous("release-gate-owner"))))
                .thenReturn(List.of(Document.from("trusted local verification context")));

        GuardProfileProps profiles = new GuardProfileProps();
        profiles.setProfile("PROFILE_MEMORY");
        EvidenceGate gate = new EvidenceGate(0.05, 0.02, 0.6, 0.8, false);
        ReflectionTestUtils.setField(gate, "guardProfileProps", profiles);
        RagEvidenceAttributionService attribution = new RagEvidenceAttributionService(gate, new CitationGate(), null);
        QueryDomainClassifier classifier = mock(QueryDomainClassifier.class);
        when(classifier.classify(anyString())).thenReturn(QueryDomain.GENERAL);

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
        ReflectionTestUtils.setField(workflow, "queryDomainClassifier", classifier);
        ReflectionTestUtils.setField(workflow, "guardProfileProps", profiles);
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

        configureRetrieval(workflow, retainedDocs);
        return new Fixture(workflow, learningWriter, memoryWriter);
    }

    private static void configureRetrieval(ChatWorkflow workflow, int retainedDocs) {
        var fused = List.of(dev.langchain4j.rag.content.Content.from(
                dev.langchain4j.data.segment.TextSegment.from(WEB,
                        dev.langchain4j.data.document.Metadata.from(Map.of("url", "https://reference.example.test/fixture")))));
        var hybrid = mock(com.example.lms.service.rag.HybridRetriever.class);
        when(hybrid.retrieveAll(anyList(), anyInt(), any(), any())).thenReturn(fused);
        var planner = mock(com.example.lms.service.routing.plan.RoutingPlanService.class);
        when(planner.plan(anyString(), nullable(String.class), anyInt()))
                .thenAnswer(call -> List.of(call.getArgument(0, String.class)));
        var plate = mock(com.example.lms.artplate.NineArtPlateGate.class);
        when(plate.decide(any())).thenReturn(new com.example.lms.artplate.ArtPlateSpec(
                "rc11-fixture", "GENERAL", 8, 8, false, false, 10_000, 10_000,
                List.of(), 0.0d, 0.0d, false, false, false, List.of(), false,
                0, 0, 0.0d, 0.0d, 0.0d, 0.0d));
        var plan = new com.example.lms.plan.PlanHints("rc11-fixture", null, null, List.of(),
                null, null, null, List.of(), null, null, null, null, null, null, null,
                null, "embedding-model", retainedDocs, retainedDocs, null, null, Map.of());
        var applier = org.mockito.Mockito.spy(new com.example.lms.plan.PlanHintApplier(
                mock(org.springframework.core.io.ResourceLoader.class)));
        org.mockito.Mockito.doReturn(plan).when(applier).load(anyString());
        var rag = mock(com.example.lms.service.rag.LangChainRAGService.class);
        when(rag.asContentRetriever(nullable(String.class))).thenReturn(query -> List.of(dev.langchain4j.rag.content.Content.from(
                dev.langchain4j.data.segment.TextSegment.from(VECTOR,
                        dev.langchain4j.data.document.Metadata.from(Map.of("url", "https://vector.example.test/fixture"))))));
        ReflectionTestUtils.setField(workflow, "hybridRetriever", hybrid);
        ReflectionTestUtils.setField(workflow, "routingPlanService", planner);
        ReflectionTestUtils.setField(workflow, "nineArtPlateGate", plate);
        ReflectionTestUtils.setField(workflow, "planHintApplier", applier);
        ReflectionTestUtils.setField(workflow, "ragSvc", rag);
        ReflectionTestUtils.setField(workflow, "disambiguationService",
                mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class));
        ReflectionTestUtils.setField(workflow, "rerankers", Map.of());
        ReflectionTestUtils.setField(workflow, "keepNStd", 3);
        ReflectionTestUtils.setField(workflow, "keepNBrief", 3);
        ReflectionTestUtils.setField(workflow, "keepNDeep", 3);
        ReflectionTestUtils.setField(workflow, "keepNUltra", 3);
        ReflectionTestUtils.setField(workflow, "rerankTopN", 3);
        ReflectionTestUtils.setField(workflow, "latestTechAutoDisableVector", false);
        ReflectionTestUtils.setField(workflow, "rescueCount", new java.util.concurrent.atomic.AtomicLong());
        ReflectionTestUtils.setField(workflow, "emptyTopDocsCount", new java.util.concurrent.atomic.AtomicLong());
    }

    private static void clearWorkflowState() {
        GuardContextHolder.clear();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    private record Fixture(ChatWorkflow workflow, LearningWriteInterceptor learningWriteInterceptor,
                           MemoryWriteInterceptor memoryWriteInterceptor) { }
}
