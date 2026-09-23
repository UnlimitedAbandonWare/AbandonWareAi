package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.example.lms.cfvm.CfvmFailureRecorder;
import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.orchestration.control.RagControlCoordinator;
import com.example.lms.orchestration.control.RagControlLearningGate;
import com.example.lms.orchestration.control.RagControlProperties;
import com.example.lms.orchestration.control.RagControlRolloutState;
import com.example.lms.orchestration.control.RagControlRuntimeAdapter;
import com.example.lms.orchestration.control.RagGuardProbeComposer;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.budget.RetrievalBudgetGovernor;
import com.example.lms.service.rag.budget.RetrievalBudgetProperties;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExtremeZBurstAspectTest {

    @TempDir
    Path tempDir;

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
    void defaultOffReturnsBaseAndTracesDisabled() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(), props,
                contradictionProvider(null), debugProvider(null));
        List<Content> base = List.of(Content.from("base"));

        Object out = aspect.aroundHybridRetrieve(new FakePjp(base, query("dynamic rag")));

        assertSame(base, out);
        assertEquals("disabled", TraceStore.get("extremez.skipReason"));
        assertEquals("disabled", TraceStore.get("extremez.bypassReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.activated"));
    }

    @Test
    void planOverrideCanEnableButMissingRetrieverFailsSoft() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(), props,
                contradictionProvider(null), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        List<Content> base = List.of();

        Object out = aspect.aroundHybridRetrieve(new FakePjp(base, query("dynamic rag")));

        assertSame(base, out);
        assertEquals("web_retriever_missing", TraceStore.get("extremez.skipReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.activated"));
    }

    @Test
    void executionPlanPrimaryOverdriveSuppressesExtremeZEvenWhenFlagRemainsTrue() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(3);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("extra evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("executionPlan.primaryMode", "OVERDRIVE");
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        List<Content> base = List.of();

        Object out = aspect.aroundHybridRetrieve(new FakePjp(base, query("conflicting special modes")));

        assertSame(base, out);
        assertEquals("special_mode_overdrive", TraceStore.get("extremez.skipReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("specialMode.conflict.extremeZ.suppressed"));
    }

    @Test
    void canonicalBurstHandlerActivationSkipsAopExpansionForSameRequest() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(3);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("extra evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("extremez.execute.activated", true);
        List<Content> base = List.of();

        Object out = aspect.aroundHybridRetrieve(new FakePjp(base, query("avoid duplicate expansion")));

        assertSame(base, out);
        assertEquals(0, retriever.queries.size());
        assertEquals("burst_handler_already_ran", TraceStore.get("extremez.skipReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.activated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void variantsAreBoundedAndDoNotRepeatOriginalQuery() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("buildVariants", String.class, int.class);
        method.setAccessible(true);

        List<String> variants = (List<String>) method.invoke(aspect, "dynamic rag routing", 2);

        assertTrue(variants.size() <= 2);
        assertFalse(variants.contains("dynamic rag routing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void englishVariantsUseEnglishBoostersWithoutKoreanSuffixes() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("buildVariants", String.class, int.class);
        method.setAccessible(true);

        List<String> variants = (List<String>) method.invoke(
                aspect,
                "machine learning optimization benchmark",
                12);

        assertTrue(variants.stream().anyMatch(v -> v.endsWith(" guide") || v.endsWith(" official")), variants::toString);
        assertTrue(variants.stream().noneMatch(v ->
                v.endsWith(" 정리") || v.endsWith(" 공식") || v.endsWith(" 출처") || v.endsWith(" 최신")), variants::toString);
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.variants.langDetect.hasKorean"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.variants.langDetect.hasEnglishOnly"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void koreanVariantsKeepKoreanBoostersAndTraceLanguage() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("buildVariants", String.class, int.class);
        method.setAccessible(true);

        List<String> variants = (List<String>) method.invoke(aspect, "RAG 기반 검색 시스템", 12);

        assertTrue(variants.stream().anyMatch(v -> v.endsWith(" 정리") || v.endsWith(" 공식")), variants::toString);
        assertTrue(variants.stream().noneMatch(v -> v.endsWith(" guide") || v.endsWith(" official")), variants::toString);
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.variants.langDetect.hasKorean"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.variants.langDetect.hasEnglishOnly"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void planGachaVariantsArePrependedWithoutRawTraceLeak() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod(
                "buildVariants", String.class, int.class, GuardContext.class);
        method.setAccessible(true);
        GuardContext ctx = new GuardContext();
        String raw = "dynamic rag routing secret variant";
        ctx.putPlanOverride("extremeZ.gachaVariants", List.of(
                raw,
                "dynamic rag routing",
                "dynamic rag routing alternate authority lane"));

        List<String> variants = (List<String>) method.invoke(aspect, "dynamic rag routing", 2, ctx);

        assertEquals(2, variants.size());
        assertEquals(raw, variants.get(0));
        assertEquals("dynamic rag routing alternate authority lane", variants.get(1));
        assertFalse(variants.contains("dynamic rag routing"));
        assertEquals(2, TraceStore.get("extremez.gachaVariants.plan.count"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
    }

    @Test
    @SuppressWarnings("unchecked")
    void highContradictionCanActivateWhenBaseDocsAreNotSparse() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("extra evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.95d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);

        List<Content> base = List.of(
                Content.from("A says the KPI is 10"),
                Content.from("B says the KPI is 99")
        );

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("compare KPI claims")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("contradiction", TraceStore.get("extremez.activation.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.risk.trigger"));
        assertEquals(1, TraceStore.get("extremez.subQueryCount"));
        assertEquals(1, TraceStore.get("extremez.parallelBranchCount"));
        assertEquals(3, TraceStore.get("extremez.mergedDocCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.rrfApplied"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void highWebErrorRateCanActivateWhenBaseDocsAreNotSparse() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("error-rate rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.await.events.count", 4L);
        TraceStore.put("web.await.events.timeout.count", 3L);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("hard sparse web")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("error_rate", TraceStore.get("extremez.activation.reason"));
        assertEquals(0.75d, (Double) TraceStore.get("extremez.risk.errorRate"), 0.0001d);
    }

    @Test
    @SuppressWarnings("unchecked")
    void afterFilterStarvationCanActivateWhenBaseDocsAreNotSparse() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("after-filter rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.naver.filter.rawCount", 3L);
        TraceStore.put("web.naver.afterFilterCount", 0L);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("official source clamp")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("starvation", TraceStore.get("extremez.activation.reason"));
        assertEquals(1.0d, (Double) TraceStore.get("extremez.risk.starvationScore"), 0.0001d);
        assertEquals("after_filter_starvation", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tavilyAfterFilterStarvationCanActivateWhenBaseDocsAreNotSparse() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("tavily after-filter rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.tavily.returnedCount", 3L);
        TraceStore.put("web.tavily.afterFilterCount", 0L);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("tavily source clamp")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("starvation", TraceStore.get("extremez.activation.reason"));
        assertEquals(1.0d, (Double) TraceStore.get("extremez.risk.starvationScore"), 0.0001d);
        assertEquals("after_filter_starvation", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tavilyZeroResultsCanActivateAsWebStarvation() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("tavily zero-result rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.tavily.zeroResults", true);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("tavily empty result query")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("starvation", TraceStore.get("extremez.activation.reason"));
        assertEquals(0.65d, (Double) TraceStore.get("extremez.risk.starvationScore"), 0.0001d);
        assertEquals("web_starvation", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerDisabledCanActivateAsRetrievalFailure() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("provider-disabled rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.brave.providerDisabled", true);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("provider disabled query")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("retrieval_failure", TraceStore.get("extremez.activation.reason"));
        assertEquals(1.0d, (Double) TraceStore.get("extremez.risk.retrievalFailureRate"), 0.0001d);
        assertEquals("provider_disabled", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tavilyProviderDisabledCanActivateAsRetrievalFailure() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("tavily disabled rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.tavily.providerDisabled", true);

        List<Content> base = List.of(Content.from("stable a"), Content.from("stable b"));

        List<Content> out = (List<Content>) aspect.aroundHybridRetrieve(new FakePjp(base, query("tavily disabled query")));

        assertEquals(3, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("retrieval_failure", TraceStore.get("extremez.activation.reason"));
        assertEquals(1.0d, (Double) TraceStore.get("extremez.risk.retrievalFailureRate"), 0.0001d);
        assertEquals("provider_disabled", TraceStore.get("extremez.risk.primaryCause"));
    }

    @Test
    void providerDisabledRecordsCfvmFailurePattern() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("provider-disabled rescue evidence")));
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(new RawMatrixBuffer()),
                provider(new FailurePatternMemoryService(
                        new ObjectMapper(), new ToolManifestCatalog(), tempDir, memory)),
                provider(null),
                provider(null),
                provider(null),
                provider(shadowLearningGate()));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null),
                null, null, provider(recorder));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.naver.providerDisabled", true);

        aspect.aroundHybridRetrieve(new FakePjp(
                List.of(Content.from("stable a"), Content.from("stable b")),
                query("provider disabled query")));

        String line = Files.readString(memory);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.recorder.available"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.recorder.buffered"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.recorder.memory.recorded"));
        assertTrue(line.contains("cfvm_failure_pattern"));
        assertFalse(line.contains("provider disabled query"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cfvmBridgeMarksOnlyItsCopiedTraceAsRagOrigin() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        props.getExtremeZ().setMaxMergedDocs(4);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(
                List.of(Content.from("provider-disabled rescue evidence")));
        CfvmFailureRecorder recorder = mock(CfvmFailureRecorder.class);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(
                provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null),
                null, null, provider(recorder));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("web.naver.providerDisabled", true);

        aspect.aroundHybridRetrieve(new FakePjp(
                List.of(Content.from("stable a"), Content.from("stable b")),
                query("provider disabled query")));

        ArgumentCaptor<Map> trace = ArgumentCaptor.forClass(Map.class);
        verify(recorder).record(
                eq("extremez"), eq("provider_disabled"), eq("ExtremeZBurstAspect"), any(), trace.capture());
        assertEquals(Boolean.TRUE, trace.getValue().get(CfvmFailureRecorder.RAG_ORIGIN_MARKER));
        assertFalse(TraceStore.getAll().containsKey(CfvmFailureRecorder.RAG_ORIGIN_MARKER));
    }

    private static RagControlLearningGate shadowLearningGate() {
        return new RagControlLearningGate(
                new RagControlCoordinator(
                        new RagGuardProbeComposer(),
                        new RagControlRolloutState(new RagControlProperties(300, 0.01d, 20))),
                new RagControlRuntimeAdapter());
    }

    @Test
    void ablationCauseIsRecordedWithoutRawQuery() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        props.getExtremeZ().setMaxSubQueries(1);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("ablation rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        TraceStore.put("orch.debug.ablation.strike",
                List.of(Map.of("factor", "qtx.llm.modelRequired", "deltaProb", 0.42d)));
        String rawQuery = "secret ablation query should not appear";

        aspect.aroundHybridRetrieve(new FakePjp(
                List.of(Content.from("stable a"), Content.from("stable b")),
                query(rawQuery)));

        assertEquals("qtx.llm.modelRequired", TraceStore.get("extremez.risk.primaryCause"));
        assertFalse(String.valueOf(TraceStore.get("extremez.risk.patternId")).isBlank());
        assertFalse(TraceStore.getAll().containsValue(rawQuery));
    }

    @Test
    void riskTraceDoesNotExposeRawQueryText() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        props.getExtremeZ().setMinBaseDocs(2);
        FixedAnalyzeRetriever retriever = new FixedAnalyzeRetriever(List.of(Content.from("rescue evidence")));
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.95d)), debugProvider(null));
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("extremeZ.enabled", true);
        GuardContextHolder.set(ctx);
        String rawQuery = "secret raw query should not appear";

        aspect.aroundHybridRetrieve(new FakePjp(
                List.of(Content.from("claim one"), Content.from("claim two")),
                query(rawQuery)));

        assertFalse(TraceStore.getAll().containsValue(rawQuery));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("extremez.query.hash"));
    }

    @Test
    void riskEventDisabledReasonUsesTraceLabels() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"));

        assertFalse(source.contains("data.put(\"disabledReason\", activated ? \"\" : risk.reason());"));
        assertFalse(source.contains(
                "data.put(\"disabledReason\", activated ? \"\" : SafeRedactor.safeMessage(risk.reason(), 120));"));
        assertFalse(source.contains("\"extremez.risk.\" + risk.reason()"));
        assertTrue(source.contains(
                "String safeReason = SafeRedactor.traceLabelOrFallback(risk.reason(), \"unknown\");"));
        assertTrue(source.contains("data.put(\"disabledReason\", activated ? \"\" : safeReason);"));
        assertTrue(source.contains("String eventFingerprint = \"extremez.risk.\" + safeReason;"));
    }

    @Test
    void noExceptionIgnoreBlocksRemainInExtremeZAspect() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"));

        assertFalse(source.contains("catch (Exception ignore)"));
    }

    @Test
    void internalFailSoftFallbacksLeaveBreadcrumbs() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"));

        assertTrue(source.contains("traceFailure(\"fingerprint.text\", e);"));
        assertTrue(source.contains("traceFailure(\"contradiction.score\", e);"));
        assertTrue(source.contains("traceFailure(\"traceFailureSignals.trace\", ignore);"));
        assertTrue(source.contains("traceFailure(\"traceFailureSignals.patternId\", ignore);"));
        assertTrue(source.contains("traceFailure(\"blackbox.refresh\", ignore);"));
        assertTrue(source.contains("traceFailure(\"riskEvent.emit\", ignore);"));
        assertTrue(source.contains("traceFailure(\"cfvm.recorder.provider\", ex);"));
        assertTrue(source.contains("log.debug(\"[ExtremeZ] trace skipped key={} err={}"));
        assertTrue(source.contains("private static void traceFailure(String stage, Throwable e)"));
    }

    @Test
    void numericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"));

        assertParserCatchNarrowed(source, "private static long toLong");
        assertParserCatchNarrowed(source, "private static double toDouble");
        assertParserCatchNarrowed(source, "private double planDouble");
    }

    @Test
    void numericTraceFailuresUseStableInvalidNumberLabel() throws Exception {
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("traceFailure", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, "parse.double", new NumberFormatException("bad double"));
        method.invoke(null, "plan.double", new NumberFormatException("bad plan override"));
        method.invoke(null, "fingerprint.text", new IllegalStateException("boom"));

        assertEquals("invalid_number", TraceStore.get("extremez.parse.double.error"));
        assertEquals("invalid_number", TraceStore.get("extremez.plan.double.error"));
        assertEquals("IllegalStateException", TraceStore.get("extremez.fingerprint.text.error"));
    }

    @Test
    void numericParsersDropNonFiniteNumberValues() throws Exception {
        Method toLong = ExtremeZBurstAspect.class.getDeclaredMethod("toLong", Object.class);
        Method toDouble = ExtremeZBurstAspect.class.getDeclaredMethod("toDouble", Object.class);
        toLong.setAccessible(true);
        toDouble.setAccessible(true);

        assertEquals(0L, toLong.invoke(null, Double.POSITIVE_INFINITY));
        assertEquals("invalid_number", TraceStore.get("extremez.parse.long.error"));

        TraceStore.clear();

        assertEquals(0.0d, (Double) toDouble.invoke(null, Double.NaN), 0.0001d);
        assertEquals("invalid_number", TraceStore.get("extremez.parse.double.error"));
    }

    @Test
    void traceHelperUsesTraceLabelsForReasonStringScalars() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("trace", String.class, Object.class);
        method.setAccessible(true);
        String rawReason = "private risk reason with student detail";

        method.invoke(aspect, "extremez.risk.reason", rawReason);
        method.invoke(aspect, "extremez.base.count", 3);

        Object storedReason = TraceStore.get("extremez.risk.reason");
        assertTrue(String.valueOf(storedReason).startsWith("hash:"), String.valueOf(storedReason));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawReason));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("student detail"));
        assertEquals(3, TraceStore.get("extremez.base.count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void budgetGovernorDoesNotReusePriorTextureHitMetric() throws Exception {
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(nullProvider(), new AnchorNarrower(),
                new NovaOrchestrationProperties(), contradictionProvider(null), debugProvider(null),
                null, provider(new RetrievalBudgetGovernor(new RetrievalBudgetProperties())));
        Method method = ExtremeZBurstAspect.class.getDeclaredMethod("governVariants", String.class, List.class, int.class);
        method.setAccessible(true);
        TraceStore.put("rag.metrics.textureHitRate", 0.95d);

        List<String> out = (List<String>) method.invoke(aspect, "latest GraphRAG status", List.of("latest GraphRAG status"), 6);

        assertFalse(out.isEmpty());
        assertEquals("texture_unavailable", TraceStore.get("extremez.budget.textureReason"));
        assertEquals(0.0d, (Double) TraceStore.get("rag.metrics.textureHitRate"), 0.0001d);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "held_authored,900,true,2,true,2,false", "held_short,250,true,2,true,1,true",
            "fast_authored,900,false,2,true,2,false", "fast_short,250,false,2,true,2,false",
            "single_authored,900,true,1,true,1,false", "single_short,250,true,1,true,1,false",
            "preapply_false_authored,900,false,2,true,2,false", "preapply_false_short,250,false,2,true,2,false",
            "disabled_authored,900,false,2,false,0,false", "disabled_short,250,false,2,false,0,false"})
    @org.junit.jupiter.api.Timeout(10)
    @SuppressWarnings("unchecked")
    void shippedVariantBudgetStopsTheNextCallAfterAHeldCurrentCallReturns(
            String control, long rawBudgetMs, boolean holdFirst, int variantCap,
            boolean enabled, int expectedCalls, boolean deadlineHit) throws Throwable {
        var mapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/safe.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        String key = "extremeZ.budgetMs";
        assertEquals(900L, original.path("params").path(key).asLong());
        assertTrue(original.path("params").path("extremeZ.enabled").asBoolean());
        assertEquals(2, original.path("params").path("extremeZ.maxSubQueries").asInt());
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put(key, rawBudgetMs);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set(key, original.path("params").path(key));
        assertEquals(original, restored, "only the raw budget changes within each fixed masking pair");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/safe.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "safe.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        GuardContext ctx = new GuardContext();
        boolean preApplyFalse = !enabled || control.startsWith("preapply_false");
        if (preApplyFalse) ctx.putPlanOverride("extremeZ.enabled", false);
        if (variantCap == 1) ctx.putPlanOverride("extremeZ.maxSubQueries", 1);
        applier.applyToGuardContext(applier.load("safe.v1"), ctx);
        assertTrue(ctx.planBool("extremeZ.enabled", false), "the typed true plan flag overwrites preexisting false");
        if (!enabled) ctx.putPlanOverride("extremeZ.enabled", false);
        assertEquals(rawBudgetMs, ctx.planLong(key, -1L));
        assertEquals(enabled, ctx.planBool("extremeZ.enabled", !enabled));
        assertEquals(variantCap, ctx.planInt("extremeZ.maxSubQueries", -1));
        assertEquals(2, ctx.planInt("extremeZ.minBaseDocs", -1));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(holdFirst ? 1 : 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var firstCallElapsedMs = new java.util.concurrent.atomic.AtomicLong();
        var trace = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        AnalyzeWebSearchRetriever retriever = new AnalyzeWebSearchRetriever(null, null, null, null, null, null) {
            @Override public List<Content> retrieve(Query query) {
                int call = calls.incrementAndGet();
                assertEquals(rawBudgetMs, TraceStore.get("extremez.budgetMs"));
                assertEquals(variantCap, TraceStore.get("extremez.variants.count"));
                assertEquals("sparse", TraceStore.get("extremez.activation.reason"));
                if (call == 1) {
                    long started = System.nanoTime();
                    entered.countDown();
                    try {
                        assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("owned fixture interrupted", e);
                    } finally {
                        firstCallElapsedMs.set(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    }
                }
                return List.of(Content.from("local variant evidence " + call));
            }
        };
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{query("machine learning optimization benchmark")});
        org.mockito.Mockito.when(pjp.proceed()).thenReturn(List.of());
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var running = caller.submit(() -> {
                TraceStore.clear();
                GuardContextHolder.set(ctx);
                try {
                    return (List<Content>) aspect.aroundHybridRetrieve(pjp);
                } catch (Throwable t) {
                    throw new IllegalStateException("owned advice fixture failed", t);
                } finally {
                    trace.set(new java.util.LinkedHashMap<>(TraceStore.getAll()));
                    GuardContextHolder.clear();
                    TraceStore.clear();
                }
            });
            if (enabled) assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            if (holdFirst) {
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> running.get(350, java.util.concurrent.TimeUnit.MILLISECONDS),
                        "the synchronous current call remains held beyond the short budget");
                assertEquals(1, calls.get());
                assertFalse(running.isDone());
                assertFalse(interrupted.get());
                release.countDown();
            }
            List<Content> result = running.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(expectedCalls, calls.get());
            assertEquals(expectedCalls, result.size(), "returned unique evidence reflects admitted variants");
            assertEquals(deadlineHit, Boolean.TRUE.equals(trace.get().get("extremez.deadline.hit")));
            assertFalse(interrupted.get());
            if (enabled) {
                assertEquals(rawBudgetMs, trace.get().get("extremez.budgetMs"));
                assertEquals(variantCap, trace.get().get("extremez.variants.count"));
                assertEquals(expectedCalls, trace.get().get("extremez.parallelBranchCount"));
                assertEquals(Boolean.TRUE, trace.get().get("extremez.activated"));
                if (holdFirst) assertTrue(firstCallElapsedMs.get() >= 350L);
            } else {
                assertEquals("disabled", trace.get().get("extremez.skipReason"));
                assertFalse(trace.get().containsKey("extremez.budgetMs"));
                assertFalse(trace.get().containsKey("extremez.variants.count"));
            }
            verify(pjp, org.mockito.Mockito.times(1)).proceed();
            assertTrue(caller.submit(() -> GuardContextHolder.get() == null && TraceStore.getAll().isEmpty())
                    .get(2, java.util.concurrent.TimeUnit.SECONDS));
            System.out.printf("TBL07_EXTREME_BUDGET control=%s rawBudgetMs=%d held=%s enabled=%s variants=%d calls=%d results=%d deadlineHit=%s firstCallMs=%d preApplyFalse=%s postApplyDisabled=%s interrupted=false workerContextCleared=true externalRequests=0%n",
                    control, rawBudgetMs, holdFirst, enabled, enabled ? variantCap : 0,
                    calls.get(), result.size(), deadlineHit, firstCallElapsedMs.get(), preApplyFalse, !enabled);
        } finally {
            release.countDown();
            caller.shutdown();
            if (!caller.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                caller.shutdownNow();
                assertTrue(caller.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "minBaseDocs,2,2,3", "minBaseDocs,1,0,1",
            "maxSubQueries,2,2,3", "maxSubQueries,1,1,2",
            "maxMergedDocs,14,2,3", "maxMergedDocs,1,2,1"})
    @org.junit.jupiter.api.Timeout(10)
    @SuppressWarnings("unchecked")
    void shippedNumericControlsChangeActivationCallsAndReturnedMembership(
            String suffix, int rawValue, int expectedCalls, int expectedResults) throws Throwable {
        var mapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/safe.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Integer> authored = Map.of("minBaseDocs", 2, "maxSubQueries", 2, "maxMergedDocs", 14);
        for (var entry : authored.entrySet()) {
            assertEquals(entry.getValue().intValue(), original.path("params").path("extremeZ." + entry.getKey()).asInt());
        }
        assertEquals(900L, original.path("params").path("extremeZ.budgetMs").asLong());
        String key = "extremeZ." + suffix;
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put(key, rawValue);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set(key, original.path("params").path(key));
        assertEquals(original, restored, "only the selected raw numeric field changes within each pair");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/safe.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "safe.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        GuardContext ctx = new GuardContext();
        applier.applyToGuardContext(applier.load("safe.v1"), ctx);
        assertTrue(ctx.planBool("extremeZ.enabled", false));
        assertEquals(900L, ctx.planLong("extremeZ.budgetMs", -1L));
        for (var entry : authored.entrySet()) {
            assertEquals(entry.getKey().equals(suffix) ? rawValue : entry.getValue().intValue(),
                    ctx.planInt("extremeZ." + entry.getKey(), -1));
        }
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        List<String> variantQueries = new ArrayList<>();
        List<Content> auxiliary = new ArrayList<>();
        AnalyzeWebSearchRetriever retriever = new AnalyzeWebSearchRetriever(null, null, null, null, null, null) {
            @Override public List<Content> retrieve(Query variant) {
                assertSame(ctx, GuardContextHolder.get());
                int call = calls.incrementAndGet();
                variantQueries.add(variant.text());
                Content content = Content.from("local numeric variant evidence " + call);
                auxiliary.add(content);
                return List.of(content);
            }
        };
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        List<Content> base = List.of(Content.from("local numeric base evidence"));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{query("machine learning optimization benchmark")});
        org.mockito.Mockito.when(pjp.proceed()).thenReturn(base);
        TraceStore.clear();
        GuardContextHolder.set(ctx);
        try {
            List<Content> result = (List<Content>) aspect.aroundHybridRetrieve(pjp);
            assertEquals(expectedCalls, calls.get(), "count actual local retriever invocations");
            assertEquals(expectedCalls, variantQueries.stream().distinct().count());
            assertEquals(expectedResults, result.size());
            assertSame(base.get(0), result.get(0), "base evidence retains first membership");
            List<Content> expected = new ArrayList<>(base);
            expected.addAll(auxiliary);
            assertEquals(expected.subList(0, expectedResults), result,
                    "check actual returned membership separately from retrieval or trace counts");
            assertEquals(Boolean.valueOf(expectedResults > base.size()), TraceStore.get("extremez.activated"));
            assertFalse(Boolean.TRUE.equals(TraceStore.get("extremez.deadline.hit")));
            assertEquals(ctx.planInt("extremeZ.minBaseDocs", -1), TraceStore.get("extremez.minBaseDocs"));
            if (expectedCalls == 0) {
                assertSame(base, result);
                assertEquals("enough_base_docs", TraceStore.get("extremez.skipReason"));
                assertFalse(TraceStore.getAll().containsKey("extremez.variants.count"));
            } else {
                assertEquals("sparse", TraceStore.get("extremez.activation.reason"));
                assertEquals(expectedCalls, TraceStore.get("extremez.variants.count"));
                assertEquals(expectedCalls, TraceStore.get("extremez.parallelBranchCount"));
                assertEquals(ctx.planInt("extremeZ.maxMergedDocs", -1), TraceStore.get("extremez.maxMergedDocs"));
            }
            verify(pjp, org.mockito.Mockito.times(1)).proceed();
            System.out.printf("TBL07_EXTREME_NUMERIC key=%s rawValue=%d calls=%d results=%d auxiliaryRetained=%d activated=%s externalRequests=0%n",
                    suffix, rawValue, calls.get(), result.size(), result.size() - base.size(), TraceStore.get("extremez.activated"));
        } finally {
            GuardContextHolder.clear();
            TraceStore.clear();
        }
        assertTrue(GuardContextHolder.get() == null && TraceStore.getAll().isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "skipWhenStrikeMode,false,strike,2", "skipWhenStrikeMode,true,strike,0",
            "skipWhenStrikeMode,false,absent,2", "skipWhenStrikeMode,true,absent,2",
            "skipWhenWebRateLimited,false,web_rate_limited,2", "skipWhenWebRateLimited,true,web_rate_limited,0",
            "skipWhenWebRateLimited,false,absent,2", "skipWhenWebRateLimited,true,absent,2",
            "skipWhenAuxDown,false,aux_soft,2", "skipWhenAuxDown,true,aux_soft,0",
            "skipWhenAuxDown,false,aux_hard,2", "skipWhenAuxDown,true,aux_hard,0",
            "skipWhenAuxDown,false,absent,2", "skipWhenAuxDown,true,absent,2"})
    @org.junit.jupiter.api.Timeout(10)
    @SuppressWarnings("unchecked")
    void shippedSkipFlagsGateActualCallsOnlyWithTheirRequestCondition(
            String suffix, boolean rawValue, String state, int expectedCalls) throws Throwable {
        var mapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/safe.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        List<String> flags = List.of("skipWhenStrikeMode", "skipWhenWebRateLimited", "skipWhenAuxDown");
        for (String flag : flags) assertFalse(original.path("params").path("extremeZ." + flag).asBoolean());
        String key = "extremeZ." + suffix;
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put(key, rawValue);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set(key, original.path("params").path(key));
        assertEquals(original, restored, "only the selected raw flag changes within a fixed request-state pair");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/safe.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "safe.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        GuardContext ctx = new GuardContext();
        applier.applyToGuardContext(applier.load("safe.v1"), ctx);
        assertTrue(ctx.planBool("extremeZ.enabled", false));
        assertEquals(900L, ctx.planLong("extremeZ.budgetMs", -1L));
        assertEquals(2, ctx.planInt("extremeZ.minBaseDocs", -1));
        assertEquals(2, ctx.planInt("extremeZ.maxSubQueries", -1));
        assertEquals(14, ctx.planInt("extremeZ.maxMergedDocs", -1));
        for (String flag : flags) assertEquals(flag.equals(suffix) && rawValue,
                ctx.planBool("extremeZ." + flag, true));
        ctx.setStrikeMode("strike".equals(state));
        ctx.setWebRateLimited("web_rate_limited".equals(state));
        ctx.setAuxDegraded("aux_soft".equals(state));
        ctx.setAuxHardDown("aux_hard".equals(state));
        assertEquals("strike".equals(state), ctx.isStrikeMode());
        assertEquals("web_rate_limited".equals(state), ctx.isWebRateLimited());
        assertEquals("aux_soft".equals(state), ctx.isAuxDegraded());
        assertEquals("aux_hard".equals(state), ctx.isAuxHardDown());
        assertEquals(state.startsWith("aux_"), ctx.isAuxDown());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        List<String> variantQueries = new ArrayList<>();
        List<Content> auxiliary = new ArrayList<>();
        AnalyzeWebSearchRetriever retriever = new AnalyzeWebSearchRetriever(null, null, null, null, null, null) {
            @Override public List<Content> retrieve(Query variant) {
                assertSame(ctx, GuardContextHolder.get());
                int call = calls.incrementAndGet();
                variantQueries.add(variant.text());
                Content content = Content.from("local skip-condition evidence " + call);
                auxiliary.add(content);
                return List.of(content);
            }
        };
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        List<Content> base = List.of(Content.from("local skip-condition base evidence"));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{query("machine learning optimization benchmark")});
        org.mockito.Mockito.when(pjp.proceed()).thenReturn(base);
        TraceStore.clear();
        GuardContextHolder.set(ctx);
        try {
            List<Content> result = (List<Content>) aspect.aroundHybridRetrieve(pjp);
            assertEquals(expectedCalls, calls.get());
            assertEquals(expectedCalls, variantQueries.stream().distinct().count());
            List<Content> expected = new ArrayList<>(base);
            expected.addAll(auxiliary);
            assertEquals(expected, result, "assert returned membership, not only skip/activation traces");
            assertEquals(1 + expectedCalls, result.size());
            assertSame(base.get(0), result.get(0));
            assertEquals(Boolean.valueOf(expectedCalls > 0), TraceStore.get("extremez.activated"));
            assertFalse(Boolean.TRUE.equals(TraceStore.get("extremez.deadline.hit")));
            String reason;
            if (expectedCalls == 0) {
                reason = switch (state) {
                    case "strike" -> "strike_mode";
                    case "web_rate_limited" -> "web_rate_limited";
                    case "aux_soft", "aux_hard" -> "aux_down";
                    default -> throw new AssertionError("no skip is expected without a matching request state");
                };
                assertSame(base, result);
                assertEquals(reason, TraceStore.get("extremez.skipReason"));
                assertFalse(TraceStore.getAll().containsKey("extremez.variants.count"));
                assertFalse(TraceStore.getAll().containsKey("extremez.budgetMs"));
            } else {
                reason = "sparse";
                assertEquals(reason, TraceStore.get("extremez.activation.reason"));
                assertEquals(expectedCalls, TraceStore.get("extremez.variants.count"));
            }
            verify(pjp, org.mockito.Mockito.times(1)).proceed();
            System.out.printf("TBL07_EXTREME_SKIP key=%s rawValue=%s state=%s calls=%d results=%d reason=%s externalRequests=0%n",
                    suffix, rawValue, state, calls.get(), result.size(), reason);
        } finally {
            GuardContextHolder.clear();
            TraceStore.clear();
        }
        assertTrue(GuardContextHolder.get() == null && TraceStore.getAll().isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "fresh,false,true,none,true,2", "fresh,false,false,none,false,0",
            "global,true,true,none,true,2", "global,true,false,none,false,2",
            "request_true,false,true,true,true,2", "request_true,false,false,true,true,2",
            "request_false,false,true,false,true,2", "request_false,false,false,false,false,0"})
    @org.junit.jupiter.api.Timeout(10)
    @SuppressWarnings("unchecked")
    void shippedEnabledFlagChangesCallsSubjectToGlobalAndRequestPrecedence(
            String control, boolean globalEnabled, boolean rawEnabled, String priorOverride,
            boolean expectedPlanEnabled, int expectedCalls) throws Throwable {
        var mapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/safe.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        String key = "extremeZ.enabled";
        assertTrue(original.path("params").path(key).asBoolean());
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put(key, rawEnabled);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set(key, original.path("params").path(key));
        assertEquals(original, restored, "only raw enable changes within each global/request control pair");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/safe.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "safe.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load("safe.v1");
        assertEquals(Boolean.valueOf(rawEnabled), plan.extremeZEnabled());
        GuardContext ctx = new GuardContext();
        if (!"none".equals(priorOverride)) ctx.putPlanOverride(key, Boolean.valueOf(priorOverride));
        applier.applyToGuardContext(plan, ctx);
        assertEquals(Boolean.valueOf(expectedPlanEnabled), ctx.getPlanOverride(key));
        assertEquals(expectedPlanEnabled, ctx.planBool(key, !expectedPlanEnabled));
        assertEquals(900L, ctx.planLong("extremeZ.budgetMs", -1L));
        assertEquals(2, ctx.planInt("extremeZ.minBaseDocs", -1));
        assertEquals(2, ctx.planInt("extremeZ.maxSubQueries", -1));
        assertEquals(14, ctx.planInt("extremeZ.maxMergedDocs", -1));
        for (String flag : List.of("skipWhenStrikeMode", "skipWhenWebRateLimited", "skipWhenAuxDown")) {
            assertFalse(ctx.planBool("extremeZ." + flag, true));
        }
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        List<String> variantQueries = new ArrayList<>();
        List<Content> auxiliary = new ArrayList<>();
        AnalyzeWebSearchRetriever retriever = new AnalyzeWebSearchRetriever(null, null, null, null, null, null) {
            @Override public List<Content> retrieve(Query variant) {
                assertSame(ctx, GuardContextHolder.get());
                int call = calls.incrementAndGet();
                variantQueries.add(variant.text());
                Content content = Content.from("local enabled-control evidence " + call);
                auxiliary.add(content);
                return List.of(content);
            }
        };
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(globalEnabled);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        List<Content> base = List.of(Content.from("local enabled-control base evidence"));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{query("machine learning optimization benchmark")});
        org.mockito.Mockito.when(pjp.proceed()).thenReturn(base);
        TraceStore.clear();
        GuardContextHolder.set(ctx);
        try {
            List<Content> result = (List<Content>) aspect.aroundHybridRetrieve(pjp);
            assertEquals(expectedCalls, calls.get(), "observe the actual consumer under fixed global/request masks");
            assertEquals(expectedCalls, variantQueries.stream().distinct().count());
            List<Content> expected = new ArrayList<>(base);
            expected.addAll(auxiliary);
            assertEquals(expected, result);
            assertEquals(1 + expectedCalls, result.size());
            assertSame(base.get(0), result.get(0));
            assertEquals(Boolean.valueOf(globalEnabled), TraceStore.get("extremez.enabled.global"));
            assertEquals(Boolean.valueOf(expectedPlanEnabled), TraceStore.get("extremez.enabled.plan"));
            assertEquals(Boolean.valueOf(expectedCalls > 0), TraceStore.get("extremez.enabled"));
            assertEquals(Boolean.valueOf(expectedCalls > 0), TraceStore.get("extremez.activated"));
            assertFalse(Boolean.TRUE.equals(TraceStore.get("extremez.deadline.hit")));
            if (expectedCalls == 0) {
                assertSame(base, result);
                assertEquals("disabled", TraceStore.get("extremez.skipReason"));
                assertFalse(TraceStore.getAll().containsKey("extremez.variants.count"));
                assertFalse(TraceStore.getAll().containsKey("extremez.budgetMs"));
            } else {
                assertEquals("sparse", TraceStore.get("extremez.activation.reason"));
                assertEquals(expectedCalls, TraceStore.get("extremez.variants.count"));
            }
            verify(pjp, org.mockito.Mockito.times(1)).proceed();
            System.out.printf("TBL07_EXTREME_ENABLED control=%s rawEnabled=%s globalEnabled=%s priorOverride=%s effectivePlan=%s calls=%d results=%d externalRequests=0%n",
                    control, rawEnabled, globalEnabled, priorOverride, expectedPlanEnabled, calls.get(), result.size());
        } finally {
            GuardContextHolder.clear();
            TraceStore.clear();
        }
        assertTrue(GuardContextHolder.get() == null && TraceStore.getAll().isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "direct,true,true", "direct,false,false",
            "primary_overdrive,true,false", "primary_overdrive,false,false"})
    @org.junit.jupiter.api.Timeout(10)
    @SuppressWarnings("unchecked")
    void braveNestedEnableChangesReturnedEvidenceWithoutStrippingOtherKnobs(
            String control, boolean rawEnabled, boolean auxiliaryExpected) throws Throwable {
        var mapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/brave.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        String key = "extremeZ.enabled";
        var originalKnobs = original.path("plan").path("overrides").path("knobs");
        assertTrue(originalKnobs.path(key).asBoolean());
        assertEquals(3, originalKnobs.path("expand.selfAsk.count").asInt());
        assertEquals(12, originalKnobs.path("expand.queryBurst.count").asInt());
        assertTrue(originalKnobs.path("overdrive.enabled").asBoolean());
        assertFalse(original.path("plan").path("when").isMissingNode());
        assertTrue(original.path("plan").path("pipeline").isArray());
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("plan").path("overrides").path("knobs"))
                .put(key, rawEnabled);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("plan").path("overrides").path("knobs"))
                .set(key, originalKnobs.path(key));
        assertEquals(original, restored, "only nested raw enable changes; all other brave fields remain intact");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/brave.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "brave.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load("brave.v1");
        assertEquals(Boolean.valueOf(rawEnabled), plan.extremeZEnabled());
        GuardContext ctx = new GuardContext();
        applier.applyToGuardContext(plan, ctx);
        assertEquals(Boolean.valueOf(rawEnabled), ctx.getPlanOverride(key));
        assertEquals(3, ctx.planInt("expand.selfAsk.count", -1));
        assertEquals(12, ctx.planInt("expand.queryBurst.count", -1));
        assertTrue(ctx.planBool("overdrive.enabled", false));
        org.junit.jupiter.api.Assertions.assertNull(ctx.getPlanOverride("executionPlan.primaryMode"));
        org.junit.jupiter.api.Assertions.assertNull(ctx.getPlanOverride("routing.executionPlan.primaryMode"));
        org.junit.jupiter.api.Assertions.assertNull(TraceStore.get("orch.pipeline.executed"));
        org.junit.jupiter.api.Assertions.assertNull(TraceStore.get("extremez.execute.activated"));
        assertFalse(ctx.isStrikeMode());
        assertFalse(ctx.isWebRateLimited());
        assertFalse(ctx.isAuxDown());
        if ("primary_overdrive".equals(control)) ctx.putPlanOverride("executionPlan.primaryMode", "OVERDRIVE");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        List<String> variantQueries = new ArrayList<>();
        List<Content> auxiliary = new ArrayList<>();
        AnalyzeWebSearchRetriever retriever = new AnalyzeWebSearchRetriever(null, null, null, null, null, null) {
            @Override public List<Content> retrieve(Query variant) {
                assertSame(ctx, GuardContextHolder.get());
                int call = calls.incrementAndGet();
                variantQueries.add(variant.text());
                Content content = Content.from("local brave nested evidence " + call);
                auxiliary.add(content);
                return List.of(content);
            }
        };
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getExtremeZ().setEnabled(false);
        ExtremeZBurstAspect aspect = new ExtremeZBurstAspect(provider(retriever), new AnchorNarrower(), props,
                contradictionProvider(new FixedContradictionScorer(0.0d)), debugProvider(null));
        List<Content> base = List.of(Content.from("local brave nested base evidence"));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{query("machine learning optimization benchmark")});
        org.mockito.Mockito.when(pjp.proceed()).thenReturn(base);
        TraceStore.clear();
        GuardContextHolder.set(ctx);
        try {
            List<Content> result = (List<Content>) aspect.aroundHybridRetrieve(pjp);
            assertEquals(auxiliaryExpected, calls.get() > 0, "observe actual auxiliary execution for the nested raw pair");
            assertEquals(calls.get(), variantQueries.stream().distinct().count());
            List<Content> expected = new ArrayList<>(base);
            expected.addAll(auxiliary);
            assertEquals(expected, result, "preserve exact returned base/auxiliary membership");
            assertEquals(1 + calls.get(), result.size());
            assertSame(base.get(0), result.get(0));
            assertFalse(Boolean.TRUE.equals(TraceStore.get("extremez.deadline.hit")));
            assertEquals(Boolean.valueOf(auxiliaryExpected), TraceStore.get("extremez.activated"));
            String reason;
            if (auxiliaryExpected) {
                reason = "sparse";
                assertTrue(calls.get() <= 12, "query-burst count is an upper bound, not an assumed execution count");
                assertEquals(12, TraceStore.get("extremez.maxSubQueries"));
                assertEquals(calls.get(), TraceStore.get("extremez.variants.count"));
            } else {
                reason = rawEnabled ? "special_mode_overdrive" : "disabled";
                assertSame(base, result);
                assertEquals(0, calls.get());
                assertFalse(TraceStore.getAll().containsKey("extremez.variants.count"));
            }
            assertEquals(reason, TraceStore.get("extremez.activation.reason"));
            verify(pjp, org.mockito.Mockito.times(1)).proceed();
            System.out.printf("TBL07_BRAVE_EXTREME control=%s rawEnabled=%s calls=%d results=%d reason=%s selfAskRetained=3 queryBurstRetained=12 overdriveRetained=true externalRequests=0%n",
                    control, rawEnabled, calls.get(), result.size(), reason);
        } finally {
            GuardContextHolder.clear();
            TraceStore.clear();
        }
        assertTrue(GuardContextHolder.get() == null && TraceStore.getAll().isEmpty());
    }
    private static Query query(String text) {
        return QueryUtils.buildQuery(text, Collections.emptyMap());
    }

    private static ObjectProvider<AnalyzeWebSearchRetriever> nullProvider() {
        return provider(null);
    }

    private static ObjectProvider<ContradictionScorer> contradictionProvider(ContradictionScorer scorer) {
        return provider(scorer);
    }

    private static ObjectProvider<DebugEventStore> debugProvider(DebugEventStore store) {
        return provider(store);
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
                return value == null ? Collections.emptyIterator() : List.of(value).iterator();
            }
        };
    }

    private static final class FixedAnalyzeRetriever extends AnalyzeWebSearchRetriever {
        private final List<Content> results;
        private final List<String> queries = new ArrayList<>();

        private FixedAnalyzeRetriever(List<Content> results) {
            super(null, null, null, null, null, null);
            this.results = results == null ? List.of() : results;
        }

        @Override
        public List<Content> retrieve(Query query) {
            queries.add(query == null ? "" : query.text());
            return results;
        }
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

    private static final class FakePjp implements ProceedingJoinPoint {
        private final Object result;
        private final Object[] args;

        private FakePjp(Object result, Object... args) {
            this.result = result;
            this.args = args;
        }

        @Override
        public Object proceed() {
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
            return result;
        }

        @Override
        public void set$AroundClosure(AroundClosure arc) {
        }

        @Override
        public Object getThis() {
            return this;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public Signature getSignature() {
            return null;
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return "method-execution";
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            return null;
        }

        @Override
        public String toShortString() {
            return "FakePjp";
        }

        @Override
        public String toLongString() {
            return "FakePjp";
        }
    }
}
