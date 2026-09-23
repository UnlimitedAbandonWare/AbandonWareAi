package com.example.lms.service.rag;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.learning.NeuralPathFormationService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.fusion.ReciprocalRankFuser;
import com.example.lms.service.rag.handler.RetrievalHandler;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import com.example.lms.service.rag.rerank.ElementConstraintScorer;
import com.example.lms.service.rag.rerank.LightWeightRanker;
import com.example.lms.service.rag.rerank.RerankGate;
import com.example.lms.service.scoring.AdaptiveScoringService;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HybridDppFusionMembershipCharacterizationTest {
    @AfterEach
    void clearContexts() {
        TraceStore.clear();
        TimeBudgetContext.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unrestrictedFusionRetainsAllDistinctDppMembersAndFirstSeenObjects(boolean sequential) {
        Probe p = run(sequential, 3, Map.of());

        assertEquals(Set.of("aaaaaaaa", "bbbbbbbb"), keys(p.branches.get("branch-one")));
        assertEquals(Set.of("aaaaaaaa", "cccccccc"), keys(p.branches.get("branch-two")));
        assertEquals(Set.of("aaaaaaaa", "bbbbbbbb", "cccccccc"), keys(p.output));
        assertEquals(p.branches.get("branch-one"), p.fuser.inputs.get(0));
        assertEquals(p.branches.get("branch-two"), p.fuser.inputs.get(1));
        assertSame(p.firstA, p.output.stream().filter(c -> text(c).equals("aaaaaaaa")).findFirst().orElseThrow());
        assertTrue(p.output.stream().allMatch(c -> p.fuser.inputs.stream().flatMap(List::stream).anyMatch(x -> x == c)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void smallerOutputBudgetSelectsASubsetWithoutInventingMembers(boolean sequential) {
        Probe p = run(sequential, 2, Map.of());

        assertEquals(2, p.output.size());
        assertEquals(2, p.fuser.limit);
        assertTrue(keys(p.output).contains("aaaaaaaa"));
        assertTrue(Set.of("aaaaaaaa", "bbbbbbbb", "cccccccc").containsAll(keys(p.output)));
        assertTrue(p.fuser.inputs.stream().allMatch(branch -> branch.size() == 2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void explicitCandidateCapTruncatesAfterDppBeforeRealFusion(boolean sequential) {
        Probe p = run(sequential, 3, Map.of("rerank.ce.topK", 1));

        assertTrue(p.branches.values().stream().allMatch(branch -> branch.size() == 2));
        assertEquals(List.of(p.branches.get("branch-one").subList(0, 1),
                p.branches.get("branch-two").subList(0, 1)), p.fuser.inputs);
        assertEquals(1, p.fuser.limit);
        assertEquals(1, p.output.size());
        assertEquals(1, p.prefuseCap);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepCountFloorsTheExplicitCandidateCap(boolean sequential) {
        Probe p = run(sequential, 3, Map.of("rerank.ce.topK", 1, "rerank.topK", 2));

        assertEquals(2, p.prefuseCap);
        assertEquals(2, p.fuser.limit);
        assertTrue(p.fuser.inputs.stream().allMatch(branch -> branch.size() == 2));
        assertEquals(2, p.output.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepCountWithoutCandidateOverrideRetainsTwiceTheRequestedCandidates(boolean sequential) {
        Probe p = run(sequential, 3, Map.of("rerank.topK", 1));

        assertEquals(2, p.prefuseCap);
        assertEquals(2, p.fuser.limit);
        assertTrue(p.fuser.inputs.stream().allMatch(branch -> branch.size() == 2));
        assertEquals(2, p.output.size());
    }

    @ParameterizedTest(name = "AP3 sequential={0} rerank={1}")
    @org.junit.jupiter.params.provider.CsvSource({"true,8", "false,8", "true,1", "false,1",
            "true,2", "false,2", "true,0", "false,0"})
    void shippedVectorOnlyRerankCapChangesRealFusionMembership(boolean sequential, int requested) throws Exception {
        String planId = "ap3_vec_dense.v1";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("main/resources/plans", planId + ".yaml"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(8, original.path("params").path("rerank_top_k").asInt(-1));
        var modified = original.deepCopy();
        var params = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params");
        if (requested == 0) params.remove("rerank_top_k");
        else params.put("rerank_top_k", requested);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set("rerank_top_k", original.path("params").path("rerank_top_k"));
        assertEquals(original, restored, "only the rerank key changes; vector-only and web-denial caps remain");
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
        var applier = new com.example.lms.plan.PlanHintApplier(requested == 8
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        applier.applyToHintsAndMeta(plan, hints, metadata);
        assertFalse(hints.isAllowWeb());
        assertTrue(hints.isAllowRag());
        assertEquals(15, hints.getVecTopK());
        assertFalse(hints.isEnableCrossEncoder());
        Integer expectedRerank = requested == 0 ? null : requested;
        for (String key : List.of("rerank.topK", "rerankTopK", "rerank_top_k")) {
            assertEquals(expectedRerank, metadata.get(key));
        }
        Probe probe = run(sequential, 3, metadata);
        int expectedLimit = requested == 1 ? 2 : 3;
        assertEquals(requested == 0 ? null : requested * 2, probe.prefuseCap);
        assertEquals(expectedLimit, probe.fuser.limit);
        assertEquals(expectedLimit, probe.output.size());
        assertEquals(2, probe.branchMetadata.size());
        for (var branchMeta : probe.branchMetadata.values()) {
            assertEquals("false", branchMeta.get("allowWeb"));
            assertEquals("true", branchMeta.get("allowRag"));
            assertEquals(expectedRerank, branchMeta.get("rerank.topK"));
            assertEquals(String.valueOf(requested == 0 ? 15 : expectedLimit), branchMeta.get("vecTopK"));
        }
        assertTrue(probe.output.stream().allMatch(c -> probe.fuser.inputs.stream().flatMap(List::stream).anyMatch(x -> x == c)));
        assertTrue(keys(probe.output).contains("aaaaaaaa"));
        if (expectedLimit == 3) assertEquals(Set.of("aaaaaaaa", "bbbbbbbb", "cccccccc"), keys(probe.output));
        System.out.printf("TBL07_AP3_RERANK sequential=%s requested=%d prefuseCap=%d fuserLimit=%d outputCount=%d webDenied=true%n",
                sequential, requested, requested == 0 ? -1 : requested * 2, probe.fuser.limit, probe.output.size());
    }

    @ParameterizedTest(name = "document CE sequential={0} candidate={1}")
    @org.junit.jupiter.params.provider.CsvSource({"true,24", "false,24", "true,25", "false,25",
            "true,1", "false,1", "true,0", "false,0"})
    void shippedDocumentCandidateCapChangesRealFusionInputAndMembership(boolean sequential, int requested)
            throws Exception {
        String planId = "document_evidence.v1";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("main/resources/plans", planId + ".yaml"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(24, original.at("/plan/overrides/knobs").path("rerank.ce.topK").asInt(-1));
        var modified = original.deepCopy();
        var knobs = (com.fasterxml.jackson.databind.node.ObjectNode) modified.at("/plan/overrides/knobs");
        if (requested == 0) knobs.remove("rerank.ce.topK");
        else knobs.put("rerank.ce.topK", requested);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.at("/plan/overrides/knobs"))
                .set("rerank.ce.topK", original.at("/plan/overrides/knobs").path("rerank.ce.topK"));
        assertEquals(original, restored, "only the candidate key changes; keepN and all source caps remain");
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
        var applier = new com.example.lms.plan.PlanHintApplier(requested == 24
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        assertEquals(8, plan.rerankTopK());
        assertEquals(requested == 0 ? null : requested, plan.rerankCeTopK());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        applier.applyToHintsAndMeta(plan, hints, metadata);
        assertTrue(hints.isAllowWeb());
        assertTrue(hints.isAllowRag());
        assertEquals(8, hints.getWebTopK());
        assertEquals(12, hints.getVecTopK());
        assertEquals(3, metadata.get("kgTopK"));
        assertEquals(8, metadata.get("rerank.topK"));
        for (String key : List.of("rerank.ce.topK", "rerank.ceTopK", "rerank_ce_top_k", "rerankCeTopK")) {
            assertEquals(requested == 0 ? null : requested, metadata.get(key));
        }
        int expectedCap = requested == 0 ? 16 : Math.max(8, requested);
        // Two controlled handler result lists total28 distinct members. They are not live source outputs.
        Map<String, List<Content>> offered = new java.util.LinkedHashMap<>();
        for (String branch : List.of("ce-alpha", "ce-beta")) {
            offered.put(branch, java.util.stream.IntStream.rangeClosed(1, 14)
                    .mapToObj(i -> Content.from(TextSegment.from(branch + "-" + i))).toList());
        }
        Map<String, Map<String, Object>> receivedMetadata = new ConcurrentHashMap<>();
        RetrievalHandler handler = (query, accumulator) -> {
            receivedMetadata.put(query.text(), QueryUtils.metadata(query));
            accumulator.addAll(offered.get(query.text()));
        };
        RecordingFuser fuser = new RecordingFuser();
        HybridRetriever retriever = retriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", sequential);
        ReflectionTestUtils.setField(retriever, "maxParallel", 2);
        ReflectionTestUtils.setField(retriever, "maxBranches", 2);
        ReflectionTestUtils.setField(retriever, "hybridRequestTimeoutMs", 10_000L);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        try {
            var output = retriever.retrieveAll(List.copyOf(offered.keySet()), 32,
                    "tbl07-document-candidate-fixture", metadata);
            assertEquals("success", TraceStore.get("hybrid.executor.terminalReason"));
            assertEquals(expectedCap, TraceStore.get("rerank.prefuse.cap"));
            assertEquals(expectedCap, TraceStore.get("rerank.prefuse.limit"));
            assertEquals(1, fuser.calls);
            assertEquals(expectedCap, fuser.limit);
            assertEquals(expectedCap, output.size());
            assertEquals(2, receivedMetadata.size());
            for (var branchMeta : receivedMetadata.values()) {
                assertEquals("true", branchMeta.get("allowWeb"));
                assertEquals("true", branchMeta.get("allowRag"));
                assertEquals("8", branchMeta.get("webTopK"));
                assertEquals(String.valueOf(Math.min(12, expectedCap)), branchMeta.get("vecTopK"));
                assertEquals(3, branchMeta.get("kgTopK"));
                assertEquals(8, branchMeta.get("rerank.topK"));
            }
            int perBranch = Math.min(14, expectedCap);
            assertEquals(List.of(offered.get("ce-alpha").subList(0, perBranch),
                    offered.get("ce-beta").subList(0, perBranch)), fuser.inputs);
            assertTrue(output.stream().allMatch(c -> fuser.inputs.stream().flatMap(List::stream).anyMatch(x -> x == c)));
            Set<String> mustKeep = offered.values().stream().flatMap(list -> list.stream().limit(expectedCap / 2))
                    .map(HybridDppFusionMembershipCharacterizationTest::text).collect(Collectors.toSet());
            Set<String> mayKeep = offered.values().stream().flatMap(list -> list.stream().limit((expectedCap + 1) / 2))
                    .map(HybridDppFusionMembershipCharacterizationTest::text).collect(Collectors.toSet());
            assertTrue(keys(output).containsAll(mustKeep));
            assertTrue(mayKeep.containsAll(keys(output)), "equal-rank tie order is intentionally not assumed");
            System.out.printf("TBL07_CE_CAP sequential=%s requested=%d keep=8 offered=28 cap=%d branchInput=%d outputCount=%d authoredCapsOnlyTightened=true%n",
                    sequential, requested, expectedCap, perBranch, output.size());
        } finally {
            retriever.shutdownRetrievalExecutor();
            TraceStore.clear();
            TimeBudgetContext.clear();
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> shippedRerankBackendControls() throws Exception {
        var applier = new com.example.lms.plan.PlanHintApplier(new org.springframework.core.io.DefaultResourceLoader());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var seen = new java.util.HashSet<String>(); var selected = new java.util.TreeSet<String>();
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        try (var paths = java.nio.file.Files.list(java.nio.file.Path.of("main/resources/plans"))) {
            for (var path : paths.filter(f -> f.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String file = path.getFileName().toString(); var plan = applier.load(file.substring(0, file.length() - 5));
                if (!seen.add(plan.planId()) || plan.rerankBackend() == null) continue;
                var tree = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("main/resources/plans", plan.planId() + ".yaml")));
                assertEquals("auto", tree.path("params").path("rerank_backend").textValue());
                assertEquals("auto", plan.rerankBackend()); selected.add(plan.planId());
                for (String control : List.of("authored", "embedding", "onnx", "noop", "unknown", "removed", "caller",
                        "params_precedence", "meta_dotted", "meta_snake", "meta_camel", "onnx_missing", "runtime_off",
                        "plan_off", "breaker_open", "auto_disabled", "gate_closed", "cooldown", "ce_enabled_auto",
                        "ce_enabled_embedding", "ce_disabled", "all_path"))
                    cases.add(org.junit.jupiter.params.provider.Arguments.of(plan.planId(), control));
            }
        }
        assertEquals(Set.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1"), selected);
        assertEquals(88, cases.size()); return cases.stream();
    }

    @ParameterizedTest(name = "{0} backend control={1}")
    @org.junit.jupiter.params.provider.MethodSource("shippedRerankBackendControls")
    void shippedBackendSelectionAndInvocationUseRetrieveFinalizationWhileRetrieveAllOnlyFuses(
            String planId, String control) throws Exception {
        TraceStore.clear(); TimeBudgetContext.clear();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("main/resources/plans", planId + ".yaml")));
        var modified = original.deepCopy(); var params = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params");
        String requested = switch (control) {
            case "embedding", "ce_enabled_embedding" -> "embedding";
            case "onnx", "onnx_missing", "runtime_off", "plan_off", "breaker_open" -> "onnx";
            case "noop" -> "noop"; case "unknown" -> "unrecognized"; case "removed", "caller" -> null; default -> "auto";
        };
        if (requested == null) params.remove("rerank_backend"); else params.put("rerank_backend", requested);
        if ("params_precedence".equals(control)) params.put("rerank.backend", "noop");
        if (control.startsWith("ce_enabled")) params.put("use_cross_encoder", true);
        if ("ce_disabled".equals(control)) params.put("use_cross_encoder", false);
        var restored = modified.deepCopy(); var restoredParams = (com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params");
        restoredParams.set("rerank_backend", original.path("params").path("rerank_backend"));
        restoredParams.set("use_cross_encoder", original.path("params").path("use_cross_encoder"));
        restoredParams.remove("rerank.backend");
        assertEquals(original, restored, "only declared backend and explicit CE admission controls change");
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
        var applier = new com.example.lms.plan.PlanHintApplier("authored".equals(control)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId); assertEquals(planId, plan.planId());
        String projected = "params_precedence".equals(control) ? "noop" : requested;
        assertEquals(projected, plan.rerankBackend());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if ("caller".equals(control)) metadata.put("rerank.backend", "onnx");
        applier.applyToHintsAndMeta(plan, hints, metadata);
        for (String alias : List.of("rerank.backend", "rerank_backend", "rerankBackend")) {
            assertEquals("caller".equals(control) && alias.equals("rerank.backend") ? "onnx" : projected, metadata.get(alias));
        }
        assertEquals(params.path("use_cross_encoder").booleanValue(), hints.isEnableCrossEncoder());
        assertEquals(String.valueOf(hints.isEnableCrossEncoder()), metadata.get("enableCrossEncoder"));
        if (control.startsWith("meta_")) {
            metadata.put("rerank.backend", "noop"); metadata.put("rerank_backend", "embedding"); metadata.put("rerankBackend", "onnx");
            if (!"meta_dotted".equals(control)) metadata.remove("rerank.backend");
            if ("meta_camel".equals(control)) metadata.remove("rerank_backend");
        }
        if ("plan_off".equals(control)) metadata.put("onnx.enabled", false);
        String effective = switch (control) {
            case "removed" -> "embedding-model"; case "caller", "meta_camel" -> "onnx";
            case "params_precedence", "meta_dotted" -> "noop"; case "meta_snake" -> "embedding"; default -> requested;
        };
        List<Content> offered = List.of(Content.from("cedar marble alpha"), Content.from("violet canoe beta"), Content.from("silver meadow gamma"));
        var received = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        RetrievalHandler handler = (query, accumulator) -> accumulator.addAll(offered);
        RecordingFuser fuser = new RecordingFuser(); HybridRetriever retriever = retriever(handler, fuser);
        var calls = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        Map<String, com.example.lms.service.rag.rerank.CrossEncoderReranker> backends = new java.util.LinkedHashMap<>();
        var reranked = new java.util.concurrent.atomic.AtomicReference<List<Content>>();
        for (String kind : List.of("onnx", "embedding", "noop")) {
            backends.put(kind + "CrossEncoderReranker", (query, candidates, topN) -> {
                calls.merge(kind, 1, Integer::sum); assertEquals(offered, candidates); assertEquals(3, topN);
                var output = new java.util.ArrayList<>(candidates);
                if (kind.equals("onnx")) java.util.Collections.rotate(output, 1);
                else if (kind.equals("embedding")) java.util.Collections.reverse(output);
                else output = new java.util.ArrayList<>(new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker().rerank(query, candidates, topN));
                reranked.set(List.copyOf(output)); return output;
            });
        }
        if ("onnx_missing".equals(control)) backends.remove("onnxCrossEncoderReranker");
        ReflectionTestUtils.setField(retriever, "rerankers", backends);
        ReflectionTestUtils.setField(retriever, "rerankerBackend", "embedding-model");
        ReflectionTestUtils.setField(retriever, "onnxRuntimeEnabled", !"runtime_off".equals(control));
        ReflectionTestUtils.setField(retriever, "onnxAutoSelectionEnabled", !"auto_disabled".equals(control));
        ReflectionTestUtils.setField(retriever, "topK", 3);
        ReflectionTestUtils.setField(retriever, "debugSequential", true);
        ReflectionTestUtils.setField(retriever, "hybridRequestTimeoutMs", 10_000L);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        var ranker = (LightWeightRanker) ReflectionTestUtils.getField(retriever, "lightWeightRanker");
        org.mockito.Mockito.when(ranker.rank(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> call.getArgument(0));
        var constraint = (ElementConstraintScorer) ReflectionTestUtils.getField(retriever, "elementConstraintScorer");
        org.mockito.Mockito.when(constraint.rescore(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> call.getArgument(1));
        var gate = (RerankGate) ReflectionTestUtils.getField(retriever, "rerankGate");
        org.mockito.Mockito.when(gate.shouldRerank(org.mockito.ArgumentMatchers.anyList())).thenReturn(!"gate_closed".equals(control));
        var complexity = (QueryComplexityGate) ReflectionTestUtils.getField(retriever, "gate");
        org.mockito.Mockito.when(complexity.assess(org.mockito.ArgumentMatchers.anyString())).thenReturn(QueryComplexityGate.Level.SIMPLE);
        var rag = (LangChainRAGService) ReflectionTestUtils.getField(retriever, "ragService");
        dev.langchain4j.rag.content.retriever.ContentRetriever vector = query -> {
            received.set(QueryUtils.metadata(query)); return offered;
        };
        org.mockito.Mockito.when(rag.asContentRetriever(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(vector);
        if ("breaker_open".equals(control)) {
            var breaker = mock(com.example.lms.infra.resilience.NightmareBreaker.class);
            org.mockito.Mockito.when(breaker.isOpen(com.example.lms.infra.resilience.NightmareKeys.RERANK_ONNX)).thenReturn(true);
            ReflectionTestUtils.setField(retriever, "nightmareBreaker", breaker);
        }
        if ("cooldown".equals(control)) ReflectionTestUtils.setField(retriever, "cooldownService", mock(com.example.lms.service.redis.RedisCooldownService.class));
        try {
            boolean all = "all_path".equals(control), enabled = hints.isEnableCrossEncoder();
            List<Content> output = all ? retriever.retrieveAll(List.of("controlled branch"), 3, "tbl07-backend", metadata)
                    : retriever.retrieve(QueryUtils.buildQuery("controlled branch", metadata));
            if (all) {
                assertEquals(1, fuser.calls); assertEquals(Set.copyOf(offered), Set.copyOf(output));
                assertNull(TraceStore.get("rerank.backend.selected")); assertNull(TraceStore.get("rerank.ce.executed")); assertTrue(calls.isEmpty());
            } else {
                assertNotNull(received.get()); assertEquals(metadata.get("allowWeb"), received.get().get("allowWeb"));
                assertEquals(metadata.get("enableCrossEncoder"), received.get().get("enableCrossEncoder"));
                String selected, reason = "requested";
                if (effective.equals("auto") || effective.equals("onnx")) {
                    if (!enabled) { selected = "noop"; reason = "cross_encoder_disabled"; }
                    else if (control.equals("onnx_missing")) { selected = "embedding"; reason = "onnx_missing"; }
                    else if (control.equals("runtime_off") || control.equals("plan_off")) { selected = "embedding"; reason = "onnx_not_allowed"; }
                    else if (control.equals("breaker_open")) { selected = "embedding"; reason = "onnx_breaker_open"; }
                    else if (control.equals("auto_disabled")) { selected = "embedding"; reason = "onnx_unusable"; }
                    else selected = "onnx";
                } else if (effective.equals("noop")) selected = "noop";
                else { selected = "embedding"; if (effective.equals("unrecognized")) reason = "unknown_backend"; }
                boolean invoked = enabled && !control.equals("gate_closed") && !control.equals("cooldown");
                assertEquals(effective, TraceStore.get("rerank.backend.requested"));
                assertEquals(selected + "CrossEncoderReranker", TraceStore.get("rerank.backend.selected"));
                assertEquals(reason, TraceStore.get("rerank.backend.fallbackReason"));
                assertEquals(selected.equals("noop"), TraceStore.get("rerank.backend.noopSelected"));
                for (String kind : List.of("onnx", "embedding", "noop")) assertEquals(invoked && kind.equals(selected) ? 1 : 0, calls.getOrDefault(kind, 0));
                assertEquals(invoked, Boolean.TRUE.equals(TraceStore.get("rerank.ce.executed")));
                if (!invoked) assertEquals(!enabled ? "disabled" : control.equals("cooldown") ? "cooldown" : "gate", TraceStore.get("rerank.ce.skipReason"));
                assertEquals(invoked ? reranked.get() : offered, output, "controlled rerank order reaches final output; selection alone does not imply invocation");
            }
            System.out.printf("TBL07_BACKEND plan=%s control=%s ce=%s requested=%s selected=%s reason=%s onnx=%d embedding=%d noop=%d output=%d%n",
                    planId, control, enabled, TraceStore.get("rerank.backend.requested"), TraceStore.get("rerank.backend.selected"),
                    TraceStore.get("rerank.backend.fallbackReason"), calls.getOrDefault("onnx", 0), calls.getOrDefault("embedding", 0), calls.getOrDefault("noop", 0), output.size());
        } finally { retriever.shutdownRetrievalExecutor(); TraceStore.clear(); TimeBudgetContext.clear(); }
    }

    @ParameterizedTest(name = "query sidecar isolation={0}")
    @ValueSource(strings = {"same_text", "different_text", "different_session"})
    void liveQueriesKeepIndependentSidecarsThroughBuildMergeAndRebuild(String control) {
        Map<String, Object> first = new java.util.LinkedHashMap<>(Map.of("rerank.backend", "noop", "enableCrossEncoder", false));
        Map<String, Object> second = new java.util.LinkedHashMap<>(Map.of("rerank.backend", "onnx", "enableCrossEncoder", true));
        if (control.equals("different_session")) {
            first.put(LangChainRAGService.META_SID, "sidecar-alpha"); second.put(LangChainRAGService.META_SID, "sidecar-beta");
        }
        String text = "sidecar isolated marker";
        var q1 = QueryUtils.buildQuery(text, first);
        assertEquals(first, QueryUtils.metadata(q1));
        var q2 = QueryUtils.buildQuery(control.equals("different_text") ? "sidecar other marker" : text, second);
        assertNotSame(q1, q2);
        assertEquals(control.equals("same_text"), q1.equals(q2), "vendor equality is separate from sidecar ownership");
        boolean firstIsolated = first.equals(QueryUtils.metadata(q1));
        boolean secondIsolated = second.equals(QueryUtils.metadata(q2));
        System.out.printf("TBL07_SIDECAR control=%s equal=%s firstIsolated=%s secondIsolated=%s%n",
                control, q1.equals(q2), firstIsolated, secondIsolated);
        assertEquals(first, QueryUtils.metadata(q1), "a second live Query must not replace the first Query's settings");
        assertEquals(second, QueryUtils.metadata(q2));
        QueryUtils.mergeMetadata(q2, Map.of("rerank.backend", "embedding"));
        assertEquals(first, QueryUtils.metadata(q1));
        assertEquals("embedding", QueryUtils.metadata(q2).get("rerank.backend"));
        var rebuilt = QueryUtils.rebuild(q1, text);
        assertNotSame(q1, rebuilt); assertEquals(first, QueryUtils.metadata(rebuilt));
        QueryUtils.mergeMetadata(rebuilt, Map.of("enableCrossEncoder", true));
        assertEquals(first, QueryUtils.metadata(q1), "rebuilding must copy, not share, mutable ownership");
        assertEquals(true, QueryUtils.metadata(rebuilt).get("enableCrossEncoder"));
        var plain = QueryUtils.buildQuery(text);
        assertTrue(QueryUtils.metadata(plain).isEmpty(), "an equal unannotated query must not inherit another sidecar");
        // All objects remain strongly reachable; this is not a GC/lifetime experiment.
        java.lang.ref.Reference.reachabilityFence(q1); java.lang.ref.Reference.reachabilityFence(q2);
        java.lang.ref.Reference.reachabilityFence(rebuilt); java.lang.ref.Reference.reachabilityFence(plain);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> rootOnnxControls() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (String plan : List.of("safe.v1", "rulebreak.v1")) {
            for (String raw : List.of("true", "false", "missing"))
                for (String control : List.of("plan", "caller_false", "caller_true"))
                    cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, raw, control));
            for (String control : List.of("runtime_off", "ce_off", "gate_closed", "legacy_false", "properties_false"))
                cases.add(org.junit.jupiter.params.provider.Arguments.of(plan, "true", control));
        }
        assertEquals(28, cases.size()); return cases.stream();
    }

    @ParameterizedTest(name = "rootOnnx plan={0} raw={1} control={2}")
    @org.junit.jupiter.params.provider.MethodSource("rootOnnxControls")
    @org.junit.jupiter.api.Timeout(10)
    void rootOnnxFlagReachesActualRerankerWhilePreservingExistingOverrideAndRuntimeCaps(
            String planId, String raw, String control) throws Exception {
        TraceStore.clear(); TimeBudgetContext.clear();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("main/resources/plans", planId + ".yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(planId.equals("rulebreak.v1"), original.path("rerank").path("onnx").path("enabled").booleanValue());
        var modified = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        var onnx = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("rerank").path("onnx");
        if (raw.equals("missing")) onnx.remove("enabled"); else onnx.put("enabled", Boolean.parseBoolean(raw));
        if (control.equals("legacy_false")) modified.put("onnx_enabled", false);
        if (control.equals("properties_false")) modified.putObject("plan").putObject("overrides").putObject("properties").put("onnx.enabled", false);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("rerank").path("onnx"))
                .set("enabled", original.path("rerank").path("onnx").path("enabled"));
        restored.remove("onnx_enabled"); restored.remove("plan");
        assertFalse(original.has("onnx_enabled")); assertFalse(original.has("plan"));
        assertEquals(original, restored, "only exact ONNX key and explicitly labelled existing-precedence controls change");
        byte[] yaml = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) return new org.springframework.core.io.ByteArrayResource(yaml) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId); assertEquals(planId, plan.planId()); assertFalse(plan.isEmpty());
        Boolean expectedTyped = control.endsWith("_false") && !control.startsWith("caller") ? Boolean.FALSE
                : raw.equals("missing") ? null : Boolean.valueOf(raw);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (control.startsWith("caller_")) metadata.put("onnx.enabled", control.equals("caller_true"));
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        applier.applyToHintsAndMeta(plan, hints, metadata);
        Boolean expectedEffective = expectedTyped != null ? expectedTyped
                : control.startsWith("caller_") ? Boolean.valueOf(control.equals("caller_true")) : null;
        assertTrue(hints.isEnableCrossEncoder());
        metadata.put("rerank.backend", "onnx");
        if (control.equals("ce_off")) metadata.put("enableCrossEncoder", false);
        List<Content> offered = List.of(Content.from("cedar root alpha"), Content.from("violet root beta"), Content.from("silver root gamma"));
        RetrievalHandler handler = (query, accumulator) -> accumulator.addAll(offered);
        HybridRetriever retriever = retriever(handler, new RecordingFuser());
        var calls = new java.util.LinkedHashMap<String, Integer>();
        Map<String, com.example.lms.service.rag.rerank.CrossEncoderReranker> backends = new java.util.LinkedHashMap<>();
        for (String kind : List.of("onnx", "embedding", "noop")) backends.put(kind + "CrossEncoderReranker", (query, candidates, topN) -> {
            calls.merge(kind, 1, Integer::sum); assertEquals(offered, candidates); assertEquals(3, topN);
            var result = new java.util.ArrayList<>(candidates);
            if (kind.equals("onnx")) java.util.Collections.rotate(result, 1);
            else if (kind.equals("embedding")) java.util.Collections.reverse(result);
            return result;
        });
        ReflectionTestUtils.setField(retriever, "rerankers", backends);
        ReflectionTestUtils.setField(retriever, "rerankerBackend", "embedding-model");
        ReflectionTestUtils.setField(retriever, "onnxRuntimeEnabled", !control.equals("runtime_off"));
        ReflectionTestUtils.setField(retriever, "onnxAutoSelectionEnabled", true);
        ReflectionTestUtils.setField(retriever, "topK", 3);
        var ranker = (LightWeightRanker) ReflectionTestUtils.getField(retriever, "lightWeightRanker");
        org.mockito.Mockito.when(ranker.rank(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(a -> a.getArgument(0));
        var constraint = (ElementConstraintScorer) ReflectionTestUtils.getField(retriever, "elementConstraintScorer");
        org.mockito.Mockito.when(constraint.rescore(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList())).thenAnswer(a -> a.getArgument(1));
        var gate = (RerankGate) ReflectionTestUtils.getField(retriever, "rerankGate");
        org.mockito.Mockito.when(gate.shouldRerank(org.mockito.ArgumentMatchers.anyList())).thenReturn(!control.equals("gate_closed"));
        var complexity = (QueryComplexityGate) ReflectionTestUtils.getField(retriever, "gate");
        org.mockito.Mockito.when(complexity.assess(org.mockito.ArgumentMatchers.anyString())).thenReturn(QueryComplexityGate.Level.SIMPLE);
        var rag = (LangChainRAGService) ReflectionTestUtils.getField(retriever, "ragService");
        var received = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        dev.langchain4j.rag.content.retriever.ContentRetriever vector = query -> { received.set(QueryUtils.metadata(query)); return offered; };
        org.mockito.Mockito.when(rag.asContentRetriever(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(vector);
        boolean ce = !control.equals("ce_off"), invoked = ce && !control.equals("gate_closed");
        String selected = !ce ? "noop" : control.equals("runtime_off") || Boolean.FALSE.equals(expectedEffective) ? "embedding" : "onnx";
        String reason = !ce ? "cross_encoder_disabled" : selected.equals("embedding") ? "onnx_not_allowed" : "requested";
        var expectedOutput = new java.util.ArrayList<>(offered);
        if (invoked && selected.equals("onnx")) java.util.Collections.rotate(expectedOutput, 1);
        else if (invoked && selected.equals("embedding")) java.util.Collections.reverse(expectedOutput);
        try {
            var output = retriever.retrieve(QueryUtils.buildQuery("root ONNX marker", metadata));
            System.out.printf("TBL07_ROOT_ONNX plan=%s raw=%s control=%s typed=%s effective=%s selected=%s onnx=%d embedding=%d noop=%d output=%d externalRequests=0%n",
                    planId, raw, control, plan.onnxEnabled(), metadata.get("onnx.enabled"), TraceStore.get("rerank.backend.selected"),
                    calls.getOrDefault("onnx", 0), calls.getOrDefault("embedding", 0), calls.getOrDefault("noop", 0), output.size());
            assertAll(
                    () -> assertEquals(expectedTyped, plan.onnxEnabled(), "exact root key reaches typed plan hint"),
                    () -> assertEquals(expectedEffective, metadata.get("onnx.enabled"), "typed plan overwrites caller metadata; absent plan preserves caller"),
                    () -> assertEquals(selected + "CrossEncoderReranker", TraceStore.get("rerank.backend.selected")),
                    () -> assertEquals(reason, TraceStore.get("rerank.backend.fallbackReason")),
                    () -> assertEquals(invoked && selected.equals("onnx") ? 1 : 0, calls.getOrDefault("onnx", 0)),
                    () -> assertEquals(invoked && selected.equals("embedding") ? 1 : 0, calls.getOrDefault("embedding", 0)),
                    () -> assertEquals(0, calls.getOrDefault("noop", 0)),
                    () -> assertEquals(invoked, Boolean.TRUE.equals(TraceStore.get("rerank.ce.executed"))),
                    () -> assertEquals(expectedOutput, output, "actual reranker order reaches output independently of selection")
            );
            assertNotNull(received.get()); assertEquals(metadata.get("onnx.enabled"), received.get().get("onnx.enabled"));
            if (!invoked) assertEquals(ce ? "gate" : "disabled", TraceStore.get("rerank.ce.skipReason"));
        } finally { retriever.shutdownRetrievalExecutor(); TraceStore.clear(); TimeBudgetContext.clear(); }
    }
    private static Probe run(boolean sequential, int limit, Map<String, Object> hints) {
        TraceStore.clear();
        TimeBudgetContext.clear();
        Content firstA = Content.from(TextSegment.from("aaaaaaaa"));
        Content duplicateA = Content.from(TextSegment.from("aaaaaaaa"));
        Content b = Content.from(TextSegment.from("bbbbbbbb"));
        Content c = Content.from(TextSegment.from("cccccccc"));
        Map<String, List<Content>> branches = new ConcurrentHashMap<>();
        Map<String, Map<String, Object>> branchMetadata = new ConcurrentHashMap<>();
        RecordingFuser fuser = new RecordingFuser();
        RetrievalHandler handler = (query, accumulator) -> {
            branchMetadata.put(query.text(), QueryUtils.metadata(query));
            List<Content> candidates = query.text().equals("branch-one")
                    ? List.of(firstA, duplicateA, b) : List.of(duplicateA, c);
            // Compose the real DPP owner with the real Hybrid/RRF boundary. This does
            // not certify DynamicRetrievalHandlerChain wiring or downstream CE policy.
            List<Content> selected = new DppDiversityReranker().rerank(
                    new DppDiversityReranker.Config(0.7d, 2), candidates, query.text(), 2,
                    HybridDppFusionMembershipCharacterizationTest::text, item -> 1.0d);
            branches.put(query.text(), List.copyOf(selected));
            accumulator.addAll(selected);
        };
        HybridRetriever retriever = retriever(handler, fuser);
        ReflectionTestUtils.setField(retriever, "debugSequential", sequential);
        ReflectionTestUtils.setField(retriever, "maxParallel", 2);
        ReflectionTestUtils.setField(retriever, "maxBranches", 2);
        ReflectionTestUtils.setField(retriever, "hybridRequestTimeoutMs", 10_000L);
        ReflectionTestUtils.setField(retriever, "fusionMode", "rrf");
        try {
            List<Content> output = retriever.retrieveAll(List.of("branch-one", "branch-two"),
                    limit, "rc11-synthetic-fixture", hints);
            assertEquals("success", TraceStore.get("hybrid.executor.terminalReason"));
            assertEquals(Boolean.TRUE, TraceStore.get("hybrid.executor.fusionSubmitted"));
            assertEquals(1, fuser.calls);
            assertEquals(2, branches.size());
            return new Probe(branches, fuser, output, firstA, TraceStore.get("rerank.prefuse.cap"), branchMetadata);
        } finally {
            retriever.shutdownRetrievalExecutor();
            TraceStore.clear();
            TimeBudgetContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static HybridRetriever retriever(RetrievalHandler handler, ReciprocalRankFuser fuser) {
        return new HybridRetriever(mock(LightWeightRanker.class), mock(RerankGate.class),
                mock(AuthorityScorer.class), handler, fuser, mock(AnswerQualityEvaluator.class),
                mock(SelfAskPlanner.class), mock(RelevanceScoringService.class),
                mock(HyperparameterService.class), mock(ElementConstraintScorer.class),
                mock(QueryTransformer.class), mock(AdaptiveScoringService.class),
                mock(KnowledgeBaseService.class), mock(NeuralPathFormationService.class),
                mock(SelfAskWebSearchRetriever.class), mock(AnalyzeWebSearchRetriever.class),
                mock(WebSearchRetriever.class), mock(QueryComplexityGate.class), mock(LangChainRAGService.class),
                mock(EmbeddingModel.class), mock(EmbeddingStore.class), mock(GameDomainDetector.class));
    }

    private static Set<String> keys(List<Content> contents) {
        return contents.stream().map(HybridDppFusionMembershipCharacterizationTest::text).collect(Collectors.toSet());
    }

    private static String text(Content content) {
        return content.textSegment().text();
    }

    private record Probe(Map<String, List<Content>> branches, RecordingFuser fuser,
                         List<Content> output, Content firstA, Object prefuseCap,
                         Map<String, Map<String, Object>> branchMetadata) { }

    private static class RecordingFuser extends ReciprocalRankFuser {
        volatile List<List<Content>> inputs;
        volatile int limit;
        volatile int calls;

        @Override
        public List<Content> fuse(List<List<Content>> lists, int topK) {
            inputs = lists.stream().map(List::copyOf).toList();
            limit = topK;
            calls++;
            return super.fuse(lists, topK);
        }
    }
}
