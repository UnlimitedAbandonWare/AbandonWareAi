package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nova.protocol.config.NovaProperties;
import com.nova.protocol.plan.PlanApplier;
import com.nova.protocol.plan.PlanLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RuleBreakRemainingProjectionBoundaryTest {
    private static final String ID = "rulebreak.v1";
    private static final String RESOURCE = "plans/" + ID + ".yaml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> leaves() {
        return Stream.of("fusion.calibratedRrf", "probe.vector.enabled", "retrieval.kalloc.rag-weight",
                "retrieval.kalloc.web-weight", "retrieval.web.timeoutMs", "retrieval.web.webTopK",
                "safety.citationGate.minOfficialSources", "safety.citationGate.minSnippetChars")
                .flatMap(f -> Stream.of("changed", "absent").map(v -> Arguments.of(f, v)));
    }

    @ParameterizedTest(name = "rulebreak-exact:{0}:{1}")
    @MethodSource("leaves")
    void originalInlineShapeAndOneExactLeafChangePreserveAllThreeProjections(String field, String variant) throws Exception {
        String raw = authored(), input = mutate(raw, field, variant);
        ObjectNode baseTree = tree(raw), changedTree = tree(input);
        assertNotEquals(raw, input); assertNotEquals(baseTree, changedTree);
        JsonNode old = at(baseTree, field);
        if (variant.equals("changed")) assertEquals(old.getNodeType(), at(changedTree, field).getNodeType());
        ObjectNode restored = changedTree.deepCopy();
        String[] parts = field.split("\\."); parent(restored, parts).set(parts[parts.length - 1], old);
        assertEquals(baseTree, restored, "only one semantic leaf changed; all bytes outside one exact replacement span remain");
        assertEquals(hints(raw), hints(input));
        ObjectNode protocolBefore = protocol(raw), protocolAfter = protocol(input);
        assertTrue(protocolBefore.path("timeouts").isNull());
        assertTrue(protocolAfter.path("timeouts").isNull(), "original inline map does not expose timeoutMs to the minimal parser");
        assertEquals(protocolBefore, protocolAfter);
        assertEquals(nova(raw), nova(input));
        System.out.printf("TBL07_RULEBREAK_EXACT field=%s variant=%s rawShapePreserved=true fullHintEqual=true protocolEqual=true novaEqual=true originalTimeoutStored=false hintReads=2 protocolReads=2 novaReads=2%n", field, variant);
    }

    @ParameterizedTest(name = "rulebreak-timeout-shape:{0}")
    @ValueSource(strings = {"authored", "block", "block_changed", "block_absent"})
    void inlineAndBlockYamlHaveDifferentProtocolTimeoutStorageButNoObservedExecution(String shape) throws Exception {
        String raw = authored(), input = raw;
        Integer expected = null;
        if (!shape.equals("authored")) {
            String nl = newline(raw);
            input = replaceOnce(raw, "  web: { webTopK: 22, timeoutMs: 2500 }",
                    "  web:" + nl + "    webTopK: 22" + nl + "    timeoutMs: 2500");
            assertEquals(tree(raw), tree(input), "shape-only change preserves full YAML meaning");
            expected = 2500;
            if (shape.equals("block_changed")) {
                input = replaceOnce(input, "    timeoutMs: 2500", "    timeoutMs: 9000"); expected = 9000;
            } else if (shape.equals("block_absent")) {
                input = replaceOnce(input, nl + "    timeoutMs: 2500", ""); expected = null;
            }
        }
        ObjectNode expectedTree = tree(raw);
        ObjectNode web = (ObjectNode) expectedTree.path("retrieval").path("web");
        if (shape.equals("block_absent")) web.remove("timeoutMs");
        else if (shape.equals("block_changed")) web.put("timeoutMs", 9000);
        assertEquals(expectedTree, tree(input));
        ObjectNode before = protocol(raw), after = protocol(input);
        if (expected != null) before.set("timeouts", JSON.valueToTree(Map.of("timeoutMs", expected)));
        assertEquals(before, after, "only protocol timeout map differs");
        Map<String, Object> serialized = protocolMap(input);
        assertEquals(ID, serialized.get("id"));
        if (expected == null) assertFalse(serialized.containsKey("timeouts"));
        else assertEquals(Map.of("timeoutMs", expected), serialized.get("timeouts"));
        assertEquals(hints(raw), hints(input)); assertEquals(nova(raw), nova(input));
        System.out.printf("TBL07_RULEBREAK_TIMEOUT shape=%s stored=%s serialized=true hintEqual=true novaEqual=true enforcementClaim=false hintReads=2 protocolReads=3 serializerReads=1 novaReads=2%n", shape, expected);
    }

    static Stream<Arguments> retrieval() {
        return Stream.of("web", "vector", "kg").flatMap(lane ->
                Stream.of("inline", "block").map(shape -> Arguments.of(lane, shape)));
    }

    @ParameterizedTest(name = "rulebreak-retrieval-neighbor:{0}:{1}")
    @MethodSource("retrieval")
    void mappedRetrievalKeysHaveExactPositiveDeltasWithOriginalOtherInlineMapsKept(String lane, String shape) throws Exception {
        String raw = authored(), nl = newline(raw);
        String k = shape.equals("inline") ? "  k: { web: 9, vector: 9, kg: 9 }" + nl
                : "  k:" + nl + "    web: 9" + nl + "    vector: 9" + nl + "    kg: 9" + nl;
        String base = replaceOnce(raw, "retrieval:" + nl, "retrieval:" + nl + k);
        String input = replaceOnce(base, lane + ": 9", lane + ": 13");
        ObjectNode restored = tree(base); restored.with("retrieval").remove("k");
        assertEquals(tree(raw), restored);
        ObjectNode expected = hints(base), actual = hints(input);
        String member = lane.equals("vector") ? "vecTopK" : lane + "TopK";
        assertEquals(9, expected.path("plan").path(member).asInt()); assertNotEquals(expected, actual);
        ((ObjectNode) expected.get("plan")).put(member, 13);
        ((ObjectNode) expected.get(lane.equals("kg") ? "metadata" : "hints")).put(member, 13);
        if (!lane.equals("kg")) ((ObjectNode) expected.get("metadata")).put(member, "13");
        assertEquals(expected, actual);
        ObjectNode expectedProtocol = protocol(base), actualProtocol = protocol(input);
        assertTrue(expectedProtocol.path("timeouts").isNull()); assertTrue(actualProtocol.path("timeouts").isNull());
        ((ObjectNode) expectedProtocol.get("kAllocation")).put(lane, 13); assertEquals(expectedProtocol, actualProtocol);
        ObjectNode expectedNova = nova(base), actualNova = nova(input);
        expectedNova.put(lane.equals("vector") ? "vectorTopK" : lane + "TopK", 13); assertEquals(expectedNova, actualNova);
        System.out.printf("TBL07_RULEBREAK_RETRIEVAL lane=%s shape=%s expectedHintDelta=true expectedProtocolDelta=true expectedNovaDelta=true originalTimeoutStored=false hintReads=2 protocolReads=2 novaReads=2%n", lane, shape);
    }

    private static String mutate(String raw, String field, String variant) {
        boolean absent = variant.equals("absent");
        return switch (field) {
            case "fusion.calibratedRrf" -> replaceOnce(raw, "calibratedRrf: true", absent ? "" : "calibratedRrf: false");
            case "probe.vector.enabled" -> replaceOnce(raw, "  vector:" + newline(raw) + "    enabled: true",
                    absent ? "  vector: {}" : "  vector:" + newline(raw) + "    enabled: false");
            case "retrieval.kalloc.rag-weight" -> replaceOnce(raw, ", rag-weight: 0.15", absent ? "" : ", rag-weight: 0.25");
            case "retrieval.kalloc.web-weight" -> replaceOnce(raw, "web-weight: 0.85, ", absent ? "" : "web-weight: 0.65, ");
            case "retrieval.web.timeoutMs" -> replaceOnce(raw, ", timeoutMs: 2500", absent ? "" : ", timeoutMs: 9000");
            case "retrieval.web.webTopK" -> replaceOnce(raw, "webTopK: 22, ", absent ? "" : "webTopK: 11, ");
            case "safety.citationGate.minOfficialSources" -> replaceOnce(raw, "minOfficialSources: 1, ", absent ? "" : "minOfficialSources: 3, ");
            case "safety.citationGate.minSnippetChars" -> replaceOnce(raw, ", minSnippetChars: 200", absent ? "" : ", minSnippetChars: 400");
            default -> throw new AssertionError(field);
        };
    }
    private static String replaceOnce(String raw, String old, String replacement) {
        assertTrue(raw.contains(old)); assertEquals(raw.indexOf(old), raw.lastIndexOf(old));
        return raw.replace(old, replacement);
    }
    private static String newline(String raw) { return raw.contains("\r\n") ? "\r\n" : "\n"; }
    private static String authored() throws Exception {
        return Files.readString(Path.of("main/resources/" + RESOURCE), StandardCharsets.UTF_8);
    }
    private static ObjectNode tree(String raw) throws Exception { return (ObjectNode) YAML.readTree(raw); }
    private static JsonNode at(JsonNode root, String field) {
        for (String part : field.split("\\.")) root = root.path(part); return root;
    }
    private static ObjectNode parent(ObjectNode root, String[] parts) {
        for (int i = 0; i < parts.length - 1; i++) root = (ObjectNode) root.path(parts[i]); return root;
    }
    private record Effect(PlanHints plan, JsonNode hints, Map<String, Object> metadata, Map<String, Object> overrides) {}

    private static ObjectNode hints(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8); AtomicInteger lookups = new AtomicInteger(), reads = new AtomicInteger();
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                assertEquals("classpath:" + RESOURCE, location); lookups.incrementAndGet();
                return new ByteArrayResource(bytes) {
                    @Override public String getFilename() { return ID + ".yaml"; }
                    @Override public InputStream getInputStream() throws java.io.IOException { reads.incrementAndGet(); return super.getInputStream(); }
                };
            }
        });
        PlanHints loaded = applier.load(ID);
        assertFalse(loaded.isEmpty()); assertEquals(ID, loaded.planId()); assertEquals(true, loaded.onnxEnabled());
        assertTrue(PlanHintApplier.dslUnwiredKeys(loaded).contains("llm"));
        OrchestrationHints hints = OrchestrationHints.defaults(); Map<String, Object> meta = new LinkedHashMap<>();
        GuardContext guard = new GuardContext(); applier.applyToHintsAndMeta(loaded, hints, meta); applier.applyToGuardContext(loaded, guard);
        assertEquals(1, lookups.get()); assertEquals(1, reads.get());
        return JSON.valueToTree(new Effect(loaded, JSON.valueToTree(hints), meta, new LinkedHashMap<>(guard.getPlanOverrides())));
    }
    private static ObjectNode protocol(String raw) {
        return exact(raw, () -> {
            NovaProperties props = new NovaProperties(); props.setDefaultPlanId("forbidden-fallback");
            var plan = new PlanApplier(new PlanLoader(), props).resolvePlan(ID, false);
            assertNotNull(plan); assertEquals(ID, plan.getId());
            return JSON.valueToTree(plan);
        });
    }
    private static Map<String, Object> protocolMap(String raw) {
        return exact(raw, () -> new PlanLoader().get(ID));
    }
    private static ObjectNode nova(String raw) {
        return exact(raw, () -> {
            var plan = new com.example.lms.nova.PlanDslLoader().load(ID);
            assertNotNull(plan); assertTrue(plan.enabled); return JSON.valueToTree(plan);
        });
    }
    private static <T> T exact(String raw, Supplier<T> body) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8); AtomicInteger reads = new AtomicInteger();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = new ClassLoader(previous) {
            @Override public InputStream getResourceAsStream(String name) {
                if (!name.startsWith("plans/")) return super.getResourceAsStream(name);
                assertEquals(RESOURCE, name); reads.incrementAndGet(); return new ByteArrayInputStream(bytes);
            }
        };
        try { Thread.currentThread().setContextClassLoader(loader); T result = body.get(); assertEquals(1, reads.get()); return result; }
        finally { Thread.currentThread().setContextClassLoader(previous); }
    }
}
