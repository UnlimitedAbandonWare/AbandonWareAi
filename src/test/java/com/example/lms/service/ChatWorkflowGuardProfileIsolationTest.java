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

class ChatWorkflowGuardProfileIsolationTest {
    private static final String A = "aurora lattice";
    private static final String B = "ember horizon";

    @ParameterizedTest
    @CsvSource({
            "SENSITIVE,NULL,PROFILE_MEMORY,false,true", "GAME,NULL,PROFILE_FREE,true,true",
            "GAME,FACT,STRICT,false,true", "SENSITIVE,CREATIVE,PROFILE_FREE,true,true",
            "SENSITIVE,NULL,PROFILE_MEMORY,false,false", "GAME,NULL,PROFILE_FREE,true,false",
            "GAME,FACT,STRICT,false,false", "SENSITIVE,CREATIVE,PROFILE_FREE,true,false"
    })
    void singleRequestRetainsDomainAndExplicitModeSelection(QueryDomain domain, String mode,
            GuardProfile expected, boolean allowed, boolean installContext) {
        LatchingGate gate = new LatchingGate(domain, QueryDomain.GENERAL, false);
        Fixture fixture = fixture(gate);
        invoke(fixture, A, mode.equals("NULL") ? null : mode, installContext);
        assertEquals(new Observation(expected, allowed, domain), gate.observations.get(A));
        assertEquals(1, gate.observations.size(), "normal workflow must reach the real gate");
    }

    @ParameterizedTest
    @CsvSource({"SENSITIVE,GAME,true", "GAME,SENSITIVE,true", "SENSITIVE,GAME,false", "GAME,SENSITIVE,false"})
    void overlappingNormalRequestsKeepTheirOwnGateProfile(QueryDomain first, QueryDomain second,
                                                         boolean installContext) throws Exception {
        LatchingGate gate = new LatchingGate(first, second, true);
        Fixture fixture = fixture(gate);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = executor.submit(() -> invoke(fixture, A, null, installContext));
            assertTrue(gate.firstAtGate.await(10, TimeUnit.SECONDS), "first workflow must reach gate");
            Future<?> b = executor.submit(() -> invoke(fixture, B, null, installContext));
            b.get(10, TimeUnit.SECONDS);
            gate.releaseFirst.countDown();
            a.get(10, TimeUnit.SECONDS);
            assertEquals(2, gate.observations.size());
            GuardProfile firstExpected = gate.profiles.profileFor(first);
            GuardProfile secondExpected = gate.profiles.profileFor(second);
            assertAll(
                    () -> assertEquals(new Observation(firstExpected, first == QueryDomain.GAME, first), gate.observations.get(A)),
                    () -> assertEquals(new Observation(secondExpected, second == QueryDomain.GAME, second), gate.observations.get(B)));
        } finally {
            gate.releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            clearWorkflowState();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requestSelectionDoesNotOverwriteTheConfiguredDefault(boolean installContext) {
        LatchingGate gate = new LatchingGate(QueryDomain.GAME, QueryDomain.GENERAL, false);
        Fixture fixture = fixture(gate);
        invoke(fixture, A, null, installContext);
        assertNull(GuardContextHolder.get(), "request fixture context must be cleared");
        assertEquals(GuardProfile.PROFILE_MEMORY, gate.profiles.currentProfile(),
                "a completed FREE request must not reconfigure subsequent default consumers");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exceptionExitPreservesCallerContextOrClearsOwnedContext(boolean installContext) {
        clearWorkflowState();
        GuardContext caller = installContext ? GuardContext.defaultContext() : null;
        if (caller != null) GuardContextHolder.set(caller);
        try {
            Fixture fixture = fixture(new LatchingGate(QueryDomain.SENSITIVE, QueryDomain.GAME, false));
            assertThrows(NullPointerException.class,
                    () -> fixture.workflow().continueChat(null, ignored -> List.of()));
            assertSame(caller, GuardContextHolder.get());
        } finally {
            clearWorkflowState();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void carriedPlanApplicationRetainsSupportedValuesAtRealRetrieval(boolean preApplied) {
        clearWorkflowState();
        Fixture fixture = fixture(new LatchingGate(QueryDomain.GAME, QueryDomain.GENERAL, false));
        var applier = (com.example.lms.plan.PlanHintApplier)
                ReflectionTestUtils.getField(fixture.workflow(), "planHintApplier");
        var plan = new com.example.lms.plan.PlanHints("rc04-fixture", null, null, List.of(),
                4, 6, 2, List.of(), 500L, 600L, 2, true, true, false,
                false, false, "embedding-model", 2, 4, null, null, Map.of());
        org.mockito.Mockito.doReturn(plan).when(applier).load(anyString());
        GuardContext context = GuardContext.defaultContext();
        context.setPlanId("rc04-fixture");
        GuardContextHolder.set(context);
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        try {
            // Models a carried controller context; no HTTP/controller chain is claimed.
            if (preApplied) applier.applyToGuardContext(plan, context);
            org.mockito.Mockito.clearInvocations(applier);
            var hybrid = (com.example.lms.service.rag.HybridRetriever)
                    ReflectionTestUtils.getField(fixture.workflow(), "hybridRetriever");
            var seenMeta = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
            var seenOverrides = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
            when(hybrid.retrieveAll(anyList(), anyInt(), any(), any())).thenAnswer(call -> {
                Map<String, Object> meta = call.getArgument(3);
                seenMeta.set(new java.util.HashMap<>(meta));
                seenOverrides.set(new java.util.HashMap<>(GuardContextHolder.get().getPlanOverrides()));
                return List.of(dev.langchain4j.rag.content.Content.from("marble canoe velvet"));
            });
            ChatRequestDto request = ChatRequestDto.builder().message(A)
                    .model("release-gate-recording-fake").maxTokens(256)
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            fixture.workflow().continueChat(request, ignored -> List.of());
            verify(applier).load("rc04-fixture");
            verify(applier).applyToGuardContext(plan, context);
            verify(applier).applyToHintsAndMeta(org.mockito.ArgumentMatchers.same(plan), any(), any());
            verify(hybrid).retrieveAll(anyList(), anyInt(), any(), any());
            assertNotNull(seenMeta.get(), "the real workflow must reach retrieval with its projected metadata");
            assertEquals(false, seenMeta.get().get("onnx.enabled"));
            assertEquals(2, seenMeta.get().get("rerank.topK"));
            assertEquals(4, seenMeta.get().get("rerank.ce.topK"));
            assertEquals(false, seenOverrides.get().get("onnx.enabled"));
            assertEquals(2, seenOverrides.get().get("rerank.topK"));
            assertEquals(2, context.getMinCitations());
            assertSame(context, GuardContextHolder.get());
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executionPlanPrecedesCurrentRetrievalIntegrity(boolean preexistingLowRecall) {
        clearWorkflowState();
        Fixture fixture = fixture(new LatchingGate(QueryDomain.GAME, QueryDomain.GENERAL, false));
        GuardContext context = GuardContext.defaultContext();
        context.setHighRiskQuery(preexistingLowRecall);
        GuardContextHolder.set(context);
        if (preexistingLowRecall) TraceStore.put("outCount", 0);
        String expectedMode = preexistingLowRecall ? "HYPERNOVA" : "NORMAL";
        var checkpoints = new java.util.ArrayList<String>();
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        var planner = (com.example.lms.service.routing.plan.RoutingPlanService)
                ReflectionTestUtils.getField(fixture.workflow(), "routingPlanService");
        var hybrid = (com.example.lms.service.rag.HybridRetriever)
                ReflectionTestUtils.getField(fixture.workflow(), "hybridRetriever");
        var profiles = mock(com.example.lms.service.rag.auth.DomainProfileLoader.class);
        when(profiles.isAllowedByProfile(anyString(), anyString())).thenReturn(false);
        ReflectionTestUtils.setField(fixture.workflow(), "promptDomainProfiles", profiles);
        when(planner.plan(anyString(), nullable(String.class), anyInt())).thenAnswer(call -> {
            checkpoints.add("planner");
            assertNull(TraceStore.get("outCount"), "workflow entry must clear preexisting request traces");
            assertEquals(expectedMode, context.getPlanOverride("executionPlan.primaryMode"));
            assertEquals(expectedMode, TraceStore.get("routing.executionPlan.applied.primaryMode"));
            assertNull(TraceStore.get("retrieval.integrity.stage.pre_compression.inputCount"));
            assertEquals(2, call.getArgument(2, Integer.class));
            return List.of(call.getArgument(0, String.class));
        });
        when(hybrid.retrieveAll(anyList(), anyInt(), any(), any())).thenAnswer(call -> {
            checkpoints.add("retrieval");
            assertNull(TraceStore.get("outCount"), "the fake planner produces no new low-recall signal");
            assertEquals(List.of("planner", "retrieval"), checkpoints);
            assertEquals(expectedMode, context.getPlanOverride("executionPlan.primaryMode"));
            assertEquals(expectedMode, TraceStore.get("routing.executionPlan.applied.primaryMode"));
            assertNull(TraceStore.get("retrieval.integrity.stage.pre_compression.inputCount"));
            return List.of(dev.langchain4j.rag.content.Content.from(
                    dev.langchain4j.data.segment.TextSegment.from("marble canoe velvet",
                            dev.langchain4j.data.document.Metadata.from(Map.of(
                                    "url", "https://untrusted.example.test/plan-probe")))));
        });
        try {
            ChatRequestDto request = ChatRequestDto.builder().message(A)
                    .model("release-gate-recording-fake").maxTokens(256)
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .officialSourcesOnly(true).useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            fixture.workflow().continueChat(request, ignored -> List.of());
            checkpoints.add("workflow-returned");
            assertEquals(List.of("planner", "retrieval", "workflow-returned"), checkpoints);
            verify(planner).plan(anyString(), nullable(String.class), anyInt());
            verify(hybrid).retrieveAll(anyList(), anyInt(), any(), any());
            verify(profiles).isAllowedByProfile("https://untrusted.example.test/plan-probe", "official");
            assertEquals(1L, TraceStore.getLong("retrieval.integrity.stage.pre_compression.inputCount"));
            assertEquals(1L, TraceStore.getLong("retrieval.integrity.stage.pre_compression.policyDeniedCount"));
            assertEquals(0L, TraceStore.getLong("retrieval.integrity.stage.pre_compression.finalUsedCount"));
            assertEquals("hard_policy_filtered_empty", TraceStore.get("retrieval.integrity.emptyReason"));
            assertEquals(expectedMode, TraceStore.get("routing.executionPlan.applied.primaryMode"));
            assertEquals(expectedMode, context.getPlanOverride("executionPlan.primaryMode"));
            assertSame(context, GuardContextHolder.get());
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> shippedQueryBurstPlannerControls() throws Exception {
        var applier = new com.example.lms.plan.PlanHintApplier(new org.springframework.core.io.DefaultResourceLoader());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var seen = new java.util.HashSet<String>();
        var selected = new java.util.TreeSet<String>();
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        try (var paths = Files.list(Path.of("main/resources/plans"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String file = path.getFileName().toString();
                var hints = applier.load(file.substring(0, file.length() - 5));
                if (!seen.add(hints.planId()) || hints.queryBurstCount() == null) continue;
                var tree = mapper.readTree(Files.readString(Path.of("main/resources/plans", hints.planId() + ".yaml"), StandardCharsets.UTF_8));
                assertEquals(hints.queryBurstCount().intValue(), tree.path("plan").path("overrides").path("knobs").path("expand.queryBurst.count").intValue());
                selected.add(hints.planId());
                for (String control : List.of("authored", "one", "in_range", "ceiling", "over_ceiling", "zero",
                        "removed", "caller_seven", "empty_return", "null_return", "three_return", "light", "tuned"))
                    cases.add(org.junit.jupiter.params.provider.Arguments.of(hints.planId(), hints.queryBurstCount(), control));
            }
        }
        assertEquals(java.util.Set.of("brave.v1", "zero_break.v1"), selected); assertEquals(26, cases.size());
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} authored={1} control={2}")
    @org.junit.jupiter.params.provider.MethodSource("shippedQueryBurstPlannerControls")
    void shippedQueryBurstControlsRequestedPlannerMaximumSeparatelyFromReturnedBranches(
            String planId, int authored, String control) throws Exception {
        clearWorkflowState();
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"), StandardCharsets.UTF_8));
            var modified = original.deepCopy();
            var knobs = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("plan").path("overrides").path("knobs");
            Integer selected = switch (control) {
                case "one" -> 1; case "in_range" -> authored - 1; case "ceiling" -> 32; case "over_ceiling" -> 33;
                case "zero" -> 0; case "removed", "caller_seven" -> null; default -> authored;
            };
            if (selected == null) knobs.remove("expand.queryBurst.count"); else knobs.put("expand.queryBurst.count", selected);
            var restored = modified.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("plan").path("overrides").path("knobs")).put("expand.queryBurst.count", authored);
            assertEquals(original, restored, "only the declared count changes; all other plan fields remain intact");
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
            var applier = org.mockito.Mockito.spy(new com.example.lms.plan.PlanHintApplier("authored".equals(control)
                    ? new org.springframework.core.io.DefaultResourceLoader() : resources));
            assertEquals(selected, applier.load(planId).queryBurstCount());
            org.mockito.Mockito.clearInvocations(applier);
            Fixture fixture = fixture(new LatchingGate(QueryDomain.GAME, QueryDomain.GENERAL, false));
            ReflectionTestUtils.setField(fixture.workflow(), "planHintApplier", applier);
            var context = GuardContext.defaultContext(); context.setPlanId(planId);
            if ("caller_seven".equals(control)) context.putPlanOverride("expand.queryBurst.count", 7);
            GuardContextHolder.set(context);
            var policy = mock(com.example.lms.search.policy.SearchPolicyEngine.class);
            var policyDecision = com.example.lms.search.policy.SearchPolicyDecision.off("synthetic");
            var beforeTuning = new java.util.concurrent.atomic.AtomicInteger(-1);
            if ("tuned".equals(control)) {
                when(policy.decide(anyString(), any())).thenReturn(policyDecision);
                when(policy.tunePlannerMaxQueries(anyInt(), any())).thenAnswer(call -> {
                    beforeTuning.set(call.getArgument(0, Integer.class)); return 4;
                });
                when(policy.apply(anyList(), anyString(), any())).thenAnswer(call -> call.getArgument(0));
                when(policy.tuneTopK(anyInt(), any())).thenAnswer(call -> call.getArgument(0));
                when(policy.tuneVecTopK(anyInt(), any())).thenAnswer(call -> call.getArgument(0));
            }
            ReflectionTestUtils.setField(fixture.workflow(), "searchPolicyEngine", policy);
            var planner = (com.example.lms.service.routing.plan.RoutingPlanService) ReflectionTestUtils.getField(fixture.workflow(), "routingPlanService");
            var hybrid = (com.example.lms.service.rag.HybridRetriever) ReflectionTestUtils.getField(fixture.workflow(), "hybridRetriever");
            var requested = new java.util.concurrent.atomic.AtomicInteger(-1);
            var plannerCalls = new java.util.concurrent.atomic.AtomicInteger();
            var returned = new java.util.concurrent.atomic.AtomicReference<List<String>>();
            var plannerQuery = new java.util.concurrent.atomic.AtomicReference<String>();
            var downstream = new java.util.concurrent.atomic.AtomicReference<List<String>>();
            when(planner.plan(anyString(), nullable(String.class), anyInt())).thenAnswer(call -> {
                plannerCalls.incrementAndGet(); requested.set(call.getArgument(2, Integer.class));
                plannerQuery.set(call.getArgument(0, String.class));
                List<String> branches = "null_return".equals(control) ? null : "empty_return".equals(control) ? List.of()
                        : "three_return".equals(control) ? List.of("branch alpha", "branch beta", "branch gamma") : List.of("branch alpha");
                returned.set(branches); return branches;
            });
            var fused = List.of(dev.langchain4j.rag.content.Content.from("marble canoe velvet"));
            when(hybrid.retrieveAll(anyList(), anyInt(), any(), any())).thenAnswer(call -> {
                downstream.set(List.copyOf(call.getArgument(0))); return fused;
            });
            var web = mock(com.example.lms.service.rag.WebSearchRetriever.class);
            when(web.retrieve(any())).thenReturn(fused);
            ReflectionTestUtils.setField(fixture.workflow(), "webSearchRetriever", web);
            ChatRequestDto request = ChatRequestDto.builder().message(A).model("release-gate-recording-fake").maxTokens(256)
                    .memoryMode("EPHEMERAL").searchMode("light".equals(control) ? SearchMode.FORCE_LIGHT : SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            fixture.workflow().continueChat(request, ignored -> List.of());
            verify(applier).load(planId);
            assertEquals(selected == null ? ("caller_seven".equals(control) ? 7 : 2) : selected.intValue(),
                    context.planInt("expand.queryBurst.count", 2));
            boolean light = "light".equals(control);
            int expectedRequested = light ? -1 : "tuned".equals(control) ? 4
                    : selected == null ? ("caller_seven".equals(control) ? 7 : 2) : Math.max(2, Math.min(selected, 32));
            assertEquals(light ? 0 : 1, plannerCalls.get()); assertEquals(expectedRequested, requested.get());
            if ("tuned".equals(control)) assertEquals(authored, beforeTuning.get());
            else verify(policy, never()).tunePlannerMaxQueries(anyInt(), any());
            if (light) assertEquals(Boolean.TRUE, TraceStore.get("search.mode.lightPlannerFanout.skipped"));
            else {
                assertNotNull(downstream.get(), "the planned list must reach the controlled Hybrid retriever");
                List<String> expectedBranches = returned.get() == null || returned.get().isEmpty() ? List.of(plannerQuery.get()) : returned.get();
                assertEquals(expectedBranches, downstream.get(), "requested maximum and actual returned branch count are separate");
                assertFalse(Boolean.TRUE.equals(TraceStore.get("retrieval.preLlm.queryFanout.capped")), "primary fixture has no finite request-budget cap");
            }
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
            System.out.printf("TBL07_PLANNER plan=%s control=%s selected=%s calls=%d requested=%d returned=%d downstream=%d tunedFrom=%d%n",
                    planId, control, selected, plannerCalls.get(), requested.get(), returned.get() == null ? -1 : returned.get().size(),
                    downstream.get() == null ? -1 : downstream.get().size(), beforeTuning.get());
        } finally {
            clearWorkflowState();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static void invoke(Fixture fixture, String query, String mode, boolean installContext) {
        clearWorkflowState();
        if (installContext) GuardContextHolder.set(GuardContext.defaultContext());
        try {
            ChatRequestDto request = ChatRequestDto.builder().message(query)
                    .model("release-gate-recording-fake").maxTokens(256).mode(mode)
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            fixture.workflow().continueChat(request, ignored -> List.of());
            verifyNoInteractions(fixture.learningWriteInterceptor(), fixture.memoryWriteInterceptor());
        } finally {
            clearWorkflowState();
        }
    }

    private record Observation(GuardProfile profile, boolean gateAllowed, QueryDomain domain) { }

    private static class LatchingGate extends EvidenceGate {
        final GuardProfileProps profiles = new GuardProfileProps();
        final Map<String, QueryDomain> domains;
        final ConcurrentMap<String, Observation> observations = new ConcurrentHashMap<>();
        final CountDownLatch firstAtGate = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final boolean pauseFirst;

        LatchingGate(QueryDomain first, QueryDomain second, boolean pauseFirst) {
            super(0.05, 0.02, 0.6, 0.8, false);
            this.domains = Map.of(A, first, B, second);
            this.pauseFirst = pauseFirst;
            profiles.setProfile("PROFILE_MEMORY");
            ReflectionTestUtils.setField(this, "guardProfileProps", profiles);
        }

        @Override
        public boolean hasSufficientCoverage(String question, List<String> rag, List<String> memory,
                List<String> kb, boolean followUp, QueryDomain domain) {
            if (pauseFirst && question.equals(A)) {
                firstAtGate.countDown();
                try {
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture latch timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture interrupted", e);
                }
            }
            GuardProfile observed = profiles.currentProfile();
            boolean allowed = super.hasSufficientCoverage(question, rag, memory, kb, followUp, domain);
            observations.put(question, new Observation(observed, allowed, domain));
            return allowed;
        }
    }

    private static Fixture fixture(LatchingGate gate) {
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

        RagEvidenceAttributionService attribution = new RagEvidenceAttributionService(gate, new CitationGate(), null);
        QueryDomainClassifier classifier = mock(QueryDomainClassifier.class);
        when(classifier.classify(anyString())).thenAnswer(call ->
                gate.domains.get(call.getArgument(0, String.class)));

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
        ReflectionTestUtils.setField(workflow, "guardProfileProps", gate.profiles);
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

        configureRetrieval(workflow);
        return new Fixture(workflow, learningWriter, memoryWriter);
    }

    private static void configureRetrieval(ChatWorkflow workflow) {
        var fused = List.of(dev.langchain4j.rag.content.Content.from(
                dev.langchain4j.data.segment.TextSegment.from("marble canoe velvet",
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
                null, "embedding-model", 1, 1, null, null, Map.of());
        var applier = org.mockito.Mockito.spy(new com.example.lms.plan.PlanHintApplier(
                mock(org.springframework.core.io.ResourceLoader.class)));
        org.mockito.Mockito.doReturn(plan).when(applier).load(anyString());
        var rag = mock(com.example.lms.service.rag.LangChainRAGService.class);
        when(rag.asContentRetriever(nullable(String.class))).thenReturn(query -> List.of());
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
