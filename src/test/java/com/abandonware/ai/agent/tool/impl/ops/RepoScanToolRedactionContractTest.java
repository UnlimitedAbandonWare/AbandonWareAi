package com.abandonware.ai.agent.tool.impl.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RepoScanToolRedactionContractTest {

    @Test
    void repoScanDoesNotReturnRawAbsoluteRootPath() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/tool/impl/ops/RepoScanTool.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("out.put(\"root\", Path.of(\".\").toAbsolutePath().normalize().toString());"));
        assertTrue(source.contains("out.put(\"rootHash\", SafeRedactor.hashValue(repoRoot.toString()));"));
        assertTrue(source.contains("out.put(\"rootLength\", repoRoot.toString().length());"));
    }

    @Test
    void repoScanDirectorySummariesDoNotReturnRawPathKeys() {
        ToolResponse response = new RepoScanTool().execute(new ToolRequest(Map.of(), null));
        Map<String, Object> repoScan = castMap(response.data().get("repoScan"));

        for (String section : java.util.List.of("mainJava", "mainResources", "testJava", "appJavaClean")) {
            Map<String, Object> summary = castMap(repoScan.get(section));
            assertFalse(summary.containsKey("path"), section + " must not expose a raw path key");
            assertTrue(summary.containsKey("pathHash"), section + " should expose a stable path hash");
            assertTrue(summary.containsKey("pathLength"), section + " should expose path length only");
        }
    }

    @Test
    void repoScanFailureDoesNotReturnRawExceptionClass() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/tool/impl/ops/RepoScanTool.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("out.put(\"error\", ex.getClass().getSimpleName());"));
        assertTrue(source.contains("out.put(\"error\", \"repo_scan_failed\");"));
        assertTrue(source.contains("traceSuppressed(\"scan\", root, ex);"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
