package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.filter.EducationDocClassifier;
import com.example.lms.service.rag.filter.GenericDocClassifier;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSearchRetrieverTraceStandardizationTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"AUTH_OR_CONFIG,auth_or_config", "RATE_LIMIT,rate_limit",
            "TIMEOUT_OR_BUDGET,timeout_or_budget", "PARSE_ERROR,parse_error"})
    void canonicalZeroResultPreservesProviderFailureCategory(String upstream, String expected) {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.failureClass", upstream);
            return List.of();
        });
        WebSearchRetriever retriever = minimalRetriever(provider);
        assertTrue(retriever.retrieve(QueryUtils.buildQuery("synthetic search", Map.of("webTopK", 1))).isEmpty());
        assertEquals(expected, TraceStore.get("webSearch.failureClass"));
    }

    @Test
    void unchangedQueryDoesNotCauseASecondCourtesyCall() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenReturn(List.of());
        minimalRetriever(provider).retrieve(QueryUtils.buildQuery("synthetic empty", Map.of("webTopK", 1)));
        verify(provider, org.mockito.Mockito.times(1)).search(anyString(), anyInt());
        assertEquals(1, TraceStore.get("webSearch.providerAttempts"));
    }

    @Test
    void courtesyFallbackFailurePreservesPreviouslyRetrievedEvidence() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenAnswer(invocation -> {
            if (((String) invocation.getArgument(0)).contains("님")) return freshEvidence("fresh.example").subList(0, 1);
            throw org.springframework.web.reactive.function.client.WebClientResponseException.create(
                    401, "synthetic", org.springframework.http.HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        });
        List<?> out = minimalRetriever(provider).retrieve(QueryUtils.buildQuery("교수님 설명", Map.of("webTopK", 1)));
        assertFalse(out.isEmpty());
        assertEquals("none", TraceStore.get("webSearch.failureClass"));
        assertEquals("auth_or_config", TraceStore.get("webSearch.providerFailureClass"));
        assertEquals(2, TraceStore.get("webSearch.providerAttempts"));
    }

    @Test
    void successfulZeroAndSuccessfulEvidenceClearPriorCanonicalFailure() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenReturn(List.of());
        var retriever = minimalRetriever(provider);
        TraceStore.put("webSearch.failureClass", "auth_or_config");
        assertTrue(retriever.retrieve(QueryUtils.buildQuery("synthetic empty", Map.of("webTopK", 1))).isEmpty());
        assertEquals("provider_empty", TraceStore.get("webSearch.failureClass"));
        TraceStore.put("web.failureClass", "AUTH_OR_CONFIG");
        when(provider.search(anyString(), anyInt())).thenReturn(freshEvidence("fresh.example"));
        retriever.retrieve(QueryUtils.buildQuery("synthetic success", Map.of("webTopK", 1)));
        assertEquals("none", TraceStore.get("webSearch.failureClass"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} declaredBudget={1}")
    @org.junit.jupiter.params.provider.CsvSource({"kg_first.v1,true", "kg_first.v1,false",
            "ap9_cost_saver.v1,true", "ap9_cost_saver.v1,false"})
    void shippedPlanWebBudgetCapsActualCourtesyCalls(String planId, boolean retainDeclaredBudget) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var declared = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        long total = declared.has("budgets") ? declared.path("budgets").path("total_ms").asLong(-1)
                : declared.path("params").path("budget_ms").asLong(-1);
        long web = declared.path("budgets").path("web_ms").asLong(total);
        assertTrue(total > 0 && web > 0 && web <= 1500, "selected plans use a positive one-attempt web cap");
        var applier = new com.example.lms.plan.PlanHintApplier(new org.springframework.core.io.DefaultResourceLoader());
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        assertEquals(web, plan.webBudgetMs());
        assertEquals(total, plan.vecBudgetMs());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        applier.applyToHintsAndMeta(plan, hints, metadata);
        assertTrue(hints.isAllowWeb());
        assertEquals(web, hints.getWebBudgetMs());
        assertEquals(total, hints.getVecBudgetMs());
        assertEquals(web, metadata.get("webBudgetMs"));
        assertEquals(total, metadata.get("vecBudgetMs"));
        // The control removes only the projected web cap; all other plan metadata stays intact.
        if (!retainDeclaredBudget) metadata.remove("webBudgetMs");
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenReturn(freshEvidence("fresh.example").subList(0, 1));
        var out = minimalRetriever(provider).retrieve(QueryUtils.buildQuery("교수님 설명", metadata));
        int expectedCalls = retainDeclaredBudget ? 1 : 2;
        assertFalse(out.isEmpty());
        verify(provider, org.mockito.Mockito.times(expectedCalls)).search(anyString(), anyInt());
        assertEquals(expectedCalls, TraceStore.get("webSearch.providerAttempts"));
        assertEquals(total, metadata.get("vecBudgetMs"));
        System.out.printf("TBL07_BUDGET plan=%s declaredBudget=%s providerCalls=%d vectorProjection=true%n",
                planId, retainDeclaredBudget, expectedCalls);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> shippedWebTopKDeclarations()
            throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:plans/*.yaml");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var applier = new com.example.lms.plan.PlanHintApplier(resolver);
        var selected = new java.util.TreeMap<String, String[]>();
        var aliases = new java.util.TreeSet<String>();
        // Current authored aliases in loader precedence order; dotted property names remain literal.
        var aliasPaths = List.of(new String[]{"/retrieval/k", "web"},
                new String[]{"/retrieval/topk", "web"},
                new String[]{"/plan/overrides/properties", "naver.search.web-top-k"},
                new String[]{"/params", "webTopK"});
        try {
            for (var resource : resources) {
                String filename = resource.getFilename();
                org.junit.jupiter.api.Assertions.assertNotNull(filename);
                var plan = applier.load(filename.substring(0, filename.length() - ".yaml".length()));
                if (plan.webTopK() == null || selected.containsKey(plan.planId())) continue;
                com.fasterxml.jackson.databind.JsonNode declared;
                try (var input = resolver.getResource("classpath:plans/" + plan.planId() + ".yaml").getInputStream()) {
                    declared = mapper.readTree(input);
                }
                int declarations = 0;
                for (String[] alias : aliasPaths) {
                    if (declared.at(alias[0]).path(alias[1]).isMissingNode()) continue;
                    declarations++;
                    aliases.add(alias[0] + "/" + alias[1]);
                    assertEquals(plan.webTopK().intValue(), declared.at(alias[0]).path(alias[1]).asInt(-1));
                    selected.put(plan.planId(), new String[]{plan.planId(), alias[0], alias[1]});
                }
                assertEquals(1, declarations, "each current selected plan has one unshadowed authored webTopK");
            }
        } finally { TraceStore.clear(); }
        assertEquals(19, resources.length, "current test-classpath inventory");
        assertEquals(java.util.Set.of("ap11_finance_special.v1", "ap1_auth_web.v1", "brave.v1",
                "document_evidence.v1", "kg_first.v1", "safe.v1", "safe_autorun.v1", "zero100.v1",
                "zero_break.v1"), selected.keySet());
        assertEquals(4, aliases.size());
        System.out.printf("TBL07_WEBTOPK_INVENTORY classpath=%d selectedPlans=%d declarations=%d aliases=%d%n",
                resources.length, selected.size(), selected.size(), aliases.size());
        return selected.values().stream().flatMap(plan -> java.util.stream.Stream.of("authored", "below", "above", "denied")
                .map(band -> org.junit.jupiter.params.provider.Arguments.of(plan[0], plan[1], plan[2], band)));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} webTopK={3}")
    @org.junit.jupiter.params.provider.MethodSource("shippedWebTopKDeclarations")
    void shippedWebTopKReachesActualProviderArgument(String planId, String section, String key, String band)
            throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        int authored = original.at(section).path(key).asInt(-1);
        assertTrue(authored > 0 && authored <= 100);
        int requested = "below".equals(band) ? 1 : "above".equals(band) ? 5 : authored;
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.at(section)).put(key, requested);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.at(section)).set(key, original.at(section).path(key));
        assertEquals(original, restored, "only the selected authored key changes");
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
        var applier = new com.example.lms.plan.PlanHintApplier(requested == authored
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        assertEquals(requested, plan.webTopK());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        boolean denied = "denied".equals(band);
        // This separate request cap must survive application of every unchanged authored plan.
        if (denied) hints.setAllowWeb(false);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        applier.applyToHintsAndMeta(plan, hints, metadata);
        assertEquals(!denied, hints.isAllowWeb());
        assertEquals(String.valueOf(!denied), metadata.get("allowWeb"));
        assertEquals(requested, hints.getWebTopK());
        assertEquals(String.valueOf(requested), metadata.get("webTopK"));
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenReturn(List.of());
        assertTrue(minimalRetriever(provider).retrieve(QueryUtils.buildQuery("synthetic topk", metadata)).isEmpty());
        int expectedCalls = denied ? 0 : 1;
        int expectedArgument = 2 * Math.max(3, requested);
        verify(provider, org.mockito.Mockito.times(expectedCalls)).search(anyString(), anyInt());
        if (!denied) verify(provider).search(anyString(), eq(expectedArgument));
        assertEquals(expectedCalls, TraceStore.get("webSearch.providerAttempts"));
        System.out.printf("TBL07_WEBTOPK plan=%s band=%s requested=%d providerArgument=%d providerCalls=%d%n",
                planId, band, requested, denied ? -1 : expectedArgument, expectedCalls);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> shippedRerankTopKDeclarations()
            throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:plans/*.yaml");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var applier = new com.example.lms.plan.PlanHintApplier(resolver);
        var selected = new java.util.TreeMap<String, String[]>();
        var aliases = new java.util.TreeSet<String>();
        var aliasPaths = List.of(new String[]{"/plan/overrides/knobs", "rerank.topK"},
                new String[]{"/params", "rerank_top_k"});
        try {
            for (var resource : resources) {
                String filename = resource.getFilename();
                org.junit.jupiter.api.Assertions.assertNotNull(filename);
                var plan = applier.load(filename.substring(0, filename.length() - ".yaml".length()));
                if (plan.rerankTopK() == null || selected.containsKey(plan.planId())) continue;
                com.fasterxml.jackson.databind.JsonNode declared;
                try (var input = resolver.getResource("classpath:plans/" + plan.planId() + ".yaml").getInputStream()) {
                    declared = mapper.readTree(input);
                }
                int declarations = 0;
                for (String[] alias : aliasPaths) {
                    if (declared.at(alias[0]).path(alias[1]).isMissingNode()) continue;
                    declarations++;
                    aliases.add(alias[0] + "/" + alias[1]);
                    assertEquals(plan.rerankTopK().intValue(), declared.at(alias[0]).path(alias[1]).asInt(-1));
                    selected.put(plan.planId(), new String[]{plan.planId(), alias[0], alias[1]});
                }
                assertEquals(1, declarations, "one unshadowed current rerank declaration per selected plan");
            }
        } finally { TraceStore.clear(); }
        assertEquals(19, resources.length);
        assertEquals(java.util.Set.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1",
                "ap9_cost_saver.v1", "document_evidence.v1"), selected.keySet());
        assertEquals(2, aliases.size());
        return selected.values().stream().flatMap(plan -> java.util.stream.Stream.of(
                        "authored_at", "authored_below", "removed", "floor_at", "floor_below")
                .map(band -> org.junit.jupiter.params.provider.Arguments.of(plan[0], plan[1], plan[2], band)));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} rerankReuse={3}")
    @org.junit.jupiter.params.provider.MethodSource("shippedRerankTopKDeclarations")
    void shippedRerankTopKChangesActualPrefetchReuseWithoutRelaxingWebCaps(
            String planId, String section, String key, String band) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        int authored = original.at(section).path(key).asInt(-1);
        assertTrue(authored > 0);
        var modified = original.deepCopy();
        boolean removed = "removed".equals(band);
        boolean floor = band.startsWith("floor_");
        var owner = (com.fasterxml.jackson.databind.node.ObjectNode) modified.at(section);
        if (removed) owner.remove(key);
        else if (floor) owner.put(key, 1);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.at(section)).set(key, original.at(section).path(key));
        assertEquals(original, restored, "only the authored rerank key changes; request caps stay intact");
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
        var applier = new com.example.lms.plan.PlanHintApplier(!removed && !floor
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        Integer expectedRerank = removed ? null : floor ? 1 : authored;
        assertEquals(expectedRerank, plan.rerankTopK());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        applier.applyToHintsAndMeta(plan, hints, metadata);
        boolean denied = "ap3_vec_dense.v1".equals(planId);
        assertEquals(!denied, hints.isAllowWeb());
        assertEquals(String.valueOf(!denied), metadata.get("allowWeb"));
        for (String alias : List.of("rerank.topK", "rerankTopK", "rerank_top_k")) {
            assertEquals(expectedRerank, metadata.get(alias));
        }
        int web = switch (planId) {
            case "ap11_finance_special.v1" -> 20;
            case "ap1_auth_web.v1" -> 12;
            case "document_evidence.v1" -> 8;
            default -> 5;
        };
        assertEquals(web, hints.getWebTopK());
        int authoredThreshold = switch (planId) {
            case "ap11_finance_special.v1" -> 12;
            case "ap1_auth_web.v1" -> 10;
            case "document_evidence.v1" -> 8;
            default -> 5;
        };
        int expectedThreshold = floor ? 3 : removed ? web : authoredThreshold;
        int available = floor ? 3 : authoredThreshold;
        if (band.endsWith("_below")) available--;
        String query = "synthetic rerank site:docs.example";
        metadata.put("prefetch.web.query", query);
        metadata.put("prefetch.web.queryHash", com.example.lms.trace.SafeRedactor.hashValue(query));
        metadata.put("prefetch.web.snippets", java.util.stream.IntStream.rangeClosed(1, available)
                .mapToObj(i -> "https://docs.example/" + i + " - synthetic evidence " + i).toList());
        if (metadata.containsKey("webProviders")) metadata.put("prefetch.web.providerScope", metadata.get("webProviders"));
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenReturn(List.of());
        var out = minimalRetriever(provider).retrieve(QueryUtils.buildQuery(query, metadata));
        int expectedCalls = denied || available >= expectedThreshold ? 0 : 1;
        verify(provider, org.mockito.Mockito.times(expectedCalls)).search(anyString(), anyInt());
        assertEquals(expectedCalls, TraceStore.get("webSearch.providerAttempts"));
        if (denied) {
            assertTrue(out.isEmpty());
            org.junit.jupiter.api.Assertions.assertNull(TraceStore.get("webSearch.siteFilterMinDocsToSkipSearch"));
        } else {
            assertEquals(Boolean.TRUE, TraceStore.get("webSearch.prefetch.identityMatch"));
            assertEquals(expectedThreshold, TraceStore.get("webSearch.siteFilterMinDocsToSkipSearch"));
            assertEquals(expectedCalls == 0 ? "skip" : "seed", TraceStore.get("webSearch.siteFilterReuseMode"));
        }
        System.out.printf("TBL07_RERANK_REUSE plan=%s band=%s authored=%d rerank=%d web=%d available=%d threshold=%d providerCalls=%d denied=%s%n",
                planId, band, authored, expectedRerank == null ? -1 : expectedRerank, web, available,
                denied ? -1 : expectedThreshold, expectedCalls, denied);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "recency schedule={0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"authored", "first_low", "first_high", "tail_changed",
            "removed", "empty", "zero_first", "zero_only", "null_first", "null_only", "negative_first",
            "plan_explicit", "caller_only", "metadata_only", "nested_alias", "nested_empty", "web_denied"})
    void shippedScheduleControlsInitialProviderArgumentWithExplicitPlanPrecedence(String control) throws Exception {
        String planId = "recency_first.v1";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"), StandardCharsets.UTF_8));
        assertEquals(mapper.valueToTree(List.of(16, 12, 8)), original.path("k_schedule"));
        var modified = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        List<Integer> expectedSchedule = switch (control) {
            case "first_low" -> List.of(1, 12, 8); case "first_high" -> List.of(20, 12, 8);
            case "tail_changed" -> List.of(16, 1, 30); case "zero_first" -> List.of(0, 12, 8);
            case "zero_only" -> List.of(0); case "null_first" -> List.of(12, 8);
            case "negative_first" -> List.of(-1, 12, 8); case "nested_alias" -> List.of(5, 4);
            case "removed", "empty", "null_only" -> List.of(); default -> List.of(16, 12, 8);
        };
        if (control.equals("removed")) modified.remove("k_schedule");
        else if (control.equals("null_first")) modified.set("k_schedule", mapper.readTree("[null,12,8]"));
        else if (control.equals("null_only")) modified.set("k_schedule", mapper.readTree("[null]"));
        else if (!control.startsWith("nested_")) modified.set("k_schedule", mapper.valueToTree(expectedSchedule));
        if (control.equals("nested_alias")) modified.putObject("retrieval").set("k_schedule", mapper.valueToTree(List.of(5, 4)));
        if (control.equals("nested_empty")) modified.putObject("retrieval").set("k_schedule", mapper.valueToTree(List.of()));
        if (control.equals("plan_explicit")) modified.putObject("retrieval").putObject("topk").put("web", 5);
        var restored = modified.deepCopy(); restored.set("k_schedule", original.path("k_schedule")); restored.remove("retrieval");
        assertEquals(original, restored, "only schedule or the explicit precedence control changes");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) return new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(control.equals("authored")
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId); assertEquals(planId, plan.planId()); assertEquals(expectedSchedule, plan.kSchedule());
        assertEquals(control.equals("plan_explicit") ? Integer.valueOf(5) : null, plan.webTopK());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        if (!control.equals("authored")) hints.setWebTopK(7);
        boolean denied = control.equals("web_denied"); if (denied) hints.setAllowWeb(false);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (control.equals("metadata_only")) metadata.put("webTopK", "9");
        applier.applyToHintsAndMeta(plan, hints, metadata);
        int expectedRequested = switch (control) {
            case "first_low" -> 1; case "first_high" -> 20; case "null_first" -> 12;
            case "plan_explicit", "nested_alias" -> 5;
            case "removed", "empty", "zero_first", "zero_only", "null_only", "negative_first" -> 7;
            default -> 16;
        };
        assertEquals(expectedRequested, hints.getWebTopK()); assertEquals(String.valueOf(expectedRequested), metadata.get("webTopK"));
        assertEquals(expectedSchedule.isEmpty() ? null : expectedSchedule, metadata.get("kSchedule"));
        assertEquals(!denied, hints.isAllowWeb()); assertEquals(String.valueOf(!denied), metadata.get("allowWeb"));
        WebSearchProvider provider = mock(WebSearchProvider.class); when(provider.search(anyString(), anyInt())).thenReturn(List.of());
        assertTrue(minimalRetriever(provider).retrieve(QueryUtils.buildQuery("synthetic schedule", metadata)).isEmpty());
        int calls = denied ? 0 : 1; int argument = 2 * Math.max(3, expectedRequested);
        verify(provider, org.mockito.Mockito.times(calls)).search(anyString(), anyInt());
        if (!denied) verify(provider).search(anyString(), eq(argument));
        assertEquals(calls, TraceStore.get("webSearch.providerAttempts"));
        System.out.printf("TBL07_SCHEDULE control=%s normalized=%s requested=%d providerArgument=%d providerCalls=%d%n",
                control, expectedSchedule, expectedRequested, denied ? -1 : argument, calls);
    }

    private static WebSearchRetriever minimalRetriever(WebSearchProvider provider) {
        when(provider.getName()).thenReturn("test");
        GameDomainDetector detector = mock(GameDomainDetector.class);
        when(detector.detect(anyString())).thenReturn("GENERAL");
        return new WebSearchRetriever(provider, null, mock(PageContentScraper.class),
                mock(AuthorityScorer.class), mock(GenericDocClassifier.class), detector, mock(EducationDocClassifier.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"401,auth_or_config", "429,rate_limit", "504,timeout_or_budget"})
    void thrownHttpFailureIsNotReportedAsGenuineEmpty(int status, String expected) {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenThrow(
                org.springframework.web.reactive.function.client.WebClientResponseException.create(
                        status, "synthetic upstream failure", org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8));
        assertTrue(minimalRetriever(provider).retrieve(
                QueryUtils.buildQuery("synthetic failure", Map.of("webTopK", 1))).isEmpty());
        assertEquals(expected, TraceStore.get("webSearch.failureClass"));
    }

    @Test
    void braveHttpStatusRefinesItsGenericHttpErrorReason() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenAnswer(invocation -> {
            TraceStore.put("web.brave.failureReason", "http-error");
            TraceStore.put("web.brave.httpStatus", 401);
            return List.of();
        });
        minimalRetriever(provider).retrieve(QueryUtils.buildQuery("synthetic auth", Map.of("webTopK", 1)));
        assertEquals("auth_or_config", TraceStore.get("webSearch.failureClass"));
    }

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void webSearchRetrieverTraceSuppressionsNormalizeNumericErrorType() {
        WebSearchRetrieverTraceSuppressions.trace(
                "metaInt.parse",
                new NumberFormatException("ownerToken=raw-secret"));

        assertEquals("invalid_number", TraceStore.get("webSearch.suppressed.metaInt.parse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("ownerToken=raw-secret"), trace);
    }

    @Test
    void webSearchRetrieverTraceSuppressionsIncludeSafeAggregateStageAndErrorType() {
        String rawStage = "metaInt.parse " + com.example.lms.test.SecretFixtures.openAiKey();

        WebSearchRetrieverTraceSuppressions.trace(
                rawStage,
                new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("webSearch.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("webSearch.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("webSearch.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("webSearch.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    @Test
    void disabledReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"webSearch.disabledReason\", disabledReason);"));
        assertTrue(source.contains(
                "TraceStore.put(\"webSearch.disabledReason\", SafeRedactor.traceLabelOrFallback(disabledReason, \"unknown\"));"));
    }

    @Test
    void webSearchRetrieverDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "WebSearchRetriever fail-soft paths need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void webSearchRetrieverRetrievalFallbacksLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertWebSearchStage(source, "multiSearch.supplemental");
        assertWebSearchStage(source, "educationClassifier.filter");
        assertWebSearchStage(source, "providerName");
        assertWebSearchStage(source, "authorityMin.filter");
        assertWebSearchStage(source, "traceWebSearchCounts");
    }

    @Test
    void webSearchRetrieverUtilityFallbacksLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertWebSearchStage(source, "retry.skippedCount");
        assertWebSearchStage(source, "retry.awaitCounters");
        assertWebSearchStage(source, "retry.sleepInterrupted");
        assertWebSearchStage(source, "extractUrl.href");
        assertWebSearchStage(source, "extractUrl.http");
        assertWebSearchStage(source, "serpCache.getCast");
        assertWebSearchStage(source, "urlMatchesAnySite.uri");
        assertWebSearchStage(source, "urlMatchesAnySite.fallback");
    }

    @Test
    void scrapeFailureLogUsesUrlDiagnosticSummary() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.debug(\"[WebSearchRetriever] scrape fail {}"));
        assertFalse(source.contains("log.debug(\"[WebSearchRetriever] scrape fail url={}"));
        assertFalse(source.contains("SafeRedactor.diagnosticValue(\"webSearch.scrape.url\", url"));
        assertTrue(source.contains("scrape fail urlPresent={} urlHash12={} urlLength={}"));
        assertTrue(source.contains("SafeRedactor.hash12(url)"));
        assertTrue(source.contains("url == null ? 0 : url.length()"));
    }

    @Test
    void metadataDebugLogUsesPurposeSummaryOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.debug(\"[WebSearch][meta] purpose={}, keys={}\", meta.get(\"purpose\"), meta.keySet());"));
        assertTrue(source.contains("log.debug(\"[WebSearch][meta] purposeHash12={} purposeLength={} keyCount={}"));
        assertTrue(source.contains("String purposeText = purpose == null ? null : String.valueOf(purpose);"));
        assertTrue(source.contains("SafeRedactor.hash12(purposeText)"));
        assertTrue(source.contains("purposeText == null ? 0 : purposeText.length()"));
        assertTrue(source.contains("meta.size()"));
    }

    @Test
    void metadataNumberParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("catch (Exception ignore) { WebSearchRetrieverTraceSuppressions.trace(\"metaInt.parse\""));
        assertFalse(source.contains("catch (Exception ignore) { WebSearchRetrieverTraceSuppressions.trace(\"metaLong.parse\""));
        assertFalse(source.contains("catch (Exception ignore) { WebSearchRetrieverTraceSuppressions.trace(\"metaDouble.parse\""));
        assertTrue(source.contains("catch (NumberFormatException ignore)"));
        assertTrue(source.contains("WebSearchRetrieverTraceSuppressions.trace(\"metaInt.parse\""));
        assertTrue(source.contains("WebSearchRetrieverTraceSuppressions.trace(\"metaLong.parse\""));
        assertTrue(source.contains("WebSearchRetrieverTraceSuppressions.trace(\"metaDouble.parse\""));
        assertWebSearchInvalidNumberStage(source, "metaInt.parse");
        assertWebSearchInvalidNumberStage(source, "metaLong.parse");
        assertWebSearchInvalidNumberStage(source, "metaDouble.parse");
    }

    @Test
    void metadataNumberParsersDropNonFiniteNumbers() throws Exception {
        Method metaInt = WebSearchRetriever.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        Method metaLong = WebSearchRetriever.class.getDeclaredMethod(
                "metaLong", Map.class, String.class, long.class);
        Method metaDouble = WebSearchRetriever.class.getDeclaredMethod(
                "metaDouble", Map.class, String.class, double.class);
        Method metaBool = WebSearchRetriever.class.getDeclaredMethod(
                "metaBool", Map.class, String.class, boolean.class);
        metaInt.setAccessible(true);
        metaLong.setAccessible(true);
        metaDouble.setAccessible(true);
        metaBool.setAccessible(true);

        Map<String, Object> meta = Map.of(
                "topK", Double.POSITIVE_INFINITY,
                "timeoutMs", Double.NEGATIVE_INFINITY,
                "minScore", Double.NaN,
                "enabled", Double.POSITIVE_INFINITY,
                "stringScore", "Infinity");
        assertEquals(5, metaInt.invoke(null, meta, "topK", 5));
        assertEquals(1200L, metaLong.invoke(null, meta, "timeoutMs", 1200L));
        assertEquals(0.25d, (Double) metaDouble.invoke(null, meta, "minScore", 0.25d), 0.0d);
        assertEquals(0.25d, (Double) metaDouble.invoke(null, meta, "stringScore", 0.25d), 0.0d);
        assertFalse((Boolean) metaBool.invoke(null, meta, "enabled", false));
    }

    @Test
    void preprocessorFailureLogUsesStructuredSafeDiagnostics() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("[WebSearchRetriever] preprocessor failed: {}"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains(
                "[AWX][rag][web] preprocessor failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(source.contains("\"web-preprocessor-error\""));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hash12(normalized)"));
        assertTrue(source.contains("normalized == null ? 0 : normalized.length()"));
    }

    private static void assertWebSearchStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[WebSearchRetriever] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing WebSearchRetriever fail-soft stage: " + stage);
    }

    private static void assertWebSearchInvalidNumberStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[WebSearchRetriever] fail-soft stage={} errorType={}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing WebSearchRetriever invalid_number fail-soft stage: " + stage);
    }

    @Test
    void authorityFilterStarvationBridgesCanonicalWebSearchAndRagCounts() {
        TraceStore.clear();
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(anyString(), anyInt())).thenReturn(List.of("alpha evidence", "beta evidence", "gamma evidence"));
        when(provider.getName()).thenReturn("test");

        AuthorityScorer authorityScorer = mock(AuthorityScorer.class);
        when(authorityScorer.weightFor(anyString())).thenReturn(0.0d);
        GenericDocClassifier genericClassifier = mock(GenericDocClassifier.class);
        GameDomainDetector domainDetector = mock(GameDomainDetector.class);
        when(domainDetector.detect(anyString())).thenReturn("GENERAL");

        WebSearchRetriever retriever = new WebSearchRetriever(
                provider,
                null,
                mock(PageContentScraper.class),
                authorityScorer,
                genericClassifier,
                domainDetector,
                mock(EducationDocClassifier.class));

        List<?> result = retriever.retrieve(QueryUtils.buildQuery("bridge starvation", Map.of(
                "web.authorityMin", 0.8d,
                "web.authorityMin.strict", true,
                "webTopK", 3)));

        assertTrue(result.isEmpty());
        assertEquals(3, TraceStore.get("webSearch.returnedCount"));
        assertEquals(0, TraceStore.get("webSearch.afterFilterCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("webSearch.afterFilterStarved"));
        assertEquals(3, TraceStore.get("rag.returnedCount"));
        assertEquals(0, TraceStore.get("rag.afterFilterCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.afterFilterStarved"));
    }

    @Test
    void forceLightModeRejectsPrefetchWhenCanonicalQueryDiffers() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(eq("rewritten final query"), anyInt())).thenReturn(freshEvidence("fresh.example"));
        when(provider.getName()).thenReturn("test");

        WebSearchRetriever retriever = retriever(provider);
        String prefetchQuery = "private original query b02";
        String finalQuery = "rewritten final query";

        List<?> result = retriever.retrieve(QueryUtils.buildQuery(finalQuery, Map.of(
                "searchMode", "FORCE_LIGHT",
                "prefetch.web.query", prefetchQuery,
                "prefetch.web.queryHash", com.example.lms.trace.SafeRedactor.hashValue(prefetchQuery),
                "prefetch.web.snippets", List.of("https://prefetch.example/light - prefetched-b02"),
                "webTopK", 3)));

        assertFalse(result.isEmpty());
        verify(provider).search(eq(finalQuery), anyInt());
        assertFalse(String.valueOf(result).contains("prefetched-b02"));
        assertEquals(Boolean.FALSE, TraceStore.get("webSearch.prefetch.identityMatch"));
        assertEquals("query_mismatch", TraceStore.get("webSearch.prefetch.mismatchReason"));
        assertEquals("fresh_search", TraceStore.get("webSearch.prefetch.decision"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(prefetchQuery), trace);
        assertFalse(trace.contains(finalQuery), trace);
    }

    @Test
    void canonicalEquivalentPrefetchKeepsForceLightReuse() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.getName()).thenReturn("test");
        WebSearchRetriever retriever = retriever(provider);

        List<?> result = retriever.retrieve(QueryUtils.buildQuery("  SAME   query  ", Map.of(
                "searchMode", "FORCE_LIGHT",
                "prefetch.web.query", "same query",
                "prefetch.web.queryHash", com.example.lms.trace.SafeRedactor.hashValue("same query"),
                "prefetch.web.snippets", List.of("https://docs.example/light - canonical evidence"),
                "webTopK", 3)));

        assertFalse(result.isEmpty());
        verify(provider, never()).search(anyString(), anyInt());
        assertEquals(Boolean.TRUE, TraceStore.get("webSearch.prefetch.identityMatch"));
        assertEquals("reuse", TraceStore.get("webSearch.prefetch.decision"));
    }

    @Test
    void siteScopeMismatchCannotReuseMatchingHostSnippets() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(eq("topic site:b.example"), anyInt())).thenReturn(freshEvidence("fresh.example"));
        when(provider.getName()).thenReturn("test");
        WebSearchRetriever retriever = retriever(provider);

        List<?> result = retriever.retrieve(QueryUtils.buildQuery("topic site:b.example", Map.of(
                "searchMode", "FORCE_LIGHT",
                "prefetch.web.query", "topic site:a.example",
                "prefetch.web.queryHash", com.example.lms.trace.SafeRedactor.hashValue("topic site:a.example"),
                "prefetch.web.snippets", List.of(
                        "https://b.example/1 - wrong-scope-one",
                        "https://b.example/2 - wrong-scope-two",
                        "https://b.example/3 - wrong-scope-three"),
                "siteFilter.minDocsToSkipSearch", 1,
                "webTopK", 3)));

        assertFalse(result.isEmpty());
        verify(provider).search(eq("topic site:b.example"), anyInt());
        assertFalse(String.valueOf(result).contains("wrong-scope"));
        assertEquals("site_scope_mismatch", TraceStore.get("webSearch.prefetch.mismatchReason"));
    }

    @Test
    void providerScopeMismatchCannotReuseExactQueryPrefetch() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(eq("provider scoped query"), anyInt())).thenReturn(freshEvidence("fresh.example"));
        when(provider.getName()).thenReturn("test");
        WebSearchRetriever retriever = retriever(provider);

        retriever.retrieve(QueryUtils.buildQuery("provider scoped query", Map.of(
                "searchMode", "FORCE_LIGHT",
                "webProviders", List.of("BRAVE"),
                "prefetch.web.providerScope", List.of("NAVER"),
                "prefetch.web.query", "provider scoped query",
                "prefetch.web.queryHash", com.example.lms.trace.SafeRedactor.hashValue("provider scoped query"),
                "prefetch.web.snippets", List.of("https://prefetch.example/provider - wrong provider"),
                "webTopK", 3)));

        verify(provider).search(eq("provider scoped query"), anyInt());
        assertEquals("provider_scope_mismatch", TraceStore.get("webSearch.prefetch.mismatchReason"));
        assertEquals("fresh_search", TraceStore.get("webSearch.prefetch.decision"));
    }

    @Test
    void missingPrefetchQueryIdentityFailsClosedToFreshSearch() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(eq("identity required"), anyInt())).thenReturn(freshEvidence("fresh.example"));
        when(provider.getName()).thenReturn("test");
        WebSearchRetriever retriever = retriever(provider);

        retriever.retrieve(QueryUtils.buildQuery("identity required", Map.of(
                "searchMode", "FORCE_LIGHT",
                "prefetch.web.snippets", List.of("https://prefetch.example/missing - missing identity"),
                "webTopK", 3)));

        verify(provider).search(eq("identity required"), anyInt());
        assertEquals(Boolean.FALSE, TraceStore.get("webSearch.prefetch.identityPresent"));
        assertEquals("identity_missing", TraceStore.get("webSearch.prefetch.mismatchReason"));
        assertEquals("fresh_search", TraceStore.get("webSearch.prefetch.decision"));
    }

    @Test
    void singleCharacterQueryDimensionCannotCollideInPrefetchIdentity() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(eq("R language guide"), anyInt())).thenReturn(freshEvidence("fresh.example"));
        when(provider.getName()).thenReturn("test");
        WebSearchRetriever retriever = retriever(provider);

        retriever.retrieve(QueryUtils.buildQuery("R language guide", Map.of(
                "searchMode", "FORCE_LIGHT",
                "prefetch.web.query", "C language guide",
                "prefetch.web.queryHash", com.example.lms.trace.SafeRedactor.hashValue("C language guide"),
                "prefetch.web.snippets", List.of("https://prefetch.example/c - wrong language"),
                "webTopK", 3)));

        verify(provider).search(eq("R language guide"), anyInt());
        assertEquals("query_mismatch", TraceStore.get("webSearch.prefetch.mismatchReason"));
    }

    @Test
    void preprocessorCannotEraseRequestedSiteScopeBeforeIdentityCheck() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search(eq("topic"), anyInt())).thenReturn(freshEvidence("fresh.example"));
        when(provider.getName()).thenReturn("test");
        WebSearchRetriever retriever = retriever(provider);
        QueryContextPreprocessor stripSite = original -> original.replaceAll("(?i)\\s*site:[^\\s]+", "");
        org.springframework.test.util.ReflectionTestUtils.setField(retriever, "preprocessor", stripSite);

        retriever.retrieve(QueryUtils.buildQuery("topic site:b.example", Map.of(
                "searchMode", "FORCE_LIGHT",
                "prefetch.web.query", "topic",
                "prefetch.web.queryHash", com.example.lms.trace.SafeRedactor.hashValue("topic"),
                "prefetch.web.snippets", List.of("https://b.example/1 - wrong site scope"),
                "webTopK", 3)));

        verify(provider).search(eq("topic"), anyInt());
        assertEquals("site_scope_mismatch", TraceStore.get("webSearch.prefetch.mismatchReason"));
    }

    @Test
    void behaviorOnlySerpCacheCannotExposeRawQueryThroughPublicTrace() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        String rawQuery = "private cache query b02";
        when(provider.search(eq(rawQuery), anyInt())).thenReturn(List.of(
                "https://fresh.example/1 - " + rawQuery,
                "https://fresh.example/2 - evidence",
                "https://fresh.example/3 - evidence"));
        when(provider.getName()).thenReturn("test");

        retriever(provider).retrieve(QueryUtils.buildQuery(rawQuery, Map.of("webTopK", 3)));

        String publicTrace = String.valueOf(TraceStore.getAll());
        assertFalse(publicTrace.contains(rawQuery), publicTrace);
        assertFalse(TraceStore.getAll().containsKey("webSearch.serpCache"), publicTrace);
    }

    private static WebSearchRetriever retriever(WebSearchProvider provider) {
        AuthorityScorer authorityScorer = mock(AuthorityScorer.class);
        when(authorityScorer.weightFor(anyString())).thenReturn(0.0d);
        GameDomainDetector domainDetector = mock(GameDomainDetector.class);
        when(domainDetector.detect(anyString())).thenReturn("GENERAL");
        return new WebSearchRetriever(
                provider,
                null,
                mock(PageContentScraper.class),
                authorityScorer,
                mock(GenericDocClassifier.class),
                domainDetector,
                mock(EducationDocClassifier.class));
    }

    private static List<String> freshEvidence(String host) {
        return List.of(
                "https://" + host + "/1 - fresh-one",
                "https://" + host + "/2 - fresh-two",
                "https://" + host + "/3 - fresh-three");
    }
}
