package com.abandonware.ai.agent.contract;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.impl.ops.VerifyContractTool;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractValidatorPathTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void canonicalManifestUsesIdFieldAndDisabledLegacyReferencesAreWarnings() {
        ToolRegistry registry = new ToolRegistry();
        Set.of(
                        "verify.contract",
                        "ops.snapshot",
                        "repo.scan",
                        "trace.snapshot",
                        "kg.query",
                        "moe.strategy.query",
                        "source.map",
                        "config.inspect",
                        "debug.trace.lookup",
                        "db_evidence_scan",
                        "failure.pattern.scan",
                        "failure.pattern.recall",
                        "failure.pattern.record",
                        "causal.probe.evaluate",
                        "counter.evidence.retrieve",
                        "evidence.coherence.verify")
                .forEach(id -> registry.register(new StubTool(id)));

        ToolManifestCatalog catalog = new ToolManifestCatalog();
        Map<String, Object> report = catalog.validate(registry);

        assertEquals(true, report.get("ok"));
        assertFalse(report.containsKey("resourcePath"));
        assertTrue(String.valueOf(report.get("resourcePathHash")).length() >= 12);
        assertEquals("tool_manifest__kchat_gpt_pro.json".length(), report.get("resourcePathLength"));
        assertEquals(0, report.get("issueCount"));
        assertTrue(((Number) report.get("warningCount")).intValue() > 0);
        assertTrue(String.valueOf(report.get("manifestIds")).contains("web.search"));
        assertEquals(16, TraceStore.get("toolManifest.registeredCount"));
        assertEquals(23, TraceStore.get("toolManifest.manifestCount"));
        assertEquals(0, TraceStore.get("toolManifest.missingInManifestCount"));
        assertTrue(String.valueOf(TraceStore.get("toolManifest.snapshotAt")).length() > 10);
    }

    @Test
    void validationReportReturnsHashOnlyPathMetadata() {
        ToolManifestCatalog catalog = new ToolManifestCatalog();
        Map<String, Object> report = catalog.writeValidationReport(registryWithManifestTools());

        assertFalse(report.containsKey("reportPath"));
        assertTrue(String.valueOf(report.get("reportPathId")).length() >= 12);
        assertTrue(String.valueOf(report.get("reportPathHash")).length() >= 12);
        assertTrue(((Number) report.get("reportPathLength")).intValue() > 0);
    }

    @Test
    void verifyContractToolReturnsHashOnlyPathMetadata() {
        VerifyContractTool tool = new VerifyContractTool(new ToolManifestCatalog(), registryWithManifestTools());

        ToolResponse response = tool.execute(new ToolRequest(Map.of(), null));
        Map<String, Object> data = response.data();

        assertFalse(data.containsKey("resourcePath"));
        assertFalse(data.containsKey("reportPath"));
        assertTrue(String.valueOf(data.get("resourcePathHash")).length() >= 12);
        assertTrue(((Number) data.get("resourcePathLength")).intValue() > 0);
        assertTrue(String.valueOf(data.get("reportPathId")).length() >= 12);
        assertTrue(String.valueOf(data.get("reportPathHash")).length() >= 12);
        assertTrue(((Number) data.get("reportPathLength")).intValue() > 0);
    }

    private static ToolRegistry registryWithManifestTools() {
        ToolRegistry registry = new ToolRegistry();
        Set.of(
                        "verify.contract",
                        "ops.snapshot",
                        "repo.scan",
                        "trace.snapshot",
                        "kg.query",
                        "moe.strategy.query",
                        "source.map",
                        "config.inspect",
                        "debug.trace.lookup",
                        "db_evidence_scan",
                        "failure.pattern.scan",
                        "failure.pattern.recall",
                        "failure.pattern.record",
                        "causal.probe.evaluate",
                        "counter.evidence.retrieve",
                        "evidence.coherence.verify")
                .forEach(id -> registry.register(new StubTool(id)));
        return registry;
    }

    private record StubTool(String id) implements AgentTool {
        @Override
        public String description() {
            return "stub";
        }

        @Override
        public ToolResponse execute(ToolRequest request) {
            return ToolResponse.ok();
        }
    }
}
