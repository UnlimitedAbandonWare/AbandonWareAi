package com.example.lms.config;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.abandonware.ai.agent.integrations.HybridRetriever;
import com.abandonware.ai.agent.tool.AgentToolInvoker;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.impl.ops.OperatorProbeAuthorityStore;
import com.example.lms.search.probe.CausalProbeTriggerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class AgentToolOpsConfigContextTest {

    @TempDir Path temporaryMemoryRoot;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentToolOpsConfig.class)
            .withBean(FailurePatternMemoryService.class, () -> new FailurePatternMemoryService(
                    new ObjectMapper().findAndRegisterModules(), new ToolManifestCatalog(),
                    temporaryMemoryRoot, temporaryMemoryRoot.resolve("memory.jsonl")))
            .withBean(CausalProbeTriggerService.class, () -> mock(CausalProbeTriggerService.class))
            .withBean(HybridRetriever.class, () -> mock(HybridRetriever.class));

    @Test
    void configRegistersFirstWaveOpsToolsWithoutLegacySideEffectTools() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentToolInvoker.class);
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertThat(registry.get("verify.contract")).isPresent();
            assertThat(registry.get("ops.snapshot")).isPresent();
            assertThat(registry.get("repo.scan")).isPresent();
            assertThat(registry.get("source.map")).isPresent();
            assertThat(registry.get("trace.snapshot")).isPresent();
            assertThat(registry.get("kg.query")).isPresent();
            assertThat(registry.get("moe.strategy.query")).isPresent();
            assertThat(registry.get("config.inspect")).isPresent();
            assertThat(registry.get("debug.trace.lookup")).isPresent();
            assertThat(registry.get("db_evidence_scan")).isPresent();
            assertThat(registry.get("failure.pattern.scan")).isPresent();
            assertThat(registry.get("failure.pattern.recall")).isPresent();
            assertThat(registry.get("failure.pattern.record")).isPresent();
            assertThat(registry.get("causal.probe.evaluate")).isPresent();
            assertThat(registry.get("counter.evidence.retrieve")).isPresent();
            assertThat(registry.get("evidence.coherence.verify")).isPresent();
            assertThat(context).hasSingleBean(OperatorProbeAuthorityStore.class);
            assertThat(registry.get("message.send")).isEmpty();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void realReadOnlyToolUsesTheRegisteredListedAndPolicyControlledIdentity() {
        contextRunner.withPropertyValues("probe.admin-token=structural-fixture-value").run(context -> {
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            AgentToolInvoker invoker = context.getBean(AgentToolInvoker.class);
            assertThat(registry.get("config.inspect")).isPresent();
            List<Map<String, Object>> tools = (List<Map<String, Object>>) invoker.describeTools().get("tools");
            Map<String, Object> listed = tools.stream().filter(row -> "config.inspect".equals(row.get("id")))
                    .findFirst().orElseThrow();
            assertThat(listed).containsEntry("registered", true).containsEntry("enabled", true)
                    .containsEntry("readOnly", true);

            Map<String, Object> result = invoker.invoke(" config.inspect ", Map.of("unused", "fixture-input"),
                    new ToolContext("structural-fixture", null), true);
            assertThat(result).containsEntry("toolId", listed.get("id")).containsEntry("ok", true)
                    .containsEntry("readOnly", true).containsEntry("policyDecision", "ALLOW")
                    .containsEntry("authorizationSource", "ADMIN_TOKEN").containsEntry("resultValidation", "PASSED")
                    .containsEntry("truncated", false);
            assertThat((Map<String, Object>) result.get("data")).containsKey("config");
            assertThat(result.toString()).doesNotContain("structural-fixture-value", "fixture-input");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingManifestAndMissingImplementationRetainDistinctAdmissionFailures() {
        contextRunner.run(context -> {
            AgentToolInvoker invoker = context.getBean(AgentToolInvoker.class);
            List<Map<String, Object>> tools = (List<Map<String, Object>>) invoker.describeTools().get("tools");
            assertThat(tools.stream().filter(row -> "web.search".equals(row.get("id"))).findFirst().orElseThrow())
                    .containsEntry("registered", false);
            assertThatThrownBy(() -> invoker.invoke("unknown-fixture-tool", Map.of(), null, true))
                    .isInstanceOfSatisfying(ToolInvocationException.class,
                            error -> assertThat(error.code()).isEqualTo("tool_manifest_missing"));
            assertThat(com.example.lms.search.TraceStore.get("tool.invoke.failReason")).isEqualTo("manifest_missing");
            assertThatThrownBy(() -> invoker.invoke("web.search", Map.of(), null, true))
                    .isInstanceOfSatisfying(ToolInvocationException.class,
                            error -> assertThat(error.code()).isEqualTo("tool_registry_missing"));
            assertThat(com.example.lms.search.TraceStore.get("tool.invoke.failReason")).isEqualTo("registry_missing");
        });
    }

    @Test
    void dbEvidenceScanCanBeInvokedThroughRegisteredOpsInvoker() {
        contextRunner.run(context -> {
            AgentToolInvoker invoker = context.getBean(AgentToolInvoker.class);

            Map<String, Object> result = invoker.invoke(
                    "db_evidence_scan",
                    Map.of(),
                    new ToolContext("ops-config-test-session", null),
                    true);

            assertThat(result.get("ok")).isEqualTo(true);
            assertThat(result.get("toolId")).isEqualTo("db_evidence_scan");
            assertThat(result.toString()).contains("schemaVersion={present=true");
            assertThat(result.toString()).contains("hash12=");
            assertThat(result.toString()).doesNotContain("agent.db_evidence_scan.v1");
            assertThat(result.toString()).doesNotContain("C:\\AbandonWare");
        });
    }

    @Test
    void evidenceVerifierCanBeInvokedThroughPolicyControlledOpsInvoker() {
        contextRunner.run(context -> {
            AgentToolInvoker invoker = context.getBean(AgentToolInvoker.class);

            Map<String, Object> result = invoker.invoke(
                    "evidence.coherence.verify",
                    Map.of(
                            "decisionQuestion", "private integration decision",
                            "originalClaim", "private integration claim",
                            "evidenceRows", List.of(
                                    evidence("e-1", "group-a"),
                                    evidence("e-2", "group-b")),
                            "officialConstraints", List.of(),
                            "allowedReleaseStatuses", List.of("APPROVE", "REJECT", "HOLD"),
                            "queryTraceRefs", List.of()),
                    new ToolContext("ops-config-test-session", null),
                    true);

            assertThat(result.get("ok")).isEqualTo(true);
            assertThat(result.get("toolId")).isEqualTo("evidence.coherence.verify");
            assertThat(com.example.lms.search.TraceStore.get("evidenceCoherence.coherenceStatus"))
                    .isEqualTo("CONSISTENT");
            assertThat(com.example.lms.search.TraceStore.get("evidenceCoherence.verificationGatePassed"))
                    .isEqualTo(true);
            assertThat(com.example.lms.search.TraceStore.get("tool.invoke.status")).isEqualTo("OK");
            assertThat(result.toString()).doesNotContain("private integration claim");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void counterRetrievalReturnsInlineOpaquePacketReferenceInsteadOfUnreadableArtifactOnly() {
        contextRunner.run(context -> {
            AgentToolInvoker invoker = context.getBean(AgentToolInvoker.class);
            OperatorProbeAuthorityStore authorityStore = context.getBean(OperatorProbeAuthorityStore.class);
            List<Map<String, String>> queries = List.of(
                    Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                    Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                    Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3"));
            Map<String, Integer> retrievalBudget = Map.of("maxDocuments", 3, "maxMillis", 5_000);
            String claim = "private integration claim";
            String question = "private integration decision";
            String authorityRef = authorityStore.issue(
                    "packet-session",
                    com.example.lms.trace.SafeRedactor.hashValue(claim),
                    "hash:222222222222",
                    com.abandonware.ai.agent.tool.impl.ops.CounterEvidenceRetrieveTool
                            .canonicalRequestHash(claim, question, queries, retrievalBudget),
                    OperatorProbeAuthorityStore.Mode.PROBE_AND_PATCH_CANDIDATE,
                    5_000);

            Map<String, Object> result = invoker.invoke(
                    "counter.evidence.retrieve",
                    Map.of(
                            "operatorAuthorityRef", authorityRef,
                            "operatorConstraintHash", "hash:222222222222",
                            "originalClaim", claim,
                            "decisionQuestion", question,
                            "queries", queries,
                            "retrievalBudget", retrievalBudget),
                    new ToolContext("packet-session", null),
                    true);

            assertThat(result.get("ok")).isEqualTo(true);
            assertThat(result).doesNotContainKey("artifact");
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertThat(String.valueOf(data.get("packetRef"))).matches("hash:[0-9a-f]{12}");
            assertThat(result.toString()).doesNotContain("private integration claim");
            assertThat(result.toString()).doesNotContain("q1");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void counterPacketCanRoundTripThroughInvokerIntoVerifierWithoutLosingOpaqueBindings() {
        contextRunner.run(context -> {
            AtomicInteger sequence = new AtomicInteger();
            HybridRetriever retriever = context.getBean(HybridRetriever.class);
            when(retriever.retrieveStrictLocal(anyString(), anyInt())).thenAnswer(ignored -> {
                int n = sequence.incrementAndGet();
                return List.of(Map.of(
                        "id", "doc-" + n,
                        "source", "local-source-" + n,
                        "snippet", "private local evidence " + n,
                        "score", 0.9d));
            });
            AgentToolInvoker invoker = context.getBean(AgentToolInvoker.class);
            ToolContext toolContext = new ToolContext("round-trip-session", null);
            OperatorProbeAuthorityStore authorityStore = context.getBean(OperatorProbeAuthorityStore.class);
            List<Map<String, String>> queries = List.of(
                    Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                    Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                    Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3"));
            Map<String, Integer> retrievalBudget = Map.of("maxDocuments", 3, "maxMillis", 5_000);
            String claim = "private round trip claim";
            String question = "private round trip question";
            String authorityRef = authorityStore.issue(
                    "round-trip-session",
                    com.example.lms.trace.SafeRedactor.hashValue(claim),
                    "hash:444444444444",
                    com.abandonware.ai.agent.tool.impl.ops.CounterEvidenceRetrieveTool
                            .canonicalRequestHash(claim, question, queries, retrievalBudget),
                    OperatorProbeAuthorityStore.Mode.PROBE_AND_PATCH_CANDIDATE,
                    5_000);

            Map<String, Object> retrieved = invoker.invoke(
                    "counter.evidence.retrieve",
                    Map.of(
                            "operatorAuthorityRef", authorityRef,
                            "operatorConstraintHash", "hash:444444444444",
                            "originalClaim", claim,
                            "decisionQuestion", question,
                            "queries", queries,
                            "retrievalBudget", retrievalBudget),
                    toolContext,
                    true);
            Map<String, Object> retrievedData = (Map<String, Object>) retrieved.get("data");
            List<String> queryTraceRefs = (List<String>) retrievedData.get("queryTraceRefs");
            List<Map<String, Object>> retrievedRows = (List<Map<String, Object>>) retrievedData.get("evidenceRows");
            assertThat(queryTraceRefs).allMatch(ref -> ref.matches("hash:[0-9a-f]{12}"));
            assertThat(retrievedRows).hasSize(3);

            List<Map<String, Object>> normalizedRows = retrievedRows.stream().map(row -> {
                assertThat(String.valueOf(row.get("evidenceId"))).matches("hash:[0-9a-f]{12}");
                assertThat(String.valueOf(row.get("independenceGroupHash"))).matches("hash:[0-9a-f]{12}");
                assertThat(String.valueOf(row.get("queryTraceRef"))).matches("hash:[0-9a-f]{12}");
                assertThat(row.get("observedAt")).isInstanceOf(String.class);
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("evidenceId", row.get("evidenceId"));
                normalized.put("claim", "normalized local claim");
                normalized.put("sourceProvenanceHash", row.get("independenceGroupHash"));
                normalized.put("observedAt", row.get("observedAt"));
                normalized.put("validAt", row.get("observedAt"));
                normalized.put("directness", "DIRECT");
                normalized.put("authority", "PRIMARY");
                normalized.put("independenceGroup", row.get("independenceGroupHash"));
                normalized.put("independence", "INDEPENDENT");
                normalized.put("relation", "SUPPORTS");
                normalized.put("coverage", "COMPLETE");
                normalized.put("constraintStrength", "SOFT");
                normalized.put("uniqueIdentifierMapping", true);
                normalized.put("queryTraceRef", row.get("queryTraceRef"));
                return Map.copyOf(normalized);
            }).toList();

            Map<String, Object> verified = invoker.invoke(
                    "evidence.coherence.verify",
                    Map.of(
                            "decisionQuestion", "private round trip question",
                            "originalClaim", "private round trip claim",
                            "evidenceRows", normalizedRows,
                            "officialConstraints", List.of(),
                            "allowedReleaseStatuses", List.of("APPROVE", "REJECT", "HOLD"),
                            "queryTraceRefs", queryTraceRefs,
                            "counterEvidencePacketRef", retrievedData.get("packetRef")),
                    toolContext,
                    true);
            Map<String, Object> verifiedData = (Map<String, Object>) verified.get("data");
            assertThat(verifiedData.get("releaseStatus")).isEqualTo("HOLD");
            assertThat(verifiedData.get("verificationGatePassed")).isEqualTo(false);
            assertThat(verifiedData.get("counterEvidencePacketConsumed")).isEqualTo(true);
            assertThat(verifiedData.get("consumedQueryTraceRefs")).isEqualTo(queryTraceRefs);
            assertThat(retrieved.toString()).doesNotContain("private local evidence");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorAuthorityRoundTripsFromCausalProbeIntoOneShotCounterRetrieval() {
        contextRunner.run(context -> {
            CausalProbeTriggerService service = context.getBean(CausalProbeTriggerService.class);
            when(service.projectCurrentTrace(
                    anyString(),
                    anyString(),
                    any(CausalProbeTriggerService.ProbeConstraints.class)))
                    .thenReturn(new CausalProbeTriggerService.Decision(
                            3,
                            true,
                            "axis_agreement",
                            "after_filter_starvation",
                            "web_search",
                            0.8d,
                            "anchor_compression_topup",
                            "source_patch_candidate",
                            2,
                            "allowed",
                            "hash:aaaaaaaaaaaa"));
            AgentToolInvoker invoker = context.getBean(AgentToolInvoker.class);
            ToolContext toolContext = new ToolContext("authority-round-trip", null);

            Map<String, Object> evaluated = invoker.invoke(
                     "causal.probe.evaluate",
                     Map.of(
                             "goal", "private operator goal",
                             "originalClaim", "private LLM hypothesis",
                             "allowedPatchCandidates", List.of("anchor_compression_topup"),
                            "forbiddenActions", List.of("delete_repository"),
                            "decisionQuestion", "private operator question",
                            "queries", List.of(
                                    Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                                    Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                                    Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3")),
                            "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", 500),
                            "dissentSignal", Map.of(
                                    "atomicObservation", "independent integration anomaly",
                                    "provenanceGroup", "independent-integration-audit",
                                    "correlatedMajorityProvenanceGroups", List.of("primary-rag"),
                                    "collapsedFrom", List.of("integration-signal-1"),
                                    "specificity", "high",
                                    "decisionChanging", true,
                                    "decisionImpact", Map.of(
                                            "ifCorroborated", "block patch candidate",
                                            "ifDisconfirmed", "retain current candidate"),
                                    "family", "provenance_and_time"),
                            "stopRequested", false,
                            "maxMillis", 500),
                    toolContext,
                    true);
            Map<String, Object> authorityData = (Map<String, Object>) evaluated.get("data");
            assertThat(authorityData.get("operatorAuthorityIssued")).isEqualTo(true);
            assertThat(authorityData.get("dissentSignalEligible")).isEqualTo(true);

             Map<String, Object> counterInput = Map.of(
                     "operatorAuthorityRef", authorityData.get("operatorAuthorityRef"),
                     "operatorGoalHash", authorityData.get("operatorGoalHash"),
                     "operatorConstraintHash", authorityData.get("constraintHash"),
                     "originalClaim", "private LLM hypothesis",
                    "decisionQuestion", "private operator question",
                    "queries", List.of(
                            Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                            Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                            Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3")),
                    "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", 500));
            Map<String, Object> retrieved = invoker.invoke(
                    "counter.evidence.retrieve", counterInput, toolContext, true);
            Map<String, Object> retrievedData = (Map<String, Object>) retrieved.get("data");
             assertThat(retrievedData.get("operatorAuthorityConsumed")).isEqualTo(true);
             assertThat(retrievedData.get("operatorEffectiveMaxMillis")).isEqualTo(500);
             assertThat(evaluated.toString()).doesNotContain("private operator goal");
             assertThat(evaluated.toString()).doesNotContain("private LLM hypothesis");
             assertThat(evaluated.toString()).doesNotContain("independent integration anomaly");
             assertThat(evaluated.toString()).doesNotContain("independent-integration-audit");

            org.junit.jupiter.api.Assertions.assertThrows(
                    ToolInvocationException.class,
                    () -> invoker.invoke("counter.evidence.retrieve", counterInput, toolContext, true));
        });
    }

    private static Map<String, Object> evidence(String id, String group) {
        return Map.ofEntries(
                Map.entry("evidenceId", id),
                Map.entry("claim", "atomic claim " + id),
                Map.entry("sourceProvenance", "artifact provenance " + id),
                Map.entry("observedAt", "2026-07-15T00:00:00Z"),
                Map.entry("validAt", "2026-07-15T00:00:00Z"),
                Map.entry("directness", "DIRECT"),
                Map.entry("authority", "PRIMARY"),
                Map.entry("independenceGroup", group),
                Map.entry("independence", "INDEPENDENT"),
                Map.entry("relation", "SUPPORTS"),
                Map.entry("coverage", "COMPLETE"),
                Map.entry("constraintStrength", "SOFT"),
                Map.entry("uniqueIdentifierMapping", false));
    }
}
