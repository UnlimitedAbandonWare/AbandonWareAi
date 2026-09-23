package com.example.lms.probe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ai.abandonware.nova.orch.ecosystem.EcosystemBufferPool;
import com.example.lms.artplate.ArtPlateEvolver;
import com.example.lms.artplate.ArtPlateRegistry;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.probe.dto.CandidateDTO;
import com.example.lms.probe.dto.SearchProbeRequest;
import com.example.lms.probe.dto.SearchProbeResponse;
import com.example.lms.probe.dto.StageSnapshot;
import com.example.lms.search.TraceStore;
import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.moe.RgbStrategySelector;
import com.example.lms.service.rag.burst.ExtremeZProperties;
import com.example.lms.service.rag.burst.ExtremeZSystemHandler;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class OrchProbeControllerTraceRedactionTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void orchEndpointReturnsRedactedQueryAndAllowlistedTraceOnly() throws Exception {
        String rawQuery = "private raw q";
        String rawTraceQuery = "private raw query";
        String rawOwnerToken = "owner-token-value";
        String rawAuthHeader = "auth-header-value";

        TraceStore.put("web.await.last.rawQuery", rawTraceQuery);
        TraceStore.put("ownerToken", rawOwnerToken);
        TraceStore.put("rawQuery", "disallowed raw query");
        TraceStore.put("authorization", rawAuthHeader);

        MockMvc mvc = standaloneSetup(new OrchProbeController(null, true, "probe-token-value")).build();

        MvcResult result = mvc.perform(get("/api/probe/orch")
                        .param("q", rawQuery)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Probe-Token", "probe-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").doesNotExist())
                .andExpect(jsonPath("$.queryPresent").value(true))
                .andExpect(jsonPath("$.queryLength").value(rawQuery.length()))
                .andExpect(jsonPath("$.queryHash12").value(SafeRedactor.hash12(rawQuery)))
                .andExpect(jsonPath("$['trace']['trace.size']").exists())
                .andExpect(jsonPath("$['trace']['web.await.last.rawQuery'].present").value(true))
                .andExpect(jsonPath("$['trace']['web.await.last.rawQuery'].len").value(rawTraceQuery.length()))
                .andExpect(jsonPath("$['trace']['web.await.last.rawQuery'].hash12").value(SafeRedactor.hash12(rawTraceQuery)))
                .andExpect(jsonPath("$.trace.ownerToken").doesNotExist())
                .andExpect(jsonPath("$.trace.rawQuery").doesNotExist())
                .andExpect(jsonPath("$.trace.authorization").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(rawQuery));
        assertFalse(body.contains(rawTraceQuery));
        assertFalse(body.contains(rawOwnerToken));
        assertFalse(body.contains(rawAuthHeader));
        assertFalse(body.contains("probe-token-value"));
    }

    @Test
    void orchEndpointRunsCredentialFreeRetrievalOrderProbeForQuery() throws Exception {
        String rawQuery = "RAG evidence starvation debug ownerToken=secret";
        OrchProbeController controller = new OrchProbeController(null, true, "probe-token-value");
        ReflectionTestUtils.setField(controller, "retrievalOrderService",
                new com.example.lms.strategy.RetrievalOrderService());
        MockMvc mvc = standaloneSetup(controller).build();

        MvcResult result = mvc.perform(get("/api/probe/orch")
                        .param("q", rawQuery)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Probe-Token", "probe-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['trace']['retrievalOrder.lastSetBy']").value("DEFAULT"))
                .andExpect(jsonPath("$['trace']['retrievalOrder.lastOrder'][0]").value("WEB"))
                .andExpect(jsonPath("$['trace']['retrievalOrder.lastOrder'][1]").value("VECTOR"))
                .andExpect(jsonPath("$['trace']['retrievalOrder.lastOrder'][2]").value("KG"))
                .andExpect(jsonPath("$['trace']['retrievalOrder.lastOrderSize']").value(3))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(rawQuery));
        assertFalse(body.contains("ownerToken"));
        assertFalse(body.contains("probe-token-value"));
    }

    @Test
    void orchEndpointReturnsTraceSnapshotHeaderBeforeServletCommit() throws Exception {
        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        when(snapshotStore.captureCurrent(any(), any(), any(), any(), any()))
                .thenReturn("snap-orch-001");
        OrchProbeController controller = new OrchProbeController(null, true, "probe-token-value");
        ReflectionTestUtils.setField(controller, "retrievalOrderService",
                new com.example.lms.strategy.RetrievalOrderService());
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/api/probe/orch")
                        .param("q", "RAG evidence starvation debug")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Probe-Token", "probe-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['trace']['retrievalOrder.lastSetBy']").value("DEFAULT"))
                .andExpect(header().string("X-Trace-Snapshot-Id", "snap-orch-001"));
    }

    @Test
    void orchEndpointExposesRedactedWebFailSoftTraceKeys() throws Exception {
        String rawReason = "disabled ownerToken=secret api_key=test-fixture";
        TraceStore.put("outCount", 0);
        TraceStore.put("stageCountsSelectedFromOut.last", Map.of("NOFILTER_SAFE", 1));
        TraceStore.put("cacheOnly.merged.count", 1);
        TraceStore.put("tracePool.size", 4);
        TraceStore.put("rescueMerge.used", true);
        TraceStore.put("starvationFallback.trigger", "officialOnly");
        TraceStore.put("starvationFallback.used", true);
        TraceStore.put("starvationFallback.poolUsed", "NOFILTER_SAFE");
        TraceStore.put("starvationFallback.pool.safe.size", 3);
        TraceStore.put("starvationFallback.pool.dev.size", 1);
        TraceStore.put("starvationFallback.count", 2);
        TraceStore.put("starvationFallback.added", 2);
        TraceStore.put("starvationFallback.poolSafeEmpty", true);
        TraceStore.put("poolSafeEmpty", true);
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
        TraceStore.put("web.brave.skipped.reason", rawReason);
        TraceStore.put("web.serpapi.rateLimited", true);
        TraceStore.put("web.serpapi.retryAfterMs", 13000L);
        TraceStore.put("web.tavily.skipped.reason", rawReason);
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.failureReason", "provider-disabled");
        TraceStore.put("vectorFallback.used", true);
        TraceStore.put("vectorFallback.reason", "web_empty");
        TraceStore.put("vectorFallback.effectiveTopK", 10);

        MockMvc mvc = standaloneSetup(new OrchProbeController(null, true, "probe-token-value")).build();

        MvcResult result = mvc.perform(get("/api/probe/orch")
                        .param("q", "private orch failsoft query ownerToken=secret")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Probe-Token", "probe-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['trace']['outCount']").value(0))
                .andExpect(jsonPath("$['trace']['stageCountsSelectedFromOut'].NOFILTER_SAFE").value(1))
                .andExpect(jsonPath("$['trace']['cacheOnly.merged.count']").value(1))
                .andExpect(jsonPath("$['trace']['tracePool.size']").value(4))
                .andExpect(jsonPath("$['trace']['rescueMerge.used']").value(true))
                .andExpect(jsonPath("$['trace']['starvationFallback.trigger']").value("officialOnly"))
                .andExpect(jsonPath("$['trace']['starvationFallback.used']").value(true))
                .andExpect(jsonPath("$['trace']['starvationFallback.poolUsed']").value("NOFILTER_SAFE"))
                .andExpect(jsonPath("$['trace']['starvationFallback.pool.safe.size']").value(3))
                .andExpect(jsonPath("$['trace']['starvationFallback.pool.dev.size']").value(1))
                .andExpect(jsonPath("$['trace']['starvationFallback.count']").value(2))
                .andExpect(jsonPath("$['trace']['starvationFallback.added']").value(2))
                .andExpect(jsonPath("$['trace']['starvationFallback.poolSafeEmpty']").value(true))
                .andExpect(jsonPath("$['trace']['poolSafeEmpty']").value(true))
                .andExpect(jsonPath("$['trace']['web.naver.providerDisabled']").value(true))
                .andExpect(jsonPath("$['trace']['web.naver.skipped.reason']").value("breaker_open_or_half_open"))
                .andExpect(jsonPath("$['trace']['web.serpapi.rateLimited']").value(true))
                .andExpect(jsonPath("$['trace']['web.serpapi.retryAfterMs']").value(13000))
                .andExpect(jsonPath("$['trace']['web.tavily.providerDisabled']").value(true))
                .andExpect(jsonPath("$['trace']['web.tavily.failureReason']").value("provider-disabled"))
                .andExpect(jsonPath("$['trace']['vectorFallback.used']").value(true))
                .andExpect(jsonPath("$['trace']['vectorFallback.reason']").value("web_empty"))
                .andExpect(jsonPath("$['trace']['vectorFallback.effectiveTopK']").value(10))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"web.brave.skipped.reason\":\"hash:"), body);
        assertTrue(body.contains("\"web.tavily.skipped.reason\":\"hash:"), body);
        assertFalse(body.contains(rawReason), body);
        assertFalse(body.contains("ownerToken"), body);
        assertFalse(body.contains("api_key=test-fixture"), body);
        assertFalse(body.contains("probe-token-value"), body);
    }

    @Test
    void orchEndpointSynthesizesCanonicalFailSoftKpisFromNamespacedTraceKeys() throws Exception {
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 1);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 4);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", true);
        TraceStore.put("web.failsoft.starvationFallback.trigger", "officialOnly");
        TraceStore.put("web.failsoft.starvationFallback.used", true);
        TraceStore.put("web.failsoft.starvationFallback.poolUsed", "NOFILTER_SAFE");
        TraceStore.put("web.failsoft.starvationFallback.pool.safe.size", 3);
        TraceStore.put("web.failsoft.starvationFallback.pool.dev.size", 1);
        TraceStore.put("web.failsoft.starvationFallback.count", "2");
        TraceStore.put("web.failsoft.starvationFallback.added", 2);
        TraceStore.put("web.failsoft.starvationFallback.poolSafeEmpty", false);

        MockMvc mvc = standaloneSetup(new OrchProbeController(null, true, "probe-token-value")).build();

        mvc.perform(get("/api/probe/orch")
                        .param("q", "private orch namespaced failsoft query ownerToken=secret")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Probe-Token", "probe-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['trace']['cacheOnly.merged.count']").value(1))
                .andExpect(jsonPath("$['trace']['tracePool.size']").value(4))
                .andExpect(jsonPath("$['trace']['rescueMerge.used']").value(true))
                .andExpect(jsonPath("$['trace']['starvationFallback.trigger']").value("officialOnly"))
                .andExpect(jsonPath("$['trace']['starvationFallback.used']").value(true))
                .andExpect(jsonPath("$['trace']['starvationFallback.poolUsed']").value("NOFILTER_SAFE"))
                .andExpect(jsonPath("$['trace']['starvationFallback.pool.safe.size']").value(3))
                .andExpect(jsonPath("$['trace']['starvationFallback.pool.dev.size']").value(1))
                .andExpect(jsonPath("$['trace']['starvationFallback.count']").value("2"))
                .andExpect(jsonPath("$['trace']['starvationFallback.added']").value(2))
                .andExpect(jsonPath("$['trace']['starvationFallback.poolSafeEmpty']").value(false))
                .andExpect(jsonPath("$['trace']['poolSafeEmpty']").value(false));
    }

    @Test
    void orchEndpointRedactsExceptionMessages() throws Exception {
        String rawFailure = "private raw q from breaker";
        NightmareBreaker breaker = mock(NightmareBreaker.class);
        when(breaker.snapshot()).thenThrow(new IllegalStateException(rawFailure));

        MockMvc mvc = standaloneSetup(new OrchProbeController(breaker, true, "probe-token-value")).build();

        MvcResult result = mvc.perform(get("/api/probe/orch")
                        .param("q", "visible input")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Probe-Token", "probe-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['breakers.open'].error").value("IllegalStateException"))
                .andExpect(jsonPath("$['breakers.open'].errorHash12").exists())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(rawFailure));
        assertFalse(body.contains("probe-token-value"));
        assertFalse(body.contains("visible input"));
    }

    @Test
    void orchEndpointReturnsSafeKgAndLangGraphTraceSummaries() throws Exception {
        String rawQuery = "private kg raw query";
        String rawPrompt = "private kg raw prompt";
        String ownerToken = "kg-owner-token-value";
        String authHeader = "Bearer " + "kg-authorization-value";
        String probeToken = "probe-token-value";

        TraceStore.put("rag.eval.kgAxis", Map.ofEntries(
                Map.entry("schemaVersion", 1),
                Map.entry("status", "ok"),
                Map.entry("kgPoolCount", 7),
                Map.entry("kgFinalCount", 3),
                Map.entry("kgScoreMean", 0.62d),
                Map.entry("kgScoreP95", 0.91d),
                Map.entry("kgFinalRetention", 0.43d),
                Map.entry("graphScore", 0.74d),
                Map.entry("graphOpportunity", "medium"),
                Map.entry("neo4jStatus", "disabled"),
                Map.entry("neo4jDisabledReason", "missing_key"),
                Map.entry("neo4jFailureClass", "missing-external-key"),
                Map.entry("signals", List.of(
                        "kg-ready",
                        Map.of(
                                "label", "kg-linked",
                                "status", "ok",
                                "rawPrompt", rawPrompt,
                                "url", "https://private.example.test/kg"))),
                Map.entry("rawQuery", rawQuery),
                Map.entry("rawPrompt", rawPrompt),
                Map.entry("ownerToken", ownerToken),
                Map.entry("authorization", authHeader)));
        TraceStore.put("rag.eval.scorecard", Map.of(
                "schemaVersion", 1,
                "status", "degraded",
                "reasonCode", "threshold_break",
                "thresholdLabels", List.of("kg_min", "score_low"),
                "rawPrompt", rawPrompt,
                "payload", "private payload"));
        TraceStore.put("rag.eval.thresholdBreaks", List.of(Map.ofEntries(
                Map.entry("label", "kg-final"),
                Map.entry("reasonCode", "below_min"),
                Map.entry("scope", "kg"),
                Map.entry("metric", "kgFinalCount"),
                Map.entry("status", "break"),
                Map.entry("category", "retention"),
                Map.entry("stage", "eval"),
                Map.entry("neo4jStatus", "disabled"),
                Map.entry("neo4jDisabledReason", "missing_key"),
                Map.entry("neo4jFailureClass", "missing-external-key"),
                Map.entry("rawQuery", rawQuery),
                Map.entry("snippet", "private snippet"),
                Map.entry("url", "https://private.example.test/doc"))));
        TraceStore.put("langgraph.node.quality_gate.reason", "kg_quality_low");
        TraceStore.put("langgraph.node.quality_gate.degraded", true);
        TraceStore.put("langgraph.invoke.failureClass", "timeout");
        TraceStore.put("langgraph.invoke.trigger", "quality_gate");
        TraceStore.put("langgraph.invoke.fallbackTriggered", true);
        TraceStore.put("retrieval.kg.neo4j.status", "disabled");
        TraceStore.put("retrieval.kg.neo4j.disabledReason", "missing_key");
        TraceStore.put("retrieval.kg.neo4j.failureClass", "missing-external-key");
        TraceStore.put("langgraph.invoke.rawPrompt", rawPrompt);
        TraceStore.put("retrieval.kg.neo4j.authorization", authHeader);
        TraceStore.put("ownerToken", ownerToken);

        MockMvc mvc = standaloneSetup(new OrchProbeController(null, true, probeToken)).build();

        MvcResult result = mvc.perform(get("/api/probe/orch")
                        .param("q", rawQuery)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-Probe-Token", probeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").doesNotExist())
                .andExpect(jsonPath("$.queryPresent").value(true))
                .andExpect(jsonPath("$.queryLength").value(rawQuery.length()))
                .andExpect(jsonPath("$.queryHash12").value(SafeRedactor.hash12(rawQuery)))
                .andExpect(jsonPath("$['trace']['rag.eval.kgAxis'].status").value("ok"))
                .andExpect(jsonPath("$['trace']['rag.eval.kgAxis'].kgPoolCount").value(7))
                .andExpect(jsonPath("$['trace']['rag.eval.kgAxis'].neo4jDisabledReason").value("missing_key"))
                .andExpect(jsonPath("$['trace']['rag.eval.kgAxis'].signals").exists())
                .andExpect(jsonPath("$['trace']['rag.eval.kgAxis'].rawQuery").doesNotExist())
                .andExpect(jsonPath("$['trace']['rag.eval.kgAxis'].rawPrompt").doesNotExist())
                .andExpect(jsonPath("$['trace']['rag.eval.scorecard'].status").value("degraded"))
                .andExpect(jsonPath("$['trace']['rag.eval.scorecard'].reasonCode").value("threshold_break"))
                .andExpect(jsonPath("$['trace']['rag.eval.scorecard'].thresholdLabels").exists())
                .andExpect(jsonPath("$['trace']['rag.eval.scorecard'].payload").doesNotExist())
                .andExpect(jsonPath("$['trace']['rag.eval.thresholdBreaks'][0].label").exists())
                .andExpect(jsonPath("$['trace']['rag.eval.thresholdBreaks'][0].metric.hash12")
                        .value(SafeRedactor.hash12("kgFinalCount")))
                .andExpect(jsonPath("$['trace']['rag.eval.thresholdBreaks'][0].snippet").doesNotExist())
                .andExpect(jsonPath("$['trace']['langgraph.node.quality_gate.reason']").value("kg_quality_low"))
                .andExpect(jsonPath("$['trace']['langgraph.node.quality_gate.degraded']").value(true))
                .andExpect(jsonPath("$['trace']['langgraph.invoke.failureClass']").value("timeout"))
                .andExpect(jsonPath("$['trace']['langgraph.invoke.trigger']").value("quality_gate"))
                .andExpect(jsonPath("$['trace']['langgraph.invoke.fallbackTriggered']").value(true))
                .andExpect(jsonPath("$['trace']['retrieval.kg.neo4j.status']").value("disabled"))
                .andExpect(jsonPath("$['trace']['retrieval.kg.neo4j.disabledReason']").value("missing_key"))
                .andExpect(jsonPath("$['trace']['retrieval.kg.neo4j.failureClass']").value("missing-external-key"))
                .andExpect(jsonPath("$['trace']['ownerToken']").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(rawQuery));
        assertFalse(body.contains(rawPrompt));
        assertFalse(body.contains(ownerToken));
        assertFalse(body.contains(authHeader));
        assertFalse(body.contains(probeToken));
        assertFalse(body.contains("rawQuery"));
        assertFalse(body.contains("rawPrompt"));
        assertFalse(body.contains("ownerToken"));
        assertFalse(body.contains("authorization"));
        assertFalse(body.contains("private payload"));
        assertFalse(body.contains("private snippet"));
        assertFalse(body.contains("private.example.test"));
    }

    @Test
    void probeConsoleLoggerUsesHashOnlyCorrelationAndQueryDiagnostics() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/ProbeConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("ev.put(\"rid\", rid)"));
        assertFalse(source.contains("ev.put(\"sid\", sid)"));
        assertFalse(source.contains("ev.put(\"q\", trunc(oneLine(qq), 220))"));

        assertTrue(source.contains("ev.put(\"ridHash\", SafeRedactor.hashValue(rid))"));
        assertTrue(source.contains("ev.put(\"sidHash\", SafeRedactor.hashValue(sid))"));
        assertTrue(source.contains("ev.put(\"queryHash\", SafeRedactor.hashValue(qq))"));
        assertTrue(source.contains("ev.put(\"queryLength\", qq == null ? 0 : qq.length())"));
    }

    @Test
    void orchProbeFallbacksLeaveScannerVisibleBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/OrchProbeController.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSkipped(\"breakers.open\", t);"));
        assertTrue(source.contains("traceSkipped(\"guard\", t);"));
        assertTrue(source.contains("traceSkipped(\"signals\", t);"));
        assertTrue(source.contains("traceSkipped(\"trace\", t);"));
        assertTrue(source.contains("[AWX][probe][orch] fallback stage={} errorType={}"));
    }

    @Test
    void probeConsoleLoggerErrorMessageUsesRedactedTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/ProbeConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("return trunc(oneLine(s), 180);"));
        assertTrue(source.contains("return SafeRedactor.traceLabelOrFallback(oneLine(s), \"\")"));

        Method method = ProbeConfig.class.getDeclaredMethod("safeMsg", String.class);
        method.setAccessible(true);

        String raw = "console trace failed C:\\Users\\nninn\\Desktop\\secret"
                + "\\ownerToken=secret-token-fixture";
        String message = String.valueOf(method.invoke(null, raw));

        assertTrue(message.startsWith("hash:"));
        assertFalse(message.contains("C:\\Users"));
        assertFalse(message.contains("ownerToken"));
        assertFalse(message.contains("secret-token-fixture"));
        assertFalse(message.contains(raw));
    }

    @Test
    void probeConsoleKpiReadsNamespacedStarvationTrigger() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("PROBE_SEARCH_TRACE");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            TraceStore.put("outCount", 0);
            TraceStore.put("web.failsoft.starvationFallback.trigger", "officialOnly");
            TraceStore.put("starvationFallback.poolSafeEmpty", true);
            TraceStore.put("tracePool.size", 6);
            TraceStore.put("rescueMerge.used", true);
            UnifiedRagOrchestrator.QueryRequest query = new UnifiedRagOrchestrator.QueryRequest();
            query.query = "private probe console query ownerToken=secret";
            query.topK = 3;

            Method method = ProbeConfig.class.getDeclaredMethod(
                    "logProbeConsole",
                    SearchProbeRequest.class,
                    UnifiedRagOrchestrator.QueryRequest.class,
                    UnifiedRagOrchestrator.QueryTrace.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    Environment.class,
                    SearchProbeResponse.class);
            method.setAccessible(true);

            method.invoke(null, null, query, null, true, true, true, new MockEnvironment(), null);

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("\"outCount\":0"), logged);
            assertTrue(logged.contains("\"starvationFallback.trigger\":\"officialOnly\""), logged);
            assertTrue(logged.contains("\"starvationFallback.poolSafeEmpty\":true"), logged);
            assertTrue(logged.contains("\"poolSafeEmpty\":true"), logged);
            assertTrue(logged.contains("\"tracePool.size\":6"), logged);
            assertTrue(logged.contains("\"rescueMerge.used\":true"), logged);
            assertFalse(logged.contains(query.query), logged);
            assertFalse(logged.contains("ownerToken"), logged);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void probeConsoleKpiExposesRedactedProviderTaxonomyAndVectorFallback() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("PROBE_SEARCH_TRACE");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            String rawReason = "disabled ownerToken=secret api_key=test-fixture";
            TraceStore.put("web.naver.skipped", true);
            TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
            TraceStore.put("web.naver.requestedCount", 10);
            TraceStore.put("web.naver.returnedCount", 3);
            TraceStore.put("web.naver.afterFilterCount", 0);
            TraceStore.put("web.naver.timeout", true);
            TraceStore.put("web.naver.timeoutMs", 1500L);
            TraceStore.put("web.brave.skipped.reason", rawReason);
            TraceStore.put("web.serpapi.providerDisabled", true);
            TraceStore.put("web.serpapi.disabledReason", "missing_serpapi_api_key");
            TraceStore.put("web.serpapi.failureReason", "provider-disabled");
            TraceStore.put("web.serpapi.providerEmpty", false);
            TraceStore.put("web.serpapi.afterFilterStarved", false);
            TraceStore.put("web.serpapi.rateLimited", true);
            TraceStore.put("web.serpapi.retryAfterMs", 13000L);
            TraceStore.put("web.tavily.skipped.reason", rawReason);
            TraceStore.put("web.tavily.providerDisabled", true);
            TraceStore.put("web.tavily.failureReason", "provider-disabled");
            TraceStore.put("vectorFallback.used", true);
            TraceStore.put("vectorFallback.reason", "web_empty");
            TraceStore.put("vectorFallback.effectiveTopK", 10);
            UnifiedRagOrchestrator.QueryRequest query = new UnifiedRagOrchestrator.QueryRequest();
            query.query = "private provider taxonomy query ownerToken=secret";

            Method method = ProbeConfig.class.getDeclaredMethod(
                    "logProbeConsole",
                    SearchProbeRequest.class,
                    UnifiedRagOrchestrator.QueryRequest.class,
                    UnifiedRagOrchestrator.QueryTrace.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    Environment.class,
                    SearchProbeResponse.class);
            method.setAccessible(true);

            method.invoke(null, null, query, null, true, true, true, new MockEnvironment(), null);

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("\"web.naver.skipped\":true"), logged);
            assertTrue(logged.contains("\"web.naver.skipped.reason\":\"breaker_open_or_half_open\""), logged);
            assertTrue(logged.contains("\"web.naver.requestedCount\":10"), logged);
            assertTrue(logged.contains("\"web.naver.returnedCount\":3"), logged);
            assertTrue(logged.contains("\"web.naver.afterFilterCount\":0"), logged);
            assertTrue(logged.contains("\"web.naver.timeout\":true"), logged);
            assertTrue(logged.contains("\"web.naver.timeoutMs\":1500"), logged);
            assertTrue(logged.contains("\"web.brave.skipped.reason\":\"hash:"), logged);
            assertTrue(logged.contains("\"web.serpapi.providerDisabled\":true"), logged);
            assertTrue(logged.contains("\"web.serpapi.failureReason\":\"provider-disabled\""), logged);
            assertTrue(logged.contains("\"web.serpapi.rateLimited\":true"), logged);
            assertTrue(logged.contains("\"web.serpapi.retryAfterMs\":13000"), logged);
            assertTrue(logged.contains("\"web.tavily.skipped.reason\":\"hash:"), logged);
            assertTrue(logged.contains("\"web.tavily.providerDisabled\":true"), logged);
            assertTrue(logged.contains("\"web.tavily.failureReason\":\"provider-disabled\""), logged);
            assertTrue(logged.contains("\"vectorFallback.used\":true"), logged);
            assertTrue(logged.contains("\"vectorFallback.reason\":\"web_empty\""), logged);
            assertTrue(logged.contains("\"vectorFallback.effectiveTopK\":10"), logged);
            assertFalse(logged.contains(rawReason), logged);
            assertFalse(logged.contains(query.query), logged);
            assertFalse(logged.contains("ownerToken"), logged);
            assertFalse(logged.contains("api_key=test-fixture"), logged);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void traceSelectedStageExposesRedactedProviderTaxonomyAndVectorFallback() {
        String rawReason = "disabled ownerToken=secret api_key=test-fixture";
        UnifiedRagOrchestrator orchestrator = mock(UnifiedRagOrchestrator.class);
        when(orchestrator.queryWithTrace(any(UnifiedRagOrchestrator.QueryRequest.class))).thenAnswer(invocation -> {
            TraceStore.put("web.naver.skipped", true);
            TraceStore.put("web.naver.skipped.reason", "breaker_open_or_half_open");
            TraceStore.put("web.naver.requestedCount", 10);
            TraceStore.put("web.naver.returnedCount", 3);
            TraceStore.put("web.naver.afterFilterCount", 0);
            TraceStore.put("web.naver.timeout", true);
            TraceStore.put("web.naver.timeoutMs", 1500L);
            TraceStore.put("web.brave.skipped.reason", rawReason);
            TraceStore.put("web.serpapi.providerDisabled", true);
            TraceStore.put("web.serpapi.failureReason", "provider-disabled");
            TraceStore.put("web.serpapi.rateLimited", true);
            TraceStore.put("web.serpapi.retryAfterMs", 13000L);
            TraceStore.put("web.tavily.skipped.reason", rawReason);
            TraceStore.put("web.tavily.providerDisabled", true);
            TraceStore.put("web.tavily.failureReason", "provider-disabled");
            TraceStore.put("outCount", 0);
            TraceStore.put("cacheOnly.merged.count", 1);
            TraceStore.put("tracePool.size", 4);
            TraceStore.put("rescueMerge.used", true);
            TraceStore.put("starvationFallback.poolSafeEmpty", true);
            TraceStore.put("poolSafeEmpty", true);
            TraceStore.put("retrieval.vectorFallback.used", true);
            TraceStore.put("retrieval.vectorFallback.reason", "web_empty");
            TraceStore.put("retrieval.vectorFallback.effectiveTopK", 10);
            TraceStore.put("embed.matryoshka.slice.actual", 2560);
            TraceStore.put("embed.matryoshka.slice.target", 1536);
            TraceStore.put("embed.matryoshka.slice.reductionRatio", 0.40d);
            TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsRatio", 0.6d);
            TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsSpeedup", 1.6667d);
            TraceStore.put("boosterMode.active", "EXTREMEZ");
            TraceStore.put("boosterMode.excludedModes", List.of("OVERDRIVE", "HYPERNOVA"));
            TraceStore.put("boosterMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
            TraceStore.put("boosterMode.exclusionReason", "single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE");
            TraceStore.put("routing.executionPlan.primaryMode", "EXTREMEZ");
            TraceStore.put("routing.executionPlan.triggers", List.of("lowRecall", "highRiskTail"));
            TraceStore.put("routing.executionPlan.applied", true);
            TraceStore.put("routing.executionPlan.applied.primaryMode", "EXTREMEZ");
            TraceStore.put("routing.executionPlan.applied.triggers", List.of("lowRecall", "highRiskTail"));
            TraceStore.put("routing.executionPlan.applied.stages", List.of("plan", "guard", "apply"));
            TraceStore.put("specialMode.conflict.suppressed", "OVERDRIVE,HYPERNOVA");
            TraceStore.put("retrievalOrder.lastSetBy", "PLAN_DSL");
            TraceStore.put("retrievalOrder.lastOrder", List.of("VECTOR", "KG", "WEB"));
            TraceStore.put("retrievalOrder.authority.owner", "PLAN_DSL");
            TraceStore.put("retrievalOrder.authority.suppressedOwner", "MoE");
            TraceStore.put("retrievalOrder.authority.reason", "rulebreak_speed_first");
            TraceStore.put("retrievalOrder.authority.suppressedReason", "plan_dsl_preempts_strategy_selection");
            TraceStore.put("hypernova.twpmP", 4.0d);
            TraceStore.put("hypernova.cvarFusedScore", 0.77d);
            TraceStore.put("hypernova.cvarAlpha", 0.25d);
            TraceStore.put("hypernova.cvarPhi", 0.618d);
            TraceStore.put("hypernova.clampApplied", true);
            TraceStore.put("hypernova.dppApplied", true);
            TraceStore.put("hypernova.dppInputCount", 8);
            TraceStore.put("hypernova.dppOutputCount", 6);
            TraceStore.put("hypernova.sourceScoreScaleMismatchCount", 2);
            TraceStore.put("hypernova.sourceScoreScaleMismatchPolicy", "fallback_to_base_norm");
            TraceStore.put("nova.hypernova.riskK.used", true);
            TraceStore.put("nova.hypernova.riskK.candidateCount", 3);
            TraceStore.put("nova.hypernova.riskK.totalK", 12);
            TraceStore.put("nova.hypernova.riskK.alloc.sum", 12);
            TraceStore.put("hypernova.riskKAlloc", Map.of("used", true, "totalK", 12, "sum", 12));
            TraceStore.put("llm.modelGuard.triggered", true);
            TraceStore.put("llm.modelGuard.mode", "FAIL_FAST");
            TraceStore.put("llm.modelGuard.endpoint", "/v1/chat/completions");
            TraceStore.put("llm.modelGuard.failReason", "EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH");
            TraceStore.put("llm.modelGuard.requestedModelHash", "hash:modelguard");
            TraceStore.put("llm.modelGuard.requestedModelLength", 19);
            UnifiedRagOrchestrator.QueryTrace trace = new UnifiedRagOrchestrator.QueryTrace();
            trace.response = new UnifiedRagOrchestrator.QueryResponse();
            return trace;
        });

        ProbePipeline pipeline = new ProbeConfig().probePipeline(
                orchestrator,
                null,
                new MockEnvironment().withProperty("probe.search.console-trace.enabled", "false"));
        SearchProbeRequest request = new SearchProbeRequest();
        request.query = "private selected trace query ownerToken=secret";

        SearchProbeResponse response = pipeline.execute(request);

        StageSnapshot selected = response.stages.stream()
                .filter(stage -> "trace:selected".equals(stage.name))
                .findFirst()
                .orElseThrow();
        assertTrue(Boolean.TRUE.equals(selected.params.get("web.naver.skipped")), String.valueOf(selected.params));
        assertTrue("breaker_open_or_half_open".equals(selected.params.get("web.naver.skipped.reason")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(10).equals(selected.params.get("web.naver.requestedCount")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(3).equals(selected.params.get("web.naver.returnedCount")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(0).equals(selected.params.get("web.naver.afterFilterCount")), String.valueOf(selected.params));
        assertTrue(Boolean.TRUE.equals(selected.params.get("web.naver.timeout")), String.valueOf(selected.params));
        assertTrue(Long.valueOf(1500L).equals(selected.params.get("web.naver.timeoutMs")), String.valueOf(selected.params));
        assertTrue(String.valueOf(selected.params.get("web.brave.skipped.reason")).startsWith("hash:"), String.valueOf(selected.params));
        assertTrue(Boolean.TRUE.equals(selected.params.get("web.serpapi.providerDisabled")), String.valueOf(selected.params));
        assertTrue("provider-disabled".equals(selected.params.get("web.serpapi.failureReason")), String.valueOf(selected.params));
        assertTrue(Boolean.TRUE.equals(selected.params.get("web.serpapi.rateLimited")), String.valueOf(selected.params));
        assertTrue(Long.valueOf(13000L).equals(selected.params.get("web.serpapi.retryAfterMs")), String.valueOf(selected.params));
        assertTrue(String.valueOf(selected.params.get("web.tavily.skipped.reason")).startsWith("hash:"), String.valueOf(selected.params));
        assertTrue(Boolean.TRUE.equals(selected.params.get("web.tavily.providerDisabled")), String.valueOf(selected.params));
        assertTrue("provider-disabled".equals(selected.params.get("web.tavily.failureReason")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(0).equals(selected.params.get("outCount")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(1).equals(selected.params.get("cacheOnly.merged.count")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(4).equals(selected.params.get("tracePool.size")), String.valueOf(selected.params));
        assertTrue(Boolean.TRUE.equals(selected.params.get("rescueMerge.used")), String.valueOf(selected.params));
        assertTrue(Boolean.TRUE.equals(selected.params.get("starvationFallback.poolSafeEmpty")), String.valueOf(selected.params));
        assertTrue(Boolean.TRUE.equals(selected.params.get("poolSafeEmpty")), String.valueOf(selected.params));
        assertTrue(Boolean.TRUE.equals(selected.params.get("vectorFallback.used")), String.valueOf(selected.params));
        assertTrue("web_empty".equals(selected.params.get("vectorFallback.reason")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(10).equals(selected.params.get("vectorFallback.effectiveTopK")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(2560).equals(selected.params.get("embed.matryoshka.slice.actual")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(1536).equals(selected.params.get("embed.matryoshka.slice.target")), String.valueOf(selected.params));
        assertTrue(Double.valueOf(0.40d).equals(selected.params.get("embed.matryoshka.slice.reductionRatio")), String.valueOf(selected.params));
        assertTrue(Double.valueOf(0.6d).equals(selected.params.get("embed.matryoshka.slice.expectedDistanceOpsRatio")), String.valueOf(selected.params));
        assertTrue(Double.valueOf(1.6667d).equals(selected.params.get("embed.matryoshka.slice.expectedDistanceOpsSpeedup")), String.valueOf(selected.params));
        assertEquals("EXTREMEZ", selected.params.get("boosterMode.active"));
        assertEquals(List.of("OVERDRIVE", "HYPERNOVA"), selected.params.get("boosterMode.excludedModes"));
        assertEquals("EXTREMEZ>HYPERNOVA>OVERDRIVE", selected.params.get("boosterMode.priority"));
        assertEquals("single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE", selected.params.get("boosterMode.exclusionReason"));
        assertEquals("EXTREMEZ", selected.params.get("routing.executionPlan.primaryMode"));
        assertEquals(List.of("lowRecall", "highRiskTail"), selected.params.get("routing.executionPlan.triggers"));
        assertEquals(true, selected.params.get("routing.executionPlan.applied"));
        assertEquals("EXTREMEZ", selected.params.get("routing.executionPlan.applied.primaryMode"));
        assertEquals(List.of("lowRecall", "highRiskTail"), selected.params.get("routing.executionPlan.applied.triggers"));
        assertEquals(List.of("plan", "guard", "apply"), selected.params.get("routing.executionPlan.applied.stages"));
        assertEquals("OVERDRIVE,HYPERNOVA", selected.params.get("specialMode.conflict.suppressed"));
        assertEquals("PLAN_DSL", selected.params.get("retrievalOrder.lastSetBy"));
        assertEquals(List.of("VECTOR", "KG", "WEB"), selected.params.get("retrievalOrder.lastOrder"));
        assertEquals("PLAN_DSL", selected.params.get("retrievalOrder.authority.owner"));
        assertEquals("MoE", selected.params.get("retrievalOrder.authority.suppressedOwner"));
        assertEquals("rulebreak_speed_first", selected.params.get("retrievalOrder.authority.reason"));
        assertEquals("plan_dsl_preempts_strategy_selection",
                selected.params.get("retrievalOrder.authority.suppressedReason"));
        assertEquals(4.0d, selected.params.get("hypernova.twpmP"));
        assertEquals(0.77d, selected.params.get("hypernova.cvarFusedScore"));
        assertEquals(0.25d, selected.params.get("hypernova.cvarAlpha"));
        assertEquals(0.618d, selected.params.get("hypernova.cvarPhi"));
        assertEquals(true, selected.params.get("hypernova.clampApplied"));
        assertEquals(true, selected.params.get("hypernova.dppApplied"));
        assertEquals(8, selected.params.get("hypernova.dppInputCount"));
        assertEquals(6, selected.params.get("hypernova.dppOutputCount"));
        assertEquals(2, selected.params.get("hypernova.sourceScoreScaleMismatchCount"));
        assertEquals("fallback_to_base_norm", selected.params.get("hypernova.sourceScoreScaleMismatchPolicy"));
        assertEquals(true, selected.params.get("nova.hypernova.riskK.used"));
        assertEquals(3, selected.params.get("nova.hypernova.riskK.candidateCount"));
        assertEquals(12, selected.params.get("nova.hypernova.riskK.totalK"));
        assertEquals(12, selected.params.get("nova.hypernova.riskK.alloc.sum"));
        @SuppressWarnings("unchecked")
        Map<String, Object> riskKAlloc = (Map<String, Object>) selected.params.get("hypernova.riskKAlloc");
        assertEquals(true, riskKAlloc.get("used"));
        assertEquals(12, riskKAlloc.get("totalK"));
        assertEquals(12, riskKAlloc.get("sum"));
        assertEquals(true, selected.params.get("llm.modelGuard.triggered"));
        assertEquals("FAIL_FAST", selected.params.get("llm.modelGuard.mode"));
        assertEquals("/v1/chat/completions", selected.params.get("llm.modelGuard.endpoint"));
        assertEquals("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH", selected.params.get("llm.modelGuard.failReason"));
        assertEquals("hash:modelguard", selected.params.get("llm.modelGuard.requestedModelHash"));
        assertEquals(19, selected.params.get("llm.modelGuard.requestedModelLength"));
        String rendered = String.valueOf(selected.params);
        assertFalse(rendered.contains(rawReason), rendered);
        assertFalse(rendered.contains("ownerToken"), rendered);
        assertFalse(rendered.contains("api_key=test-fixture"), rendered);
    }

    @Test
    void traceSelectedStageDerivesTracePoolKpisFromPoolItems() {
        UnifiedRagOrchestrator orchestrator = mock(UnifiedRagOrchestrator.class);
        when(orchestrator.queryWithTrace(any(UnifiedRagOrchestrator.QueryRequest.class))).thenAnswer(invocation -> {
            TraceStore.put("outCount", 0);
            TraceStore.append("tracePool.items", Map.of("kind", "cacheOnly", "rank", 1));
            TraceStore.append("tracePool.items", Map.of("kind", "cacheOnly", "rank", 2));
            UnifiedRagOrchestrator.QueryTrace trace = new UnifiedRagOrchestrator.QueryTrace();
            trace.response = new UnifiedRagOrchestrator.QueryResponse();
            return trace;
        });

        ProbePipeline pipeline = new ProbeConfig().probePipeline(
                orchestrator,
                null,
                new MockEnvironment().withProperty("probe.search.console-trace.enabled", "false"));
        SearchProbeRequest request = new SearchProbeRequest();
        request.query = "private selected trace pool query ownerToken=secret";

        SearchProbeResponse response = pipeline.execute(request);

        StageSnapshot selected = response.stages.stream()
                .filter(stage -> "trace:selected".equals(stage.name))
                .findFirst()
                .orElseThrow();
        assertEquals(2L, ((Number) selected.params.get("tracePool.size")).longValue());
        assertEquals(Boolean.TRUE, selected.params.get("rescueMerge.used"));
    }

    @Test
    void traceSelectedStageSeedsRuntimeHarmonyRouteKeysWhenOrchestratorLeavesThemEmpty() {
        UnifiedRagOrchestrator orchestrator = mock(UnifiedRagOrchestrator.class);
        when(orchestrator.queryWithTrace(any(UnifiedRagOrchestrator.QueryRequest.class))).thenAnswer(invocation -> {
            UnifiedRagOrchestrator.QueryTrace trace = new UnifiedRagOrchestrator.QueryTrace();
            trace.response = new UnifiedRagOrchestrator.QueryResponse();
            return trace;
        });

        ProbeConfig config = new ProbeConfig();
        ReflectionTestUtils.setField(config, "retrievalOrderService", new RetrievalOrderService());
        ProbePipeline pipeline = config.probePipeline(
                orchestrator,
                null,
                new MockEnvironment().withProperty("probe.search.console-trace.enabled", "false"));
        SearchProbeRequest request = new SearchProbeRequest();
        request.query = "private route harmony query ownerToken=secret";
        request.flags.seedOnly = true;
        request.flags.useWeb = false;
        request.flags.useRag = false;

        SearchProbeResponse response = pipeline.execute(request);

        StageSnapshot selected = response.stages.stream()
                .filter(stage -> "trace:selected".equals(stage.name))
                .findFirst()
                .orElseThrow();
        assertEquals(1.0d, selected.params.get("ablation.score.current"));
        assertEquals("DEFAULT", selected.params.get("retrievalOrder.lastSetBy"));
        assertEquals("NONE", selected.params.get("boosterMode.active"));
        assertEquals(Boolean.FALSE, selected.params.get("boosterMode.conflictResolved"));
        assertEquals("NORMAL", selected.params.get("routing.executionPlan.primaryMode"));
        String rendered = String.valueOf(selected.params);
        assertFalse(rendered.contains("ownerToken"), rendered);
        assertFalse(rendered.contains(request.query), rendered);
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceSelectedStagePublishesExtremeZCancelShieldStateFromRuntimeHandler() {
        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        try {
            ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                    null, null, null, null, null, null, new ExtremeZProperties(), rawExecutor);
            TraceStore.clear();
            ObjectProvider<ExtremeZSystemHandler> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(handler);
            UnifiedRagOrchestrator orchestrator = mock(UnifiedRagOrchestrator.class);
            when(orchestrator.queryWithTrace(any(UnifiedRagOrchestrator.QueryRequest.class))).thenAnswer(invocation -> {
                UnifiedRagOrchestrator.QueryTrace trace = new UnifiedRagOrchestrator.QueryTrace();
                trace.response = new UnifiedRagOrchestrator.QueryResponse();
                return trace;
            });

            ProbeConfig config = new ProbeConfig();
            ReflectionTestUtils.setField(config, "extremeZSystemHandlerProvider", provider);
            ProbePipeline pipeline = config.probePipeline(
                    orchestrator,
                    null,
                    new MockEnvironment().withProperty("probe.search.console-trace.enabled", "false"));
            SearchProbeRequest request = new SearchProbeRequest();
            request.query = "private extremez cancel shield ownerToken=secret";
            request.flags.seedOnly = true;
            request.flags.useWeb = false;
            request.flags.useRag = false;

            SearchProbeResponse response = pipeline.execute(request);

            StageSnapshot selected = response.stages.stream()
                    .filter(stage -> "trace:selected".equals(stage.name))
                    .findFirst()
                    .orElseThrow();
            assertEquals(Boolean.TRUE, selected.params.get("extremeZ.cancelShieldWrapped"));
            assertEquals(0L, selected.params.get("extremeZ.timeBudgetConsumedMs"));
            String rendered = String.valueOf(selected.params);
            assertFalse(rendered.contains("ownerToken"), rendered);
            assertFalse(rendered.contains(request.query), rendered);
        } finally {
            rawExecutor.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceSelectedStagePublishesCfvmTemperatureStateFromRuntimeBuffer() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        buffer.setBoltzmannTemp(0.42d);
        TraceStore.clear();
        ObjectProvider<RawMatrixBuffer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(buffer);
        UnifiedRagOrchestrator orchestrator = mock(UnifiedRagOrchestrator.class);
        when(orchestrator.queryWithTrace(any(UnifiedRagOrchestrator.QueryRequest.class))).thenAnswer(invocation -> {
            UnifiedRagOrchestrator.QueryTrace trace = new UnifiedRagOrchestrator.QueryTrace();
            trace.response = new UnifiedRagOrchestrator.QueryResponse();
            return trace;
        });

        ProbeConfig config = new ProbeConfig();
        ReflectionTestUtils.setField(config, "rawMatrixBufferProvider", provider);
        ProbePipeline pipeline = config.probePipeline(
                orchestrator,
                null,
                new MockEnvironment().withProperty("probe.search.console-trace.enabled", "false"));
        SearchProbeRequest request = new SearchProbeRequest();
        request.query = "private cfvm temperature ownerToken=secret";
        request.flags.seedOnly = true;
        request.flags.useWeb = false;
        request.flags.useRag = false;

        SearchProbeResponse response = pipeline.execute(request);

        StageSnapshot selected = response.stages.stream()
                .filter(stage -> "trace:selected".equals(stage.name))
                .findFirst()
                .orElseThrow();
        assertEquals(0.42d, (Double) selected.params.get("cfvm.boltzmannTemp"), 0.0001d);
        assertEquals(Boolean.TRUE, selected.params.get("cfvm.tempAnnealApplied"));
        String rendered = String.valueOf(selected.params);
        assertFalse(rendered.contains("ownerToken"), rendered);
        assertFalse(rendered.contains(request.query), rendered);
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceSelectedStagePublishesMoeEvolverRegistrationFromRuntimeSelector() {
        ObjectProvider<ArtPlateEvolver> evolverProvider = mock(ObjectProvider.class);
        ObjectProvider<ArtPlateRegistry> registryProvider = mock(ObjectProvider.class);
        when(evolverProvider.getIfAvailable()).thenReturn(new ArtPlateEvolver());
        when(registryProvider.getIfAvailable()).thenReturn(new ArtPlateRegistry());
        RgbStrategySelector selector = new RgbStrategySelector(evolverProvider, registryProvider);
        ObjectProvider<RgbStrategySelector> selectorProvider = mock(ObjectProvider.class);
        when(selectorProvider.getIfAvailable()).thenReturn(selector);
        UnifiedRagOrchestrator orchestrator = mock(UnifiedRagOrchestrator.class);
        when(orchestrator.queryWithTrace(any(UnifiedRagOrchestrator.QueryRequest.class))).thenAnswer(invocation -> {
            UnifiedRagOrchestrator.QueryTrace trace = new UnifiedRagOrchestrator.QueryTrace();
            trace.response = new UnifiedRagOrchestrator.QueryResponse();
            return trace;
        });

        ProbeConfig config = new ProbeConfig();
        ReflectionTestUtils.setField(config, "rgbStrategySelectorProvider", selectorProvider);
        ProbePipeline pipeline = config.probePipeline(
                orchestrator,
                null,
                new MockEnvironment().withProperty("probe.search.console-trace.enabled", "false"));
        SearchProbeRequest request = new SearchProbeRequest();
        request.query = "private moe evolver ownerToken=secret";
        request.flags.seedOnly = true;
        request.flags.useWeb = false;
        request.flags.useRag = false;

        SearchProbeResponse response = pipeline.execute(request);

        StageSnapshot selected = response.stages.stream()
                .filter(stage -> "trace:selected".equals(stage.name))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.TRUE, selected.params.get("moe.evolverPlateRegistered"));
        assertEquals(Boolean.TRUE, selected.params.get("moe.artplate.evolver.available"));
        String rendered = String.valueOf(selected.params);
        assertFalse(rendered.contains("ownerToken"), rendered);
        assertFalse(rendered.contains(request.query), rendered);
    }

    @Test
    void ecosystemBufferSeedModeChargesPoolAndExposesSelectedTraceWithoutRawSeedText() {
        String rawSeedSnippet = "bounded recirculation smoke seed Authorization: Bearer secret";
        EcosystemBufferPool pool = new EcosystemBufferPool(4, 300_000L, 2);
        UnifiedRagOrchestrator orchestrator = mock(UnifiedRagOrchestrator.class);
        when(orchestrator.queryWithTrace(any(UnifiedRagOrchestrator.QueryRequest.class))).thenAnswer(invocation -> {
            UnifiedRagOrchestrator.QueryRequest q = invocation.getArgument(0);
            assertTrue("ecosystem-buffer".equals(q.seedMode), q.seedMode);
            assertTrue(q.seedCandidates == null || q.seedCandidates.isEmpty(), String.valueOf(q.seedCandidates));
            EcosystemBufferPool.Recirculation scan = pool.recirculateSafe(2, snippet -> false);
            pool.recordRecirculationScan(scan);
            pool.recordRecirculationSelection(scan.safe().size());
            TraceStore.put("starvationFallback.trigger", "BELOW_MIN_CITATIONS");
            TraceStore.put("web.failsoft.starvationFallback.trigger", null);
            TraceStore.put("web.failsoft.starvationFallback", null);
            TraceStore.put("web.failsoft.starvationFallback.used", true);
            TraceStore.put("web.failsoft.starvationFallback.poolUsed", "NOFILTER_SAFE");
            TraceStore.put("web.failsoft.starvationFallback.pool.safe.size", 2);
            TraceStore.put("web.failsoft.starvationFallback.pool.dev.size", 0);
            TraceStore.put("web.failsoft.starvationFallback.count", "1");
            TraceStore.put("web.failsoft.starvationFallback.added", 1);
            TraceStore.put("poolSafeEmpty", true);
            UnifiedRagOrchestrator.QueryTrace trace = new UnifiedRagOrchestrator.QueryTrace();
            trace.response = new UnifiedRagOrchestrator.QueryResponse();
            UnifiedRagOrchestrator.Doc recirculated = new UnifiedRagOrchestrator.Doc();
            recirculated.title = "docs seed";
            recirculated.snippet = rawSeedSnippet;
            recirculated.id = "https://docs.example.com/ecosystem-smoke";
            recirculated.source = "WEB";
            recirculated.rank = 1;
            trace.web = List.of(recirculated);
            trace.pool = List.of(recirculated);
            trace.finalResults = List.of(recirculated);
            return trace;
        });

        ProbeConfig config = new ProbeConfig();
        ReflectionTestUtils.setField(config, "ecosystemBufferPool", pool);
        ProbePipeline pipeline = config.probePipeline(
                orchestrator,
                null,
                new MockEnvironment()
                        .withProperty("probe.search.console-trace.enabled", "false")
                        .withProperty("probe.search.ecosystem-buffer-seed.enabled", "true"));
        SearchProbeRequest request = new SearchProbeRequest();
        request.query = "private ecosystem probe query ownerToken=secret";
        request.seedMode = "ecosystem-buffer";
        request.webTopK = 3;
        CandidateDTO seed = new CandidateDTO();
        seed.title = "docs seed";
        seed.snippet = rawSeedSnippet;
        seed.url = "https://docs.example.com/ecosystem-smoke";
        seed.source = "WEB";
        seed.score = 0.9d;
        seed.rank = 1;
        request.seed = List.of(seed);

        SearchProbeResponse response = pipeline.execute(request);

        StageSnapshot seedStage = response.stages.stream()
                .filter(stage -> "ecosystem:buffer-seed".equals(stage.name))
                .findFirst()
                .orElseThrow();
        assertTrue(Boolean.TRUE.equals(seedStage.params.get("enabled")), String.valueOf(seedStage.params));
        assertTrue(Integer.valueOf(1).equals(seedStage.params.get("seedCount")), String.valueOf(seedStage.params));
        assertTrue(Integer.valueOf(1).equals(seedStage.params.get("chargedCount")), String.valueOf(seedStage.params));
        assertTrue(String.valueOf(seedStage.params.get("seedHash12")).matches("[0-9a-f]{12}"),
                String.valueOf(seedStage.params));

        StageSnapshot selected = response.stages.stream()
                .filter(stage -> "trace:selected".equals(stage.name))
                .findFirst()
                .orElseThrow();
        assertTrue(Boolean.TRUE.equals(selected.params.get("ecosystem.recirculate.used")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(1).equals(selected.params.get("ecosystem.recirculate.safe")), String.valueOf(selected.params));
        assertTrue("ecosystem->NOFILTER_SAFE".equals(selected.params.get("starvationFallback.trigger")), String.valueOf(selected.params));
        assertTrue(Boolean.TRUE.equals(selected.params.get("starvationFallback.used")), String.valueOf(selected.params));
        assertTrue("NOFILTER_SAFE".equals(selected.params.get("starvationFallback.poolUsed")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(2).equals(selected.params.get("starvationFallback.pool.safe.size")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(0).equals(selected.params.get("starvationFallback.pool.dev.size")), String.valueOf(selected.params));
        assertTrue("1".equals(selected.params.get("starvationFallback.count")), String.valueOf(selected.params));
        assertTrue(Integer.valueOf(1).equals(selected.params.get("starvationFallback.added")), String.valueOf(selected.params));
        assertTrue(Boolean.FALSE.equals(selected.params.get("poolSafeEmpty")), String.valueOf(selected.params));
        assertTrue(response.finalResults.isEmpty(), String.valueOf(response.finalResults));

        String rendered = String.valueOf(response.stages) + response.finalResults;
        assertFalse(rendered.contains(rawSeedSnippet), rendered);
        assertFalse(rendered.contains("Authorization"), rendered);
        assertFalse(rendered.contains("ownerToken=secret"), rendered);
    }
}
