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

class ChatWorkflowCrossEncoderMembershipCharacterizationTest {
    @Test
    void defaultCandidateBudgetPassesAllFusedMembersAndUsesReturnedSelection() {
        Probe p = probe(null, null, true, "reverse");
        assertEquals(p.fused, p.reranker.input);
        assertEquals(3, p.reranker.topN);
        assertEquals(List.of(p.fused.get(7), p.fused.get(6), p.fused.get(5)), p.promotedWeb);
    }

    @Test
    void explicitCandidateCapLimitsOnlyTheScoredPrefix() {
        Probe p = probe(2, 5, true, "reverse");
        assertEquals(p.fused.subList(0, 5), p.reranker.input);
        assertEquals(2, p.reranker.topN);
        assertEquals(List.of(p.fused.get(4), p.fused.get(3)), p.promotedWeb);
    }

    @Test
    void candidateCapCannotFallBelowTheRequestedKeepCount() {
        Probe p = probe(3, 1, true, "reverse");
        assertEquals(p.fused.subList(0, 3), p.reranker.input);
        assertEquals(3, p.reranker.topN);
        assertEquals(List.of(p.fused.get(2), p.fused.get(1), p.fused.get(0)), p.promotedWeb);
    }

    @Test
    void keepCountAloneDerivesTwiceAsManyScoringCandidates() {
        Probe p = probe(2, null, true, "reverse");
        assertEquals(p.fused.subList(0, 4), p.reranker.input);
        assertEquals(2, p.reranker.topN);
        assertEquals(List.of(p.fused.get(3), p.fused.get(2)), p.promotedWeb);
    }

    @Test
    void disabledCrossEncoderUsesFusedPrefixWithoutCallingReranker() {
        Probe p = probe(2, 5, false, "reverse");
        assertEquals(0, p.reranker.calls);
        assertEquals(p.fused.subList(0, 2), p.promotedWeb);
    }

    @Test
    void emptyRerankerResultFallsBackToCandidatePrefix() {
        Probe p = probe(2, 5, true, "empty");
        assertEquals(p.fused.subList(0, 5), p.reranker.input);
        assertEquals(p.fused.subList(0, 2), p.promotedWeb);
        assertEquals("empty", p.fallbackReason);
    }

    @Test
    void rerankerExceptionFallsBackToCandidatePrefix() {
        Probe p = probe(2, 5, true, "exception");
        assertEquals(p.fused.subList(0, 5), p.reranker.input);
        assertEquals(p.fused.subList(0, 2), p.promotedWeb);
        assertEquals("exception", p.fallbackReason);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} flip={1} plate={2}")
    @org.junit.jupiter.params.provider.CsvSource({
            "ap11_finance_special.v1,false,true", "ap11_finance_special.v1,false,false",
            "ap11_finance_special.v1,true,true", "ap11_finance_special.v1,true,false",
            "ap1_auth_web.v1,false,true", "ap1_auth_web.v1,false,false",
            "ap1_auth_web.v1,true,true", "ap1_auth_web.v1,true,false",
            "ap9_cost_saver.v1,false,true", "ap9_cost_saver.v1,false,false",
            "ap9_cost_saver.v1,true,true", "ap9_cost_saver.v1,true,false",
            "ap3_vec_dense.v1,false,true", "ap3_vec_dense.v1,false,false",
            "ap3_vec_dense.v1,true,true", "ap3_vec_dense.v1,true,false"})
    void shippedCrossEncoderFlagRespectsPlateAndWebCaps(String planId, boolean flip, boolean plateEnabled)
            throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        var authored = original.path("params").path("use_cross_encoder");
        assertTrue(authored.isBoolean());
        boolean selectedCe = flip ? !authored.booleanValue() : authored.booleanValue();
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put("use_cross_encoder", selectedCe);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params")).set("use_cross_encoder", authored);
        assertEquals(original, restored, "only the authored CE key changes; budgets and retrieval caps stay intact");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = org.mockito.Mockito.spy(new com.example.lms.plan.PlanHintApplier(flip
                ? resources : new org.springframework.core.io.DefaultResourceLoader()));
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        assertEquals(selectedCe, plan.useCrossEncoder());
        org.mockito.Mockito.clearInvocations(applier);
        Probe p = probe(null, null, plateEnabled, "reverse", applier, plan);
        boolean webAllowed = !Boolean.FALSE.equals(plan.allowWeb());
        int expectedCalls = webAllowed && plateEnabled && selectedCe ? 1 : 0;
        assertEquals(expectedCalls, p.reranker.calls);
        verify(applier).load(planId);
        if (webAllowed) {
            for (String key : List.of("use_cross_encoder", "useCrossEncoder", "cross_encoder.enabled", "crossEncoder.enabled")) {
                assertEquals(selectedCe, p.retrievalMetadata.get(key));
            }
            assertEquals(String.valueOf(plateEnabled && selectedCe), p.retrievalMetadata.get("enableCrossEncoder"));
            assertEquals("true", p.retrievalMetadata.get("allowWeb"));
            assertEquals(String.valueOf(plan.webTopK() == null ? 8 : plan.webTopK()), p.retrievalMetadata.get("webTopK"));
            assertEquals(plan.rerankTopK(), p.retrievalMetadata.get("rerank.topK"));
            if (plan.webBudgetMs() != null) assertEquals(plan.webBudgetMs(), p.retrievalMetadata.get("webBudgetMs"));
            var expected = new java.util.ArrayList<>(p.fused);
            if (expectedCalls == 1) java.util.Collections.reverse(expected);
            int keep = Math.min(plan.rerankTopK(), expected.size());
            if (Boolean.TRUE.equals(plan.officialSourcesOnly())) {
                assertTrue(p.promotedWeb.isEmpty(), "the unchanged official-source policy remains fail-closed");
                assertEquals(keep, p.officialFilterInput);
                assertEquals(keep, p.officialFilterDropped);
                assertEquals("policy_resolver_unavailable", p.officialFilterReason);
            } else {
                assertEquals(expected.subList(0, keep), p.promotedWeb);
            }
            if (expectedCalls == 0) assertEquals(selectedCe ? "skipped_by_plate" : "skipped_by_plan", p.skipReason);
        } else {
            assertTrue(p.retrievalMetadata.isEmpty(), "AP3 stops before Web hint projection and rerank");
            assertTrue(p.promotedWeb.isEmpty());
        }
        System.out.printf("TBL07_WORKFLOW_CE plan=%s flip=%s declared=%s plate=%s webAllowed=%s rerankerCalls=%d promoted=%d officialPolicy=%s officialDropped=%s%n",
                planId, flip, selectedCe, plateEnabled, webAllowed, p.reranker.calls, p.promotedWeb.size(), plan.officialSourcesOnly(), p.officialFilterDropped);
    }

    private static Probe probe(Integer keep, Integer cap, boolean crossEncoder, String resultMode) {
        return probe(keep, cap, crossEncoder, resultMode, null, null);
    }

    private static Probe probe(Integer keep, Integer cap, boolean crossEncoder, String resultMode,
            com.example.lms.plan.PlanHintApplier realApplier, com.example.lms.plan.PlanHints selectedPlan) {
        clearWorkflowState();
        MemoryHoldFixture fixture = memoryHoldFixture();
        ChatWorkflow workflow = fixture.workflow();
        var fused = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> dev.langchain4j.rag.content.Content.from(
                        dev.langchain4j.data.segment.TextSegment.from("synthetic evidence member " + i)))
                .toList();
        var hybrid = mock(com.example.lms.service.rag.HybridRetriever.class);
        var received = new java.util.ArrayList<Map<String, Object>>();
        when(hybrid.retrieveAll(anyList(), anyInt(), any(), any())).thenAnswer(call -> {
            Map<String, Object> metadata = call.getArgument(3);
            received.add(new java.util.LinkedHashMap<>(metadata));
            return fused;
        });
        var planner = mock(com.example.lms.service.routing.plan.RoutingPlanService.class);
        when(planner.plan(anyString(), nullable(String.class), anyInt()))
                .thenAnswer(call -> List.of(call.getArgument(0, String.class)));
        var plate = mock(com.example.lms.artplate.NineArtPlateGate.class);
        when(plate.decide(any())).thenReturn(new com.example.lms.artplate.ArtPlateSpec(
                "rc11-fixture", "GENERAL", 8, 8, false, false, 10_000, 10_000,
                List.of(), 0.0d, 0.0d, false, false, false, List.of(), crossEncoder,
                0, 0, 0.0d, 0.0d, 0.0d, 0.0d));
        var plan = selectedPlan != null ? selectedPlan : new com.example.lms.plan.PlanHints("rc11-fixture", null, null, List.of(),
                null, null, null, List.of(), null, null, null, null, null, null, null,
                null, "embedding-model", keep, cap, null, null, Map.of());
        var applier = realApplier != null ? realApplier : org.mockito.Mockito.spy(new com.example.lms.plan.PlanHintApplier(
                mock(org.springframework.core.io.ResourceLoader.class)));
        if (realApplier == null) org.mockito.Mockito.doReturn(plan).when(applier).load(anyString());
        var rag = mock(com.example.lms.service.rag.LangChainRAGService.class);
        when(rag.asContentRetriever(nullable(String.class))).thenReturn(query -> List.of());
        var reranker = new RecordingReranker(resultMode);
        ReflectionTestUtils.setField(workflow, "hybridRetriever", hybrid);
        ReflectionTestUtils.setField(workflow, "routingPlanService", planner);
        ReflectionTestUtils.setField(workflow, "nineArtPlateGate", plate);
        ReflectionTestUtils.setField(workflow, "planHintApplier", applier);
        ReflectionTestUtils.setField(workflow, "ragSvc", rag);
        ReflectionTestUtils.setField(workflow, "disambiguationService",
                mock(com.example.lms.service.disambiguation.QueryDisambiguationService.class));
        ReflectionTestUtils.setField(workflow, "rerankers", Map.of("embeddingCrossEncoderReranker", reranker));
        ReflectionTestUtils.setField(workflow, "keepNStd", 3);
        ReflectionTestUtils.setField(workflow, "keepNBrief", 3);
        ReflectionTestUtils.setField(workflow, "keepNDeep", 3);
        ReflectionTestUtils.setField(workflow, "keepNUltra", 3);
        ReflectionTestUtils.setField(workflow, "rerankTopN", 3);
        ReflectionTestUtils.setField(workflow, "latestTechAutoDisableVector", false);
        ReflectionTestUtils.setField(workflow, "rescueCount", new java.util.concurrent.atomic.AtomicLong());
        ReflectionTestUtils.setField(workflow, "emptyTopDocsCount", new java.util.concurrent.atomic.AtomicLong());
        var captured = new java.util.ArrayList<List<dev.langchain4j.rag.content.Content>>();
        when(fixture.attribution().promoteForPromptDetailed(
                anyString(), nullable(List.class), nullable(List.class), anyList(), any(), anyBoolean()))
                .thenAnswer(call -> {
                    List<dev.langchain4j.rag.content.Content> web = call.getArgument(1);
                    captured.add(web == null ? List.of() : List.copyOf(web));
                    return RagEvidenceAttributionService.PromotionResult.unavailable();
                });
        try {
            ChatRequestDto request = ChatRequestDto.builder()
                    .message("Compare the supplied synthetic evidence documents for this retrieval fixture.")
                    .model("release-gate-recording-fake").maxTokens(256).mode("FACT")
                    .memoryMode("FULL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true))
                    .build();
            if (selectedPlan != null) {
                var context = com.example.lms.service.guard.GuardContext.defaultContext();
                context.setPlanId(selectedPlan.planId());
                GuardContextHolder.set(context);
            }
            workflow.continueChat(request, ignored -> List.of());
            boolean webAllowed = selectedPlan == null || !Boolean.FALSE.equals(selectedPlan.allowWeb());
            if (webAllowed) verify(hybrid).retrieveAll(org.mockito.ArgumentMatchers.eq(List.of(request.getMessage())),
                    anyInt(), nullable(Object.class), any());
            else verify(hybrid, never()).retrieveAll(anyList(), anyInt(), any(), any());
            boolean ceAllowed = selectedPlan == null || !Boolean.FALSE.equals(selectedPlan.useCrossEncoder());
            assertEquals(webAllowed && crossEncoder && ceAllowed ? 1 : 0, reranker.calls);
            assertEquals(1, captured.size(), "must reach real prompt promotion after CE selection");
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
            List<dev.langchain4j.rag.content.Content> promoted = captured.get(0);
            assertTrue(promoted.stream().allMatch(c -> fused.stream().anyMatch(original -> c == original)),
                    "selected original Content identities must survive to prompt promotion");
            return new Probe(fused, reranker, promoted, TraceStore.get("rerank.fallback.reason"),
                    received.isEmpty() ? Map.of() : received.get(0), TraceStore.get("rerank"),
                    TraceStore.get("retrieval.integrity.stage.pre_compression.inputCount"),
                    TraceStore.get("retrieval.integrity.stage.pre_compression.filteredCount"),
                    TraceStore.get("retrieval.integrity.emptyReason"));
        } finally {
            clearWorkflowState();
        }
    }

    private record Probe(List<dev.langchain4j.rag.content.Content> fused, RecordingReranker reranker,
                         List<dev.langchain4j.rag.content.Content> promotedWeb, Object fallbackReason,
                         Map<String, Object> retrievalMetadata, Object skipReason, Object officialFilterInput,
                         Object officialFilterDropped, Object officialFilterReason) { }

    private static class RecordingReranker implements com.example.lms.service.rag.rerank.CrossEncoderReranker {
        final String mode;
        List<dev.langchain4j.rag.content.Content> input;
        int topN;
        int calls;

        RecordingReranker(String mode) { this.mode = mode; }

        @Override
        public List<dev.langchain4j.rag.content.Content> rerank(String query,
                List<dev.langchain4j.rag.content.Content> candidates, int count) {
            input = List.copyOf(candidates);
            topN = count;
            calls++;
            if (mode.equals("exception")) throw new IllegalStateException("synthetic reranker failure");
            if (mode.equals("empty")) return List.of();
            var reversed = new java.util.ArrayList<>(candidates);
            java.util.Collections.reverse(reversed);
            return List.copyOf(reversed.subList(0, Math.min(count, reversed.size())));
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
            FactVerifierService verifier,
            AttachmentService attachmentService,
            RagEvidenceAttributionService attribution,
            LearningWriteInterceptor learningWriteInterceptor,
            MemoryWriteInterceptor memoryWriteInterceptor) {
    }

}
