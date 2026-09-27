package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanHintApplierTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "false,false", "string_false,false", "0,false",
            "yes,true", "invalid,absent", "missing,absent"})
    void exactDppKeyIsNormalizedWithoutOpeningSiblingKeysOrOverwritingRequest(String raw, String expected) {
        String yaml = "plan:\n  id: dpp_fixture.v1\n  overrides:\n    knobs:\n"
                + ("missing".equals(raw) ? "" : "      diversity.dpp.enabled: " + ("string_false".equals(raw) ? "\"false\"" : raw) + "\n")
                + "      diversity.unrelated.enabled: true\n";
        var applier = new PlanHintApplier(resourceLoaderFor("dpp_fixture.v1", yaml));
        var plan = applier.load("dpp_fixture.v1");
        var ctx = new GuardContext();
        Map<String, Object> meta = new HashMap<>();
        applier.applyToGuardContext(plan, ctx);
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        Object value = "absent".equals(expected) ? null : Boolean.valueOf(expected);
        assertEquals(value, ctx.getPlanOverride("diversity.dpp.enabled"));
        assertEquals(value, meta.get("diversity.dpp.enabled"));
        assertNull(ctx.getPlanOverride("diversity.unrelated.enabled"));
        assertFalse(meta.containsKey("diversity.unrelated.enabled"));
        ctx.putPlanOverride("diversity.dpp.enabled", false);
        meta.put("diversity.dpp.enabled", true);
        applier.applyToGuardContext(plan, ctx);
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        assertEquals(false, ctx.getPlanOverride("diversity.dpp.enabled"));
        assertEquals(true, meta.get("diversity.dpp.enabled"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "false,false", "string_false,false", "0,false",
            "yes,true", "invalid,absent", "missing,absent"})
    void exactVectorEnableIsNormalizedWithoutCoercingSiblingOrOverwritingRequest(String raw, String expected) {
        String yaml = "plan:\n  id: vector_fixture.v1\nparams:\n  allowRag: true\n  retrieval:\n    vector:\n"
                + ("missing".equals(raw) ? "" : "      enabled: " + ("string_false".equals(raw) ? "\"false\"" : raw) + "\n")
                + "      budget: 17\n";
        var applier = new PlanHintApplier(resourceLoaderFor("vector_fixture.v1", yaml));
        var plan = applier.load("vector_fixture.v1");
        assertEquals("vector_fixture.v1", plan.planId());
        assertFalse(plan.isEmpty());
        var ctx = new GuardContext();
        Map<String, Object> meta = new HashMap<>();
        applier.applyToGuardContext(plan, ctx);
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        Object value = "absent".equals(expected) ? null : Boolean.valueOf(expected);
        assertEquals(value, ctx.getPlanOverride("retrieval.vector.enabled"));
        assertEquals(value, meta.get("retrieval.vector.enabled"));
        assertEquals(17, ctx.getPlanOverride("retrieval.vector.budget"));
        assertEquals(17, meta.get("retrieval.vector.budget"));
        ctx.putPlanOverride("retrieval.vector.enabled", false);
        meta.put("retrieval.vector.enabled", true);
        applier.applyToGuardContext(plan, ctx);
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        assertEquals(false, ctx.getPlanOverride("retrieval.vector.enabled"));
        assertEquals(true, meta.get("retrieval.vector.enabled"));
    }

    @Test
    void braveExpandSelfAskCountActivatesRequestScopedSelfAskMetadata() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        PlanHints hints = applier.load("brave");
        OrchestrationHints orchestrationHints = OrchestrationHints.defaults();
        orchestrationHints.setEnableSelfAsk(false);
        Map<String, Object> meta = new HashMap<>();

        applier.applyToHintsAndMeta(hints, orchestrationHints, meta);

        assertEquals(3, meta.get("expand.selfAsk.count"));
        assertEquals("true", String.valueOf(meta.get("selfask.enabled")));
        assertEquals("true", String.valueOf(meta.get("enableSelfAsk")));
        assertEquals("expand.selfAsk.count", meta.get("selfask.planOverride.reason"));
        assertTrue(orchestrationHints.isEnableSelfAsk());
    }

    @Test
    void passthroughMetadataCarriesZero100SearchKnobs() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        PlanHints hints = applier.load("zero100.v1");
        OrchestrationHints orchestrationHints = OrchestrationHints.defaults();
        Map<String, Object> meta = new HashMap<>();

        applier.applyToHintsAndMeta(hints, orchestrationHints, meta);

        assertEquals(Boolean.TRUE, meta.get("search.zero100.enabled"));
        assertEquals(400, meta.get("search.zero100.sliceMs"));
        assertEquals(9, meta.get("search.zero100.queryBurstMax"));
    }

    @Test
    void zero100AliasDoesNotBecomeZeroBreak() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        PlanHints hints = applier.load("zero100");
        OrchestrationHints orchestrationHints = OrchestrationHints.defaults();
        Map<String, Object> meta = new HashMap<>();

        applier.applyToHintsAndMeta(hints, orchestrationHints, meta);

        assertEquals("zero100.v1", hints.planId());
        assertEquals(Boolean.TRUE, meta.get("search.zero100.enabled"));
    }

    @Test
    void malformedSensitivePlanFailsClosedToSafe() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("bad_token.v1", """
                id: bad_token.v1
                params:
                  owner-token: ${OWNER_TOKEN}
                """));

        PlanHints hints = applier.load("bad_token.v1");

        assertEquals("safe.v1", hints.planId());
        String invalid = String.valueOf(TraceStore.get("plan.schema.invalid"));
        assertTrue(invalid.contains("forbidden_sensitive_key:params.owner-token"));
        assertFalse(invalid.contains("OWNER_TOKEN"));
        assertEquals("bad_token.v1->safe.v1", TraceStore.get("plan.schema.fallback"));
    }

    @Test
    void camelCaseSecretPlanKeyFailsClosedWithoutValueLeak() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("client_secret.v1", """
                id: client_secret.v1
                params:
                  clientSecret: do-not-log-this-value
                """));

        PlanHints hints = applier.load("client_secret.v1");

        assertEquals("safe.v1", hints.planId());
        String invalid = String.valueOf(TraceStore.get("plan.schema.invalid"));
        assertTrue(invalid.contains("forbidden_sensitive_key:params.clientSecret"));
        assertFalse(invalid.contains("do-not-log-this-value"));
    }

    @Test
    void sensitivePlanIdIsRedactedFromInvalidPlanTrace() {
        String fakeKey = "sk-" + "test-1234567890abcdefghijklmnop";
        String rawPlanId = fakeKey + ".v1";
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor(rawPlanId,
                "id: " + rawPlanId + "\n"
                        + "params:\n"
                        + "  owner-token: ${OWNER_TOKEN}\n"));

        PlanHints hints = applier.load(rawPlanId);

        assertEquals("safe.v1", hints.planId());
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(fakeKey));
        assertFalse(trace.contains(rawPlanId));
        assertTrue(trace.contains("forbidden_sensitive_key:params.owner-token"));
    }

    @Test
    void pathLikePlanIdFallsBackWithoutTraceLeak() {
        String rawPlanId = "../private customer plan.v1";
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("safe.v1", """
                id: safe.v1
                params:
                  retrieval:
                    k:
                      web: 2
                """));

        PlanHints hints = applier.load(rawPlanId);

        assertEquals("safe.v1", hints.planId());
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("../"));
        assertFalse(trace.contains("private customer plan"));
        assertFalse(trace.contains(rawPlanId));
    }

    @Test
    void nonCredentialWordsContainingSecretDoNotFalseRedPlanDsl() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("neutral_secretariat.v1", """
                id: neutral_secretariat.v1
                params:
                  secretariatNote: public routing note
                """));

        PlanHints hints = applier.load("neutral_secretariat.v1");

        assertEquals("neutral_secretariat.v1", hints.planId());
        assertFalse(String.valueOf(TraceStore.getAll()).contains("forbidden_sensitive_key"));
    }

    @Test
    void malformedPlanYamlRecordsStableLoadErrorWithoutParserClassName() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("broken_parse.v1", "id: ["));

        PlanHints hints = applier.load("broken_parse.v1");

        assertEquals("broken_parse.v1", hints.planId());
        String trace = String.valueOf(TraceStore.get("plan.load.error"));
        assertTrue(trace.contains("broken_parse.v1:load_failed"));
        assertFalse(trace.contains("JsonParseException"));
        assertFalse(trace.contains("Exception"));
    }

    @Test
    void nonFiniteNumericHintsAreIgnoredInsteadOfOverflowing() throws Exception {
        Method asInt = PlanHintApplier.class.getDeclaredMethod("asInt", Object.class);
        Method asLong = PlanHintApplier.class.getDeclaredMethod("asLong", Object.class);
        asInt.setAccessible(true);
        asLong.setAccessible(true);

        assertEquals(null, asInt.invoke(null, Double.POSITIVE_INFINITY));
        assertEquals(null, asInt.invoke(null, Double.NaN));
        assertEquals(null, asLong.invoke(null, Double.NEGATIVE_INFINITY));
        assertEquals(null, asLong.invoke(null, Double.NaN));
    }

    @Test
    void guardContextReceivesExplicitSelfAskPlanOverride() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        PlanHints hints = applier.load("brave");
        GuardContext guardContext = new GuardContext();

        applier.applyToGuardContext(hints, guardContext);

        assertEquals(3, guardContext.getPlanOverride("expand.selfAsk.count"));
        assertEquals(Boolean.TRUE, guardContext.getPlanOverride("selfask.enabled"));
        assertEquals("expand.selfAsk.count", guardContext.getPlanOverride("selfask.planOverride.reason"));
    }

    @Test
    void documentEvidencePlanLoadsAttachmentFocusedRagKnobs() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());

        PlanHints hints = applier.load("document_evidence.v1");

        assertEquals(3, hints.minCitations());
        assertEquals(Boolean.TRUE, hints.onnxEnabled());
        assertEquals(Boolean.TRUE, hints.overdriveEnabled());
        assertEquals(12, hints.vecTopK());
        assertEquals(24, hints.rerankCeTopK());
        assertEquals(8, hints.rerankTopK());
    }

    @Test
    void nestedAndLegacyBoundedPlanFieldsReachTheExistingHintsContext() {
        PlanHintApplier nestedApplier = new PlanHintApplier(resourceLoaderFor("nested_fields.v1", """
                id: nested_fields.v1
                retrieval:
                  topk: { web: 7, vector: 9, kg: 3 }
                budgets: { web_ms: 600, vec_ms: 700 }
                guards: { min_citations: 2 }
                """));
        PlanHints nested = nestedApplier.load("nested_fields.v1");
        OrchestrationHints nestedContext = OrchestrationHints.defaults();
        Map<String, Object> nestedMeta = new HashMap<>();

        nestedApplier.applyToHintsAndMeta(nested, nestedContext, nestedMeta);

        assertEquals(7, nestedContext.getWebTopK());
        assertEquals(9, nestedContext.getVecTopK());
        assertEquals(600L, nestedContext.getWebBudgetMs());
        assertEquals(700L, nestedContext.getVecBudgetMs());
        assertTrue(String.valueOf(TraceStore.get("plan.fields.applied")).contains("topk.web"));
        assertTrue(String.valueOf(TraceStore.get("plan.fields.applied")).contains("budget_ms.vector"));

        PlanHintApplier legacyApplier = new PlanHintApplier(resourceLoaderFor("legacy_fields.v1", """
                id: legacy_fields.v1
                params:
                  web_top_k: 5
                  vector_top_k: 6
                  budget_ms: 800
                """));
        PlanHints legacy = legacyApplier.load("legacy_fields.v1");
        OrchestrationHints legacyContext = OrchestrationHints.defaults();
        Map<String, Object> legacyMeta = new HashMap<>();
        legacyMeta.put("webBudgetMs", 9_999L);
        legacyMeta.put("vecBudgetMs", 9_999L);

        legacyApplier.applyToHintsAndMeta(legacy, legacyContext, legacyMeta);

        assertEquals(5, legacyContext.getWebTopK());
        assertEquals(6, legacyContext.getVecTopK());
        assertEquals(800L, legacyContext.getWebBudgetMs());
        assertEquals(800L, legacyContext.getVecBudgetMs());
        assertEquals(800L, legacyMeta.get("webBudgetMs"));
        assertEquals(800L, legacyMeta.get("vecBudgetMs"));
        assertTrue(String.valueOf(TraceStore.get("plan.fields.applied")).contains("budget_ms"));
    }

    @Test
    void ambiguousGenericTopkIsExplicitlyRejectedWhileLegacyBudgetStillApplies() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("ambiguous_topk.v1", """
                id: ambiguous_topk.v1
                params:
                  topk: 7
                  budget_ms: 600
                """));

        PlanHints hints = applier.load("ambiguous_topk.v1");

        assertNull(hints.webTopK());
        assertNull(hints.vecTopK());
        assertEquals(600L, hints.webBudgetMs());
        assertEquals(600L, hints.vecBudgetMs());
        assertTrue(String.valueOf(TraceStore.get("plan.fields.rejected"))
                .contains("topk:ambiguous_generic"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(" 7"));
    }

    @Test
    void invalidOverflowAndGuardViolatingFieldsUseFixedReasonsAndLeaveDefaults() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("invalid_fields.v1", """
                id: invalid_fields.v1
                retrieval:
                  topk:
                    web: -1
                    vector: not-a-number
                    kg: 101
                budgets:
                  web_ms: -1
                  vec_ms: 120001
                guards:
                  min_citations: -1
                params:
                  budget_ms: 999999999999999999999999999999
                """));

        PlanHints hints = applier.load("invalid_fields.v1");
        String rejected = String.valueOf(TraceStore.get("plan.fields.rejected"));

        assertNull(hints.webTopK());
        assertNull(hints.vecTopK());
        assertNull(hints.kgTopK());
        assertNull(hints.webBudgetMs());
        assertNull(hints.vecBudgetMs());
        assertNull(hints.minCitations());
        assertTrue(rejected.contains("topk.web:non_positive"));
        assertTrue(rejected.contains("topk.vector:invalid_number"));
        assertTrue(rejected.contains("topk.kg:out_of_range"));
        assertTrue(rejected.contains("budget_ms.web:non_positive"));
        assertTrue(rejected.contains("budget_ms.vector:out_of_range"));
        assertTrue(rejected.contains("budget_ms:overflow"));
        assertTrue(rejected.contains("guard.min_citations:non_positive"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("not-a-number"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("999999999999"));
    }

    @Test
    void cachedPlanReemitsItsAllowlistedFieldDecisions() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("cached_fields.v1", """
                id: cached_fields.v1
                retrieval:
                  topk: { web: 4 }
                """));
        applier.load("cached_fields.v1");
        TraceStore.clear();

        applier.load("cached_fields.v1");

        assertTrue(String.valueOf(TraceStore.get("plan.fields.applied")).contains("topk.web"));
    }

    @Test
    void rerankBackendTraceUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/plan/PlanHintApplier.java"));

        assertFalse(source.contains("TraceStore.put(\"plan.rerankBackend\", ph.rerankBackend());"));
        assertTrue(source.contains("TraceStore.put(\"plan.rerankBackendHash\", SafeRedactor.hashValue(ph.rerankBackend()));"));
        assertTrue(source.contains("TraceStore.put(\"plan.rerankBackendLength\", ph.rerankBackend() == null ? 0 : ph.rerankBackend().length());"));
        assertFalse(source.contains("catch (Exception ignored) { return null; }"));
        assertFalse(source.contains("catch (NumberFormatException ignored) { return null; }"));
        assertTrue(source.contains("traceParseSkipped(\"int\", ignored);"));
        assertTrue(source.contains("traceParseSkipped(\"long\", ignored);"));
        assertTrue(source.contains("PlanHintApplier parse skipped stage="));
        assertTrue(source.contains("private static String errorType(Throwable error)"));
        assertTrue(source.contains("error instanceof NumberFormatException"));
        assertTrue(source.contains("return \"invalid_number\";"));
        assertTrue(source.contains("+ errorType(error)"));
        assertFalse(source.contains("+ SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), \"unknown\")"));
    }

    private static ResourceLoader resourceLoaderFor(String planId, String yaml) {
        DefaultResourceLoader delegate = new DefaultResourceLoader();
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                if (location.contains(planId)) {
                    byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
                    return new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return planId + ".yaml";
                        }
                    };
                }
                return delegate.getResource(location);
            }

            @Override
            public ClassLoader getClassLoader() {
                return delegate.getClassLoader();
            }
        };
    }
}
