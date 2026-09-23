package ai.abandonware.nova.orch.failpattern;

import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.example.lms.search.TraceStore;
import com.example.lms.test.SecretFixtures;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePatternMemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recordStoresSanitizedLowCardinalityPattern() throws Exception {
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        FailurePatternMemoryService service = service(memory);

        Map<String, Object> out = service.record(Map.of(
                "kind", "patch_judgment",
                "source", "codex",
                "failureClass", "timeout",
                "hotspot", "QueryTransformer",
                "intent", "raw query ownerToken=owner-secret",
                "evidence", "stack trace api_key=" + SecretFixtures.openAiKey() + " raw prompt should not persist",
                "patchAction", "fallback_raw_query",
                "decision", "reuse"));

        String line = Files.readString(memory);
        assertEquals(Boolean.TRUE, out.get("recorded"));
        assertFalse(out.toString().contains(memory.toAbsolutePath().normalize().toString()));
        assertEquals(SafeRedactor.hashValue(memory.toAbsolutePath().normalize().toString()), out.get("memoryPathHash"));
        assertEquals(memory.toAbsolutePath().normalize().toString().length(), out.get("memoryPathLength"));
        assertTrue(line.contains("intentHash12"));
        assertTrue(line.contains("evidenceHash12"));
        assertFalse(line.contains("owner-secret"));
        assertFalse(line.contains(SecretFixtures.openAiKey()));
        assertFalse(line.contains("raw prompt should not persist"));
        assertFalse(line.contains("raw query"));
    }

    @Test
    void matrixValuesCannotPersistShortAuthorizationOrApiKeyLikeLabels() throws Exception {
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        FailurePatternMemoryService service = service(memory);
        String rawAuthorization = "Authorization Bearer " + SecretFixtures.openAiKey();

        Map<String, Object> out = service.record(Map.of(
                "kind", "cfvm_failure_pattern",
                "source", "cfvm",
                "failureClass", "timeout",
                "hotspot", "DynamicRetrievalHandlerChain",
                "intent", "cfvm:abc123",
                "evidence", "compact-signature",
                "matrix", Map.of(
                        "failurePatternKind", rawAuthorization,
                        "rawPrompt", "private raw prompt should be ignored",
                        "m1", 3)));

        String line = Files.readString(memory);
        assertEquals(Boolean.TRUE, out.get("recorded"));
        assertTrue(line.contains("failurePatternKind"));
        assertTrue(line.contains("label_hash_"));
        assertTrue(line.contains("\"m1\":3"));
        assertFalse(line.contains("Authorization"));
        assertFalse(line.contains("Bearer"));
        assertFalse(line.contains(SecretFixtures.openAiKey()));
        assertFalse(line.contains("rawPrompt"));
        assertFalse(line.contains("private raw prompt"));
    }

    @Test
    void recordPublishesFailurePatternMemoryTraceAliases() throws Exception {
        TraceStore.clear();
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        FailurePatternMemoryService service = service(memory);

        service.record(Map.of(
                "kind", "cfvm_failure_pattern",
                "source", "cfvm",
                "failureClass", "timeout",
                "hotspot", "DynamicRetrievalHandlerChain",
                "intent", "cfvm:abc123",
                "evidence", "compact-signature",
                "boltzmannWeight", 0.42d,
                "matrix", Map.of(
                        "m1", 3,
                        "m2", 5,
                        "failurePatternKind", "timeout")));

        assertEquals(Boolean.TRUE, TraceStore.get("failurePattern.memory.stored"));
        assertEquals("", TraceStore.get("failurePattern.memory.skipReason"));
        assertEquals(3, TraceStore.get("failurePattern.memory.tileCount"));
        assertEquals(0.42d, ((Number) TraceStore.get("failurePattern.memory.boltzmann.weight")).doubleValue(), 1.0e-12d);
    }

    @Test
    void recordFailurePublishesFailurePatternMemorySkipTrace() throws Exception {
        TraceStore.clear();
        Path memoryDirectory = tempDir.resolve("failure-pattern-memory-dir.jsonl");
        Files.createDirectories(memoryDirectory);
        FailurePatternMemoryService service = service(memoryDirectory);

        service.record(Map.of(
                "kind", "patch_judgment",
                "source", "codex",
                "failureClass", "timeout",
                "hotspot", "QueryTransformer",
                "boltzmannWeight", Double.NaN,
                "matrix", Map.of("m1", 1)));

        assertEquals(Boolean.FALSE, TraceStore.get("failurePattern.memory.stored"));
        assertEquals("memory_write_failed", TraceStore.get("failurePattern.memory.skipReason"));
        assertEquals(1, TraceStore.get("failurePattern.memory.tileCount"));
        assertEquals(0.0d, ((Number) TraceStore.get("failurePattern.memory.boltzmann.weight")).doubleValue(), 1.0e-12d);
    }

    @Test
    void recordFailureUsesStableRedactedErrorLabel() throws Exception {
        Path memoryDirectory = tempDir.resolve("failure-pattern-memory-dir.jsonl");
        Files.createDirectories(memoryDirectory);
        FailurePatternMemoryService service = service(memoryDirectory);

        Map<String, Object> out = service.record(Map.of(
                "kind", "patch_judgment",
                "source", "codex",
                "failureClass", "timeout",
                "hotspot", "QueryTransformer",
                "intent", "raw query ownerToken=owner-secret",
                "evidence", "stack trace api_key=" + SecretFixtures.openAiKey()));

        assertEquals(Boolean.FALSE, out.get("recorded"));
        assertEquals("memory_write_failed", out.get("error"));
        assertFalse(out.toString().contains("AccessDeniedException"), out.toString());
        assertFalse(out.toString().contains("FileSystemException"), out.toString());
        assertFalse(out.toString().contains("owner-secret"), out.toString());
        assertFalse(out.toString().contains(SecretFixtures.openAiKey()), out.toString());
    }

    @Test
    void memoryServiceFallbackCatchesUseRedactedSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/FailurePatternMemoryService.java"));

        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePatternMemory.write\", ex);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePatternMemory.scanRoot\", ignore);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePatternMemory.scanFile\", ignore);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePatternMemory.manifestRead\", ignore);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePatternMemory.fileSize\", ex);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePatternMemory.readRows\", ex);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePatternMemory.malformedRow\", ignore);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePatternMemory.boundedInt\", ignore);"));
    }

    @Test
    void boundedIntDropsNonFiniteNumbersToFallback() throws Exception {
        Method boundedInt = FailurePatternMemoryService.class.getDeclaredMethod(
                "boundedInt", Object.class, int.class, int.class, int.class);
        boundedInt.setAccessible(true);

        assertEquals(5, boundedInt.invoke(null, Double.POSITIVE_INFINITY, 5, 1, 20));
        assertEquals(5, boundedInt.invoke(null, Double.NaN, 5, 1, 20));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recallTailKeepsOnlyTheBoundedNewestRowsWithoutReadAllLines() throws Exception {
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 1_050; i++) {
            rows.append("{\"row\":").append(i).append("}\n");
        }
        Files.writeString(memory, rows);
        FailurePatternMemoryService service = service(memory);
        Method readRows = FailurePatternMemoryService.class.getDeclaredMethod("readRows");
        readRows.setAccessible(true);

        List<Map<String, Object>> retained =
                (List<Map<String, Object>>) readRows.invoke(service);
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/FailurePatternMemoryService.java"));

        assertEquals(1_000, retained.size());
        assertEquals(50, retained.get(0).get("row"));
        assertEquals(1_049, retained.get(retained.size() - 1).get("row"));
        assertFalse(source.contains("Files.readAllLines(memoryPath"));
        assertTrue(source.contains("ArrayDeque<String> tail"));
    }

    @Test
    void recallRanksMatchingPatchJudgments() throws Exception {
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        FailurePatternMemoryService service = service(memory);
        service.record(Map.of(
                "kind", "patch_judgment",
                "source", "codex",
                "failureClass", "timeout",
                "hotspot", "QueryTransformer",
                "intent", "query transformer open",
                "patchAction", "fallback_raw_query"));
        service.record(Map.of(
                "kind", "patch_judgment",
                "source", "codex",
                "failureClass", "rate-limit",
                "hotspot", "NaverSearchService",
                "intent", "provider throttled",
                "patchAction", "cooldown_provider"));

        Map<String, Object> out = service.recall(Map.of(
                "kind", "patch_judgment",
                "source", "codex",
                "failureClass", "timeout",
                "hotspot", "QueryTransformer",
                "intent", "query transformer retry"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) out.get("matches");
        assertEquals("fallback_raw_query", matches.get(0).get("patchAction"));
        assertEquals("timeout", matches.get(0).get("failureClass"));
        assertTrue((Integer) matches.get(0).get("score") > 0);
    }

    @Test
    void scanCountsResiduesWithoutReturningSourceText() throws Exception {
        Files.createDirectories(tempDir.resolve("main/java/example"));
        Files.createDirectories(tempDir.resolve("main/resources"));
        String source = """
                package example;
                class Residue {
                  void x() {
                    try { throw new RuntimeException("raw source body"); } catch (Exception ignored) {%s}
                    String a = "placeholder";
                    String b = "zombie code";
                    String c = "%s";
                  }
                }
                """.formatted(" ", "COM" + "FYUI residue");
        Files.writeString(tempDir.resolve("main/java/example/Residue.java"), source);
        Files.writeString(tempDir.resolve("main/resources/tool_manifest__kchat_gpt_pro.json"), """
                {"tools":[{"id":"legacy.disabled","enabled":false,"disabledReason":"disabled_tool"}]}
                """);
        FailurePatternMemoryService service = service(tempDir.resolve("memory.jsonl"));

        Map<String, Object> out = service.scan(Map.of("limit", 8));
        String rendered = out.toString();

        assertEquals(1, count(out, "placeholder"));
        assertEquals(1, count(out, "swallowedCatch"));
        assertEquals(1, count(out, "zombieSignal"));
        assertEquals(1, count(out, "com" + "fyResidue"));
        assertEquals(1, count(out, "disabledTool"));
        assertFalse(rendered.contains("raw source body"));
        assertFalse(rendered.contains(tempDir.toAbsolutePath().normalize().toString()));
        assertEquals(SafeRedactor.hashValue(tempDir.toAbsolutePath().normalize().toString()), out.get("rootHash"));
        assertEquals(tempDir.toAbsolutePath().normalize().toString().length(), out.get("rootLength"));
    }

    @SuppressWarnings("unchecked")
    private static int count(Map<String, Object> scan, String key) {
        Map<String, Object> residues = (Map<String, Object>) scan.get("residues");
        Map<String, Object> row = (Map<String, Object>) residues.get(key);
        return ((Number) row.get("count")).intValue();
    }

    private FailurePatternMemoryService service(Path memory) {
        return new FailurePatternMemoryService(
                new ObjectMapper(),
                new ToolManifestCatalog(),
                tempDir,
                memory);
    }
}
