package com.example.lms.service.rag.overdrive;

import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.energy.ContradictionScorer;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverdriveGuardTest {

    private OverdriveGuard guard;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        GuardContextHolder.clear();
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.0d));
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 4);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.55d);
        ReflectionTestUtils.setField(guard, "contradictionTh", 0.60d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.55d);
        ReflectionTestUtils.setField(guard, "errorRateTh", 0.35d);
        ReflectionTestUtils.setField(guard, "errorWeight", 0.10d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
    }

    @AfterEach
    void tearDown() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void activatesOnSparsePoolAndTracesDecision() {
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.50d);

        boolean activated = guard.shouldActivate("hard sparse question", List.of(Content.from("only one")));

        assertTrue(activated);
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.triggered"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.trigger.activated"));
        assertEquals((Double) TraceStore.get("overdrive.score"),
                (Double) TraceStore.get("overdrive.trigger.score"), 0.0001d);
        assertEquals("base_threshold", TraceStore.get("overdrive.reason"));
        assertEquals(1, TraceStore.get("overdrive.candidates.count"));
        assertEquals(1, TraceStore.get("overdrive.candidateCount"));
        assertEquals(0.25d, (Double) TraceStore.get("overdrive.authorityMean"), 0.0001d);
        assertTrue(String.valueOf(TraceStore.get("overdrive.triggerReasons")).contains("sparse"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.trigger.sparse"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.trigger.lowAuth"));
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.trigger.contradicted"));
        assertEquals(1, TraceStore.get("overdrive.trigger.candidateCount"));
        assertEquals(0.25d, (Double) TraceStore.get("overdrive.trigger.avgAuthority"), 0.0001d);
        assertEquals(0.0d, (Double) TraceStore.get("overdrive.trigger.contradictionScore"), 0.0001d);
        assertEquals(0, TraceStore.get("overdrive.stagesApplied"));
        assertEquals(-1, TraceStore.get("overdrive.finalCandidateCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.exactPhraseProbeUsed"));
    }

    @Test
    void skipsWhenDisabled() {
        ReflectionTestUtils.setField(guard, "enabled", false);

        assertFalse(guard.shouldActivate("query", List.of(Content.from("x"))));
        assertEquals("disabled", TraceStore.get("overdrive.skipReason"));
        assertEquals("disabled", TraceStore.get("overdrive.bypassReason"));
    }

    @Test
    void guardContextCanDisableOverdriveBeforeContradictionWork() {
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("overdrive.enabled", false);
        GuardContextHolder.set(ctx);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence"),
                Content.from("URL: https://www.gov.kr/c\nstable evidence"),
                Content.from("URL: https://www.gov.kr/d\nstable evidence")
        );

        assertFalse(guard.shouldActivate("official query", docs));
        assertEquals("guard_context_skip", TraceStore.get("overdrive.skipReason"));
    }

    @Test
    void activatesOnContradictionWhenBaseSignalsAreWeak() {
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.95d));
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.55d);
        ReflectionTestUtils.setField(guard, "contradictionTh", 0.60d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.30d);
        ReflectionTestUtils.setField(guard, "errorRateTh", 0.35d);
        ReflectionTestUtils.setField(guard, "errorWeight", 0.10d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);

        List<Content> docs = List.of(
                Content.from("A says revenue is 10"),
                Content.from("B says revenue is 99")
        );

        assertTrue(guard.shouldActivate("compare facts", docs));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
        assertEquals("threshold", TraceStore.get("overdrive.reason"));
    }

    @Test
    void recordsErrorRateAndCanUseItAsBoundedSignal() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.05d);
        ReflectionTestUtils.setField(guard, "errorRateTh", 0.35d);
        ReflectionTestUtils.setField(guard, "errorWeight", 0.10d);
        TraceStore.put("web.await.events.count", 4L);
        TraceStore.put("web.await.events.timeout.count", 3L);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertTrue(guard.shouldActivate("web unstable query", docs));
        assertEquals(0.75d, (Double) TraceStore.get("overdrive.error.rate"), 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
    }

    @Test
    void recordsStarvationAsBoundedScoreOnly() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.20d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
        TraceStore.put("web.naver.filter.rawCount", 5L);
        TraceStore.put("web.naver.afterFilterCount", 0L);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertFalse(guard.shouldActivate("secret starvation query should not leak", docs));
        assertEquals(0.16d, (Double) TraceStore.get("overdrive.score"), 0.0001d);
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.activated"));
        assertFalse(TraceStore.getAll().containsValue("secret starvation query should not leak"));
    }

    @Test
    void recordsTavilyStarvationAsBoundedScoreOnly() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.20d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
        TraceStore.put("web.tavily.returnedCount", 5L);
        TraceStore.put("web.tavily.afterFilterCount", 0L);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertFalse(guard.shouldActivate("secret tavily starvation query should not leak", docs));
        assertEquals(0.16d, (Double) TraceStore.get("overdrive.score"), 0.0001d);
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.activated"));
        assertFalse(TraceStore.getAll().containsValue("secret tavily starvation query should not leak"));
    }

    @Test
    void tavilyProviderDisabledAndZeroResultContributeToRetrievalFailure() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.05d);
        ReflectionTestUtils.setField(guard, "errorRateTh", 0.35d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.zeroResults", true);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertTrue(guard.shouldActivate("secret tavily disabled query should not leak", docs));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
        assertFalse(TraceStore.getAll().containsValue("secret tavily disabled query should not leak"));
    }

    @Test
    void planAggressiveLowersThresholdForStarvationRouting() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.20d);
        ReflectionTestUtils.setField(guard, "starvationTh", 0.50d);
        ReflectionTestUtils.setField(guard, "starvationWeight", 0.08d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.08d);
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("overdrive.aggressive", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.naver.filter.rawCount", 5L);
        TraceStore.put("web.naver.afterFilterCount", 0L);

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertTrue(guard.shouldActivate("secret starvation query should not leak", docs));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.aggressive"));
        assertEquals(0.12d, (Double) TraceStore.get("overdrive.score.threshold.effective"), 0.0001d);
        assertFalse(TraceStore.getAll().containsValue("secret starvation query should not leak"));
    }

    @Test
    void blackboxRiskContributesAsBoundedRetrievalFailureSignal() {
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.0d), provider(blackboxService(true)));
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.10d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.20d);
        TraceStore.put("ablation.events.count", 2);
        TraceStore.put("ablation.probabilities", List.of(
                java.util.Map.of("step", "web.await", "guard", "missing_future", "p", 0.90d, "delta", 0.10d)));

        List<Content> docs = List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence")
        );

        assertTrue(guard.shouldActivate("raw blackbox query should not leak", docs));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.activated"));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.blackbox.available"));
        assertEquals(0.20d, (Double) TraceStore.get("overdrive.score"), 0.0001d);
        assertEquals("web_await_bypass", TraceStore.get("overdrive.blackbox.restoreAction"));
        assertFalse(TraceStore.getAll().containsValue("raw blackbox query should not leak"));
    }

    @Test
    void disabledBlackboxConsumerDoesNotWriteBlackboxRiskTrace() {
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.0d), provider(blackboxService(false)));
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.90d);
        ReflectionTestUtils.setField(guard, "retrievalFailureWeight", 0.20d);
        TraceStore.put("web.naver.providerDisabled", true);

        assertFalse(guard.shouldActivate("provider disabled but blackbox off", List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence"))));

        assertFalse(TraceStore.getAll().containsKey("blackbox.risk.riskScore"));
        assertFalse(TraceStore.getAll().containsKey("blackbox.risk.dominantFailure"));
    }

    @Test
    void missingBlackboxProviderLeavesExplicitAbsentBreadcrumb() {
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.90d);

        assertFalse(guard.shouldActivate("blackbox absent query", List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence"))));

        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.blackbox.absent"));
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.blackbox.available"));
        assertEquals("provider_unavailable", TraceStore.get("overdrive.blackbox.disabledReason"));
        assertFalse(TraceStore.getAll().containsValue("blackbox absent query"));
    }

    @Test
    void blackboxRefreshFailureLeavesBreadcrumb() {
        guard = new OverdriveGuard(authority(), new FixedContradictionScorer(0.0d), throwingProvider());
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "minPool", 2);
        ReflectionTestUtils.setField(guard, "minAuthorityAvg", 0.25d);
        ReflectionTestUtils.setField(guard, "scoreThreshold", 0.90d);

        assertFalse(guard.shouldActivate("blackbox failure query", List.of(
                Content.from("URL: https://www.gov.kr/a\nstable evidence"),
                Content.from("URL: https://www.gov.kr/b\nstable evidence"))));

        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.blackbox.refresh.failed"));
        assertEquals("IllegalStateException", TraceStore.get("overdrive.blackbox.refresh.errorType"));
        assertFalse(TraceStore.getAll().containsValue("blackbox failure query"));
    }

    @Test
    void overdriveReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));

        assertFalse(source.contains("TraceStore.put(\"overdrive.skipReason\", activated ? \"\" : reason);"));
        assertFalse(source.contains("TraceStore.put(\"overdrive.reason\", reason);"));
        assertFalse(source.contains("String safeReason = SafeRedactor.safeMessage(reason, 120);"));
        assertTrue(source.contains("String safeReason = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"overdrive.skipReason\", activated ? \"\" : safeReason);"));
        assertTrue(source.contains("TraceStore.put(\"overdrive.reason\", safeReason);"));
    }

    @Test
    void traceDecisionFailureIsDiagnosableInsteadOfSilentIgnore() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));
        String body = source.substring(source.indexOf("private static void traceDecision"),
                source.indexOf("private static String safeHash"));

        assertFalse(body.contains("catch (Throwable ignore)"));
        assertTrue(body.contains("log.debug(\"[OverdriveGuard] traceDecision failed err={}\""));
    }

    @Test
    void autowiredAuthorityScorerUsesCanonicalQualifier() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));

        assertTrue(source.contains("@Qualifier(\"authAuthorityScorer\")"));
        assertTrue(source.indexOf("@Qualifier(\"authAuthorityScorer\")")
                < source.indexOf("AuthorityScorer authority,"));
    }

    @Test
    void overdriveSettingsAreConfigurationPropertiesBackedInsteadOfInlineValues() throws Exception {
        String guardSource = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));
        String propsSource = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/overdrive/OverdriveProperties.java"));

        assertFalse(guardSource.contains("@Value(\"${rag.overdrive."),
                "OverdriveGuard should not own raw @Value bindings");
        assertTrue(guardSource.contains("OverdriveProperties properties"),
                "OverdriveGuard should receive the typed overdrive settings object");
        assertTrue(propsSource.contains("@ConfigurationProperties(prefix = \"rag.overdrive\")"));
        assertTrue(propsSource.contains("private Trigger trigger = new Trigger();"));
    }

    @Test
    void traceSnapshotFailureLeavesStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"));

        assertTrue(source.contains("stage=trace.snapshot"));
        assertTrue(source.contains("stage=blackbox.refresh"));
        assertTrue(source.contains("stage=long.parse"));
        assertTrue(source.contains("stage=double.parse"));
        assertTrue(source.contains("stage=hash.fallback"));
        assertTrue(source.contains("err=trace-failure"));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> shippedOverdriveControls() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var applier = new com.example.lms.plan.PlanHintApplier(new org.springframework.core.io.DefaultResourceLoader());
        var seen = new java.util.HashSet<String>();
        var selected = new java.util.TreeSet<String>();
        var aliases = new java.util.HashSet<String>();
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        try (var paths = Files.list(Path.of("main/resources/plans"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String file = path.getFileName().toString();
                var hints = applier.load(file.substring(0, file.length() - 5));
                if (!seen.add(hints.planId()) || hints.overdriveEnabled() == null) continue;
                var tree = mapper.readTree(Files.readString(Path.of("main/resources/plans", hints.planId() + ".yaml")));
                String alias = tree.path("overdrive").isBoolean() ? "root"
                        : tree.path("plan").path("overrides").path("knobs").has("overdrive.enabled") ? "knobs" : "properties";
                var raw = "root".equals(alias) ? tree.path("overdrive") : tree.path("plan").path("overrides").path(alias).path("overdrive.enabled");
                assertTrue(raw.isBoolean()); assertEquals(hints.overdriveEnabled(), raw.booleanValue());
                selected.add(hints.planId()); aliases.add(alias);
                for (String control : List.of("authored", "flip", "removed", "precedence")) {
                    for (boolean caller : List.of(false, true)) cases.add(org.junit.jupiter.params.provider.Arguments.of(
                            hints.planId(), alias, raw.booleanValue(), control, caller));
                }
                for (String control : List.of("guard_aux", "guard_strike", "guard_compression", "guard_global", "guard_score"))
                    cases.add(org.junit.jupiter.params.provider.Arguments.of(hints.planId(), alias, raw.booleanValue(), control, false));
            }
        }
        assertEquals(java.util.Set.of("brave.v1", "document_evidence.v1", "recency_first.v1", "zero_break.v1"), selected);
        assertEquals(3, aliases.size()); assertEquals(52, cases.size());
        return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} alias={1} control={3} caller={4}")
    @org.junit.jupiter.params.provider.MethodSource("shippedOverdriveControls")
    void shippedOverdriveControlsActualDecisionWithoutBypassingOtherGuards(String planId, String alias,
            boolean authored, String control, boolean callerEnabled) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml")));
        var modified = original.deepCopy();
        boolean rootAlias = "root".equals(alias);
        String leaf = rootAlias ? "overdrive" : "overdrive.enabled";
        var parent = (com.fasterxml.jackson.databind.node.ObjectNode) (rootAlias ? modified : modified.path("plan").path("overrides").path(alias));
        assertEquals(authored, parent.path(leaf).booleanValue());
        if ("flip".equals(control)) parent.put(leaf, !authored);
        if ("removed".equals(control)) parent.remove(leaf);
        if (control.startsWith("guard_")) parent.put(leaf, true);
        if ("precedence".equals(control)) {
            if ("knobs".equals(alias)) {
                assertFalse(original.has("overdrive"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) modified).put("overdrive", !authored);
            } else {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) modified;
                for (String segment : List.of("plan", "overrides", "knobs")) {
                    node = node.has(segment) ? (com.fasterxml.jackson.databind.node.ObjectNode) node.get(segment) : node.putObject(segment);
                }
                assertFalse(node.has("overdrive.enabled"));
                node.put("overdrive.enabled", !authored);
            }
        }
        var restored = modified.deepCopy();
        var restoredParent = (com.fasterxml.jackson.databind.node.ObjectNode) (rootAlias ? restored : restored.path("plan").path("overrides").path(alias));
        restoredParent.put(leaf, authored);
        if ("precedence".equals(control)) {
            if ("knobs".equals(alias)) ((com.fasterxml.jackson.databind.node.ObjectNode) restored).remove("overdrive");
            else if (rootAlias) {
                assertFalse(original.has("plan"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) restored).remove("plan");
            } else ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("plan").path("overrides"))
                    .set("knobs", original.path("plan").path("overrides").path("knobs").deepCopy());
        }
        assertEquals(original, restored, "all other authored plan fields remain unchanged");
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
        var hints = applier.load(planId);
        Boolean projected = "removed".equals(control) ? null : control.startsWith("guard_") ? true
                : "flip".equals(control) || "precedence".equals(control) && !rootAlias ? !authored : authored;
        assertEquals(planId, hints.planId()); assertEquals(projected, hints.overdriveEnabled());
        GuardContext context = new GuardContext();
        context.putPlanOverride("overdrive.enabled", callerEnabled);
        applier.applyToGuardContext(hints, context);
        boolean enabled = projected == null ? callerEnabled : projected;
        assertEquals(enabled, context.planBool("overdrive.enabled", !enabled));
        if ("guard_aux".equals(control)) context.setAuxDown(true);
        if ("guard_strike".equals(control)) context.setStrikeMode(true);
        if ("guard_compression".equals(control)) context.setCompressionMode(true);
        GuardContextHolder.set(context);
        var contradictionCalls = new java.util.concurrent.atomic.AtomicInteger();
        ContradictionScorer scorer = new ContradictionScorer() {
            @Override public double score(String a, String b) {
                contradictionCalls.incrementAndGet(); return "guard_score".equals(control) ? 0.0d : 1.0d;
            }
        };
        OverdriveProperties properties = new OverdriveProperties();
        if ("guard_global".equals(control)) properties.setEnabled(false);
        OverdriveGuard actual = new OverdriveGuard(authority(), scorer, properties, null);
        boolean activated = actual.shouldActivate("synthetic comparison", List.of(Content.from("synthetic member a"), Content.from("synthetic member b")));
        boolean contextSkip = !enabled || List.of("guard_aux", "guard_strike", "guard_compression").contains(control);
        boolean globalSkip = "guard_global".equals(control);
        boolean scoreSkip = "guard_score".equals(control);
        boolean expected = !contextSkip && !globalSkip && !scoreSkip;
        assertEquals(expected, activated); assertEquals(expected, TraceStore.get("overdrive.activated"));
        assertEquals(contextSkip || globalSkip ? 0 : 1, contradictionCalls.get(), "plan and context skips precede pairwise contradiction work");
        String reason = globalSkip ? "disabled" : contextSkip ? "guard_context_skip" : scoreSkip ? "below_threshold" : "threshold";
        assertEquals(reason, TraceStore.get("overdrive.reason"));
        assertEquals(2, TraceStore.get("overdrive.candidates.count"));
        System.out.printf("TBL07_OVERDRIVE plan=%s alias=%s control=%s caller=%s projected=%s enabled=%s activated=%s contradictionCalls=%d reason=%s%n",
                planId, alias, control, callerEnabled, projected, enabled, activated, contradictionCalls.get(), reason);
    }

    private static RagFailureBlackboxService blackboxService(boolean enabled) {
        RagFailureBlackboxService service = new RagFailureBlackboxService(null, null, null);
        ReflectionTestUtils.setField(service, "enabled", enabled);
        return service;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }

    private static ObjectProvider<RagFailureBlackboxService> throwingProvider() {
        return new ObjectProvider<>() {
            @Override
            public RagFailureBlackboxService getObject(Object... args) {
                throw new IllegalStateException("ownerToken=secret-blackbox");
            }

            @Override
            public RagFailureBlackboxService getObject() {
                throw new IllegalStateException("ownerToken=secret-blackbox");
            }

            @Override
            public RagFailureBlackboxService getIfAvailable() {
                throw new IllegalStateException("ownerToken=secret-blackbox");
            }

            @Override
            public RagFailureBlackboxService getIfUnique() {
                throw new IllegalStateException("ownerToken=secret-blackbox");
            }
        };
    }

    private static AuthorityScorer authority() {
        return new AuthorityScorer("", "", "", "", "", "", "", "", "",
                1.0d, 0.85d, 0.80d, 0.70d, 0.55d, 0.25d);
    }

    private static final class FixedContradictionScorer extends ContradictionScorer {
        private final double score;

        private FixedContradictionScorer(double score) {
            this.score = score;
        }

        @Override
        public double score(String a, String b) {
            return score;
        }
    }
}
