package com.example.lms.agent.context;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.io.TempDir;

class AgentPipelineHealthControllerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void pipelineHealthSummarizesDbSnapshotsAndRedactedLaneState() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of(
                "ACTIVE", 4L,
                "PENDING", 1L,
                "QUARANTINED", 0L,
                "STALE", 2L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of(Map.of(
                "strategyName", "ArtPlate",
                "successRate", 0.75d,
                "sampleCount", 4L,
                "averageReward", 0.7d,
                "updatedAt", "2026-06-01T10:00:00"))));
        when(provider.ledgerSnapshot()).thenReturn(ledger(
                List.of(Map.of("hotspot", "webSearch.naver", "count", 3L)),
                List.of(Map.of("hotspot", "webSearch.naver", "createdAt", "2026-06-02T10:00:00"))));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "webRetriever", mock(ContentRetriever.class));
        ReflectionTestUtils.setField(controller, "vectorRetriever", mock(ContentRetriever.class));
        ReflectionTestUtils.setField(controller, "kgRetriever", mock(ContentRetriever.class));
        ReflectionTestUtils.setField(controller, "onnxReranker", new Object());
        ReflectionTestUtils.setField(controller, "queryTransformer", mock(QueryTransformer.class));
        ReflectionTestUtils.setField(controller, "promptInjector", mock(AgentDbContextPromptInjector.class));
        ReflectionTestUtils.setField(controller, "nightmareBreaker", mock(NightmareBreaker.class));
        ReflectionTestUtils.setField(controller, "naverKeys", "id:secret-raw-value");
        ReflectionTestUtils.setField(controller, "onnxModelPath", "models/reranker.onnx");

        Map<String, Object> health = controller.pipelineHealth();

        assertNotNull(health.get("capturedAt"));
        Map<String, Object> memoryGate = map(health.get("memoryGate"));
        assertEquals("OK", memoryGate.get("status"));
        assertEquals(7L, memoryGate.get("total"));
        assertEquals(4L, memoryGate.get("active"));
        assertEquals(1L, memoryGate.get("pending"));
        assertEquals(0L, memoryGate.get("quarantined"));
        assertEquals(2L, memoryGate.get("stale"));

        Map<String, Object> web = lane(health, "webSearch");
        assertEquals("OK", web.get("status"));
        assertEquals(Boolean.TRUE, web.get("enabled"));
        assertFalse(health.toString().contains("secret-raw-value"));

        Map<String, Object> onnx = lane(health, "onnx");
        assertEquals("OK", onnx.get("status"));

        assertEquals("ArtPlate", map(list(health.get("strategyPerformances")).get(0)).get("strategyName"));
        assertEquals("webSearch.naver", map(list(health.get("hotspotDistribution")).get(0)).get("hotspot"));
        assertEquals("2026-06-02T10:00:00", map(list(health.get("recentFailures")).get(0)).get("createdAt"));
    }

    @Test
    void pipelineHealthClassifiesMemoryAndLaneWarnings() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of(
                "ACTIVE", 2L,
                "PENDING", 1L,
                "QUARANTINED", 2L,
                "STALE", 0L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("queryTransformer.bypassed", true);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "webRetriever", mock(ContentRetriever.class));
        ReflectionTestUtils.setField(controller, "queryTransformer", mock(QueryTransformer.class));
        ReflectionTestUtils.setField(controller, "naverKeys", "${NAVER_KEYS}");
        ReflectionTestUtils.setField(controller, "naverClientId", "");
        ReflectionTestUtils.setField(controller, "onnxModelPath", "");

        Map<String, Object> health = controller.pipelineHealth();

        Map<String, Object> memoryGate = map(health.get("memoryGate"));
        assertEquals("WARN", memoryGate.get("status"));
        assertEquals("quarantine_ratio_40_pct", memoryGate.get("reason"));

        Map<String, Object> web = lane(health, "webSearch");
        assertEquals("DISABLED", web.get("status"));
        assertEquals("web_provider_no_key", web.get("disabledReason"));

        Map<String, Object> vector = lane(health, "vectorSearch");
        assertEquals("DISABLED", vector.get("status"));
        assertEquals("bean_missing", vector.get("disabledReason"));

        Map<String, Object> onnx = lane(health, "onnx");
        assertEquals("DISABLED", onnx.get("status"));
        assertEquals("bean_missing", onnx.get("disabledReason"));

        Map<String, Object> qtx = lane(health, "queryTransformer");
        assertEquals("WARN", qtx.get("status"));
        assertEquals("bypassed", qtx.get("disabledReason"));
    }

    @Test
    void pipelineHealthShowsCausalProbeLaneFromTraceWithoutRawValues() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("causalProbe.evidenceReady", true);
        TraceStore.put("causalProbe.triggerReason", "axis_agreement");
        TraceStore.put("causalProbe.dominantFailure", "after_filter_starvation");
        TraceStore.put("causalProbe.action", "source_patch_candidate");
        TraceStore.put("causalProbe.confidence", 0.875d);
        TraceStore.put("causalProbe.axisCount", 3);
        TraceStore.put("causalProbe.where", "NeedleOutcomeRewarder.recordOutcome");
        TraceStore.put("rawQuery", "private raw query must not leak");

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);

        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> causal = lane(health, "causalProbe");

        assertEquals("WARN", causal.get("status"));
        assertEquals(Boolean.TRUE, causal.get("enabled"));
        assertEquals("axis_agreement", causal.get("disabledReason"));
        assertEquals("after_filter_starvation", causal.get("dominantFailure"));
        assertEquals("source_patch_candidate", causal.get("action"));
        assertEquals(0.875d, (Double) causal.get("confidence"), 0.0001d);
        assertEquals(3, causal.get("axisCount"));
        assertEquals("needleoutcomerewarder.recordoutcome", causal.get("where"));
        assertTrue(String.valueOf(causal.get("detail")).contains("dominant=after_filter_starvation"));
        assertFalse(health.toString().contains("private raw query must not leak"));
    }

    @Test
    void pipelineHealthMergesSessionScopedProbeRoundSnapshotsWithoutRawValues() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("sessionId", "hash:session-1");

        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        Map<String, Object> startTrace = Map.ofEntries(
                Map.entry("orch.probeRound.roundRef", "hash:aaaaaaaaaaaa"),
                Map.entry("orch.probeRound.phase", "NORMALIZATION_REQUIRED"),
                Map.entry("orch.probeRound.hypothesis", "after_filter_starvation"),
                Map.entry("orch.probeRound.patchCandidate", "source_patch_candidate"),
                Map.entry("orch.probeRound.causalConfidence", 0.74d),
                Map.entry("orch.probeRound.counterEvidenceCount", 3),
                Map.entry("orch.probeRound.retrievalConfidence", 0.82d),
                Map.entry("orch.probeRound.verificationGatePassed", false),
                Map.entry("rawQuery", "private start claim"));
        Map<String, Object> resumeTrace = Map.ofEntries(
                Map.entry("orch.probeRound.roundRef", "hash:aaaaaaaaaaaa"),
                Map.entry("orch.probeRound.phase", "VERIFIED"),
                Map.entry("orch.probeRound.coherenceStatus", "consistent"),
                Map.entry("orch.probeRound.releaseStatus", "approve"),
                Map.entry("orch.probeRound.confidenceRange", "high_to_high"),
                Map.entry("orch.probeRound.verifiedEvidenceCount", 3),
                Map.entry("orch.probeRound.verificationGatePassed", true),
                Map.entry("rawEvidence", "private verifier evidence"));
        Map<String, Object> foreignTrace = Map.of(
                "orch.probeRound.phase", "VERIFIED",
                "orch.probeRound.hypothesis", "foreign_private_hypothesis");
        TraceSnapshotStore.TraceSnapshot resume = probeSnapshot(
                "snap-resume", "hash:session-1", "/internal/agent/probe-round:resume", resumeTrace);
        TraceSnapshotStore.TraceSnapshot start = probeSnapshot(
                "snap-start", "hash:session-1", "/internal/agent/probe-round:start", startTrace);
        TraceSnapshotStore.TraceSnapshot foreign = probeSnapshot(
                "snap-foreign", "hash:session-2", "/internal/agent/probe-round:resume", foreignTrace);
        when(snapshotStore.listSummaries(20)).thenReturn(List.of(
                Map.of("id", "snap-foreign"), Map.of("id", "snap-resume"), Map.of("id", "snap-start")));
        when(snapshotStore.listSummaries(50)).thenReturn(List.of(
                Map.of("id", "snap-foreign"), Map.of("id", "snap-resume"), Map.of("id", "snap-start")));
        when(snapshotStore.get("snap-foreign")).thenReturn(Optional.of(foreign));
        when(snapshotStore.get("snap-resume")).thenReturn(Optional.of(resume));
        when(snapshotStore.get("snap-start")).thenReturn(Optional.of(start));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);

        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> probeRound = lane(health, "causalProbe");

        assertEquals("OK", probeRound.get("status"));
        assertEquals("VERIFIED", probeRound.get("phase"));
        assertEquals("after_filter_starvation", probeRound.get("dominantFailure"));
        assertEquals("source_patch_candidate", probeRound.get("patchCandidate"));
        assertEquals(0.74d, probeRound.get("confidence"));
        assertEquals(3, probeRound.get("counterEvidenceCount"));
        assertEquals(0.82d, probeRound.get("retrievalConfidence"));
        assertEquals("consistent", probeRound.get("coherenceStatus"));
        assertEquals("approve", probeRound.get("releaseStatus"));
        assertEquals("high_to_high", probeRound.get("confidenceRange"));
        assertEquals(Boolean.TRUE, probeRound.get("verificationGatePassed"));
        assertEquals("trace:probeRound", probeRound.get("source"));
        assertFalse(health.toString().contains("private start claim"));
        assertFalse(health.toString().contains("private verifier evidence"));
        assertFalse(health.toString().contains("foreign_private_hypothesis"));
    }

    @Test
    void pipelineHealthDoesNotBorrowStartEvidenceFromAnOlderRoundInTheSameSession() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("sessionId", "hash:session-1");

        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        Map<String, Object> newestResumeTrace = Map.ofEntries(
                Map.entry("orch.probeRound.roundRef", "hash:aaaaaaaaaaaa"),
                Map.entry("orch.probeRound.phase", "VERIFIED"),
                Map.entry("orch.probeRound.coherenceStatus", "consistent"),
                Map.entry("orch.probeRound.releaseStatus", "approve"),
                Map.entry("orch.probeRound.confidenceRange", "medium_to_high"),
                Map.entry("orch.probeRound.verifiedEvidenceCount", 2),
                Map.entry("orch.probeRound.verificationGatePassed", true));
        Map<String, Object> olderStartTrace = Map.ofEntries(
                Map.entry("orch.probeRound.roundRef", "hash:bbbbbbbbbbbb"),
                Map.entry("orch.probeRound.phase", "NORMALIZATION_REQUIRED"),
                Map.entry("orch.probeRound.hypothesis", "stale_round_hypothesis"),
                Map.entry("orch.probeRound.patchCandidate", "stale_round_patch"),
                Map.entry("orch.probeRound.causalConfidence", 0.99d),
                Map.entry("orch.probeRound.counterEvidenceCount", 9),
                Map.entry("orch.probeRound.retrievalConfidence", 0.98d),
                Map.entry("rawQuery", "private stale round claim"));
        TraceSnapshotStore.TraceSnapshot newestResume = probeSnapshot(
                "snap-new-resume", "hash:session-1", "/internal/agent/probe-round:resume", newestResumeTrace);
        TraceSnapshotStore.TraceSnapshot olderStart = probeSnapshot(
                "snap-old-start", "hash:session-1", "/internal/agent/probe-round:start", olderStartTrace);
        List<Map<String, Object>> summaries = List.of(
                Map.of("id", "snap-new-resume"), Map.of("id", "snap-old-start"));
        when(snapshotStore.listSummaries(20)).thenReturn(summaries);
        when(snapshotStore.listSummaries(50)).thenReturn(summaries);
        when(snapshotStore.get("snap-new-resume")).thenReturn(Optional.of(newestResume));
        when(snapshotStore.get("snap-old-start")).thenReturn(Optional.of(olderStart));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);

        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> probeRound = lane(health, "causalProbe");

        assertEquals("VERIFIED", probeRound.get("phase"));
        assertEquals("none", probeRound.get("dominantFailure"));
        assertEquals("none", probeRound.get("patchCandidate"));
        assertEquals(0.0d, probeRound.get("confidence"));
        assertEquals(0, probeRound.get("counterEvidenceCount"));
        assertEquals(0.0d, probeRound.get("retrievalConfidence"));
        assertEquals("medium_to_high", probeRound.get("confidenceRange"));
        assertFalse(health.toString().contains("stale_round_hypothesis"));
        assertFalse(health.toString().contains("stale_round_patch"));
        assertFalse(health.toString().contains("private stale round claim"));
    }

    @Test
    void pipelineHealthStopsAtNewestUncorrelatedProbeRoundInsteadOfResurrectingOlderRound() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("sessionId", "hash:session-1");

        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        Map<String, Object> newestRefLessTrace = Map.ofEntries(
                Map.entry("orch.probeRound.phase", "CAUSAL_ONLY"),
                Map.entry("orch.probeRound.reason", "operator_authority_not_issued"),
                Map.entry("orch.probeRound.verificationGatePassed", false));
        Map<String, Object> olderVerifiedTrace = Map.ofEntries(
                Map.entry("orch.probeRound.roundRef", "hash:cccccccccccc"),
                Map.entry("orch.probeRound.phase", "VERIFIED"),
                Map.entry("orch.probeRound.hypothesis", "resurrected_old_hypothesis"),
                Map.entry("orch.probeRound.patchCandidate", "resurrected_old_patch"),
                Map.entry("orch.probeRound.coherenceStatus", "consistent"),
                Map.entry("orch.probeRound.releaseStatus", "approve"),
                Map.entry("orch.probeRound.verificationGatePassed", true));
        TraceSnapshotStore.TraceSnapshot newestRefLess = probeSnapshot(
                "snap-new-ref-less", "hash:session-1", "/internal/agent/probe-round:start", newestRefLessTrace);
        TraceSnapshotStore.TraceSnapshot olderVerified = probeSnapshot(
                "snap-old-verified", "hash:session-1", "/internal/agent/probe-round:resume", olderVerifiedTrace);
        List<Map<String, Object>> summaries = List.of(
                Map.of("id", "snap-new-ref-less"), Map.of("id", "snap-old-verified"));
        when(snapshotStore.listSummaries(20)).thenReturn(summaries);
        when(snapshotStore.listSummaries(50)).thenReturn(summaries);
        when(snapshotStore.get("snap-new-ref-less")).thenReturn(Optional.of(newestRefLess));
        when(snapshotStore.get("snap-old-verified")).thenReturn(Optional.of(olderVerified));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);

        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> probeRound = lane(health, "causalProbe");

        assertEquals("CAUSAL_ONLY", probeRound.get("phase"));
        assertEquals("none", probeRound.get("dominantFailure"));
        assertEquals("none", probeRound.get("patchCandidate"));
        assertEquals(Boolean.FALSE, probeRound.get("verificationGatePassed"));
        assertFalse(health.toString().contains("resurrected_old_hypothesis"));
        assertFalse(health.toString().contains("resurrected_old_patch"));
    }

    @Test
    void onnxLaneUsesRuntimePlaceholderReasonWhenModelPathIsNotUsable() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "onnxReranker", new Object());
        ReflectionTestUtils.setField(controller, "onnxModelPath", "classpath:models/your-cross-encoder.onnx");

        Map<String, Object> onnx = lane(controller.pipelineHealth(), "onnx");

        assertEquals("DISABLED", onnx.get("status"));
        assertEquals("placeholder_or_too_small_model", onnx.get("disabledReason"));
    }

    @Test
    void webLaneAcceptsBraveCredentialWithoutNaverKey() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "webRetriever", mock(ContentRetriever.class));
        ReflectionTestUtils.setField(controller, "naverKeys", "");
        ReflectionTestUtils.setField(controller, "naverClientId", "");
        ReflectionTestUtils.setField(controller, "naverClientSecret", "");
        ReflectionTestUtils.setField(controller, "braveKey", "brave-secret-raw-value");

        Map<String, Object> health = controller.pipelineHealth();

        Map<String, Object> web = lane(health, "webSearch");
        assertEquals("OK", web.get("status"));
        assertEquals(Boolean.TRUE, web.get("enabled"));
        assertFalse(health.toString().contains("brave-secret-raw-value"));
    }

    @Test
    void pipelineHealthShowsProviderCredentialStatesWithoutSecretValues() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "naverKeys", "");
        ReflectionTestUtils.setField(controller, "naverClientId", "naver-client-id-raw");
        ReflectionTestUtils.setField(controller, "naverClientSecret", "naver-client-secret-raw");
        ReflectionTestUtils.setField(controller, "braveKey", "");
        ReflectionTestUtils.setField(controller, "serpApiKey", "serpapi-secret-raw");
        ReflectionTestUtils.setField(controller, "tavilyKey", "${TAVILY_API_KEY}");

        Map<String, Object> health = controller.pipelineHealth();

        Map<String, Object> naver = providerState(health, "naver");
        assertEquals("OK", naver.get("status"));
        assertEquals(Boolean.TRUE, naver.get("hasKey"));
        assertEquals("naver.client-pair", naver.get("keySource"));

        Map<String, Object> brave = providerState(health, "brave");
        assertEquals("DISABLED", brave.get("status"));
        assertEquals(Boolean.FALSE, brave.get("hasKey"));
        assertEquals("missing_brave_api_key", brave.get("disabledReason"));

        Map<String, Object> serpapi = providerState(health, "serpapi");
        assertEquals("OK", serpapi.get("status"));
        assertEquals("serpapi.api-key", serpapi.get("keySource"));

        Map<String, Object> tavily = providerState(health, "tavily");
        assertEquals("DISABLED", tavily.get("status"));
        assertEquals("missing_tavily_api_key", tavily.get("disabledReason"));

        String rendered = health.toString();
        assertFalse(rendered.contains("naver-client-id-raw"));
        assertFalse(rendered.contains("naver-client-secret-raw"));
        assertFalse(rendered.contains("serpapi-secret-raw"));
    }

    @Test
    void pipelineHealthShowsSupabaseExternalEvidenceReadinessWithoutTokenValues(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path goalNextSummary = tempDir.resolve("goal-next-auto.summary.json");
        Files.writeString(goalNextSummary, """
                {
                  "supabaseSmoke": {
                    "projectScopeStatus": "project_ref_missing",
                    "mcpDecision": "mcp_endpoint_auth_required",
                    "evidenceNeededCount": 24
                  },
                  "supabaseApply": {
                    "evidenceNeededCount": 24,
                    "requiredEnvNames": ["SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"],
                    "requiredMcpTools": ["execute_sql", "get_advisors"],
                    "requiredResultNames": ["schemas_and_tables", "rls_and_table_flags", "policies"]
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController missing = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(missing, "supabaseProjectRef", "");
        ReflectionTestUtils.setField(missing, "supabaseAccessToken", "");
        ReflectionTestUtils.setField(missing, "goalNextAutoSummaryPath", goalNextSummary.toString());

        Map<String, Object> missingState = externalEvidence(missing.pipelineHealth(), "supabase");
        assertEquals("WARN", missingState.get("status"));
        assertEquals(Boolean.TRUE, missingState.get("readOnly"));
        assertEquals(Boolean.FALSE, missingState.get("mutationAllowed"));
        assertEquals(Boolean.FALSE, missingState.get("projectScoped"));
        assertEquals(Boolean.FALSE, missingState.get("authPresent"));
        assertEquals("project_ref_missing", missingState.get("projectScopeStatus"));
        assertEquals("auth_missing", missingState.get("authStatus"));
        assertEquals(Boolean.TRUE, missingState.get("mcpReadOnly"));
        assertEquals("database,debugging,docs", missingState.get("mcpFeatureGroups"));
        assertEquals("mcp.supabase.com", missingState.get("mcpEndpointHost"));
        assertEquals("project_ref_missing", missingState.get("evidenceNeeded"));
        assertEquals("set_SUPABASE_PROJECT_REF", missingState.get("nextAction"));
        assertEquals("mcp_endpoint_auth_required", missingState.get("mcpDecision"));
        assertEquals(24, missingState.get("evidenceNeededCount"));
        assertEquals("supabase_project_ref,supabase_access_token", missingState.get("requiredEnvNames"));
        assertEquals("execute_sql,get_advisors", missingState.get("requiredMcpTools"));
        assertEquals("schemas_and_tables,rls_and_table_flags,policies", missingState.get("requiredResultNames"));

        AgentPipelineHealthController ready = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(ready, "supabaseProjectRef", "demo-project-ref");
        ReflectionTestUtils.setField(ready, "supabaseAccessToken", "supabase-access-token-raw");

        Map<String, Object> readyState = externalEvidence(ready.pipelineHealth(), "supabase");
        assertEquals("OK", readyState.get("status"));
        assertEquals(Boolean.TRUE, readyState.get("readOnly"));
        assertEquals(Boolean.FALSE, readyState.get("mutationAllowed"));
        assertEquals(Boolean.TRUE, readyState.get("projectScoped"));
        assertEquals(Boolean.TRUE, readyState.get("authPresent"));
        assertEquals("project_ref_present", readyState.get("projectScopeStatus"));
        assertEquals("auth_present", readyState.get("authStatus"));
        assertEquals(Boolean.TRUE, readyState.get("mcpReadOnly"));
        assertEquals("database,debugging,docs", readyState.get("mcpFeatureGroups"));
        assertEquals("mcp.supabase.com", readyState.get("mcpEndpointHost"));
        assertEquals("run_supabase_context_probe", readyState.get("nextAction"));
        assertFalse(ready.pipelineHealth().toString().contains("supabase-access-token-raw"));
        assertFalse(ready.pipelineHealth().toString().contains("demo-project-ref"));
    }

    @Test
    void pipelineHealthExposesGoalNextFreshnessForChatDebugHeartbeat(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path goalNextSummary = tempDir.resolve("goal-next-auto.summary.json");
        Files.writeString(goalNextSummary, """
                {
                  "ok": false,
                  "decision": "evidence_needed",
                  "failureClassification": "evidence_needed",
                  "firstAction": "set_SUPABASE_PROJECT_REF",
                  "generatedAt": "2026-06-25T07:30:02.4519749Z",
                  "externalInputGate": {
                    "status": "external_input_needed",
                    "mutationAllowed": false,
                    "repeatCount": 25,
                    "localPatchJustified": false
                  },
                  "desktopControlLoop": {
                    "localReady": true,
                    "externalEvidenceComplete": false
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "goalNextAutoSummaryPath", goalNextSummary.toString());

        Map<String, Object> goalNext = externalEvidence(controller.pipelineHealth(), "goal-next-auto");
        assertEquals("WARN", goalNext.get("status"));
        assertEquals("2026-06-25T07:30:02.4519749Z", goalNext.get("generatedAt"));
        assertEquals(60, goalNext.get("staleAfterMinutes"));
        assertTrue(goalNext.containsKey("ageMinutes"));
        assertTrue(goalNext.containsKey("stale"));
        assertEquals(Boolean.TRUE, goalNext.get("readOnly"));
        assertEquals(Boolean.FALSE, goalNext.get("mutationAllowed"));
        assertFalse(controller.pipelineHealth().toString().contains("SUPABASE_ACCESS_TOKEN"));
    }

    @Test
    void pipelineHealthShowsPatchDropReadinessCountsWithoutReadingPatchBodies(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path patchDrop = tempDir.resolve("__patch_drop__");
        Files.createDirectories(patchDrop.resolve("notebook"));
        Files.createDirectories(patchDrop.resolve("applied"));
        Files.writeString(patchDrop.resolve("active-topic-v3.patch"), "diff --git a/a b/a\n", StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.patch"), "diff --git a/b b/b\n", StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.manifest.json"),
                """
                        {
                          "slug": "producer-topic",
                          "node": "notebook",
                          "status": "ACTIVE",
                          "activePatch": "producer-topic-notebook-v3.patch",
                          "sourceIsolation": {
                            "guard": "PASS",
                            "sourceRootKind": "local-worktree",
                            "sharedSourceRoot": false,
                            "directCanonicalSourceEdit": false,
                            "canonicalRoot": "C:\\\\secret-canonical-root"
                          }
                        }
                        """,
                StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.report.md"), "report only\n", StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.verify.log"), "verify only\n", StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.sha256.txt"), "sha only\n", StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("report-only-notebook-v3.manifest.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("applied").resolve("old-topic-v3.patch"), "diff --git a/c b/c\n", StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "patchDropRoot", patchDrop.toString());

        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> patchdrop = externalEvidence(health, "patchdrop");
        assertEquals("WARN", patchdrop.get("status"));
        assertEquals(Boolean.TRUE, patchdrop.get("readOnly"));
        assertEquals(Boolean.FALSE, patchdrop.get("mutationAllowed"));
        assertEquals(1, patchdrop.get("activeTopLevelPatchCount"));
        assertEquals(1, patchdrop.get("nestedProducerPatchCount"));
        assertEquals(1, patchdrop.get("reportOnlyPendingCount"));
        assertEquals(1, patchdrop.get("appliedPatchCount"));
        assertEquals("producer-topic", patchdrop.get("pendingProducerTopic"));
        assertEquals("notebook", patchdrop.get("pendingProducerNode"));
        assertEquals("ACTIVE", patchdrop.get("pendingProducerStatus"));
        assertEquals("producer-topic-notebook-v3.patch", patchdrop.get("pendingProducerPatchName"));
        assertEquals(Boolean.TRUE, patchdrop.get("pendingProducerReportPresent"));
        assertEquals(Boolean.TRUE, patchdrop.get("pendingProducerVerifyLogPresent"));
        assertEquals(Boolean.TRUE, patchdrop.get("pendingProducerShaPresent"));
        assertEquals(Boolean.TRUE, patchdrop.get("pendingProducerBundleComplete"));
        assertEquals("PASS", patchdrop.get("pendingProducerSourceIsolationGuard"));
        assertEquals("local-worktree", patchdrop.get("pendingProducerSourceRootKind"));
        assertEquals(Boolean.FALSE, patchdrop.get("pendingProducerSharedSourceRoot"));
        assertEquals(Boolean.FALSE, patchdrop.get("pendingProducerDirectCanonicalSourceEdit"));
        assertEquals(Boolean.TRUE, patchdrop.get("pendingProducerSourceIsolationOk"));
        assertEquals("patchdrop_apply_candidate_pending", patchdrop.get("evidenceNeeded"));
        assertEquals("run_patchdrop_janitor_inventory", patchdrop.get("nextAction"));
        Map<String, Object> external = overview(health, "externalEvidence");
        assertEquals("WARN", external.get("status"));
        Map<String, Object> blocker = overview(health, "debugBlocker");
        assertEquals("WARN", blocker.get("status"));
        assertEquals("service=patchdrop action=run_patchdrop_janitor_inventory", blocker.get("detail"));
        assertFalse(health.toString().contains("diff --git"));
        assertFalse(health.toString().contains("secret-canonical-root"));
    }

    @Test
    void pipelineHealthShowsPatchDropProducerSidecarCompletenessWithoutReadingPatchBodies(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path patchDrop = tempDir.resolve("__patch_drop__");
        Files.createDirectories(patchDrop.resolve("notebook"));
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.patch"), "diff --git a/b b/b\n", StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.manifest.json"),
                """
                        {
                          "slug": "producer-topic",
                          "node": "notebook",
                          "status": "ACTIVE",
                          "activePatch": "producer-topic-notebook-v3.patch"
                        }
                        """,
                StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.report.md"), "report only\n", StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.sha256.txt"), "sha only\n", StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "patchDropRoot", patchDrop.toString());

        Map<String, Object> patchdrop = externalEvidence(controller.pipelineHealth(), "patchdrop");
        assertEquals("producer-topic", patchdrop.get("pendingProducerTopic"));
        assertEquals(Boolean.TRUE, patchdrop.get("pendingProducerReportPresent"));
        assertEquals(Boolean.FALSE, patchdrop.get("pendingProducerVerifyLogPresent"));
        assertEquals(Boolean.TRUE, patchdrop.get("pendingProducerShaPresent"));
        assertEquals(Boolean.FALSE, patchdrop.get("pendingProducerBundleComplete"));
        assertFalse(controller.pipelineHealth().toString().contains("diff --git"));
    }

    @Test
    void pipelineHealthShowsDefaultModelRuntimeStateWithoutRawModelOrErrorText() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));

        TraceStore.put("llm.defaultModel.waitStatus", true);
        TraceStore.put("llm.defaultModel.waitStatus.code", "waiting_for_default_model");
        TraceStore.put("llm.defaultModel.route", "local");
        TraceStore.put("llm.model", "gemma4:26b-private-raw");
        TraceStore.put("llm.fastBailTimeout", true);
        TraceStore.put("llm.fastBailTimeout.timeoutHits", 2);
        TraceStore.put("llm.fastBailTimeout.attempt", 0);
        TraceStore.put("llm.fastBailTimeout.maxAttempts", 1);
        TraceStore.put("llm.error.code", "TIMEOUT");
        TraceStore.put("llm.error.message", "Authorization=private-token");
        TraceStore.put("answer.mode", "FALLBACK_EVIDENCE");

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);

        Map<String, Object> modelRuntime = map(controller.pipelineHealth().get("modelRuntime"));

        assertEquals("WARN", modelRuntime.get("status"));
        assertEquals("timeout_fast_bail", modelRuntime.get("reason"));
        assertEquals(Boolean.TRUE, modelRuntime.get("waiting"));
        assertEquals("waiting_for_default_model", modelRuntime.get("defaultWaitCode"));
        assertEquals("local", modelRuntime.get("route"));
        assertEquals(Boolean.TRUE, modelRuntime.get("fastBailTimeout"));
        assertEquals(2, modelRuntime.get("timeoutHits"));
        assertEquals(0, modelRuntime.get("attempt"));
        assertEquals(1, modelRuntime.get("maxAttempts"));
        assertEquals("timeout", modelRuntime.get("errorCode"));
        assertEquals("fallback_evidence", modelRuntime.get("answerMode"));
        assertEquals("fallback_sent_model_timed_out", modelRuntime.get("deliveryState"));
        assertEquals(Boolean.FALSE, modelRuntime.get("finalDeliveryExpected"));
        assertEquals("current_trace", modelRuntime.get("source"));
        assertTrue(String.valueOf(modelRuntime.get("modelHash")).startsWith("hash:"));
        assertEquals("gemma4:26b-private-raw".length(), modelRuntime.get("modelLength"));
        String rendered = modelRuntime.toString();
        assertFalse(rendered.contains("gemma4:26b-private-raw"));
        assertFalse(rendered.contains("private-token"));
        assertFalse(rendered.contains("Authorization"));
    }

    @Test
    void pipelineHealthReadsChatStreamDefaultModelWaitStatusAlias() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));

        TraceStore.put("chat.stream.defaultModel.waitStatus", true);
        TraceStore.put("chat.stream.defaultModel.waitStatus.code", "waiting_for_default_model");
        TraceStore.put("llm.model", "stream-model-private-raw");

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);

        Map<String, Object> modelRuntime = map(controller.pipelineHealth().get("modelRuntime"));

        assertEquals("WARN", modelRuntime.get("status"));
        assertEquals("waiting_for_default_model", modelRuntime.get("reason"));
        assertEquals(Boolean.TRUE, modelRuntime.get("waiting"));
        assertEquals("waiting_for_default_model", modelRuntime.get("defaultWaitCode"));
        assertEquals("awaiting_default_model", modelRuntime.get("deliveryState"));
        assertEquals(Boolean.TRUE, modelRuntime.get("finalDeliveryExpected"));
        assertEquals("current_trace", modelRuntime.get("source"));
        assertTrue(String.valueOf(modelRuntime.get("modelHash")).startsWith("hash:"));
        assertEquals("stream-model-private-raw".length(), modelRuntime.get("modelLength"));
        assertFalse(modelRuntime.toString().contains("stream-model-private-raw"));
    }

    @Test
    void pipelineHealthFallsBackToRecentTraceSnapshotForModelRuntimeState() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.clear();

        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        Map<String, Object> snapshotTrace = Map.of(
                "llm.fastBailTimeout", true,
                "llm.fastBailTimeout.timeoutHits", 1,
                "llm.defaultModel.route", "local",
                "llm.model", "snapshot-model-raw",
                "answer.mode", "FALLBACK_LOCAL");
        TraceSnapshotStore.TraceSnapshot snapshot = new TraceSnapshotStore.TraceSnapshot(
                "snap-model", 1L, "2026-06-24T00:00:00Z",
                null, null, null, null,
                "chat_final", "POST", "/api/chat", 200, null,
                true, snapshotTrace.size(), Map.of(), snapshotTrace, Map.of(), null, false);
        when(snapshotStore.listSummaries(5)).thenReturn(List.of(Map.of("id", "snap-model")));
        when(snapshotStore.get("snap-model")).thenReturn(Optional.of(snapshot));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);

        Map<String, Object> modelRuntime = map(controller.pipelineHealth().get("modelRuntime"));

        assertEquals("WARN", modelRuntime.get("status"));
        assertEquals("timeout_fast_bail", modelRuntime.get("reason"));
        assertEquals("trace_snapshot", modelRuntime.get("source"));
        assertEquals("local", modelRuntime.get("route"));
        assertEquals(1, modelRuntime.get("timeoutHits"));
        assertEquals("fallback_local", modelRuntime.get("answerMode"));
        assertEquals("fallback_sent_model_timed_out", modelRuntime.get("deliveryState"));
        assertEquals(Boolean.FALSE, modelRuntime.get("finalDeliveryExpected"));
        assertTrue(String.valueOf(modelRuntime.get("modelHash")).startsWith("hash:"));
        assertEquals("snapshot-model-raw".length(), modelRuntime.get("modelLength"));
        assertFalse(modelRuntime.toString().contains("snapshot-model-raw"));
    }

    @Test
    void pipelineHealthShowsProviderRuntimePressureWithoutRawProviderText() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));

        TraceStore.put("web.await.events.timeout.count", 2L);
        TraceStore.put("web.await.cancelSuppressed", 1);
        TraceStore.put("web.failsoft.rateLimitBackoff.serpapi.last.kind", "AWAIT_TIMEOUT");
        TraceStore.put("web.failsoft.rateLimitBackoff.serpapi.last.delayMs", 123);
        TraceStore.put("web.brave.cancelled", true);
        TraceStore.put("web.brave.failureDetail", "Authorization=private-token raw-query");

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);

        Map<String, Object> providerRuntime = map(controller.pipelineHealth().get("providerRuntime"));

        assertEquals("WARN", providerRuntime.get("status"));
        assertEquals("await_timeout", providerRuntime.get("reason"));
        assertEquals("current_trace", providerRuntime.get("source"));
        assertEquals(2, providerRuntime.get("awaitTimeoutCount"));
        assertEquals(1, providerRuntime.get("cancelSuppressedCount"));
        assertEquals(1, providerRuntime.get("cooldownSkippedCount"));
        assertEquals(123, providerRuntime.get("maxBackoffDelayMs"));
        assertEquals(List.of("brave"), providerRuntime.get("cancelledProviders"));
        assertFalse(providerRuntime.toString().contains("private-token"));
        assertFalse(providerRuntime.toString().contains("raw-query"));
        assertFalse(providerRuntime.toString().contains("Authorization"));
    }

    @Test
    void pipelineHealthShowsAnswerOutputHealthWithoutRawAnswerText() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));

        TraceStore.put("answer.mode", "FALLBACK_EVIDENCE");
        TraceStore.put("chat.emptyAnswerGuard.triggered", true);
        TraceStore.put("chat.emptyAnswerGuard.fallback", "composer_blank");
        TraceStore.put("chat.emptyAnswerGuard.evidenceDocs", 3);
        TraceStore.put("orch.evidenceList.traceInjected", true);
        TraceStore.put("orch.evidenceList.traceInjected.blankBaseFallback", true);
        TraceStore.put("guard.evidenceList.derivedTitle.count", 2);
        TraceStore.put("guard.evidenceList.derivedSnippet.count", 1);
        TraceStore.put("chat.emptyAnswerGuard.rawText", "Authorization=private-token raw prompt");

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);

        Map<String, Object> answerOutput = map(controller.pipelineHealth().get("answerOutput"));

        assertEquals("WARN", answerOutput.get("status"));
        assertEquals("empty_answer_guard", answerOutput.get("reason"));
        assertEquals("fallback_evidence", answerOutput.get("answerMode"));
        assertEquals("composer_blank", answerOutput.get("emptyAnswerFallback"));
        assertEquals(Boolean.TRUE, answerOutput.get("emptyAnswerGuardTriggered"));
        assertEquals(Boolean.TRUE, answerOutput.get("evidenceListTraceInjected"));
        assertEquals(Boolean.TRUE, answerOutput.get("blankBaseFallback"));
        assertEquals(3, answerOutput.get("evidenceDocs"));
        assertEquals(2, answerOutput.get("derivedTitleCount"));
        assertEquals(1, answerOutput.get("derivedSnippetCount"));
        assertFalse(answerOutput.toString().contains("private-token"));
        assertFalse(answerOutput.toString().contains("raw prompt"));
        assertFalse(answerOutput.toString().contains("Authorization"));
    }

    @Test
    void pipelineHealthShowsTraceSnapshotReadinessWithoutRawSnapshotIdentifiers() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceSnapshotStore snapshotStore = mock(TraceSnapshotStore.class);
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("id", "snap-private-id");
        summary.put("path", "/api/chat?token=private-token");
        summary.put("pathHash", "hash:path");
        summary.put("pathLength", 29);
        summary.put("reason", "chat_final");
        summary.put("status", 200);
        summary.put("hasHtml", true);
        summary.put("htmlTruncated", true);
        summary.put("traceEntryCount", 9);
        summary.put("eventCount", 2);
        summary.put("controlCount", 1);
        summary.put("error", "Authorization=private-token");
        when(snapshotStore.listSummaries(5)).thenReturn(List.of(summary));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", snapshotStore);

        Map<String, Object> traceSnapshotHealth = map(controller.pipelineHealth().get("traceSnapshotHealth"));

        assertEquals("OK", traceSnapshotHealth.get("status"));
        assertEquals("ready", traceSnapshotHealth.get("reason"));
        assertEquals(Boolean.TRUE, traceSnapshotHealth.get("available"));
        assertEquals(1, traceSnapshotHealth.get("summaryCount"));
        assertEquals("chat_final", traceSnapshotHealth.get("latestReason"));
        assertEquals(200, traceSnapshotHealth.get("latestStatusCode"));
        assertEquals("hash:path", traceSnapshotHealth.get("latestPathHash"));
        assertEquals(29, traceSnapshotHealth.get("latestPathLength"));
        assertEquals(Boolean.TRUE, traceSnapshotHealth.get("latestHasHtml"));
        assertEquals(Boolean.TRUE, traceSnapshotHealth.get("latestHtmlTruncated"));
        assertEquals(Boolean.TRUE, traceSnapshotHealth.get("latestErrorPresent"));
        assertEquals(9, traceSnapshotHealth.get("latestTraceEntryCount"));
        assertFalse(traceSnapshotHealth.toString().contains("snap-private-id"));
        assertFalse(traceSnapshotHealth.toString().contains("/api/chat"));
        assertFalse(traceSnapshotHealth.toString().contains("private-token"));
        assertFalse(traceSnapshotHealth.toString().contains("Authorization"));
    }

    @Test
    void pipelineHealthShowsDebugEventStoreHealthWithoutRawEventValues() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        DebugEventStore debugEventStore = new DebugEventStore();
        debugEventStore.emit(
                DebugProbeType.MODEL_GUARD,
                DebugEventLevel.WARN,
                "model.guard.timeout",
                "Authorization=private-token raw prompt",
                "ModelGuard",
                Map.of("safeCount", 2, "secret", "sk-local-private"),
                new IllegalStateException("ownerToken=private-token"));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "debugEventStore", debugEventStore);

        Map<String, Object> debugEventHealth = map(controller.pipelineHealth().get("debugEventHealth"));

        assertEquals("WARN", debugEventHealth.get("status"));
        assertEquals("recent_warn_or_error", debugEventHealth.get("reason"));
        assertEquals(Boolean.TRUE, debugEventHealth.get("available"));
        assertEquals(1, debugEventHealth.get("recentEventCount"));
        assertEquals(1, debugEventHealth.get("fingerprintCount"));
        assertEquals("warn", debugEventHealth.get("latestLevel"));
        assertEquals("model_guard", debugEventHealth.get("latestProbe"));
        String storedFingerprint = SafeRedactor.hashValue("model.guard.timeout");
        assertEquals(SafeRedactor.hashValue(storedFingerprint), debugEventHealth.get("latestFingerprintHash"));
        assertEquals(storedFingerprint.length(), debugEventHealth.get("latestFingerprintLength"));
        assertEquals(1, debugEventHealth.get("maxWindowCount"));
        assertEquals(0, debugEventHealth.get("totalSuppressed"));
        String rendered = debugEventHealth.toString();
        assertFalse(rendered.contains("model.guard.timeout"));
        assertFalse(rendered.contains("private-token"));
        assertFalse(rendered.contains("raw prompt"));
        assertFalse(rendered.contains("ownerToken"));
        assertFalse(rendered.contains("sk-local-private"));
    }

    @Test
    void pipelineHealthShowsFailSoftLadderHealthWithoutRawQueryValues() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("outCount", 0);
        TraceStore.put("stageCountsSelectedFromOut", Map.of("web", 0, "vector", 2));
        TraceStore.put("cacheOnly.merged.count", 2);
        TraceStore.put("tracePool.size", 4);
        TraceStore.put("rescueMerge.used", true);
        TraceStore.put("starvationFallback.trigger", "cache_only_rescue");
        TraceStore.put("poolSafeEmpty", true);
        TraceStore.put("rawQuery", "Authorization=private-token raw question");

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);

        Map<String, Object> failSoftLadder = map(controller.pipelineHealth().get("failSoftLadder"));

        assertEquals("WARN", failSoftLadder.get("status"));
        assertEquals("starvation_fallback", failSoftLadder.get("reason"));
        assertEquals("current_trace", failSoftLadder.get("source"));
        assertEquals(0, failSoftLadder.get("outCount"));
        assertEquals(2, failSoftLadder.get("stageSelectedKeyCount"));
        assertEquals(2, failSoftLadder.get("cacheOnlyMergedCount"));
        assertEquals(4, failSoftLadder.get("tracePoolSize"));
        assertEquals(Boolean.TRUE, failSoftLadder.get("rescueMergeUsed"));
        assertEquals(Boolean.TRUE, failSoftLadder.get("poolSafeEmpty"));
        assertEquals("cache_only_rescue", failSoftLadder.get("starvationFallbackTrigger"));
        assertFalse(failSoftLadder.toString().contains("private-token"));
        assertFalse(failSoftLadder.toString().contains("raw question"));
        assertFalse(failSoftLadder.toString().contains("Authorization"));
    }

    @Test
    void pipelineHealthReadsHybridEmptyFallbackCacheOnlyAliasesWithoutRawQueryValues() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count", 7);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 9);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", true);
        TraceStore.put("rawQuery", "Authorization=private-token cache rescue question");

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);

        Map<String, Object> failSoftLadder = map(controller.pipelineHealth().get("failSoftLadder"));

        assertEquals("WARN", failSoftLadder.get("status"));
        assertEquals("starvation_fallback", failSoftLadder.get("reason"));
        assertEquals("current_trace", failSoftLadder.get("source"));
        assertEquals(7, failSoftLadder.get("cacheOnlyMergedCount"));
        assertEquals(9, failSoftLadder.get("tracePoolSize"));
        assertEquals(Boolean.TRUE, failSoftLadder.get("rescueMergeUsed"));
        assertFalse(failSoftLadder.toString().contains("private-token"));
        assertFalse(failSoftLadder.toString().contains("cache rescue question"));
        assertFalse(failSoftLadder.toString().contains("Authorization"));
    }

    @Test
    void pipelineHealthShowsVectorFallbackAndStarvationPoolWithoutRawQueryValues() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("web.failsoft.outCount", 0);
        TraceStore.put("retrieval.vectorFallback.used", true);
        TraceStore.put("retrieval.vectorFallback.reason", "kg_rescue");
        TraceStore.put("retrieval.vectorFallback.effectiveTopK", 6);
        TraceStore.put("web.failsoft.starvationFallback.count", 3);
        TraceStore.put("web.failsoft.starvationFallback.added", 2);
        TraceStore.put("web.failsoft.starvationFallback.pool.safe.size", 5);
        TraceStore.put("web.failsoft.starvationFallback.pool.dev.size", 1);
        TraceStore.put("rawQuery", "Authorization=private-token vector fallback question");

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);

        Map<String, Object> failSoftLadder = map(controller.pipelineHealth().get("failSoftLadder"));

        assertEquals("WARN", failSoftLadder.get("status"));
        assertEquals("vector_fallback", failSoftLadder.get("reason"));
        assertEquals(Boolean.TRUE, failSoftLadder.get("vectorFallbackUsed"));
        assertEquals("kg_rescue", failSoftLadder.get("vectorFallbackReason"));
        assertEquals(6, failSoftLadder.get("vectorFallbackEffectiveTopK"));
        assertEquals(3, failSoftLadder.get("starvationFallbackCount"));
        assertEquals(2, failSoftLadder.get("starvationFallbackAdded"));
        assertEquals(5, failSoftLadder.get("starvationSafePoolSize"));
        assertEquals(1, failSoftLadder.get("starvationDevPoolSize"));
        assertFalse(failSoftLadder.toString().contains("private-token"));
        assertFalse(failSoftLadder.toString().contains("vector fallback question"));
        assertFalse(failSoftLadder.toString().contains("Authorization"));
    }

    @Test
    void pipelineHealthShowsDebugAiMetricsReadinessWithoutRawEventValues() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        DebugEventStore debugEventStore = new DebugEventStore();
        debugEventStore.emit(
                DebugProbeType.WEB_SEARCH,
                DebugEventLevel.WARN,
                "web.search.rate-limit",
                "Authorization=private-token raw query",
                "WebSearch",
                Map.of("failureClass", "rate-limit", "secret", "sk-local-private"),
                null);

        ChatUsageLedger chatUsageLedger = new ChatUsageLedger();
        ChatUsageLedger.ModelAttempt usageAttempt = chatUsageLedger.beginModelInvocation(
                ChatUsageLedger.ModelPurpose.PRIMARY,
                ChatUsageLedger.ConfiguredCap.omitted(null, null, ChatUsageLedger.CapSource.ROUTER_MODEL));
        usageAttempt.responseReceived(null);
        DebugAiMetricsService metricsService = new DebugAiMetricsService(debugEventStore);
        ReflectionTestUtils.setField(metricsService, "chatUsageLedger", chatUsageLedger);
        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "debugAiMetricsService", metricsService);

        Map<String, Object> debugAiMetrics = map(controller.pipelineHealth().get("debugAiMetrics"));

        assertEquals("WARN", debugAiMetrics.get("status"));
        assertEquals("warn_events", debugAiMetrics.get("reason"));
        assertEquals(Boolean.TRUE, debugAiMetrics.get("available"));
        assertEquals(1, debugAiMetrics.get("totalEvents"));
        assertEquals(1, debugAiMetrics.get("warnEvents"));
        assertEquals(0, debugAiMetrics.get("errorEvents"));
        assertEquals("web_search", debugAiMetrics.get("topTile"));
        assertEquals("warn", debugAiMetrics.get("topTileStatus"));
        assertEquals("rate-limit", debugAiMetrics.get("topFailureClass"));
        assertEquals("awx.chat-usage.v1", map(debugAiMetrics.get("chatUsage")).get("schemaVersion"));
        assertEquals(1L, ((Number) map(map(debugAiMetrics.get("chatUsage")).get("modelInvocations"))
                .get("attempts")).longValue());
        assertTrue(((Number) debugAiMetrics.get("tileCount")).intValue() >= 1);
        assertFalse(debugAiMetrics.toString().contains("private-token"));
        assertFalse(debugAiMetrics.toString().contains("raw query"));
        assertFalse(debugAiMetrics.toString().contains("sk-local-private"));
        assertFalse(debugAiMetrics.toString().contains("Authorization"));
    }

    @Test
    void pipelineHealthFallbackCatchesLeaveTraceSuppressedBreadcrumbs(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        DebugEventStore throwingDebugStore = mock(DebugEventStore.class);
        when(throwingDebugStore.list(20)).thenThrow(new IllegalStateException("ownerToken=debug-private"));
        DebugAiMetricsService throwingMetrics = mock(DebugAiMetricsService.class);
        when(throwingMetrics.snapshot(80, 60_000L))
                .thenThrow(new IllegalStateException("ownerToken=metrics-private"));
        TraceSnapshotStore throwingSnapshotStore = mock(TraceSnapshotStore.class);
        when(throwingSnapshotStore.listSummaries(5))
                .thenThrow(new IllegalStateException("ownerToken=snapshot-private"));
        Path computerSmoke = tempDir.resolve("computer-use-smoke.json");
        Path browserSmoke = tempDir.resolve("browser-ui-smoke.json");
        Files.writeString(computerSmoke, "{not-json ownerToken=computer-private", StandardCharsets.UTF_8);
        Files.writeString(browserSmoke, "{not-json ownerToken=browser-private", StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "debugEventStore", throwingDebugStore);
        ReflectionTestUtils.setField(controller, "debugAiMetricsService", throwingMetrics);
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", throwingSnapshotStore);
        ReflectionTestUtils.setField(controller, "computerUseSmokePath", computerSmoke.toString());
        ReflectionTestUtils.setField(controller, "browserSmokePath", browserSmoke.toString());

        Map<String, Object> health = controller.pipelineHealth();

        assertEquals("debug_event_lookup_failed", map(health.get("debugEventHealth")).get("reason"));
        assertEquals("debug_ai_metrics_lookup_failed", map(health.get("debugAiMetrics")).get("reason"));
        assertEquals("snapshot_lookup_failed", map(health.get("traceSnapshotHealth")).get("reason"));
        assertEquals("computer_use_smoke_unreadable", externalEvidence(health, "computer-use").get("evidenceNeeded"));
        assertEquals("browser_ui_smoke_unreadable", externalEvidence(health, "browser").get("evidenceNeeded"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.pipelineHealth.suppressed.debug_event_health"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.pipelineHealth.suppressed.debug_ai_metrics"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.pipelineHealth.suppressed.trace_snapshot_health"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.pipelineHealth.suppressed.computer_use_evidence"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.pipelineHealth.suppressed.browser_evidence"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken"));
        assertFalse(trace.contains("private"));
    }

    @Test
    void pipelineHealthShowsQueryRewritePostprocessCountsInDebugAiMetrics() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        DebugEventStore debugEventStore = new DebugEventStore();
        debugEventStore.emit(
                DebugProbeType.QUERY_TRANSFORMER,
                DebugEventLevel.INFO,
                "query.rewrite.super-tokens",
                "query rewrite postprocess",
                "ChatApiController.stream.final",
                Map.of(
                        "stage", "query_rewrite",
                        "superCount", 3,
                        "branchCount", 3,
                        "subModelCount", 3,
                        "branchTitleCount", 3,
                        "branchTitleHashes", List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "ownerToken=private-title-hash"),
                        "branchAxisCount", 3,
                        "paddedCount", 2,
                        "titleHash12", "private-title-hash"),
                null);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "debugAiMetricsService", new DebugAiMetricsService(debugEventStore));

        Map<String, Object> debugAiMetrics = map(controller.pipelineHealth().get("debugAiMetrics"));

        assertEquals("OK", debugAiMetrics.get("status"));
        assertEquals("ready", debugAiMetrics.get("reason"));
        assertEquals(3, debugAiMetrics.get("queryRewriteSubModelCount"));
        assertEquals(3, debugAiMetrics.get("queryRewriteBranchTitleCount"));
        assertEquals(2, debugAiMetrics.get("queryRewriteBranchTitleHashCount"));
        assertEquals(3, debugAiMetrics.get("queryRewriteBranchAxisCount"));
        assertEquals(2, debugAiMetrics.get("queryRewritePaddedCount"));
        assertFalse(debugAiMetrics.toString().contains("private-title-hash"));
    }

    @Test
    void pipelineHealthSummarizesCoreAndUiDebugOverviewWithoutSecretValues(@TempDir Path tempDir) {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of(
                "ACTIVE", 1L,
                "PENDING", 3L,
                "QUARANTINED", 0L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("llm.fastBailTimeout", true);
        TraceStore.put("llm.modelGuard.triggered", true);
        TraceStore.put("llm.model", "private-default-model-name");
        TraceStore.put("answer.mode", "FALLBACK_EVIDENCE");
        TraceStore.put("chat.emptyAnswerGuard.triggered", true);
        TraceStore.put("chat.emptyAnswerGuard.fallback", "composer_blank");
        TraceStore.put("chat.emptyAnswerGuard.evidenceDocs", 2);
        TraceStore.put("orch.evidenceList.traceInjected", true);
        TraceStore.put("externalEvidence.requestedLanes", List.of("supabase"));
        TraceStore.put("queryTransformer.bypassed", true);
        TraceStore.put("causalProbe.evidenceReady", true);
        TraceStore.put("causalProbe.triggerReason", "axis_agreement");
        TraceStore.put("causalProbe.dominantFailure", "after_filter_starvation");
        TraceStore.put("causalProbe.action", "source_patch_candidate");
        TraceStore.put("causalProbe.confidence", 0.875d);
        TraceStore.put("causalProbe.axisCount", 3);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "webRetriever", mock(ContentRetriever.class));
        ReflectionTestUtils.setField(controller, "queryTransformer", mock(QueryTransformer.class));
        ReflectionTestUtils.setField(controller, "naverKeys", "");
        ReflectionTestUtils.setField(controller, "braveKey", "brave-secret-raw-value");
        ReflectionTestUtils.setField(controller, "supabaseProjectRef", "");
        ReflectionTestUtils.setField(controller, "supabaseAccessToken", "supabase-token-raw");
        ReflectionTestUtils.setField(controller, "browserSmokePath", tempDir.resolve("missing-browser-smoke.json").toString());

        Map<String, Object> health = controller.pipelineHealth();

        Map<String, Object> rollup = overview(health, "debugRollup");
        assertEquals("WARN", rollup.get("status"));
        assertEquals("pending_backlog", rollup.get("reason"));
        assertEquals("core=WARN model=WARN answer=WARN search=WARN external=WARN ui=SUPPORTING_EVIDENCE_MISSING", rollup.get("detail"));
        assertEquals("pipeline-health", rollup.get("source"));

        Map<String, Object> core = overview(health, "coreRuntime");
        assertEquals("WARN", core.get("status"));
        assertEquals("pending_backlog", core.get("reason"));
        assertTrue(String.valueOf(core.get("detail")).contains("lanes="));

        Map<String, Object> model = overview(health, "modelRuntime");
        assertEquals("WARN", model.get("status"));
        assertEquals("timeout_fast_bail", model.get("reason"));
        assertEquals("current_trace", model.get("source"));
        assertEquals("source=current_trace delivery=fallback_sent_model_timed_out wait=none hits=0 guard=yes error=none", model.get("detail"));

        Map<String, Object> answer = overview(health, "answerOutput");
        assertEquals("WARN", answer.get("status"));
        assertEquals("empty_answer_guard", answer.get("reason"));
        assertEquals("mode=fallback_evidence guard=yes fallback=composer_blank trace=yes evidenceDocs=2", answer.get("detail"));

        Map<String, Object> search = overview(health, "webProviders");
        assertEquals("WARN", search.get("status"));
        assertEquals("provider_disabled", search.get("reason"));
        assertEquals("enabled=1 disabled=3", search.get("detail"));

        Map<String, Object> causal = overview(health, "causalProbe");
        assertEquals("WARN", causal.get("status"));
        assertEquals("axis_agreement", causal.get("reason"));
        assertEquals("dominant=after_filter_starvation action=source_patch_candidate confidence=0.875 axes=3", causal.get("detail"));
        assertEquals("trace:causalProbe", causal.get("source"));

        Map<String, Object> external = overview(health, "externalEvidence");
        assertEquals("WARN", external.get("status"));
        assertEquals("project_ref_missing", external.get("reason"));

        Map<String, Object> blocker = overview(health, "debugBlocker");
        assertEquals("WARN", blocker.get("status"));
        assertEquals("project_ref_missing", blocker.get("reason"));
        assertEquals("service=supabase action=set_supabase_project_ref", blocker.get("detail"));
        assertEquals("external-evidence", blocker.get("source"));

        Map<String, Object> uiDebug = overview(health, "uiDebug");
        assertEquals("WARN", uiDebug.get("status"));
        assertEquals("browser_ui_smoke_missing", uiDebug.get("reason"));
        assertEquals("surface=unknown reachable=no screenshot=no target=no", uiDebug.get("detail"));
        assertEquals("local-ui-proof", uiDebug.get("source"));

        String rendered = health.toString();
        assertFalse(rendered.contains("private-default-model-name"));
        assertFalse(rendered.contains("brave-secret-raw-value"));
        assertFalse(rendered.contains("supabase-token-raw"));
    }

    @Test
    void invalidCausalProbeConfidenceLeavesRedactedTraceBreadcrumb(@TempDir Path tempDir) {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("causalProbe.evidenceReady", true);
        TraceStore.put("causalProbe.triggerReason", "axis_agreement");
        TraceStore.put("causalProbe.dominantFailure", "loader_starvation");
        TraceStore.put("causalProbe.action", "recovery_mode");
        TraceStore.put("causalProbe.confidence", "ownerToken=private-token");
        TraceStore.put("causalProbe.axisCount", 2);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "browserSmokePath", tempDir.resolve("missing-browser-smoke.json").toString());

        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> causalProbe = lane(health, "causalProbe");

        assertEquals(0.0d, causalProbe.get("confidence"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.pipelineHealth.suppressed.bounded_double_parse"));
        assertEquals("NumberFormatException",
                TraceStore.get("agent.pipelineHealth.suppressed.bounded_double_parse.errorType"));
        assertFalse(health.toString().contains("ownerToken"));
        assertFalse(health.toString().contains("private-token"));
    }

    @Test
    void pipelineHealthIncludesComputerUseExternalEvidenceFromCountOnlySmoke(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path smoke = tempDir.resolve("computer-use-smoke.json");
        Files.writeString(smoke, """
                {
                  "ok": true,
                  "decision": "ok",
                  "reachable": true,
                  "guiOnly": true,
                  "noTerminalAutomation": true,
                  "supportingOnly": true,
                  "stale": false,
                  "generatedAt": "2026-06-25T05:46:48.261Z",
                  "ageMinutes": 11.75,
                  "staleAfterMinutes": 60,
                  "appCount": 42,
                  "runningCount": 21,
                  "targetableWindowCount": 299,
                  "storesAppNames": false,
                  "storesRawAppNames": false,
                  "storesWindowTitles": false,
                  "secretHits": 0,
                  "rawSecretPatternHits": 0,
                  "privateWindowTitle": "do-not-render-this-title"
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "computerUseSmokePath", smoke.toString());
        ReflectionTestUtils.setField(controller, "noetherStatusPath", tempDir.resolve("missing-noether.json").toString());

        Map<String, Object> health = controller.pipelineHealth();

        Map<String, Object> computer = externalEvidence(health, "computer-use");
        assertEquals("OK", computer.get("status"));
        assertEquals(Boolean.TRUE, computer.get("readOnly"));
        assertEquals(Boolean.FALSE, computer.get("mutationAllowed"));
        assertEquals(Boolean.TRUE, computer.get("reachable"));
        assertEquals(Boolean.TRUE, computer.get("guiOnly"));
        assertEquals(Boolean.TRUE, computer.get("noTerminalAutomation"));
        assertEquals(Boolean.TRUE, computer.get("supportingOnly"));
        assertEquals(Boolean.TRUE, computer.get("countOnly"));
        assertEquals("ok", computer.get("decision"));
        assertEquals("2026-06-25T05:46:48.261Z", computer.get("generatedAt"));
        assertEquals(11, computer.get("ageMinutes"));
        assertEquals(60, computer.get("staleAfterMinutes"));
        assertEquals(Boolean.FALSE, computer.get("storesAppNames"));
        assertEquals(Boolean.FALSE, computer.get("storesRawAppNames"));
        assertEquals(Boolean.FALSE, computer.get("storesWindowTitles"));
        assertEquals(0, computer.get("secretHits"));
        assertEquals(42, computer.get("appCount"));
        assertEquals(299, computer.get("targetableWindowCount"));
        assertEquals("computer_use_supporting_evidence_current", computer.get("nextAction"));

        Map<String, Object> external = overview(health, "externalEvidence");
        String externalDetail = String.valueOf(external.get("detail"));
        assertTrue(externalDetail.startsWith("services=6 "));
        assertTrue(externalDetail.contains("computer=ok"));
        assertTrue(externalDetail.contains("noether=noether_status_missing"));
        assertFalse(health.toString().contains("do-not-render-this-title"));
    }

    @Test
    void pipelineHealthTreatsGeneratedComputerSmokeWithoutExplicitTtlAsStale(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path smoke = tempDir.resolve("computer-use-smoke.json");
        Files.writeString(smoke, """
                {
                  "ok": true,
                  "decision": "ok",
                  "reachable": true,
                  "guiOnly": true,
                  "noTerminalAutomation": true,
                  "supportingOnly": true,
                  "stale": false,
                  "generatedAt": "2026-06-25T05:46:48.261Z",
                  "appCount": 42,
                  "targetableWindowCount": 299,
                  "storesAppNames": false,
                  "storesRawAppNames": false,
                  "storesWindowTitles": false,
                  "secretHits": 0,
                  "rawSecretPatternHits": 0
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "computerUseSmokePath", smoke.toString());

        Map<String, Object> computer = externalEvidence(controller.pipelineHealth(), "computer-use");

        assertEquals("WARN", computer.get("status"));
        assertEquals(Boolean.TRUE, computer.get("stale"));
        assertTrue(((Number) computer.get("ageMinutes")).longValue() >= 60);
        assertEquals(60, computer.get("staleAfterMinutes"));
        assertEquals("computer_use_smoke_stale", computer.get("evidenceNeeded"));
        assertEquals("run_computer_use_lightweight_smoke", computer.get("nextAction"));
    }

    @Test
    void pipelineHealthAcceptsProofStripBrowserAndMinimalComputerSmoke(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path computer = tempDir.resolve("computer-use-smoke.json");
        Files.writeString(computer, "\uFEFF" + """
                {
                  "ok": true,
                  "decision": "ok",
                  "checkedAt": "2026-06-25T07:00:00.000Z",
                  "appCount": 42,
                  "runningCount": 22,
                  "windowCount": 147,
                  "rawSecretPatternHits": []
                }
                """, StandardCharsets.UTF_8);
        Path browser = tempDir.resolve("browser-ui-smoke.json");
        Files.writeString(browser, "\uFEFF" + """
                {
                  "checkedAt": "2026-06-25T07:00:00.000Z",
                  "proofRootPresent": true,
                  "proofCellCount": 6,
                  "proofNames": "local,browser,computer,supabase,producer,action",
                  "flowStepCount": 6,
                  "missionAxisCount": 5,
                  "cockpitCellCount": 5,
                  "matrixCellCount": 4,
                  "heartbeatFieldCount": 29,
                  "allDetailCellCount": 51,
                  "detailLeakHits": [],
                  "rawSecretPatternHits": []
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "computerUseSmokePath", computer.toString());
        ReflectionTestUtils.setField(controller, "browserSmokePath", browser.toString());
        ReflectionTestUtils.setField(controller, "patchDropRoot", tempDir.resolve("missing-patchdrop").toString());
        ReflectionTestUtils.setField(controller, "goalNextAutoSummaryPath", tempDir.resolve("missing-goal-next.json").toString());
        ReflectionTestUtils.setField(controller, "noetherStatusPath", tempDir.resolve("missing-noether.json").toString());

        Map<String, Object> health = controller.pipelineHealth();

        Map<String, Object> computerEvidence = externalEvidence(health, "computer-use");
        assertEquals("OK", computerEvidence.get("status"));
        assertEquals(Boolean.TRUE, computerEvidence.get("reachable"));
        assertEquals(Boolean.TRUE, computerEvidence.get("guiOnly"));
        assertEquals(Boolean.TRUE, computerEvidence.get("noTerminalAutomation"));
        assertEquals(Boolean.TRUE, computerEvidence.get("supportingOnly"));
        assertEquals(Boolean.TRUE, computerEvidence.get("countOnly"));
        assertEquals("2026-06-25T07:00:00.000Z", computerEvidence.get("generatedAt"));
        assertEquals(42, computerEvidence.get("appCount"));
        assertEquals(147, computerEvidence.get("targetableWindowCount"));
        assertEquals(0, computerEvidence.get("secretHits"));
        assertEquals("computer_use_supporting_evidence_current", computerEvidence.get("nextAction"));

        Map<String, Object> browserEvidence = externalEvidence(health, "browser");
        assertEquals("OK", browserEvidence.get("status"));
        assertEquals(Boolean.TRUE, browserEvidence.get("reachable"));
        assertEquals(Boolean.TRUE, browserEvidence.get("localhost"));
        assertEquals(Boolean.TRUE, browserEvidence.get("screenshotCaptured"));
        assertEquals(Boolean.TRUE, browserEvidence.get("targetContentVisible"));
        assertEquals("proof_strip_visible", browserEvidence.get("statusClass"));
        assertEquals("iab", browserEvidence.get("browserSurface"));
        assertEquals("2026-06-25T07:00:00.000Z", browserEvidence.get("generatedAt"));
        assertEquals(0, browserEvidence.get("secretHits"));
        assertEquals("browser_local_ui_smoke_current", browserEvidence.get("nextAction"));
    }

    @Test
    void pipelineHealthWarnsWhenComputerUseSmokeStoresNamesOrTitles(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path smoke = tempDir.resolve("computer-use-smoke.json");
        Files.writeString(smoke, """
                {
                  "ok": true,
                  "reachable": true,
                  "guiOnly": true,
                  "noTerminalAutomation": true,
                  "supportingOnly": true,
                  "stale": false,
                  "appCount": 2,
                  "runningCount": 1,
                  "targetableWindowCount": 1,
                  "storesAppNames": true,
                  "storesRawAppNames": false,
                  "storesWindowTitles": true,
                  "secretHits": 0,
                  "rawSecretPatternHits": 0,
                  "rawWindowTitle": "private-title-must-not-render"
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "computerUseSmokePath", smoke.toString());

        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> computer = externalEvidence(health, "computer-use");

        assertEquals("WARN", computer.get("status"));
        assertEquals("computer_use_privacy_boundary_incomplete", computer.get("evidenceNeeded"));
        assertEquals(Boolean.FALSE, computer.get("countOnly"));
        assertEquals(Boolean.TRUE, computer.get("storesAppNames"));
        assertEquals(Boolean.FALSE, computer.get("storesRawAppNames"));
        assertEquals(Boolean.TRUE, computer.get("storesWindowTitles"));
        assertFalse(health.toString().contains("private-title-must-not-render"));
    }

    @Test
    void pipelineHealthSummarizesExternalEvidenceLaneStatusInOverview(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path computer = tempDir.resolve("computer-use-smoke.json");
        Files.writeString(computer, """
                {
                  "ok": true,
                  "reachable": true,
                  "guiOnly": true,
                  "noTerminalAutomation": true,
                  "supportingOnly": true,
                  "stale": false,
                  "appCount": 3,
                  "runningCount": 2,
                  "targetableWindowCount": 4,
                  "secretHits": 0,
                  "rawSecretPatternHits": 0
                }
                """, StandardCharsets.UTF_8);
        Path browser = tempDir.resolve("browser-ui-smoke.json");
        Files.writeString(browser, """
                {
                  "ok": false,
                  "reachable": false,
                  "localhost": true,
                  "screenshotCaptured": true,
                  "statusClass": "localhost_unreachable",
                  "targetContentVisible": false,
                  "browserSurface": "iab",
                  "secretHits": 0,
                  "rawSecretPatternHits": 0
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "computerUseSmokePath", computer.toString());
        ReflectionTestUtils.setField(controller, "browserSmokePath", browser.toString());
        ReflectionTestUtils.setField(controller, "patchDropRoot", tempDir.resolve("missing-patchdrop").toString());
        ReflectionTestUtils.setField(controller, "goalNextAutoSummaryPath", tempDir.resolve("missing-goal-next.json").toString());
        ReflectionTestUtils.setField(controller, "noetherStatusPath", tempDir.resolve("missing-noether.json").toString());
        ReflectionTestUtils.setField(controller, "supabaseProjectRef", "");
        ReflectionTestUtils.setField(controller, "supabaseAccessToken", "");

        Map<String, Object> external = overview(controller.pipelineHealth(), "externalEvidence");

        assertEquals("WARN", external.get("status"));
        assertEquals("patchdrop_root_missing", external.get("reason"));
        assertEquals(
                "services=6 warn=5 supabase=project_ref_missing computer=ok browser=localhost_unreachable patchdrop=patchdrop_root_missing goalNext=goal_next_auto_summary_missing noether=noether_status_missing",
                external.get("detail"));
    }

    @Test
    void pipelineHealthIncludesBrowserExternalEvidenceWithoutRawUrlsOrScreenshots(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path smoke = tempDir.resolve("browser-ui-smoke.json");
        Files.writeString(smoke, """
                {
                  "ok": false,
                  "reachable": false,
                  "localhost": true,
                  "screenshotCaptured": false,
                  "statusClass": "server_unreachable",
                  "targetContentVisible": false,
                  "browserSurface": "iab",
                  "evidenceNeeded": "browser_session_evidence_needed",
                  "generatedAt": "2026-06-25T06:01:02.003Z",
                  "ageMinutes": 91.25,
                  "staleAfterMinutes": 60,
                  "stale": true,
                  "nextAction": "start_local_server_then_rerun_browser_local_ui_smoke",
                  "errorClass": "localhost_unreachable",
                  "rawUrl": "http://localhost:8080/private-debug-path",
                  "screenshotPath": "C:/private/browser-proof.png",
                  "secretHits": 0,
                  "rawSecretPatternHits": 0
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "browserSmokePath", smoke.toString());

        Map<String, Object> browser = externalEvidence(controller.pipelineHealth(), "browser");
        assertEquals("WARN", browser.get("status"));
        assertEquals(Boolean.TRUE, browser.get("readOnly"));
        assertEquals(Boolean.FALSE, browser.get("mutationAllowed"));
        assertEquals("local-ui-proof", browser.get("evidenceScope"));
        assertEquals(Boolean.FALSE, browser.get("reachable"));
        assertEquals(Boolean.TRUE, browser.get("localhost"));
        assertEquals(Boolean.FALSE, browser.get("screenshotCaptured"));
        assertEquals("server_unreachable", browser.get("statusClass"));
        assertEquals(Boolean.FALSE, browser.get("targetContentVisible"));
        assertEquals("iab", browser.get("browserSurface"));
        assertEquals(Boolean.TRUE, browser.get("stale"));
        assertEquals(91, browser.get("ageMinutes"));
        assertEquals(60, browser.get("staleAfterMinutes"));
        assertEquals("2026-06-25T06:01:02.003Z", browser.get("generatedAt"));
        assertEquals(0, browser.get("secretHits"));
        assertEquals("browser_session_evidence_needed", browser.get("evidenceNeeded"));
        assertEquals("start_local_server_then_rerun_browser_local_ui_smoke", browser.get("nextAction"));
        assertEquals("localhost_unreachable", browser.get("errorClass"));
        assertFalse(controller.pipelineHealth().toString().contains("private-debug-path"));
        assertFalse(controller.pipelineHealth().toString().contains("browser-proof.png"));
    }

    @Test
    void pipelineHealthTreatsGeneratedBrowserSmokeWithoutExplicitTtlAsStale(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path smoke = tempDir.resolve("browser-ui-smoke.json");
        Files.writeString(smoke, """
                {
                  "ok": true,
                  "reachable": true,
                  "localhost": true,
                  "screenshotCaptured": true,
                  "targetContentVisible": true,
                  "browserSurface": "iab",
                  "generatedAt": "2026-06-25T06:01:02.003Z",
                  "stale": false,
                  "secretHits": 0,
                  "rawSecretPatternHits": 0
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "browserSmokePath", smoke.toString());

        Map<String, Object> browser = externalEvidence(controller.pipelineHealth(), "browser");

        assertEquals("WARN", browser.get("status"));
        assertEquals(Boolean.TRUE, browser.get("stale"));
        assertTrue(((Number) browser.get("ageMinutes")).longValue() >= 60);
        assertEquals(60, browser.get("staleAfterMinutes"));
        assertEquals("browser_ui_smoke_stale", browser.get("evidenceNeeded"));
        assertEquals("run_browser_local_ui_smoke", browser.get("nextAction"));
    }

    @Test
    void pipelineHealthAcceptsPublicDomainBrowserEvidenceWithoutRawUrls(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path smoke = tempDir.resolve("browser-ui-smoke.json");
        Files.writeString(smoke, """
                {
                  "ok": true,
                  "reachable": true,
                  "localhost": false,
                  "publicDomain": true,
                  "targetUrl": "https://abandonwareai.kro.kr/private-debug-path",
                  "screenshotCaptured": true,
                  "statusClass": "ui_visible",
                  "targetContentVisible": true,
                  "browserSurface": "iab",
                  "secretHits": 0,
                  "rawSecretPatternHits": 0
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "browserSmokePath", smoke.toString());

        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> browser = externalEvidence(health, "browser");
        assertEquals("OK", browser.get("status"));
        assertEquals(Boolean.FALSE, browser.get("localhost"));
        assertEquals(Boolean.TRUE, browser.get("publicDomain"));
        assertEquals(Boolean.TRUE, browser.get("targetAccepted"));
        assertEquals("abandonwareai.kro.kr", browser.get("targetHost"));
        assertEquals("public-domain-ui-proof", browser.get("evidenceScope"));
        assertEquals("browser_public_domain_ui_smoke_current", browser.get("nextAction"));
        assertFalse(health.toString().contains("https://abandonwareai.kro.kr/private-debug-path"));
        assertFalse(health.toString().contains("private-debug-path"));
        Map<String, Object> uiDebug = overview(health, "uiDebug");
        assertEquals("public-domain-ui-proof", uiDebug.get("source"));
    }

    @Test
    void pipelineHealthRequestsPublicDomainBrowserSmokeWhenConfiguredSmokeIsMissing(@TempDir Path tempDir) {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "browserSmokePath", tempDir.resolve("missing-browser-smoke.json").toString());
        ReflectionTestUtils.setField(controller, "appPublicBaseUrl", "https://abandonwareai.kro.kr");

        Map<String, Object> health = controller.pipelineHealth();
        Map<String, Object> browser = externalEvidence(health, "browser");
        assertEquals("WARN", browser.get("status"));
        assertEquals(Boolean.FALSE, browser.get("reachable"));
        assertEquals(Boolean.FALSE, browser.get("localhost"));
        assertEquals(Boolean.TRUE, browser.get("publicDomain"));
        assertEquals(Boolean.TRUE, browser.get("targetAccepted"));
        assertEquals("abandonwareai.kro.kr", browser.get("targetHost"));
        assertEquals("public-domain-ui-proof", browser.get("evidenceScope"));
        assertEquals("browser_ui_smoke_missing", browser.get("evidenceNeeded"));
        assertEquals("run_browser_public_domain_ui_smoke", browser.get("nextAction"));
        assertFalse(health.toString().contains("https://abandonwareai.kro.kr"));
    }

    @Test
    void pipelineHealthKeepsOptionalExternalEvidenceSupportingWhenDesktopProofIsReady(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path computer = tempDir.resolve("computer-use-smoke.json");
        Files.writeString(computer, """
                {
                  "ok": true,
                  "decision": "ok",
                  "reachable": true,
                  "guiOnly": true,
                  "noTerminalAutomation": true,
                  "supportingOnly": true,
                  "appCount": 4,
                  "targetableWindowCount": 7,
                  "storesAppNames": false,
                  "storesRawAppNames": false,
                  "storesWindowTitles": false,
                  "secretHits": 0,
                  "rawSecretPatternHits": 0
                }
                """, StandardCharsets.UTF_8);
        Path goalNext = tempDir.resolve("goal-next-auto.summary.json");
        Files.writeString(goalNext, """
                {
                  "ok": true,
                  "decision": "continue",
                  "firstAction": "none",
                  "firstActionSource": "desktop_control_loop",
                  "generatedAt": "2026-06-25T07:00:00.000Z",
                  "externalInputGate": {
                    "localPatchJustified": false
                  },
                  "desktopControlLoop": {
                    "localReady": true,
                    "completionReady": true,
                    "externalEvidenceComplete": false
                  },
                  "sourceHealthExit": 0,
                  "completionAuditExit": 0,
                  "supabaseSmoke": {
                    "projectScopeStatus": "project_ref_present",
                    "mcpDecision": "ready"
                  },
                  "supabaseApply": {
                    "evidenceNeededCount": 0
                  },
                  "externalApply": {
                    "evidenceNeededCount": 0
                  }
                }
                """, StandardCharsets.UTF_8);
        Path noether = tempDir.resolve("noether-subagent-status.json");
        Files.writeString(noether, """
                {
                  "ok": true,
                  "responded": true,
                  "waiting": false,
                  "lastMessageKind": "response_seen",
                  "secretHits": 0
                }
                """, StandardCharsets.UTF_8);
        Path patchDrop = tempDir.resolve("patchdrop");
        Files.createDirectories(patchDrop.resolve("notebook"));
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.patch"),
                "diff --git a/b b/b\n", StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("producer-topic-notebook-v3.manifest.json"),
                """
                        {
                          "slug": "producer-topic",
                          "node": "notebook",
                          "status": "ACTIVE",
                          "activePatch": "producer-topic-notebook-v3.patch"
                        }
                        """, StandardCharsets.UTF_8);
        Files.writeString(patchDrop.resolve("notebook").resolve("report-only-notebook-v3.manifest.json"),
                "{}", StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "computerUseSmokePath", computer.toString());
        ReflectionTestUtils.setField(controller, "browserSmokePath", tempDir.resolve("missing-browser-smoke.json").toString());
        ReflectionTestUtils.setField(controller, "goalNextAutoSummaryPath", goalNext.toString());
        ReflectionTestUtils.setField(controller, "noetherStatusPath", noether.toString());
        ReflectionTestUtils.setField(controller, "patchDropRoot", patchDrop.toString());
        ReflectionTestUtils.setField(controller, "supabaseProjectRef", "");
        ReflectionTestUtils.setField(controller, "supabaseAccessToken", "");
        ReflectionTestUtils.setField(controller, "appPublicBaseUrl", "http://localhost:8080");

        Map<String, Object> health = controller.pipelineHealth();

        Map<String, Object> external = overview(health, "externalEvidence");
        assertEquals("OK", external.get("status"));
        assertEquals("ready", external.get("reason"));
        assertTrue(String.valueOf(external.get("detail")).contains("browser=browser_ui_smoke_missing"));

        Map<String, Object> blocker = overview(health, "debugBlocker");
        assertEquals("OK", blocker.get("status"));
        assertEquals("ready", blocker.get("reason"));
        assertEquals("service=none action=none", blocker.get("detail"));

        Map<String, Object> rollup = overview(health, "debugRollup");
        assertTrue(String.valueOf(rollup.get("detail")).contains("external=OK"));
        assertTrue(String.valueOf(rollup.get("detail")).contains("ui=SUPPORTING_EVIDENCE_MISSING"));

        Map<String, Object> uiDebug = overview(health, "uiDebug");
        assertEquals("WARN", uiDebug.get("status"));
        assertEquals("browser_ui_smoke_missing", uiDebug.get("reason"));
        assertEquals("local-ui-proof", uiDebug.get("source"));

        Map<String, Object> supabase = externalEvidence(health, "supabase");
        assertEquals("WARN", supabase.get("status"));
        assertEquals("project_ref_missing", supabase.get("evidenceNeeded"));

        Map<String, Object> patchdrop = externalEvidence(health, "patchdrop");
        assertEquals("WARN", patchdrop.get("status"));
        assertEquals(1, patchdrop.get("nestedProducerPatchCount"));
        assertEquals(1, patchdrop.get("reportOnlyPendingCount"));
        assertEquals("patchdrop_report_only_pending", patchdrop.get("evidenceNeeded"));

        TraceStore.put("externalEvidence.mode", "external_evidence");
        TraceStore.put("externalEvidence.source", "chat_request_tags");
        TraceStore.put("externalEvidence.executionThread", false);
        TraceStore.put("externalEvidence.requestedLanes", List.of("supabase"));

        Map<String, Object> requestedHealth = controller.pipelineHealth();
        Map<String, Object> requestedSupabase = externalEvidence(requestedHealth, "supabase");
        assertEquals("external_evidence", requestedSupabase.get("lanePolicy"));
        assertEquals("WARN", overview(requestedHealth, "externalEvidence").get("status"));
        assertEquals("service=supabase action=set_supabase_project_ref",
                overview(requestedHealth, "debugBlocker").get("detail"));
    }

    @Test
    void pipelineHealthUiDebugMirrorsSuccessfulBrowserSmoke(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path smoke = tempDir.resolve("browser-ui-smoke.json");
        Files.writeString(smoke, """
                {
                  "ok": true,
                  "reachable": true,
                  "localhost": true,
                  "screenshotCaptured": true,
                  "statusClass": "ok",
                  "targetContentVisible": true,
                  "browserSurface": "iab",
                  "secretHits": 0,
                  "rawSecretPatternHits": 0
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "browserSmokePath", smoke.toString());

        Map<String, Object> uiDebug = overview(controller.pipelineHealth(), "uiDebug");

        assertEquals("OK", uiDebug.get("status"));
        assertEquals("browser_local_ui_smoke_current", uiDebug.get("reason"));
        assertEquals("surface=iab reachable=yes screenshot=yes target=yes", uiDebug.get("detail"));
        assertEquals("local-ui-proof", uiDebug.get("source"));
        Map<String, Object> rollup = overview(controller.pipelineHealth(), "debugRollup");
        assertTrue(String.valueOf(rollup.get("detail")).contains("ui=OK"));
    }

    @Test
    void pipelineHealthIncludesGoalNextAutoExternalEvidenceWithoutArtifactPaths(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path summary = tempDir.resolve("goal-next-auto.summary.json");
        Files.writeString(summary, "\uFEFF" + """
                {
                  "ok": false,
                  "generatedAt": "2026-06-25T04:03:17.0126152Z",
                  "decision": "evidence_needed",
                  "failureClassification": "evidence_needed",
                  "firstAction": "set_SUPABASE_PROJECT_REF",
                  "firstActionSource": "supabase_apply",
                  "nextActionCount": 36,
                  "nextActionSources": ["supabase_apply", "external_apply", "source_health_scorecard", "desktop_control_loop"],
                  "topActions": [
                    {"source": "supabase_apply", "action": "set_SUPABASE_PROJECT_REF", "decision": "evidence_needed"},
                    {"source": "browser_use", "action": "rerun_browser_local_ui_smoke", "decision": "evidence_needed"},
                    {"source": "private_path", "action": "C:/private/should-not-render.json", "decision": "ignored"}
                  ],
                  "externalInputGate": {
                    "status": "external_input_needed",
                    "repeatCount": 18,
                    "localPatchJustified": false,
                    "mutationAllowed": false
                  },
                  "desktopControlLoop": {
                    "localReady": true,
                    "externalEvidenceComplete": false
                  },
                  "computerUse": {
                    "present": true,
                    "parsed": true,
                    "ok": false,
                    "decision": "evidence_needed",
                    "reachable": true,
                    "appCount": 42,
                    "runningCount": 22,
                    "windowCount": 308,
                    "ageMinutes": 96.571,
                    "staleAfterMinutes": 60,
                    "stale": true,
                    "nextAction": "rerun_computer_use_lightweight_smoke",
                    "secretHits": 0,
                    "rawSecretPatternHits": 0,
                    "path": "C:/private/computer-use-smoke.json"
                  },
                  "browserUse": {
                    "present": true,
                    "parsed": true,
                    "ok": false,
                    "decision": "evidence_needed",
                    "evidenceNeeded": "public-listener-unreachable",
                    "reachable": true,
                    "localhost": true,
                    "screenshotCaptured": true,
                    "statusClass": "ui_visible",
                    "targetContentVisible": true,
                    "browserSurface": "iab",
                    "ageMinutes": 96.567,
                    "staleAfterMinutes": 60,
                    "stale": true,
                    "nextAction": "rerun_browser_local_ui_smoke",
                    "secretHits": 0,
                    "rawSecretPatternHits": 0,
                    "path": "C:/private/browser-ui-smoke.json"
                  },
                  "supabaseSmoke": {
                    "projectScopeStatus": "project_ref_missing",
                    "mcpDecision": "mcp_endpoint_auth_required",
                    "evidenceNeededCount": 24
                  },
                  "supabaseApply": {
                    "evidenceNeededCount": 24,
                    "requiredEnvNames": ["SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"],
                    "requiredMcpTools": ["execute_sql", "get_advisors"],
                    "requiredResultNames": ["schemas_and_tables", "rls_and_table_flags", "policies"]
                  },
                  "externalApply": {
                    "evidenceNeededCount": 12,
                    "requiredRoles": ["macmini", "notebook"],
                    "requiredProducerEvidenceFiles": ["macmini-node-smoke.json", "notebook-producer-handoff.json"],
                    "requiredPatchDropSidecars": ["pendingNotice", ".manifest.json", ".patch", ".verify.log"],
                    "requiredSourceIsolation": {
                      "guard": "PASS",
                      "sourceRootKind": "local-worktree",
                      "directCanonicalSourceEdit": false
                    },
                    "copiedEvidenceCount": 0,
                    "copiedHandoffCount": 0
                  },
                  "supabaseSmokeExit": 2,
                  "externalApplyExit": 2,
                  "sourceHealthExit": 0,
                  "completionAuditExit": 0,
                  "artifacts": {
                    "summary": "C:/private/path/should-not-render.json"
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "goalNextAutoSummaryPath", summary.toString());

        Map<String, Object> health = controller.pipelineHealth();

        Map<String, Object> goalNext = externalEvidence(health, "goal-next-auto");
        assertEquals("WARN", goalNext.get("status"));
        assertEquals(Boolean.TRUE, goalNext.get("readOnly"));
        assertEquals(Boolean.FALSE, goalNext.get("mutationAllowed"));
        assertEquals("evidence_needed", goalNext.get("decision"));
        assertEquals("set_SUPABASE_PROJECT_REF", goalNext.get("nextAction"));
        assertEquals("supabase_apply", goalNext.get("nextActionSource"));
        assertEquals(36, goalNext.get("nextActionCount"));
        assertEquals("supabase_apply,external_apply,source_health_scorecard,desktop_control_loop",
                goalNext.get("nextActionSources"));
        assertEquals("supabase_apply:set_SUPABASE_PROJECT_REF,browser_use:rerun_browser_local_ui_smoke,private_path:path_value_redacted",
                goalNext.get("topActions"));
        assertEquals("external_input_needed", goalNext.get("externalInputGateStatus"));
        assertEquals(Boolean.FALSE, goalNext.get("externalInputMutationAllowed"));
        assertEquals("2026-06-25T04:03:17.0126152Z", goalNext.get("summaryGeneratedAt"));
        assertNotNull(goalNext.get("summaryFileUpdatedAt"));
        assertTrue(String.valueOf(goalNext.get("summaryPathHash")).startsWith("hash:"));
        assertTrue((Integer) goalNext.get("summaryPathLength") > 0);
        assertEquals("evidence_needed", goalNext.get("evidenceNeeded"));
        assertEquals(Boolean.FALSE, goalNext.get("localPatchJustified"));
        assertEquals(Boolean.TRUE, goalNext.get("localReady"));
        assertEquals(Boolean.FALSE, goalNext.get("externalEvidenceComplete"));
        assertEquals(Boolean.TRUE, goalNext.get("computerUseReachable"));
        assertEquals(Boolean.TRUE, goalNext.get("computerUseStale"));
        assertEquals(42, goalNext.get("computerUseAppCount"));
        assertEquals(308, goalNext.get("computerUseWindowCount"));
        assertEquals("evidence_needed", goalNext.get("computerUseDecision"));
        assertEquals("rerun_computer_use_lightweight_smoke", goalNext.get("computerUseNextAction"));
        assertEquals(Boolean.TRUE, goalNext.get("browserUseReachable"));
        assertEquals(Boolean.TRUE, goalNext.get("browserUseStale"));
        assertEquals(Boolean.TRUE, goalNext.get("browserUseScreenshotCaptured"));
        assertEquals(Boolean.TRUE, goalNext.get("browserUseTargetContentVisible"));
        assertEquals("ui_visible", goalNext.get("browserUseStatusClass"));
        assertEquals("iab", goalNext.get("browserUseSurface"));
        assertEquals("public-listener-unreachable", goalNext.get("browserUseEvidenceNeeded"));
        assertEquals("rerun_browser_local_ui_smoke", goalNext.get("browserUseNextAction"));
        assertEquals(18, goalNext.get("repeatCount"));
        assertEquals(2, goalNext.get("supabaseSmokeExit"));
        assertEquals(2, goalNext.get("externalApplyExit"));
        assertEquals(0, goalNext.get("sourceHealthExit"));
        assertEquals(0, goalNext.get("completionAuditExit"));
        assertEquals("supabase=2 external=2 source=0 audit=0", goalNext.get("exitSummary"));
        assertEquals("project_ref_missing", goalNext.get("supabaseProjectScopeStatus"));
        assertEquals("mcp_endpoint_auth_required", goalNext.get("supabaseMcpDecision"));
        assertEquals(24, goalNext.get("supabaseEvidenceNeededCount"));
        assertEquals("supabase_project_ref,supabase_access_token", goalNext.get("supabaseRequiredEnvNames"));
        assertEquals("execute_sql,get_advisors", goalNext.get("supabaseRequiredMcpTools"));
        assertEquals("schemas_and_tables,rls_and_table_flags,policies", goalNext.get("supabaseRequiredResultNames"));
        assertEquals(12, goalNext.get("externalEvidenceNeededCount"));
        assertEquals("macmini,notebook", goalNext.get("externalRequiredRoles"));
        assertEquals("macmini-node-smoke.json,notebook-producer-handoff.json",
                goalNext.get("externalRequiredProducerEvidenceFiles"));
        assertEquals("pendingNotice,.manifest.json,.patch,.verify.log", goalNext.get("externalRequiredPatchDropSidecars"));
        assertEquals("PASS", goalNext.get("externalRequiredSourceIsolationGuard"));
        assertEquals("local-worktree", goalNext.get("externalRequiredSourceRootKind"));
        assertEquals(Boolean.FALSE, goalNext.get("externalRequiredDirectCanonicalSourceEdit"));
        assertEquals(0, goalNext.get("externalCopiedEvidenceCount"));
        assertEquals(0, goalNext.get("externalCopiedHandoffCount"));
        assertFalse(health.toString().contains("private/path"));
        assertFalse(health.toString().contains("computer-use-smoke.json"));
        assertFalse(health.toString().contains("browser-ui-smoke.json"));
    }

    @Test
    void pipelineHealthIncludesNoetherSubagentEvidenceWithoutRawMessages(@TempDir Path tempDir) throws Exception {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        Path status = tempDir.resolve("noether-subagent-status.json");
        Files.writeString(status, """
                {
                  "ok": true,
                  "agentId": "019ef668-8bab-73e1-9e1d-ee6c6011068e",
                  "agentName": "Noether",
                  "waiting": true,
                  "responded": true,
                  "lastMessageKind": "standing_by",
                  "evidenceNeeded": null,
                  "nextAction": "wait_for_new_redacted_evidence",
                  "rawMessage": "do not render this raw response",
                  "secretHits": 0,
                  "rawSecretPatternHits": 0
                }
                """, StandardCharsets.UTF_8);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "noetherStatusPath", status.toString());

        Map<String, Object> noether = externalEvidence(controller.pipelineHealth(), "noether");

        assertEquals("OK", noether.get("status"));
        assertEquals(Boolean.TRUE, noether.get("readOnly"));
        assertEquals(Boolean.FALSE, noether.get("mutationAllowed"));
        assertEquals("subagent-response-proof", noether.get("evidenceScope"));
        assertEquals("noether", noether.get("agentName"));
        assertTrue(String.valueOf(noether.get("agentIdHash")).startsWith("hash:"));
        assertEquals("019ef668-8bab-73e1-9e1d-ee6c6011068e".length(), noether.get("agentIdLength"));
        assertEquals(Boolean.TRUE, noether.get("waiting"));
        assertEquals(Boolean.TRUE, noether.get("responded"));
        assertEquals("standing_by", noether.get("lastMessageKind"));
        assertEquals("ready", noether.get("evidenceNeeded"));
        assertEquals("wait_for_new_redacted_evidence", noether.get("nextAction"));
        assertEquals(0, noether.get("secretHits"));
        assertFalse(noether.toString().contains("019ef668-8bab-73e1-9e1d-ee6c6011068e"));
        assertFalse(noether.toString().contains("do not render this raw response"));
    }

    @Test
    void pipelineHealthProjectsRequestedExternalEvidenceLanesAsNonExecutionThreads() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        TraceStore.put("externalEvidence.mode", "external_evidence");
        TraceStore.put("externalEvidence.source", "chat_request_tags");
        TraceStore.put("externalEvidence.executionThread", false);
        TraceStore.put("externalEvidence.requestedLanes",
                List.of("supabase", "superpowers", "computer-use", "browser"));
        TraceStore.put("superpowers.evidenceNeeded", "external_evidence_lane");
        TraceStore.put("superpowers.readOnly", true);
        TraceStore.put("superpowers.mutationAllowed", false);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);

        Map<String, Object> health = controller.pipelineHealth();

        for (String service : List.of("supabase", "superpowers", "computer-use", "browser")) {
            Map<String, Object> row = externalEvidence(health, service);
            assertEquals(Boolean.TRUE, row.get("readOnly"), service);
            assertEquals(Boolean.FALSE, row.get("mutationAllowed"), service);
            assertEquals(Boolean.FALSE, row.get("executionThread"), service);
            assertEquals("external_evidence", row.get("lanePolicy"), service);
        }
        Map<String, Object> superpowers = externalEvidence(health, "superpowers");
        assertEquals("WARN", superpowers.get("status"));
        assertEquals("external_evidence_lane", superpowers.get("evidenceNeeded"));
        assertEquals("external-evidence-lane", superpowers.get("evidenceScope"));
        assertEquals("collect_superpowers_external_evidence", superpowers.get("nextAction"));
        assertEquals("chat_request_tags", superpowers.get("sourceStage"));
    }

    @Test
    void circuitBreakerLaneChecksSerpApiAndTavilyBreakers() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenReturn(memory(Map.of("ACTIVE", 1L)));
        when(provider.strategySnapshot()).thenReturn(strategy(List.of()));
        when(provider.ledgerSnapshot()).thenReturn(ledger(List.of(), List.of()));
        NightmareBreaker breaker = mock(NightmareBreaker.class);

        AgentPipelineHealthController controller = new AgentPipelineHealthController(provider);
        ReflectionTestUtils.setField(controller, "nightmareBreaker", breaker);

        Map<String, Object> health = controller.pipelineHealth();

        assertEquals("OK", lane(health, "circuitBreaker").get("status"));
        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(breaker).isAnyOpen(keys.capture());
        List<String> checkedKeys = Arrays.asList(keys.getValue());
        assertTrue(checkedKeys.contains("websearch:serpapi"), checkedKeys::toString);
        assertTrue(checkedKeys.contains("websearch:tavily"), checkedKeys::toString);
    }

    private static AgentDbContextProvider.MemorySnapshot memory(Map<String, Long> counts) {
        AgentDbContextProvider.MemorySnapshot snapshot = new AgentDbContextProvider.MemorySnapshot();
        snapshot.statusCounts.putAll(counts);
        return snapshot;
    }

    private static AgentDbContextProvider.StrategySnapshot strategy(List<Map<String, Object>> performances) {
        AgentDbContextProvider.StrategySnapshot snapshot = new AgentDbContextProvider.StrategySnapshot();
        snapshot.performances.addAll(performances);
        return snapshot;
    }

    private static AgentDbContextProvider.LedgerSnapshot ledger(
            List<Map<String, Object>> hotspots,
            List<Map<String, Object>> failures) {
        AgentDbContextProvider.LedgerSnapshot snapshot = new AgentDbContextProvider.LedgerSnapshot();
        snapshot.hotspotDistribution.addAll(hotspots);
        snapshot.recentFailures.addAll(failures);
        return snapshot;
    }

    private static TraceSnapshotStore.TraceSnapshot probeSnapshot(
            String id,
            String sessionHash,
            String path,
            Map<String, Object> trace) {
        return new TraceSnapshotStore.TraceSnapshot(
                id, 1L, "2026-07-15T00:00:00Z",
                sessionHash, sessionHash, null, null,
                "http_request", "POST", path, 200, null,
                true, trace.size(), Map.of(), trace, Map.of(), null, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> lane(Map<String, Object> health, String name) {
        return list(health.get("lanes")).stream()
                .map(AgentPipelineHealthControllerTest::map)
                .filter(row -> name.equals(row.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> providerState(Map<String, Object> health, String provider) {
        return list(health.get("webProviders")).stream()
                .map(AgentPipelineHealthControllerTest::map)
                .filter(row -> provider.equals(row.get("provider")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> externalEvidence(Map<String, Object> health, String service) {
        return list(health.get("externalEvidence")).stream()
                .map(AgentPipelineHealthControllerTest::map)
                .filter(row -> service.equals(row.get("service")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> overview(Map<String, Object> health, String name) {
        return list(health.get("debugOverview")).stream()
                .map(AgentPipelineHealthControllerTest::map)
                .filter(row -> name.equals(row.get("name")))
                .findFirst()
                .orElseThrow();
    }
}
