package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAwareGuardScorecardTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void scorecardBlockRecommendationIsEnforcedByGuardDecision() {
        TraceStore.put("blackbox.risk.routingDecision", "BLOCK");
        TraceStore.put("blackbox.risk.blockRecommended", Boolean.TRUE);
        TraceStore.put("blackbox.risk.reasonCode", "block_policyrisk_observe_only");

        EvidenceAwareGuard guard = new EvidenceAwareGuard();
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context for a normal guard pass."));

        EvidenceAwareGuard.GuardDecision decision = guard.guardWithEvidence(
                "This is a normal grounded draft based on the source snippet.",
                evidence,
                0);

        assertEquals(EvidenceAwareGuard.GuardAction.BLOCK, decision.action());
        assertEquals("CONSTITUTIONAL_SCORECARD_BLOCK", TraceStore.get("guard.final.action"));
        assertEquals("block_policyrisk_observe_only", TraceStore.get("guard.final.action.reason"));
    }

    @Test
    void scorecardBlockReasonDoesNotExposeRawFreeFormReasonInMessage() {
        String fakeKey = "sk-" + "scorecardReasonSecret1234567890";
        String rawReason = "private prompt ownerToken=" + fakeKey + " needs manual review";
        TraceStore.put("blackbox.risk.routingDecision", "BLOCK");
        TraceStore.put("blackbox.risk.blockRecommended", Boolean.TRUE);
        TraceStore.put("blackbox.risk.reasonCode", rawReason);

        EvidenceAwareGuard guard = new EvidenceAwareGuard();
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context for a normal guard pass."));

        EvidenceAwareGuard.GuardDecision decision = guard.guardWithEvidence(
                "This is a normal grounded draft based on the source snippet.",
                evidence,
                0);

        String message = decision.finalDraft();
        String traceReason = String.valueOf(TraceStore.get("guard.final.action.reason"));
        assertEquals(EvidenceAwareGuard.GuardAction.BLOCK, decision.action());
        assertTrue(traceReason.startsWith("hash:"), traceReason);
        assertTrue(message.contains("reason=hash:"), message);
        assertFalse(message.contains("private prompt"), message);
        assertFalse(message.contains("ownerToken"), message);
        assertFalse(message.contains(fakeKey), message);
        assertFalse(traceReason.contains("private prompt"), traceReason);
        assertFalse(traceReason.contains(fakeKey), traceReason);
    }

    @Test
    void degradedEvidenceDiagnosticsUseLastStageCountsFallback() {
        java.util.Map<String, Object> stageCounts = new java.util.LinkedHashMap<>();
        stageCounts.put("NOFILTER_SAFE", 2);
        stageCounts.put("OFFICIAL", 0);
        TraceStore.put("stageCountsSelectedFromOut.last", stageCounts);

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context.")));

        assertTrue(output.contains("- web.failsoft.stageCountsSelectedFromOut: {NOFILTER_SAFE=2, OFFICIAL=0}"),
                output);
        assertFalse(output.contains("- web.failsoft.stageCountsSelectedFromOut: (missing)"), output);
    }

    @Test
    void degradedEvidenceDiagnosticsUseCanonicalStarvationTriggerFallback() {
        TraceStore.put("starvationFallback.trigger", "officialOnly->NOFILTER_SAFE");

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context.")));

        assertTrue(output.contains("- web.failsoft.starvationFallback.trigger: officialOnly->NOFILTER_SAFE"),
                output);
        assertTrue(output.contains("web.failsoft.starvationFallback(trigger=officialOnly->NOFILTER_SAFE"),
                output);
        assertFalse(output.contains("- web.failsoft.starvationFallback.trigger: (missing)"), output);
    }

    @Test
    void degradedEvidenceDiagnosticsUseCanonicalPoolAndNamespacedCacheFallbacks() {
        TraceStore.put("starvationFallback.poolUsed", "cache_only");
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 3);

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context.")));

        assertTrue(output.contains("- web.failsoft.starvationFallback.poolUsed: cache_only"), output);
        assertTrue(output.contains("- cacheOnly.merged.count: 3"), output);
        assertFalse(output.contains("- web.failsoft.starvationFallback.poolUsed: (missing)"), output);
        assertFalse(output.contains("- cacheOnly.merged.count: (missing)"), output);
    }

    @Test
    void degradedEvidenceKeyPointsSkipQueryIrrelevantSnippetsWhenUserQueryIsKnown() {
        GuardContext ctx = new GuardContext();
        ctx.setUserQuery("현재 RAG/LIGHT 설정으로 집중을 높이는 방법을 한국어로 세 문장만 답해줘.");
        GuardContextHolder.set(ctx);

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://support.example.test/spotlight",
                        "Spotlight 로컬 검색 재시작 방법",
                        "Spotlight 검색을 재시작하려면 killall Spotlight 명령을 실행하고 로컬 인덱스를 재생성합니다.")));

        assertFalse(output.contains("### 핵심 포인트\n- Spotlight 검색을 재시작"),
                output);
        assertEquals(Boolean.TRUE, TraceStore.get("guard.degradedToEvidence.keyPoints.queryFiltered"));
    }

    @Test
    void degradedEvidenceKeyPointsKeepQueryRelevantSnippetsWhenUserQueryIsKnown() {
        GuardContext ctx = new GuardContext();
        ctx.setUserQuery("현재 RAG/LIGHT 설정으로 집중을 높이는 방법을 한국어로 세 문장만 답해줘.");
        GuardContextHolder.set(ctx);

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://support.example.test/focus",
                        "RAG LIGHT 집중 설정",
                        "RAG LIGHT 설정에서는 검색 범위를 줄이고 알림을 낮춰 집중을 유지하는 방식이 도움이 됩니다.")));

        assertTrue(output.contains("### 핵심 포인트"), output);
        assertTrue(output.contains("RAG LIGHT 설정에서는 검색 범위를 줄이고 알림을 낮춰 집중을 유지하는 방식이 도움이 됩니다."), output);
        assertEquals(1, TraceStore.get("guard.degradedToEvidence.keyPoints.renderedCount"));
    }

    @Test
    void degradedEvidenceListPrioritizesOfficialChangelogDocsWhenRequested() {
        GuardContext ctx = new GuardContext();
        ctx.setUserQuery("OpenAI API 최신 변경사항을 공식 changelog 근거 위주로 알려줘");
        GuardContextHolder.set(ctx);

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://personal.example/openai-api-summary",
                        "개인 블로그 요약",
                        "개인 블로그가 OpenAI API 변경사항을 해설한 글입니다."),
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://platform.openai.com/docs/changelog",
                        "OpenAI API changelog",
                        "See all of the latest features and updates to the OpenAI API."),
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://platform.openai.com/docs/api-reference",
                        "OpenAI API reference",
                        "Official API documentation for current request and response fields.")));

        assertTrue(output.contains("공식/changelog 성격의 근거를 우선"), output);
        assertTrue(output.indexOf("OpenAI API changelog") < output.indexOf("개인 블로그 요약"), output);
        assertTrue(output.indexOf("OpenAI API reference") < output.indexOf("개인 블로그 요약"), output);
        assertFalse(output.contains("검색 엔진 및 커뮤니티 데이터를 바탕"), output);
        assertEquals(Boolean.TRUE, TraceStore.get("guard.degradedToEvidence.officialOrChangelogIntent"));
        assertEquals(2, TraceStore.get("guard.degradedToEvidence.priorityEvidenceCount"));
    }

    @Test
    void degradedEvidenceListReturnsEvidenceNeededInsteadOfOffDomainDocsForOfficialProbe() {
        GuardContext ctx = new GuardContext();
        ctx.setUserQuery("RAG web-search verification: answer only from official OpenAI and Supabase "
                + "docs/changelog evidence; if official evidence is missing say evidence_needed.");
        GuardContextHolder.set(ctx);

        String output = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://github.com/example/community-rag",
                        "Community RAG mirror",
                        "A community mirror discusses OpenAI and Supabase RAG setup."),
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://medium.com/example/supabase-mcp",
                        "Supabase MCP tutorial",
                        "A personal tutorial summarizes Supabase MCP setup.")));

        assertTrue(output.contains("evidence_needed"), output);
        assertFalse(output.contains("github.com"), output);
        assertFalse(output.contains("medium.com"), output);
        assertEquals(Boolean.TRUE, TraceStore.get("guard.degradedToEvidence.officialOrChangelogIntent"));
        assertEquals(0, TraceStore.get("guard.degradedToEvidence.priorityEvidenceCount"));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> shippedCitationMinimums() throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:plans/*.yaml");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var applier = new com.example.lms.plan.PlanHintApplier(resolver);
        var selected = new java.util.TreeMap<String, String[]>();
        var aliases = new java.util.TreeSet<String>();
        int declarations = 0;
        // Ordered by the documented current loader precedence; dotted property names stay literal.
        var aliasPaths = java.util.List.of(new String[]{"/guards", "min_citations"},
                new String[]{"/gates", "citationMin"}, new String[]{"/gates", "citation_min"},
                new String[]{"/plan/overrides/properties", "gate.citation.min"});
        try {
            for (var resource : resources) {
                String file = resource.getFilename();
                org.junit.jupiter.api.Assertions.assertNotNull(file);
                var plan = applier.load(file.substring(0, file.length() - ".yaml".length()));
                if (plan.minCitations() == null || selected.containsKey(plan.planId())) continue;
                var chosen = resolver.getResource("classpath:plans/" + plan.planId() + ".yaml");
                com.fasterxml.jackson.databind.JsonNode declared;
                try (var input = chosen.getInputStream()) { declared = mapper.readTree(input); }
                String[] winner = null;
                for (String[] alias : aliasPaths) {
                    if (declared.at(alias[0]).path(alias[1]).isMissingNode()) continue;
                    declarations++;
                    aliases.add(alias[0] + "/" + alias[1]);
                    if (winner == null) winner = alias;
                }
                org.junit.jupiter.api.Assertions.assertNotNull(winner, "unmapped selected citation alias");
                selected.put(plan.planId(), new String[]{plan.planId(), winner[0], winner[1]});
            }
        } finally {
            TraceStore.clear();
        }
        assertEquals(19, resources.length, "current test-classpath resource inventory");
        assertEquals(java.util.Set.of("brave.v1", "document_evidence.v1", "hyper_nova.v1", "kg_first.v1",
                "safe.v1", "safe_autorun.v1", "zero100.v1", "zero_break.v1"), selected.keySet());
        assertEquals(9, declarations, "includes the lower-priority document-evidence declaration");
        assertEquals(4, aliases.size());
        System.out.printf("TBL07_CITATION_INVENTORY classpath=%d selectedPlans=%d declarations=%d aliases=%d%n",
                resources.length, selected.size(), declarations, aliases.size());
        return selected.values().stream().flatMap(plan -> java.util.stream.Stream.of("empty", "below", "at")
                .map(band -> org.junit.jupiter.params.provider.Arguments.of(plan[0], plan[1], plan[2], band)));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} citations={3}")
    @org.junit.jupiter.params.provider.MethodSource("shippedCitationMinimums")
    void shippedPlanCitationMinimumReachesActualGuard(String planId, String section, String key,
            String band) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var declared = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                StandardCharsets.UTF_8));
        int required = declared.at(section).path(key).asInt(-1);
        assertTrue(required > 1 && required <= 100, "fixture needs a bounded positive deficit");
        var applier = new com.example.lms.plan.PlanHintApplier(new org.springframework.core.io.DefaultResourceLoader());
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        assertEquals(required, plan.minCitations());
        GuardContext ctx = new GuardContext();
        applier.applyToGuardContext(plan, ctx);
        assertEquals(required, ctx.getMinCitations());
        ctx.setUserQuery("Summarize the provided source context briefly.");
        GuardContextHolder.set(ctx);
        int actual = switch (band) { case "empty" -> 0; case "below" -> required - 1; default -> required; };
        var evidence = java.util.stream.IntStream.range(0, actual)
                .mapToObj(i -> new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source/" + i, "Grounded source",
                        "Grounded source context supports this summary."))
                .toList();
        var decision = new EvidenceAwareGuard().guardWithEvidence(
                "Grounded source context supports this summary.", evidence, 0);
        boolean insufficient = "insufficient_citations".equals(TraceStore.get("guard.detour"));
        System.out.printf("TBL07_CITATION plan=%s band=%s required=%d actual=%d insufficient=%s action=%s degraded=%s%n",
                planId, band, required, actual, insufficient, decision.action(), decision.degradedToEvidence());
        assertEquals(actual < required, insufficient);
        if (actual < required) {
            assertEquals(required, TraceStore.get("guard.minCitations.required"));
            assertEquals(actual, TraceStore.get("guard.minCitations.actual"));
            assertEquals("DEGRADE_EVIDENCE_LIST", TraceStore.get("guard.detour.route"));
            assertEquals(EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY, decision.action());
            assertTrue(decision.degradedToEvidence());
        } else {
            org.junit.jupiter.api.Assertions.assertNull(TraceStore.get("guard.minCitations.required"));
            org.junit.jupiter.api.Assertions.assertNull(TraceStore.get("guard.minCitations.actual"));
            // Other quality policies remain independent of satisfying the count.
            assertFalse("insufficient_citations".equals(TraceStore.get("guard.final.action.reason")));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "citation precedence={0}")
    @org.junit.jupiter.params.provider.CsvSource({"raise_primary,4", "raise_secondary,3",
            "remove_primary,3", "remove_primary_raise_secondary,4"})
    void duplicateCitationAliasPrecedenceReachesActualGuard(String variant, int required) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/document_evidence.v1.yaml"),
                StandardCharsets.UTF_8));
        assertEquals(3, original.path("guards").path("min_citations").asInt());
        assertEquals(3, original.at("/plan/overrides/properties").path("gate.citation.min").asInt());
        var modified = original.deepCopy();
        var primary = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("guards");
        var secondary = (com.fasterxml.jackson.databind.node.ObjectNode) modified.at("/plan/overrides/properties");
        if (variant.startsWith("remove_primary")) primary.remove("min_citations");
        if ("raise_primary".equals(variant)) primary.put("min_citations", 4);
        if (variant.endsWith("raise_secondary")) secondary.put("gate.citation.min", 4);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("guards")).put("min_citations", 3);
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.at("/plan/overrides/properties"))
                .put("gate.citation.min", 3);
        assertEquals(original, restored, "only the named precedence controls change");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/document_evidence.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "document_evidence.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(resources);
        var plan = applier.load("document_evidence.v1");
        assertEquals("document_evidence.v1", plan.planId());
        assertEquals(required, plan.minCitations());
        GuardContext ctx = new GuardContext();
        applier.applyToGuardContext(plan, ctx);
        assertEquals(required, ctx.getMinCitations());
        ctx.setUserQuery("Summarize the provided source context briefly.");
        GuardContextHolder.set(ctx);
        var evidence = java.util.stream.IntStream.range(0, 3).mapToObj(i -> new EvidenceAwareGuard.EvidenceDoc(
                "https://example.test/source/" + i, "Grounded source",
                "Grounded source context supports this summary.")).toList();
        var decision = new EvidenceAwareGuard().guardWithEvidence(
                "Grounded source context supports this summary.", evidence, 0);
        boolean insufficient = "insufficient_citations".equals(TraceStore.get("guard.detour"));
        assertEquals(required > 3, insufficient);
        if (insufficient) {
            assertEquals(required, TraceStore.get("guard.minCitations.required"));
            assertEquals(3, TraceStore.get("guard.minCitations.actual"));
            assertEquals(EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY, decision.action());
            assertTrue(decision.degradedToEvidence());
        }
        System.out.printf("TBL07_CITATION_PRECEDENCE variant=%s required=%d actual=3 insufficient=%s%n",
                variant, required, insufficient);
    }

    @Test
    void minCitationDetourUsesCanonicalStarvationTriggerForEntityEscalation() {
        GuardContext ctx = new GuardContext();
        ctx.setMinCitations(2);
        ctx.setEntityQuery(true);
        ctx.setUserQuery("Who is Ada Lovelace?");
        GuardContextHolder.set(ctx);
        TraceStore.put("starvationFallback.trigger", "BELOW_MIN_CITATIONS");

        EvidenceAwareGuard.GuardDecision decision = new EvidenceAwareGuard().guardWithEvidence(
                "Ada Lovelace was a computing pioneer.",
                List.of(new EvidenceAwareGuard.EvidenceDoc(
                        "https://example.test/source",
                        "Source title",
                        "Source snippet with enough grounded context.")),
                0);

        assertEquals(EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY, decision.action());
        assertEquals("ESCALATE_REWRITE", TraceStore.get("guard.detour.route"));
        assertEquals(Boolean.TRUE, TraceStore.get("guard.detour.forceEscalate"));
        assertEquals("BELOW_MIN_CITATIONS", TraceStore.get("guard.detour.forceEscalate.trigger"));
    }

    @Test
    void minCitationDetourKeepsConciseRequestsOutOfUserFacingDiagnostics() {
        GuardContext ctx = new GuardContext();
        ctx.setMinCitations(2);
        ctx.setUserQuery("오늘 주요 AI 뉴스 하나만 한 문장으로 알려줘.");
        GuardContextHolder.set(ctx);

        EvidenceAwareGuard.GuardDecision decision = new EvidenceAwareGuard().guardWithEvidence(
                "OpenAI news summary.",
                List.of(new EvidenceAwareGuard.EvidenceDoc(
                        "https://openai.com/news/",
                        "OpenAI News",
                        "Stay up to speed on the rapid advancement of AI technology.")),
                0);

        String output = decision.finalDraft();
        assertEquals(EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY, decision.action());
        assertEquals("DEGRADE_EVIDENCE_LIST", TraceStore.get("guard.detour.route"));
        assertTrue(decision.degradedToEvidence());
        assertTrue(output.contains("https://openai.com/news/"), output);
        assertFalse(output.contains("추가 근거 확보용 추천 검색어"), output);
        assertFalse(output.contains("### 진단"), output);
        assertFalse(output.contains("### Fix hints"), output);
        assertFalse(output.contains("Orchestration Auto Report"), output);
    }

    @Test
    void minCitationDetourDoesNotEchoOffDomainNegativeClausesInSuggestions() {
        String query = "RAG random probe 2026-07-08 final official evidence: compare OpenAI Responses API web_search tooling "
                + "and Supabase MCP read_only project_ref setup using official/external sources only. "
                + "If official OpenAI/Supabase URLs are unavailable, answer evidence_needed without listing GitHub mirrors, "
                + "NVIDIA docs, Streamlit tutorials, WFGY/community pages, or local PDFs as evidence.";
        GuardContext ctx = new GuardContext();
        ctx.setMinCitations(2);
        ctx.setUserQuery(query);
        GuardContextHolder.set(ctx);

        EvidenceAwareGuard.GuardDecision decision = new EvidenceAwareGuard().guardWithEvidence(
                "OpenAI official source was found, but Supabase official source is still missing.",
                List.of(new EvidenceAwareGuard.EvidenceDoc(
                        "https://developers.openai.com/api/docs/guides/deep-research",
                        "OpenAI Deep Research",
                        "Official OpenAI developer documentation.")),
                0);

        String output = decision.finalDraft();
        assertEquals(EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY, decision.action());
        assertTrue(decision.degradedToEvidence());
        assertTrue(output.contains("https://developers.openai.com/api/docs/guides/deep-research"), output);
        assertFalse(output.contains("GitHub mirrors"), output);
        assertFalse(output.contains("NVIDIA docs"), output);
        assertFalse(output.contains("Streamlit tutorials"), output);
        assertFalse(output.contains("WFGY/community"), output);
        assertFalse(output.contains("local PDFs"), output);
    }

    @Test
    void escalationFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[guard] escalation failed, falling back to original draft. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void evidenceListUrlEnrichmentLivesOutsideGuardLargeFile() throws Exception {
        Path guardPath = Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java");
        Path helperPath = Path.of("main/java/com/example/lms/service/guard/EvidenceDocListEnricher.java");

        String guard = Files.readString(guardPath, StandardCharsets.UTF_8);

        assertTrue(Files.exists(helperPath), "URL-derived evidence list enrichment should live outside the guard large file");
        String helper = Files.readString(helperPath, StandardCharsets.UTF_8);
        assertTrue(guard.contains("EvidenceDocListEnricher.enrich(docs)"));
        assertFalse(guard.contains("private static java.util.List<EvidenceDoc> enrichEvidenceDocsForList("));
        assertFalse(guard.contains("private static String urlHost("));
        assertFalse(guard.contains("private static String deriveSnippetFromUrl("));
        assertTrue(helper.contains("final class EvidenceDocListEnricher"));
        assertTrue(helper.contains("static java.util.List<EvidenceAwareGuard.EvidenceDoc> enrich("));
    }

    @Test
    void evidenceAwareGuardDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java"),
                StandardCharsets.UTF_8);

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "EvidenceAwareGuard guard diagnostics must leave trace/debug breadcrumbs instead of exact empty catch blocks");
    }

    @Test
    void bestEffortFailureNumericTraceUsesStableErrorType() throws Exception {
        Method method = EvidenceAwareGuard.class.getDeclaredMethod(
                "traceBestEffortFailure", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, "guard.test.numeric", new NumberFormatException("raw private token"));

        assertEquals(Boolean.TRUE, TraceStore.get("guard.test.numeric.failed"));
        assertEquals("invalid_number", TraceStore.get("guard.test.numeric.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private token"));
    }
}
