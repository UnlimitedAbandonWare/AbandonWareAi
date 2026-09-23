package com.example.lms.service.rag.orchestrator;

import com.example.lms.plan.PlanHintApplier;
import com.example.lms.plan.PlanHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedRagOrchestratorPlanHintsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PlanHintApplier.class, () -> new PlanHintApplier(new DefaultResourceLoader()))
            .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> contents("web", 30))
            .withBean("vectorRetriever", ContentRetriever.class, () -> query -> contents("vector", 30))
            .withBean(LangChainRAGService.class, () -> leafReturning(query -> contents("vector", 30)))
            .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> contents("kg", 10))
            .withBean(UnifiedRagOrchestrator.class);

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void bravePlanAppliesWebTopKAndSelfAskWithoutRawQueryDebug() {
        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            UnifiedRagOrchestrator.QueryRequest request = baseRequest("brave.v1");
            request.query = "raw brave query should not leak";

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            assertEquals("PlanHintApplier", response.debug.get("plan.source"));
            assertEquals("brave.v1", response.debug.get("plan.id"));
            assertEquals(true, response.debug.get("plan.applied"));
            assertEquals("applied:brave.v1", response.debug.get("planHints"));
            assertEquals("not_used", response.debug.get("planDsl.status"));
            assertEquals(false, response.debug.get("planDsl.loaded"));
            assertFalse(String.valueOf(response.debug.get("planDsl")).startsWith("applied:"));
            String unwiredKeys = String.valueOf(response.debug.get("planDsl.unwiredKeys"));
            assertTrue(unwiredKeys.contains("llm"));
            assertTrue(unwiredKeys.contains("guard"));
            assertTrue(unwiredKeys.contains("plan.when"));
            assertTrue(unwiredKeys.contains("plan.pipeline"));
            assertEquals(response.debug.get("planDsl.unwiredKeys"), TraceStore.get("planDsl.unwiredKeys"));
            assertEquals(18, response.debug.get("plan.webTopK"));
            assertEquals("success:18", response.debug.get("stage.web"));
            assertEquals(true, response.debug.get("plan.selfAsk.enabled"));
            assertEquals("missing_selfAskPlanner", response.debug.get("selfAsk"));
            assertFalse(String.valueOf(response.debug).contains("raw brave query should not leak"));
        });
    }

    @Test
    void ap9CostSaverDisablesCrossEncoderThroughPlanHints() {
        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            UnifiedRagOrchestrator.QueryRequest request = baseRequest("ap9_cost_saver.v1");

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            assertEquals("ap9_cost_saver.v1", response.debug.get("plan.id"));
            assertEquals(false, response.debug.get("plan.onnx.enabled"));
            assertFalse(request.enableOnnx);
        });
    }

    @Test
    void zero100PlanAppliesPerSourceKValues() {
        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            UnifiedRagOrchestrator.QueryRequest request = baseRequest("zero100.v1");
            request.useKg = true;

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            assertEquals("zero100.v1", response.debug.get("plan.id"));
            assertEquals(8, response.debug.get("plan.webTopK"));
            assertEquals(4, response.debug.get("plan.vectorTopK"));
            assertEquals(2, response.debug.get("plan.kgTopK"));
            assertEquals("success:8", response.debug.get("stage.web"));
            assertEquals("success:4", response.debug.get("stage.vector"));
            assertEquals("success:2", response.debug.get("stage.kg"));
            assertTrue(String.valueOf(TraceStore.getAll()).contains("zero100.v1"));
        });
    }

    @Test
    void nestedTopkPlanReachesActiveRetrieversWithAllowlistedDecisionEvidence() {
        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            UnifiedRagOrchestrator.QueryRequest request = baseRequest("document_evidence.v1");
            request.useKg = true;

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            assertEquals("success:8", response.debug.get("stage.web"));
            assertEquals("success:12", response.debug.get("stage.vector"));
            assertEquals("success:3", response.debug.get("stage.kg"));
            assertTrue(String.valueOf(TraceStore.get("plan.fields.applied")).contains("topk.web"));
            assertTrue(String.valueOf(TraceStore.get("plan.fields.applied")).contains("topk.vector"));
            assertTrue(String.valueOf(TraceStore.get("plan.fields.applied")).contains("topk.kg"));
        });
    }

    @Test
    void traceStorePlanIdRedactsSensitiveRequestLabel() {
        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            String secret = "sk-" + "planIdSecret123456789012345";
            UnifiedRagOrchestrator.QueryRequest request = baseRequest("plan=" + secret);

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

            Object tracePlanId = TraceStore.get("plan.id");
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(String.valueOf(tracePlanId).contains(secret));
            assertFalse(trace.contains(secret));
            assertFalse(String.valueOf(response.planApplied).contains(secret));
            assertFalse(String.valueOf(response.debug).contains(secret));
        });
    }

    @Test
    void planHintApplyFailureUsesStableReasonWithoutExceptionClass() {
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> new PlanHintApplier(new DefaultResourceLoader()) {
                    @Override
                    public PlanHints load(String planId) {
                        throw new IllegalArgumentException("private plan token should not leak");
                    }
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
                    UnifiedRagOrchestrator.QueryRequest request = baseRequest("failing_plan.v1");
                    request.useWeb = false;
                    request.useVector = false;
                    request.useKg = false;

                    UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

                    assertEquals("plan_apply_failed", response.debug.get("plan.disabledReason"));
                    assertEquals("plan_apply_failed", TraceStore.get("plan.apply.error"));
                    assertFalse(String.valueOf(response.debug).contains("IllegalArgumentException"));
                    assertFalse(String.valueOf(TraceStore.getAll()).contains("IllegalArgumentException"));
                    assertFalse(String.valueOf(response.debug).contains("private plan token should not leak"));
                    assertFalse(String.valueOf(TraceStore.getAll()).contains("private plan token should not leak"));
                });
    }

    @Test
    void numericTraceParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(source, "private static double toDouble");
        assertParserCatchNarrowed(source, "private static int traceInt");
        assertParserCatchNarrowed(source, "private static int relationThumbnailInt");
    }

    static Stream<Arguments> shippedApPlanCaps() {
        return Stream.of(
                new Object[]{"ap1_auth_web.v1", true, false},
                new Object[]{"ap3_vec_dense.v1", false, true},
                new Object[]{"ap9_cost_saver.v1", true, true},
                new Object[]{"ap11_finance_special.v1", true, true})
                .flatMap(plan -> Stream.of(false, true).flatMap(requestWeb ->
                        Stream.of(false, true).map(requestRag -> Arguments.of(
                                plan[0], plan[1], plan[2], requestWeb, requestRag))));
    }

    @ParameterizedTest(name = "{0} requestWeb={3} requestRag={4}")
    @MethodSource("shippedApPlanCaps")
    void shippedApPlanCapsReachActualRetrieverInvocations(String planId,
            boolean planWeb, boolean planRag, boolean requestWeb, boolean requestRag) {
        AtomicInteger webCalls = new AtomicInteger();
        AtomicInteger vectorCalls = new AtomicInteger();
        AtomicInteger kgCalls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> new PlanHintApplier(new DefaultResourceLoader()))
                .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> {
                    webCalls.incrementAndGet();
                    return contents("web", 30);
                })
                .withBean("vectorRetriever", ContentRetriever.class, () -> query -> {
                    vectorCalls.incrementAndGet();
                    return contents("vector", 30);
                })
                .withBean(LangChainRAGService.class, () -> leafReturning(query -> {
                    vectorCalls.incrementAndGet();
                    return contents("vector", 30);
                }))
                .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> {
                    kgCalls.incrementAndGet();
                    return contents("kg", 10);
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest(planId);
                    request.useWeb = requestWeb;
                    request.useVector = requestRag;
                    request.useKg = requestRag;
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertEquals(planId, response.debug.get("plan.id"));
                    boolean expectedWeb = requestWeb && planWeb;
                    boolean expectedRag = requestRag && planRag;
                    assertEquals(expectedWeb, request.useWeb);
                    assertEquals(expectedRag, request.useVector);
                    assertEquals(expectedRag, request.useKg);
                    assertEquals(expectedWeb, webCalls.get() > 0, "actual web invocation");
                    assertEquals(expectedRag, vectorCalls.get() > 0, "actual vector invocation");
                    assertEquals(expectedRag, kgCalls.get() > 0, "actual KG invocation");
                    if (!expectedWeb) assertEquals(0, webCalls.get());
                    if (!expectedRag) {
                        assertEquals(0, vectorCalls.get());
                        assertEquals(0, kgCalls.get());
                    }
                });
    }

    @ParameterizedTest(name = "allowRag={0} explicitKgTopK={1}")
    @org.junit.jupiter.params.provider.CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void planRagDenyCapSurvivesPositiveKgAllocation(boolean allowRag, boolean explicitKgTopK) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/safe.v1.yaml")));
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.get("params")).put("allowRag", allowRag);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.get("params")).remove("allowRag");
        assertTrue(original.equals(restored), "fixture changes only params.allowRag");
        assertTrue(original.path("retrieval").path("k").path("kg").asInt() > 0,
                "shipped plan must retain its positive KG allocation");
        byte[] planBytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override
            public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/safe.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(planBytes) {
                        @Override public String getFilename() { return "safe.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        AtomicInteger vectorCalls = new AtomicInteger();
        AtomicInteger kgCalls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> new PlanHintApplier(resources))
                .withBean("vectorRetriever", ContentRetriever.class, () -> query -> {
                    vectorCalls.incrementAndGet();
                    return contents("vector", 30);
                })
                .withBean(LangChainRAGService.class, () -> leafReturning(query -> {
                    vectorCalls.incrementAndGet();
                    return contents("vector", 30);
                }))
                .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> {
                    kgCalls.incrementAndGet();
                    return contents("kg", 10);
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest("safe.v1");
                    request.useWeb = false;
                    request.useVector = true;
                    request.useKg = true;
                    if (explicitKgTopK) request.kgTopK = 2;
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    System.out.printf("TBL07_CAP allowRag=%s explicitKgTopK=%s kgCalls=%d vectorCalls=%d requestUseKg=%s requestUseVector=%s%n",
                            allowRag, explicitKgTopK, kgCalls.get(), vectorCalls.get(), request.useKg, request.useVector);
                    assertEquals("safe.v1", response.debug.get("plan.id"));
                    assertEquals(allowRag, kgCalls.get() > 0, "actual KG invocation must obey the RAG cap");
                    assertEquals(allowRag, vectorCalls.get() > 0, "actual vector invocation must obey the RAG cap");
                    assertEquals(allowRag, request.useKg);
                    assertEquals(allowRag, request.useVector);
                });
    }

    @ParameterizedTest(name = "AP3 selectedCe={0} initialOnnx={1}")
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void shippedVectorOnlyPlanCrossEncoderFlagControlsActualOnnxInvocation(
            boolean selectedCe, boolean initialOnnx) throws Exception {
        String planId = "ap3_vec_dense.v1";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        var authored = original.path("params").path("use_cross_encoder");
        assertTrue(authored.isBoolean());
        assertFalse(authored.booleanValue());
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put("use_cross_encoder", selectedCe);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params")).set("use_cross_encoder", authored);
        assertEquals(original, restored, "only CE changes; the vector-only plan and authored limits remain intact");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        AtomicInteger webCalls = new AtomicInteger();
        AtomicInteger vectorCalls = new AtomicInteger();
        AtomicInteger onnxCalls = new AtomicInteger();
        com.example.lms.service.rag.rerank.CrossEncoderReranker onnx = (query, candidates, topN) -> {
            onnxCalls.incrementAndGet();
            assertFalse(candidates.isEmpty());
            var reversed = new java.util.ArrayList<>(candidates);
            java.util.Collections.reverse(reversed);
            return List.copyOf(reversed.subList(0, Math.min(topN, reversed.size())));
        };
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> new PlanHintApplier(selectedCe ? resources : new DefaultResourceLoader()))
                .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> {
                    webCalls.incrementAndGet();
                    return contents("web", 30);
                })
                .withBean("vectorRetriever", ContentRetriever.class, () -> query -> {
                    vectorCalls.incrementAndGet();
                    return contents("vector", 30);
                })
                .withBean(LangChainRAGService.class, () -> leafReturning(query -> {
                    vectorCalls.incrementAndGet();
                    return contents("vector", 30);
                }))
                .withBean("onnxCrossEncoderReranker", com.example.lms.service.rag.rerank.CrossEncoderReranker.class, () -> onnx)
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest(planId);
                    request.enableOnnx = initialOnnx;
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertEquals(planId, response.debug.get("plan.id"));
                    assertFalse(request.useWeb);
                    assertTrue(request.useVector);
                    assertEquals(0, webCalls.get());
                    assertEquals(1, vectorCalls.get());
                    assertEquals("success:15", response.debug.get("stage.vector"));
                    assertEquals(selectedCe, request.enableOnnx);
                    assertEquals(selectedCe ? 1 : 0, onnxCalls.get());
                    assertEquals(selectedCe, TraceStore.get("rerank.onnx.requested"));
                    assertEquals(selectedCe, TraceStore.get("rerank.onnx.orchestrator.executed"));
                    assertFalse(response.results.isEmpty());
                    assertTrue(response.results.stream().allMatch(doc -> "VECTOR".equalsIgnoreCase(doc.source)));
                    // Fusion keeps a candidate window wider than topK; the
                    // per-source soft cap no longer starves a single-source
                    // pool — eligible candidates top up to the final topK.
                    assertEquals(8, response.results.size());
                    System.out.printf("TBL07_AP3_CE selected=%s initial=%s webCalls=%d vectorCalls=%d onnxCalls=%d results=%d%n",
                            selectedCe, initialOnnx, webCalls.get(), vectorCalls.get(), onnxCalls.get(), response.results.size());
                });
    }

    static Stream<Arguments> shippedVectorTopKCases() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var applier = new PlanHintApplier(new DefaultResourceLoader());
        var seen = new java.util.HashSet<String>();
        var selected = new java.util.TreeSet<String>();
        var aliases = new java.util.TreeSet<String>();
        var cases = new java.util.ArrayList<Arguments>();
        try (var paths = Files.list(Path.of("main/resources/plans"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String requested = path.getFileName().toString().replaceFirst("\\.yaml$", "");
                var plan = applier.load(requested);
                if (!seen.add(plan.planId()) || plan.vecTopK() == null || plan.vecTopK() <= 0) continue;
                String planId = plan.planId();
                var tree = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                        StandardCharsets.UTF_8));
                var matches = new java.util.ArrayList<List<String>>();
                for (var segments : List.of(List.of("params", "vecTopK"), List.of("retrieval", "topk", "vector"),
                        List.of("retrieval", "k", "vector"))) {
                    var value = tree;
                    for (String segment : segments) value = value.path(segment);
                    if (value.isIntegralNumber()) matches.add(segments);
                }
                assertEquals(1, matches.size(), "each selected vector plan has one authored alias in this family");
                String key = String.join(".", matches.get(0));
                selected.add(planId); aliases.add(key);
                for (String variant : List.of("authored", "one", "removed", "caller_two")) {
                    for (boolean tryWeb : List.of(false, true)) {
                        cases.add(Arguments.of(planId, key, plan.vecTopK(), variant, tryWeb));
                    }
                }
            }
        }
        assertEquals(java.util.Set.of("ap3_vec_dense.v1", "document_evidence.v1", "kg_first.v1", "safe.v1", "zero100.v1"), selected);
        assertEquals(3, aliases.size());
        assertEquals(40, cases.size());
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {3} tryWeb={4}")
    @MethodSource("shippedVectorTopKCases")
    void shippedVectorTopKControlsActualStageMembership(String planId, String key, int authoredCap,
            String variant, boolean tryWeb) throws Exception {
        TraceStore.clear();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        var segments = key.split("\\.");
        var modified = original.deepCopy();
        var parent = modified;
        for (int i = 0; i < segments.length - 1; i++) parent = parent.path(segments[i]);
        String leaf = segments[segments.length - 1];
        var authoredValue = parent.path(leaf).deepCopy();
        assertEquals(authoredCap, authoredValue.intValue());
        if ("one".equals(variant)) ((com.fasterxml.jackson.databind.node.ObjectNode) parent).put(leaf, 1);
        if ("removed".equals(variant)) ((com.fasterxml.jackson.databind.node.ObjectNode) parent).remove(leaf);
        var restored = modified.deepCopy();
        var restoredParent = restored;
        for (int i = 0; i < segments.length - 1; i++) restoredParent = restoredParent.path(segments[i]);
        ((com.fasterxml.jackson.databind.node.ObjectNode) restoredParent).set(leaf, authoredValue);
        assertEquals(original, restored, "only the selected authored vector-cap key may change");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        boolean changed = "one".equals(variant) || "removed".equals(variant);
        var applier = new PlanHintApplier(changed ? resources : new DefaultResourceLoader());
        var selectedPlan = applier.load(planId);
        Integer projected = "removed".equals(variant) ? null : "one".equals(variant) ? 1 : authoredCap;
        assertEquals(projected, selectedPlan.vecTopK());
        var offered = IntStream.range(0, 30).mapToObj(i -> Content.from(
                dev.langchain4j.data.segment.TextSegment.from("vector fixture member " + i,
                        dev.langchain4j.data.document.Metadata.from(java.util.Map.of("fixtureMember", "member-" + i)))))
                .toList();
        AtomicInteger webCalls = new AtomicInteger();
        AtomicInteger vectorCalls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier)
                .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> {
                    webCalls.incrementAndGet();
                    return List.of();
                })
                .withBean("vectorRetriever", ContentRetriever.class, () -> query -> {
                    vectorCalls.incrementAndGet();
                    return offered;
                })
                .withBean(LangChainRAGService.class, () -> leafReturning(query -> {
                    vectorCalls.incrementAndGet();
                    return offered;
                }))
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest(planId);
                    request.useWeb = tryWeb;
                    request.useVector = true;
                    request.useKg = false;
                    request.kgTopK = 0; // Explicitly isolate this stage from implicit positive plan KG allocation.
                    request.enableOnnx = false;
                    if ("caller_two".equals(variant)) request.vectorTopK = 2;
                    var trace = context.getBean(UnifiedRagOrchestrator.class).queryWithTrace(request);
                    boolean webAttempted = tryWeb && !Boolean.FALSE.equals(selectedPlan.allowWeb());
                    Integer expectedRequestCap = "caller_two".equals(variant) ? Integer.valueOf(2) : projected;
                    int expected = expectedRequestCap == null ? (webAttempted ? 16 : 8) : expectedRequestCap;
                    assertEquals(planId, trace.response.debug.get("plan.id"));
                    assertEquals(expectedRequestCap, request.vectorTopK);
                    assertEquals(webAttempted, request.useWeb);
                    assertFalse(request.useKg);
                    assertEquals(0, request.kgTopK);
                    assertEquals(webAttempted ? 1 : 0, webCalls.get());
                    assertEquals(1, vectorCalls.get());
                    assertEquals(expected, trace.vector.size());
                    assertEquals("success:" + expected, trace.response.debug.get("stage.vector"));
                    assertEquals(IntStream.range(0, expected).mapToObj(i -> "member-" + i).toList(),
                            trace.vector.stream().map(doc -> doc.meta.get("fixtureMember")).toList(),
                            "the actual vector stage retains exactly the selected prefix, before later fusion or policy filters");
                    String expectedSource = webAttempted ? "VECTOR-FALLBACK" : "VECTOR";
                    assertTrue(trace.vector.stream().allMatch(doc -> expectedSource.equals(doc.source)));
                    if (webAttempted) assertEquals(expected, TraceStore.get("retrieval.vectorFallback.effectiveTopK"));
                    System.out.printf("TBL07_VECTOR_TOPK plan=%s key=%s variant=%s tryWeb=%s webCalls=%d vectorCalls=%d projected=%s retained=%d source=%s%n",
                            planId, key, variant, tryWeb, webCalls.get(), vectorCalls.get(), expectedRequestCap, trace.vector.size(), expectedSource);
                });
    }

    static Stream<Arguments> shippedKgTopKCases() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var applier = new PlanHintApplier(new DefaultResourceLoader());
        var seen = new java.util.HashSet<String>();
        var selected = new java.util.TreeSet<String>();
        var aliases = new java.util.TreeSet<String>();
        var cases = new java.util.ArrayList<Arguments>();
        try (var paths = Files.list(Path.of("main/resources/plans"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String filename = path.getFileName().toString();
                var plan = applier.load(filename.substring(0, filename.length() - 5));
                if (!seen.add(plan.planId()) || plan.kgTopK() == null || plan.kgTopK() <= 0) continue;
                var tree = mapper.readTree(Files.readString(Path.of("main/resources/plans", plan.planId() + ".yaml"),
                        StandardCharsets.UTF_8));
                var matches = new java.util.ArrayList<String>();
                for (String container : List.of("k", "topk")) {
                    if (tree.path("retrieval").path(container).path("kg").isIntegralNumber()) matches.add(container);
                }
                assertEquals(1, matches.size(), "one authored KG alias per selected plan");
                String container = matches.get(0);
                selected.add(plan.planId()); aliases.add(container);
                for (String variant : List.of("authored", "one", "removed", "caller_one", "caller_zero", "rag_denied")) {
                    for (boolean initialKg : List.of(false, true)) {
                        cases.add(Arguments.of(plan.planId(), container, plan.kgTopK(), variant, initialKg));
                    }
                }
            }
        }
        assertEquals(java.util.Set.of("document_evidence.v1", "kg_first.v1", "safe.v1", "zero100.v1"), selected);
        assertEquals(2, aliases.size());
        assertEquals(48, cases.size());
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {3} initialKg={4}")
    @MethodSource("shippedKgTopKCases")
    void shippedKgTopKControlsStageMembersAndIndependentAdmission(String planId, String container,
            int authoredCap, String variant, boolean initialKg) throws Exception {
        TraceStore.clear();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        var parent = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path(container);
        var authoredValue = parent.path("kg").deepCopy();
        assertEquals(authoredCap, authoredValue.intValue());
        if ("one".equals(variant)) parent.put("kg", 1);
        if ("removed".equals(variant)) parent.remove("kg");
        boolean denied = "rag_denied".equals(variant);
        if (denied) {
            assertTrue(!original.path("params").has("allowRag") || original.path("params").path("allowRag").asBoolean());
            var params = ((com.fasterxml.jackson.databind.node.ObjectNode) modified).withObject("/params");
            params.put("allowRag", false);
        }
        var restored = modified.deepCopy();
        if (denied) {
            if (original.path("params").has("allowRag")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params")).set("allowRag", original.path("params").path("allowRag"));
            } else if (original.has("params")) ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params")).remove("allowRag");
            else ((com.fasterxml.jackson.databind.node.ObjectNode) restored).remove("params");
        } else {
            ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path(container)).set("kg", authoredValue);
        }
        assertEquals(original, restored, "each control changes only its declared KG-count or RAG-deny key");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        boolean changed = "one".equals(variant) || "removed".equals(variant) || denied;
        var applier = new PlanHintApplier(changed ? resources : new DefaultResourceLoader());
        var selectedPlan = applier.load(planId);
        Integer projected = "removed".equals(variant) ? null : "one".equals(variant) ? 1 : authoredCap;
        assertEquals(projected, selectedPlan.kgTopK());
        if (denied) assertEquals(false, selectedPlan.allowRag());
        var offered = IntStream.range(0, 60).mapToObj(i -> Content.from(
                dev.langchain4j.data.segment.TextSegment.from("KG fixture member " + i,
                        dev.langchain4j.data.document.Metadata.from(java.util.Map.of("fixtureMember", "member-" + i)))))
                .toList();
        AtomicInteger kgCalls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier)
                .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> {
                    kgCalls.incrementAndGet();
                    return offered;
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest(planId);
                    request.topK = 64; // Keep the thumbnail cap above the fallback50 and every authored KG count.
                    request.useWeb = false;
                    request.useVector = false;
                    request.useKg = initialKg;
                    request.enableOnnx = false;
                    if ("caller_one".equals(variant)) request.kgTopK = 1;
                    if ("caller_zero".equals(variant)) request.kgTopK = 0;
                    var trace = context.getBean(UnifiedRagOrchestrator.class).queryWithTrace(request);
                    boolean callerSpecified = variant.startsWith("caller_");
                    Integer expectedCap = callerSpecified ? Integer.valueOf("caller_zero".equals(variant) ? 0 : 1) : projected;
                    // Caller-disabled axes are explicit restrictions: a plan may size an
                    // enabled axis (kgTopK) or deny it (allowRag=false), but may not
                    // re-enable an axis the caller turned off.
                    boolean enabled = !denied && initialKg;
                    int retained = enabled ? (expectedCap == null ? 50 : expectedCap) : 0;
                    assertEquals(planId, trace.response.debug.get("plan.id"));
                    assertEquals(expectedCap, request.kgTopK);
                    assertEquals(enabled, request.useKg);
                    assertFalse(request.useWeb);
                    assertFalse(request.useVector);
                    assertEquals(enabled ? 1 : 0, kgCalls.get());
                    assertEquals(retained, trace.kg.size());
                    assertEquals(enabled ? (retained == 0 ? "empty" : "success:" + retained) : "disabled",
                            trace.response.debug.get("stage.kg"));
                    assertEquals(new java.util.HashSet<>(IntStream.range(0, retained).mapToObj(i -> "member-" + i).toList()),
                            new java.util.HashSet<>(trace.kg.stream().map(doc -> doc.meta.get("fixtureMember")).toList()),
                            "the actual KG stage keeps exactly the admitted prefix members; thumbnail order is independent");
                    assertTrue(trace.kg.stream().allMatch(doc -> "KG".equals(doc.source)));
                    if (enabled) assertEquals(expectedCap == null ? 50 : expectedCap,
                            TraceStore.get("retrieval.kg.relationThumbnail.prefetchK"));
                    System.out.printf("TBL07_KG_TOPK plan=%s key=retrieval.%s.kg variant=%s initial=%s enabled=%s cap=%s calls=%d retained=%d%n",
                            planId, container, variant, initialKg, enabled, expectedCap, kgCalls.get(), trace.kg.size());
                });
    }

    static Stream<Arguments> shippedAllowCapControls() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var applier = new PlanHintApplier(new DefaultResourceLoader());
        var seen = new java.util.HashSet<String>();
        var selected = new java.util.TreeSet<String>();
        var cases = new java.util.ArrayList<Arguments>();
        int declarations = 0;
        try (var paths = Files.list(Path.of("main/resources/plans"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String filename = path.getFileName().toString();
                var plan = applier.load(filename.substring(0, filename.length() - 5));
                if (!seen.add(plan.planId())) continue;
                var tree = mapper.readTree(Files.readString(Path.of("main/resources/plans", plan.planId() + ".yaml"),
                        StandardCharsets.UTF_8));
                for (String field : List.of("allowWeb", "allowRag")) {
                    if (!tree.path("params").path(field).isBoolean()) continue;
                    declarations++; selected.add(plan.planId());
                    cases.add(Arguments.of(plan.planId(), field, "flip"));
                    cases.add(Arguments.of(plan.planId(), field, "root_conflict"));
                    if ("allowRag".equals(field)) cases.add(Arguments.of(plan.planId(), field, "vector_alias_conflict"));
                }
            }
        }
        assertEquals(9, declarations);
        assertEquals(java.util.Set.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1",
                "ap9_cost_saver.v1", "document_evidence.v1"), selected);
        cases.add(Arguments.of("document_evidence.v1", "allowRag", "baseline"));
        cases.add(Arguments.of("document_evidence.v1", "allowRag", "caller_off"));
        cases.add(Arguments.of("ap3_vec_dense.v1", "allowWeb", "vector_mode_baseline"));
        cases.add(Arguments.of("ap3_vec_dense.v1", "allowWeb", "vector_mode_flip_web"));
        cases.add(Arguments.of("ap3_vec_dense.v1", "allowRag", "vector_mode_flip_rag"));
        assertEquals(28, cases.size());
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("shippedAllowCapControls")
    void shippedAllowCapsRespectPrecedenceAndVectorOnlyShadow(String planId, String field, String control)
            throws Exception {
        TraceStore.clear();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        var params = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params");
        boolean authored = params.path(field).booleanValue();
        boolean vectorModeControl = control.startsWith("vector_mode_");
        boolean vectorCapControl = planId.equals("ap1_auth_web.v1") && field.equals("allowRag");
        if (vectorCapControl) ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path("vector")).put("enabled", true);
        boolean flip = "flip".equals(control) || control.startsWith("vector_mode_flip_");
        if (flip) params.put(field, !authored);
        if ("root_conflict".equals(control)) {
            assertFalse(original.has(field));
            ((com.fasterxml.jackson.databind.node.ObjectNode) modified).put(field, !authored);
        }
        if ("vector_alias_conflict".equals(control)) {
            assertFalse(original.path("params").has("allowVector"));
            params.put("allowVector", !authored);
        }
        if (vectorModeControl) {
            assertTrue(original.path("params").path("vectorOnly").asBoolean());
            params.put("vectorOnly", false);
            ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path("web")).put("enabled", true);
        }
        var restored = modified.deepCopy();
        var restoredParams = (com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params");
        restoredParams.set(field, original.path("params").path(field));
        if ("root_conflict".equals(control)) ((com.fasterxml.jackson.databind.node.ObjectNode) restored).remove(field);
        if ("vector_alias_conflict".equals(control)) restoredParams.remove("allowVector");
        if (vectorModeControl) {
            restoredParams.set("vectorOnly", original.path("params").path("vectorOnly"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path("web"))
                    .set("enabled", original.path("retrieval").path("web").path("enabled"));
        }
        if (vectorCapControl) ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path("vector"))
                .set("enabled", original.path("retrieval").path("vector").path("enabled"));
        assertEquals(original, restored, "only declared raw-cap/alias and independent retrieval-admission controls change");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var originalPlan = new PlanHintApplier(new DefaultResourceLoader()).load(planId);
        var applier = new PlanHintApplier(modified.equals(original) ? new DefaultResourceLoader() : resources);
        var plan = applier.load(planId);
        Boolean expectedWeb = originalPlan.allowWeb();
        Boolean expectedRag = originalPlan.allowRag();
        boolean shadow = original.path("params").path("vectorOnly").asBoolean() && !vectorModeControl;
        if (flip && !shadow) {
            if ("allowWeb".equals(field)) expectedWeb = !authored;
            else expectedRag = !authored;
        }
        assertEquals(expectedWeb, plan.allowWeb());
        assertEquals(expectedRag, plan.allowRag());
        boolean callerEnabled = !"caller_off".equals(control);
        boolean webEnabled = callerEnabled && !Boolean.FALSE.equals(expectedWeb);
        boolean ragEnabled = callerEnabled && !Boolean.FALSE.equals(expectedRag);
        AtomicInteger webCalls = new AtomicInteger();
        AtomicInteger vectorCalls = new AtomicInteger();
        AtomicInteger kgCalls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier)
                .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> {
                    webCalls.incrementAndGet(); return contents("web", 4);
                })
                .withBean("vectorRetriever", ContentRetriever.class, () -> query -> {
                    vectorCalls.incrementAndGet(); return contents("vector", 4);
                })
                .withBean(LangChainRAGService.class, () -> leafReturning(query -> {
                    vectorCalls.incrementAndGet(); return contents("vector", 4);
                }))
                .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> {
                    kgCalls.incrementAndGet(); return contents("kg", 4);
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest(planId);
                    request.useWeb = callerEnabled;
                    request.useVector = callerEnabled;
                    request.useKg = callerEnabled;
                    request.kgTopK = 2; // Keep count-triggered implicit KG admission outside this cap-only control.
                    request.enableOnnx = false;
                    var trace = context.getBean(UnifiedRagOrchestrator.class).queryWithTrace(request);
                    assertEquals(planId, trace.response.debug.get("plan.id"));
                    assertEquals(webEnabled, request.useWeb);
                    assertEquals(ragEnabled, request.useVector);
                    assertEquals(ragEnabled, request.useKg);
                    assertEquals(webEnabled ? 1 : 0, webCalls.get());
                    assertEquals(ragEnabled ? 1 : 0, vectorCalls.get());
                    assertEquals(ragEnabled ? 1 : 0, kgCalls.get());
                    assertEquals(!webEnabled, trace.web.isEmpty());
                    assertEquals(!ragEnabled, trace.vector.isEmpty());
                    assertEquals(!ragEnabled, trace.kg.isEmpty());
                    System.out.printf("TBL07_ALLOW_CAP plan=%s field=%s control=%s shadow=%s webCalls=%d vectorCalls=%d kgCalls=%d%n",
                            planId, field, control, shadow, webCalls.get(), vectorCalls.get(), kgCalls.get());
                });
    }

    static Stream<Arguments> shippedOfficialOnlyControls() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var applier = new PlanHintApplier(new DefaultResourceLoader());
        var seen = new java.util.HashSet<String>();
        var selected = new java.util.TreeSet<String>();
        var aliases = new java.util.HashSet<String>();
        var cases = new java.util.ArrayList<Arguments>();
        try (var paths = Files.list(Path.of("main/resources/plans"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String filename = path.getFileName().toString();
                var plan = applier.load(filename.substring(0, filename.length() - 5));
                if (!seen.add(plan.planId()) || plan.officialSourcesOnly() == null) continue;
                var tree = mapper.readTree(Files.readString(Path.of("main/resources/plans", plan.planId() + ".yaml"),
                        StandardCharsets.UTF_8));
                var matches = new java.util.ArrayList<String>();
                for (String key : List.of("params.officialSourcesOnly", "retrieval.officialSourcesOnly", "official_sources_only")) {
                    var value = tree;
                    for (String segment : key.split("\\.")) value = value.path(segment);
                    if (value.isBoolean()) matches.add(key);
                }
                assertEquals(1, matches.size(), "one authored official-only alias per selected plan");
                selected.add(plan.planId()); aliases.add(matches.get(0));
                for (String control : List.of("authored", "flip", "removed", "precedence")) {
                    for (boolean callerOfficial : List.of(false, true)) {
                        cases.add(Arguments.of(plan.planId(), matches.get(0), plan.officialSourcesOnly(), control, callerOfficial));
                    }
                }
            }
        }
        assertEquals(java.util.Set.of("ap11_finance_special.v1", "ap1_auth_web.v1", "safe.v1", "zero100.v1", "recency_first.v1"), selected);
        assertEquals(3, aliases.size());
        assertEquals(40, cases.size());
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {3} callerOfficial={4}")
    @MethodSource("shippedOfficialOnlyControls")
    void shippedOfficialOnlyControlsFinalMixedSourceMembership(String planId, String key, boolean authored,
            String control, boolean callerOfficial) throws Exception {
        TraceStore.clear();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        var segments = key.split("\\.");
        var parent = modified;
        for (int i = 0; i < segments.length - 1; i++) parent = parent.path(segments[i]);
        String leaf = segments[segments.length - 1];
        assertEquals(authored, parent.path(leaf).booleanValue());
        if ("flip".equals(control)) ((com.fasterxml.jackson.databind.node.ObjectNode) parent).put(leaf, !authored);
        if ("removed".equals(control)) ((com.fasterxml.jackson.databind.node.ObjectNode) parent).remove(leaf);
        if ("precedence".equals(control)) {
            assertFalse(original.has("officialSourcesOnly"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) modified).put("officialSourcesOnly", !authored);
        }
        var restored = modified.deepCopy();
        var restoredParent = restored;
        for (int i = 0; i < segments.length - 1; i++) restoredParent = restoredParent.path(segments[i]);
        ((com.fasterxml.jackson.databind.node.ObjectNode) restoredParent).put(leaf, authored);
        if ("precedence".equals(control)) ((com.fasterxml.jackson.databind.node.ObjectNode) restored).remove("officialSourcesOnly");
        assertEquals(original, restored, "only the declared official-only key or conflicting root alias changes");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new PlanHintApplier("authored".equals(control) ? new DefaultResourceLoader() : resources);
        Boolean selectedFlag = "removed".equals(control) ? null : "flip".equals(control) ? !authored
                : "precedence".equals(control) && !key.startsWith("retrieval.") ? !authored : authored;
        assertEquals(selectedFlag, applier.load(planId).officialSourcesOnly());
        var whitelist = new com.example.lms.service.rag.auth.DomainWhitelist();
        whitelist.setEnableDomainFilter(true);
        whitelist.setDomainAllowlist(List.of("allowed.example.test"));
        var specs = List.of(
                new String[]{"allowed-root", "https://allowed.example.test/a"},
                new String[]{"allowed-sub", "https://docs.allowed.example.test/a"},
                new String[]{"denied", "https://independent.example.test/a"},
                new String[]{"suffix-lookalike", "https://notallowed.example.test/a"},
                new String[]{"missing-url", ""},
                new String[]{"invalid-url", "not a url"});
        var offered = new java.util.ArrayList<Content>();
        for (var spec : specs) {
            var metadata = new java.util.HashMap<String, Object>();
            metadata.put("fixtureMember", spec[0]);
            if (!spec[1].isEmpty()) metadata.put("url", spec[1]);
            offered.add(Content.from(dev.langchain4j.data.segment.TextSegment.from("synthetic member " + spec[0],
                    dev.langchain4j.data.document.Metadata.from(metadata))));
        }
        AtomicInteger webCalls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier)
                .withBean(com.example.lms.service.rag.auth.DomainWhitelist.class, () -> whitelist)
                .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> {
                    webCalls.incrementAndGet(); return offered;
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest(planId);
                    request.useWeb = true;
                    request.useVector = false;
                    request.useKg = false;
                    request.kgTopK = 0;
                    request.enableOnnx = false;
                    request.whitelistOnly = callerOfficial;
                    var trace = context.getBean(UnifiedRagOrchestrator.class).queryWithTrace(request);
                    // Strict whitelist is a caller restriction: a plan may tighten it
                    // (officialSourcesOnly=true) but may never relax an explicit
                    // caller whitelistOnly=true.
                    boolean enforced = callerOfficial || Boolean.TRUE.equals(selectedFlag);
                    var allMembers = new java.util.HashSet<>(specs.stream().map(spec -> spec[0]).toList());
                    var expectedMembers = enforced ? java.util.Set.of("allowed-root", "allowed-sub") : allMembers;
                    assertEquals(planId, trace.response.debug.get("plan.id"));
                    assertEquals(enforced, request.whitelistOnly);
                    assertEquals(1, webCalls.get());
                    assertEquals(6, trace.web.size());
                    assertEquals(allMembers, new java.util.HashSet<>(trace.fused.stream().map(doc -> doc.meta.get("fixtureMember")).toList()),
                            "every mixed candidate must reach fusion before the official-only filter");
                    assertEquals(expectedMembers.size(), trace.response.results.size());
                    assertEquals(expectedMembers, new java.util.HashSet<>(trace.response.results.stream().map(doc -> doc.meta.get("fixtureMember")).toList()));
                    if (enforced) assertEquals(4, trace.response.debug.get("stage.whitelist.filtered"));
                    else assertFalse(trace.response.debug.containsKey("stage.whitelist.filtered"));
                    System.out.printf("TBL07_OFFICIAL_ONLY plan=%s key=%s control=%s caller=%s selected=%s enforced=%s before=%d after=%d%n",
                            planId, key, control, callerOfficial, selectedFlag, enforced, trace.fused.size(), trace.response.results.size());
                });
    }

    static Stream<Arguments> shippedOnnxControls() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var applier = new PlanHintApplier(new DefaultResourceLoader());
        var seen = new java.util.HashSet<String>();
        var selected = new java.util.TreeSet<String>();
        var cases = new java.util.ArrayList<Arguments>();
        try (var paths = Files.list(Path.of("main/resources/plans"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String name = path.getFileName().toString();
                var hints = applier.load(name.substring(0, name.length() - 5));
                if (!seen.add(hints.planId())) continue;
                var tree = mapper.readTree(Files.readString(Path.of("main/resources/plans", hints.planId() + ".yaml"), StandardCharsets.UTF_8));
                boolean rootAlias = tree.has("onnx_enabled");
                var raw = rootAlias ? tree.path("onnx_enabled") : tree.path("plan").path("overrides").path("properties").path("onnx.enabled");
                if (raw.isMissingNode()) continue;
                selected.add(hints.planId());
                if ("zero_break.v1".equals(hints.planId())) {
                    assertEquals("${ONNX_ENABLED:false}", raw.textValue());
                    assertEquals(null, hints.onnxEnabled(), "unexpanded placeholder has no typed Boolean projection");
                } else {
                    assertTrue(raw.isBoolean()); assertTrue(raw.booleanValue()); assertEquals(Boolean.TRUE, hints.onnxEnabled());
                }
                assertEquals(null, hints.useCrossEncoder(), "authored pipeline labels do not imply an explicit CE override");
                for (String control : List.of("authored", "true", "false", "removed", "alias_conflict", "ce_false", "ce_true")) {
                    for (boolean initial : List.of(false, true)) cases.add(Arguments.of(hints.planId(), rootAlias, control, initial));
                }
            }
        }
        assertEquals(java.util.Set.of("brave.v1", "document_evidence.v1", "recency_first.v1", "safe_autorun.v1", "zero_break.v1"), selected);
        assertEquals(70, cases.size());
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} control={2} initialOnnx={3}")
    @MethodSource("shippedOnnxControls")
    void shippedOnnxDeclarationControlsInvocationOrPreservesCallerForPlaceholder(String planId, boolean rootAlias,
            String control, boolean initialOnnx) throws Exception {
        TraceStore.clear();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"), StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        String leaf = rootAlias ? "onnx_enabled" : "onnx.enabled";
        var parent = (com.fasterxml.jackson.databind.node.ObjectNode) (rootAlias ? modified : modified.path("plan").path("overrides").path("properties"));
        var raw = parent.get(leaf).deepCopy();
        if ("true".equals(control) || "false".equals(control)) parent.put(leaf, Boolean.parseBoolean(control));
        if ("removed".equals(control)) parent.remove(leaf);
        if ("alias_conflict".equals(control)) {
            if (rootAlias) {
                assertFalse(original.has("onnx"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) modified).putObject("onnx").put("enabled", false);
            } else {
                assertFalse(original.has("onnx_enabled"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) modified).put("onnx_enabled", "zero_break.v1".equals(planId));
            }
        }
        boolean ceControl = control.startsWith("ce_");
        if (ceControl) {
            assertFalse(original.has("use_cross_encoder"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) modified).put("use_cross_encoder", "ce_true".equals(control));
        }
        var restored = modified.deepCopy();
        var restoredParent = (com.fasterxml.jackson.databind.node.ObjectNode) (rootAlias ? restored : restored.path("plan").path("overrides").path("properties"));
        restoredParent.set(leaf, raw);
        if ("alias_conflict".equals(control)) ((com.fasterxml.jackson.databind.node.ObjectNode) restored).remove(rootAlias ? "onnx" : "onnx_enabled");
        if (ceControl) ((com.fasterxml.jackson.databind.node.ObjectNode) restored).remove("use_cross_encoder");
        assertEquals(original, restored, "other authored caps, pipeline entries and official-only policy remain intact");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new PlanHintApplier("authored".equals(control) ? new DefaultResourceLoader() : resources);
        Boolean projected = "zero_break.v1".equals(planId) ? null : Boolean.TRUE;
        if ("true".equals(control) || "false".equals(control)) projected = Boolean.valueOf(control);
        if ("removed".equals(control)) projected = null;
        if ("alias_conflict".equals(control)) projected = rootAlias || "zero_break.v1".equals(planId);
        assertEquals(projected, applier.load(planId).onnxEnabled());
        boolean expected = ceControl ? "ce_true".equals(control) : projected == null ? initialOnnx : projected;
        AtomicInteger webCalls = new AtomicInteger();
        AtomicInteger onnxCalls = new AtomicInteger();
        var offered = new java.util.ArrayList<Content>();
        for (int i = 0; i < 6; i++) offered.add(Content.from(dev.langchain4j.data.segment.TextSegment.from("synthetic member " + i,
                dev.langchain4j.data.document.Metadata.from(java.util.Map.of("fixtureMember", "m" + i, "url", "https://allowed.example.test/" + i)))));
        com.example.lms.service.rag.rerank.CrossEncoderReranker onnx = (query, candidates, topN) -> {
            onnxCalls.incrementAndGet();
            assertEquals(6, candidates.size());
            var reverse = new java.util.ArrayList<>(candidates); java.util.Collections.reverse(reverse);
            return List.copyOf(reverse);
        };
        var whitelist = new com.example.lms.service.rag.auth.DomainWhitelist();
        whitelist.setEnableDomainFilter(true); whitelist.setDomainAllowlist(List.of("allowed.example.test"));
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier)
                .withBean(com.example.lms.service.rag.auth.DomainWhitelist.class, () -> whitelist)
                .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> { webCalls.incrementAndGet(); return offered; })
                .withBean("onnxCrossEncoderReranker", com.example.lms.service.rag.rerank.CrossEncoderReranker.class, () -> onnx)
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest(planId); request.enableOnnx = initialOnnx;
                    request.useWeb = true; request.useVector = false; request.useKg = false; request.kgTopK = 0;
                    var trace = context.getBean(UnifiedRagOrchestrator.class).queryWithTrace(request);
                    assertEquals(planId, trace.response.debug.get("plan.id"));
                    assertEquals(1, webCalls.get()); assertEquals(6, trace.web.size()); assertEquals(6, trace.fused.size());
                    assertEquals(expected, request.enableOnnx); assertEquals(expected ? 1 : 0, onnxCalls.get());
                    assertEquals(expected, TraceStore.get("rerank.onnx.requested"));
                    assertEquals(expected, TraceStore.get("rerank.onnx.orchestrator.executed"));
                    var expectedIds = new java.util.ArrayList<>(trace.fused.stream().map(doc -> doc.meta.get("fixtureMember")).toList());
                    if (expected) java.util.Collections.reverse(expectedIds);
                    assertEquals(expectedIds, trace.response.results.stream().map(doc -> doc.meta.get("fixtureMember")).toList(),
                            "the controlled reranker's actual order reaches final results with all six members");
                    System.out.printf("TBL07_ONNX plan=%s control=%s initial=%s projected=%s effective=%s calls=%d candidates=%d results=%d%n",
                            planId, control, initialOnnx, applier.load(planId).onnxEnabled(), expected, onnxCalls.get(), trace.fused.size(), trace.response.results.size());
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "false,true", "missing,true",
            "true,false", "false,false", "missing,false"})
    void zeroBreakDppPlanCapsActualManagedRerankWithoutReenablingCallerOff(String raw, boolean callerEnabled) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero_break.v1.yaml"), StandardCharsets.UTF_8));
        String key = "diversity.dpp.enabled";
        var modified = original.deepCopy();
        var knobs = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("plan").path("overrides").path("knobs");
        if ("missing".equals(raw)) knobs.remove(key); else knobs.put(key, Boolean.parseBoolean(raw));
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("plan").path("overrides").path("knobs"))
                .set(key, original.path("plan").path("overrides").path("knobs").path(key));
        assertEquals(original, restored);
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero_break.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero_break.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new PlanHintApplier(original.equals(modified) ? new DefaultResourceLoader() : resources);
        var calls = new AtomicInteger();
        var returnedCount = new AtomicInteger();
        var dpp = new com.example.lms.service.rag.rerank.DppDiversityReranker() {
            @Override public <T> List<T> rerank(Config config, List<T> input, String query, int k,
                    java.util.function.Function<? super T, String> textOf,
                    java.util.function.ToDoubleFunction<? super T> relevanceOf,
                    java.util.function.Function<? super T, String> stableKeyOf) {
                calls.incrementAndGet();
                List<T> result = super.rerank(config, input, query, k, textOf, relevanceOf, stableKeyOf);
                returnedCount.set(result.size());
                return result;
            }
        };
        boolean expected = callerEnabled && !"false".equals(raw);
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier)
                .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> q -> contents("local-dpp", 3))
                .withBean("vectorRetriever", ContentRetriever.class, () -> q -> List.of())
                .withBean(LangChainRAGService.class, () -> leafReturning(q -> List.of()))
                .withBean(com.example.lms.service.rag.rerank.DppDiversityReranker.class, () -> dpp)
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest("zero_break.v1");
                    request.enableDiversity = callerEnabled;
                    request.enableOnnx = false;
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertEquals(expected, request.enableDiversity);
                    assertEquals(expected ? 1 : 0, calls.get());
                    if (expected) {
                        assertEquals(3, returnedCount.get());
                        assertEquals(3, response.debug.get("stage.dpp"));
                        assertEquals("spring_managed_reranker", TraceStore.get("rag.orchestrator.dpp.source"));
                    } else {
                        assertFalse(response.debug.containsKey("stage.dpp"));
                    }
                    assertEquals("not_used", response.debug.get("planDsl.status"));
                    assertTrue(String.valueOf(response.debug.get("planDsl.unwiredKeys")).contains("plan.pipeline"));
                    System.out.printf("TBL07_DPP_UNIFIED raw=%s callerEnabled=%s effectiveEnabled=%s calls=%d externalRequests=0%n",
                            raw, callerEnabled, request.enableDiversity, calls.get());
                });
    }

    @ParameterizedTest(name = "documentVector raw={0} control={1}")
    @org.junit.jupiter.params.provider.CsvSource({"true,allowed", "false,allowed", "missing,allowed",
            "true,rag_denied", "false,rag_denied", "missing,rag_denied",
            "true,caller_denied", "false,caller_denied", "missing,caller_denied"})
    void documentVectorFlagCapsActualCallsWithoutDisablingKgOrReenablingCaller(String raw, String control) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/document_evidence.v1.yaml"), StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        var vectorParams = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params").path("retrieval").path("vector");
        if ("missing".equals(raw)) vectorParams.remove("enabled"); else vectorParams.put("enabled", Boolean.parseBoolean(raw));
        boolean allowRag = !"rag_denied".equals(control);
        boolean callerVector = !"caller_denied".equals(control);
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put("allowRag", allowRag);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params").path("retrieval").path("vector"))
                .set("enabled", original.path("params").path("retrieval").path("vector").path("enabled"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set("allowRag", original.path("params").path("allowRag"));
        assertEquals(original, restored, "only nested vector key and independent allowRag control change");
        assertTrue(modified.path("plan").path("overrides").path("properties").path("retrieval.vector.enabled").asBoolean(),
                "duplicate properties spelling stays authored true in every case");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/document_evidence.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "document_evidence.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var appliedMeta = new java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>>();
        var applier = new PlanHintApplier(original.equals(modified) ? new DefaultResourceLoader() : resources) {
            @Override public void applyToHintsAndMeta(PlanHints plan,
                    com.example.lms.orchestration.OrchestrationHints hints, java.util.Map<String, Object> meta) {
                super.applyToHintsAndMeta(plan, hints, meta);
                appliedMeta.set(new java.util.LinkedHashMap<>(meta));
            }
        };
        var parsed = applier.load("document_evidence.v1");
        assertEquals(allowRag, parsed.allowRag());
        var guard = new com.example.lms.service.guard.GuardContext();
        applier.applyToGuardContext(parsed, guard);
        Object projected = "missing".equals(raw) ? null : Boolean.valueOf(raw);
        assertEquals(projected, guard.getPlanOverride("retrieval.vector.enabled"));
        var vectorCalls = new AtomicInteger();
        var kgCalls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier)
                .withBean("vectorRetriever", ContentRetriever.class, () -> query -> {
                    vectorCalls.incrementAndGet();
                    return contents("document-local-vector", 3);
                })
                .withBean(LangChainRAGService.class, () -> leafReturning(query -> {
                    vectorCalls.incrementAndGet();
                    return contents("document-local-vector", 3);
                }))
                .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> {
                    kgCalls.incrementAndGet();
                    return contents("document-local-kg", 2);
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest("document_evidence.v1");
                    request.useWeb = false;
                    request.useVector = callerVector;
                    request.useKg = true;
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertEquals("document_evidence.v1", response.debug.get("plan.id"));
                    assertEquals(projected, appliedMeta.get().get("retrieval.vector.enabled"), "actual owner projection is observed");
                    boolean vectorExpected = allowRag && callerVector && !"false".equals(raw);
                    assertEquals(vectorExpected, request.useVector);
                    assertEquals(vectorExpected ? 1 : 0, vectorCalls.get(), "raw vector false caps actual calls while RAG and caller OFF remain authoritative");
                    assertEquals(allowRag, request.useKg);
                    assertEquals(allowRag ? 1 : 0, kgCalls.get(), "caller vector OFF is independent of KG");
                    assertEquals("not_used", response.debug.get("planDsl.status"));
                    System.out.printf("TBL07_DOCUMENT_VECTOR raw=%s control=%s metadataProjected=%s vectorCalls=%d kgCalls=%d requestVector=%s propertySpellingRetained=true externalRequests=0%n",
                            raw, control, appliedMeta.get().containsKey("retrieval.vector.enabled"), vectorCalls.get(), kgCalls.get(), request.useVector);
                });
    }

    @ParameterizedTest(name = "rootWeb raw={0} control={1}")
    @org.junit.jupiter.params.provider.CsvSource({"true,allowed", "false,allowed", "missing,allowed",
            "true,plan_denied", "false,plan_denied", "missing,plan_denied",
            "true,caller_denied", "false,caller_denied", "missing,caller_denied",
            "true,vector_only", "false,vector_only"})
    void rootWebFlagCapsActualCallsWithoutReenablingCallerOrDisablingRag(String raw, String control) throws Exception {
        String key = "retrieval.web.enabled";
        String planId = control.equals("vector_only") ? "ap3_vec_dense.v1" : "ap11_finance_special.v1";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"), StandardCharsets.UTF_8));
        var modified = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        var webNode = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path("web");
        if (raw.equals("missing")) webNode.remove("enabled"); else webNode.put("enabled", Boolean.parseBoolean(raw));
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path("web"))
                .set("enabled", original.path("retrieval").path("web").path("enabled"));
        assertEquals(original, restored, "only the exact root WEB key changes before independent cap controls");
        if (control.equals("plan_denied")) ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put("allowWeb", false);
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                assertEquals("classpath:plans/" + planId + ".yaml", location);
                return new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
            }
        };
        var appliedMeta = new java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>>();
        var applier = new PlanHintApplier(resources) {
            @Override public void applyToHintsAndMeta(PlanHints plan,
                    com.example.lms.orchestration.OrchestrationHints hints, java.util.Map<String, Object> meta) {
                super.applyToHintsAndMeta(plan, hints, meta);
                appliedMeta.set(new java.util.LinkedHashMap<>(meta));
            }
        };
        var parsed = applier.load(planId); assertEquals(planId, parsed.planId()); assertFalse(parsed.isEmpty());
        boolean planAllowsWeb = !List.of("plan_denied", "vector_only").contains(control);
        assertEquals(planAllowsWeb, parsed.allowWeb());
        var guard = new com.example.lms.service.guard.GuardContext();
        applier.applyToGuardContext(parsed, guard);
        Object projected = raw.equals("missing") ? null : Boolean.valueOf(raw);
        var webCalls = new AtomicInteger(); var vectorCalls = new AtomicInteger(); var kgCalls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier)
                .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> {
                    webCalls.incrementAndGet(); return contents("root-web", 3);
                })
                .withBean("vectorRetriever", ContentRetriever.class, () -> query -> {
                    vectorCalls.incrementAndGet(); return contents("root-vector", 3);
                })
                .withBean(LangChainRAGService.class, () -> leafReturning(query -> {
                    vectorCalls.incrementAndGet(); return contents("root-vector", 3);
                }))
                .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> {
                    kgCalls.incrementAndGet(); return contents("root-kg", 2);
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest(planId);
                    request.useWeb = !control.equals("caller_denied");
                    request.useVector = true; request.useKg = true; request.kgTopK = 2; request.enableOnnx = false;
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    boolean expected = planAllowsWeb && !control.equals("caller_denied") && !raw.equals("false");
                    System.out.printf("TBL07_ROOT_WEB_UNIFIED raw=%s control=%s projected=%s requestWeb=%s expectedWeb=%s webCalls=%d vectorCalls=%d kgCalls=%d externalRequests=0%n",
                            raw, control, appliedMeta.get().get(key), request.useWeb, expected, webCalls.get(), vectorCalls.get(), kgCalls.get());
                    org.junit.jupiter.api.Assertions.assertAll(
                            () -> assertEquals(planId, response.debug.get("plan.id")),
                            () -> assertEquals(projected, appliedMeta.get().get(key), "actual owner projection"),
                            () -> assertEquals(projected, guard.getPlanOverride(key), "context projection"),
                            () -> assertEquals(expected, request.useWeb, "false caps caller request without re-enabling"),
                            () -> assertEquals(expected ? 1 : 0, webCalls.get(), "actual WEB call count"),
                            () -> assertEquals(1, vectorCalls.get(), "WEB cap preserves VECTOR"),
                            () -> assertEquals(1, kgCalls.get(), "WEB cap preserves KG"));
                    assertTrue(request.useVector); assertTrue(request.useKg);
                });
    }

    static Stream<Arguments> apRootVectorControls() {
        var cases = new java.util.ArrayList<Arguments>();
        for (String plan : List.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1", "hyper_nova.v1"))
            for (String raw : List.of("true", "false", "missing"))
                for (String control : List.of("authored", "caller_denied"))
                    cases.add(Arguments.of(plan, raw, control));
        for (String raw : List.of("true", "false", "missing")) {
            cases.add(Arguments.of("ap11_finance_special.v1", raw, "rag_denied"));
            cases.add(Arguments.of("ap1_auth_web.v1", raw, "admissible"));
        }
        cases.add(Arguments.of("ap11_finance_special.v1", "invalid", "authored"));
        cases.add(Arguments.of("ap11_finance_special.v1", "true", "params_false"));
        cases.add(Arguments.of("ap11_finance_special.v1", "false", "knobs_true"));
        assertEquals(39, cases.size()); return cases.stream();
    }

    @ParameterizedTest(name = "AProotVector plan={0} raw={1} control={2}")
    @MethodSource("apRootVectorControls")
    void apRootVectorFlagReachesActualCallsWithinCallerAndRagCaps(String planId, String raw, String control) throws Exception {
        String key = "retrieval.vector.enabled";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"), StandardCharsets.UTF_8));
        assertEquals(!planId.equals("ap1_auth_web.v1"), original.path("retrieval").path("vector").path("enabled").asBoolean());
        var modified = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path("vector");
        if (raw.equals("missing")) node.remove("enabled");
        else if (raw.equals("invalid")) node.put("enabled", "invalid-fixture-boolean");
        else node.put("enabled", Boolean.parseBoolean(raw));
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path("vector"))
                .set("enabled", original.path("retrieval").path("vector").path("enabled"));
        assertEquals(original, restored, "initial mutation is confined to the exact AP root vector flag");
        if (control.equals("rag_denied")) modified.with("params").put("allowRag", false);
        if (control.equals("admissible")) modified.with("params").put("allowRag", true);
        if (control.equals("params_false")) modified.with("params").put(key, "false");
        if (control.equals("knobs_true")) modified.with("plan").with("overrides").with("knobs").put(key, "true");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                assertEquals("classpath:plans/" + planId + ".yaml", location);
                return new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
            }
        };
        var appliedMeta = new java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>>();
        var applier = new PlanHintApplier(resources) {
            @Override public void applyToHintsAndMeta(PlanHints plan,
                    com.example.lms.orchestration.OrchestrationHints hints, java.util.Map<String, Object> meta) {
                super.applyToHintsAndMeta(plan, hints, meta);
                appliedMeta.set(new java.util.LinkedHashMap<>(meta));
            }
        };
        var plan = applier.load(planId); assertEquals(planId, plan.planId()); assertFalse(plan.isEmpty());
        boolean allowRag = !control.equals("rag_denied") && (!planId.equals("ap1_auth_web.v1") || control.equals("admissible"));
        Boolean expectedPlanAllowRag = planId.equals("hyper_nova.v1") ? null : Boolean.valueOf(allowRag);
        assertEquals(expectedPlanAllowRag, plan.allowRag());
        Boolean projected = List.of("missing", "invalid").contains(raw) ? null : Boolean.valueOf(raw);
        Boolean effective = control.equals("params_false") ? Boolean.FALSE : control.equals("knobs_true") ? Boolean.TRUE : projected;
        var guard = new com.example.lms.service.guard.GuardContext(); applier.applyToGuardContext(plan, guard);
        var vectorCalls = new AtomicInteger(); var kgCalls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier)
                .withBean("vectorRetriever", ContentRetriever.class, () -> query -> {
                    vectorCalls.incrementAndGet(); return contents("AP-root-vector", 3);
                })
                .withBean(LangChainRAGService.class, () -> leafReturning(query -> {
                    vectorCalls.incrementAndGet(); return contents("AP-root-vector", 3);
                }))
                .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> {
                    kgCalls.incrementAndGet(); return contents("AP-root-kg", 2);
                })
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    var request = baseRequest(planId);
                    request.useWeb = false; request.useVector = !control.equals("caller_denied");
                    request.useKg = true; request.kgTopK = 2; request.enableOnnx = false;
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    boolean expected = allowRag && !control.equals("caller_denied") && !Boolean.FALSE.equals(effective);
                    System.out.printf("TBL07_AP_ROOT_VECTOR_UNIFIED plan=%s raw=%s control=%s projected=%s metadata=%s expectedVector=%s requestVector=%s vectorCalls=%d expectedKg=%s kgCalls=%d externalRequests=0%n",
                            planId, raw, control, plan.raw().get(key), appliedMeta.get().get(key), expected, request.useVector, vectorCalls.get(), allowRag, kgCalls.get());
                    org.junit.jupiter.api.Assertions.assertAll(
                            () -> assertEquals(planId, response.debug.get("plan.id")),
                            () -> assertEquals(projected, plan.raw().get(key), "exact AP root producer"),
                            () -> assertEquals(effective, appliedMeta.get().get(key), "params and knobs precedence"),
                            () -> assertEquals(effective, guard.getPlanOverride(key), "context projection"),
                            () -> assertEquals(expected, request.useVector, "caller and RAG caps remain authoritative"),
                            () -> assertEquals(expected ? 1 : 0, vectorCalls.get(), "actual VECTOR call count"),
                            () -> assertEquals(allowRag, request.useKg),
                            () -> assertEquals(allowRag ? 1 : 0, kgCalls.get(), "VECTOR cap does not disable KG"));
                    assertFalse(request.useWeb);
                });
    }

    private static UnifiedRagOrchestrator.QueryRequest baseRequest(String planId) {
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "plan hint verification";
        request.planId = planId;
        request.useWeb = true;
        request.useVector = true;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = true;
        request.topK = 8;
        return request;
    }

    private static List<Content> contents(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Content.from(prefix + " evidence " + i))
                .toList();
    }

    /**
     * Unified VECTOR resolves through LangChainRAGService.asContentRetriever
     * (the pure vector leaf). Fixtures that exercise the vector stage wire the
     * leaf here; the 'vectorRetriever' hybrid bean is intentionally not the
     * axis dependency anymore.
     */
    static LangChainRAGService leafReturning(ContentRetriever retriever) {
        LangChainRAGService service = org.mockito.Mockito.mock(LangChainRAGService.class);
        org.mockito.Mockito.when(service.asContentRetriever(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(retriever);
        return service;
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse > start, "parser call should be locatable: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be locatable: " + signature);
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
    }
}
