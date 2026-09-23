package com.nova.protocol.plan;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nova.protocol.config.NovaProperties;
import com.nova.protocol.context.PlanContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.util.context.Context;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HyperNovaAllocationBoundaryTest {
    private static final String ID = "hyper_nova.v1";
    private static final String RESOURCE = "plans/" + ID + ".yaml";
    private static final String DEFAULT = "allocation-fixture-default";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> leaves() {
        return Stream.of("k_allocation.total", "k_allocation.max_source_share",
                "k_allocation.floor.web", "k_allocation.floor.vec", "k_allocation.floor.kg")
                .flatMap(f -> Stream.of("changed", "absent").map(v -> Arguments.of(f, v)));
    }

    @ParameterizedTest(name = "hyper-allocation:{0}:{1}")
    @MethodSource("leaves")
    void exactAuthoredLeavesDoNotChangeThreeCompleteProjections(String field, String variant) throws Exception {
        ObjectNode base = authored(), input = base.deepCopy();
        JsonNode old = at(base, field);
        assertTrue(old.isNumber());
        ObjectNode parent = parent(input, field);
        String leaf = field.substring(field.lastIndexOf('.') + 1);
        if (variant.equals("absent")) parent.remove(leaf);
        else if (old.isIntegralNumber()) parent.put(leaf, old.intValue() + 7);
        else parent.put(leaf, old.doubleValue() + 0.125);
        if (!variant.equals("absent")) {
            assertEquals(old.isIntegralNumber(), at(input, field).isIntegralNumber());
            assertEquals(old.getNodeType(), at(input, field).getNodeType());
        }
        assertNotEquals(base, input);
        ObjectNode restored = input.deepCopy();
        parent(restored, field).set(leaf, old);
        assertEquals(base, restored);
        assertEquals(protocol(base, "block"), protocol(input, "block"));
        assertEquals(hints(base), hints(input), "full typed/raw hints, orchestration hints, metadata and independent guard overrides");
        assertEquals(nova(base), nova(input), "enabled DTO from exact selected resource");
        System.out.printf("TBL07_HYPER_ALLOCATION field=%s variant=%s protocolEqual=true hintsEqual=true novaEqual=true protocolReads=2 hintReads=2 novaReads=2%n", field, variant);
    }

    static Stream<Arguments> retrieval() {
        return Stream.of("web", "vector", "kg").flatMap(lane ->
                Stream.of("block", "inline").map(shape -> Arguments.of(lane, shape)));
    }

    @ParameterizedTest(name = "hyper-retrieval-neighbor:{0}:{1}")
    @MethodSource("retrieval")
    void mappedRetrievalAllocationChangesOnlyExpectedMembersInAllThreePaths(String lane, String shape) throws Exception {
        ObjectNode base = authored();
        base.with("retrieval").putObject("k").put("web", 9).put("vector", 9).put("kg", 9);
        ObjectNode input = base.deepCopy();
        ((ObjectNode) input.path("retrieval").path("k")).put(lane, 13);
        ObjectNode expected = protocol(base, shape), actual = protocol(input, shape);
        assertEquals(Map.of("web", 9, "vector", 9, "kg", 9), JSON.convertValue(expected.path("kAllocation"), Map.class));
        assertNotEquals(expected, actual);
        ((ObjectNode) expected.get("kAllocation")).put(lane, 13);
        assertEquals(expected, actual);
        assertHintDelta(base, input, lane);
        ObjectNode expectedNova = nova(base), actualNova = nova(input);
        assertNotEquals(expectedNova, actualNova);
        expectedNova.put(lane.equals("vector") ? "vectorTopK" : lane + "TopK", 13);
        assertEquals(expectedNova, actualNova);
        System.out.printf("TBL07_HYPER_RETRIEVAL lane=%s shape=%s expectedProtocolDelta=true expectedHintDelta=true expectedNovaDelta=true protocolReads=2 hintReads=2 novaReads=2%n", lane, shape);
    }

    @ParameterizedTest(name = "hyper-sibling-neighbor:{0}")
    @ValueSource(strings = {"web", "vector", "kg"})
    void directAllocationSiblingIsAHintOnlyPathRatherThanAFloorAlias(String lane) throws Exception {
        ObjectNode base = authored();
        base.with("k_allocation").put("web", 9).put("vector", 9).put("kg", 9);
        ObjectNode input = base.deepCopy();
        input.with("k_allocation").put(lane, 13);
        assertEquals(base.path("k_allocation").path("floor"), input.path("k_allocation").path("floor"));
        assertHintDelta(base, input, lane);
        assertEquals(protocol(base, "block"), protocol(input, "block"));
        assertEquals(nova(base), nova(input));
        System.out.printf("TBL07_HYPER_SIBLING lane=%s expectedHintDelta=true floorUnchanged=true protocolEqual=true novaEqual=true protocolReads=2 hintReads=2 novaReads=2%n", lane);
    }

    @ParameterizedTest(name = "hyper-context:{0}")
    @ValueSource(strings = {"wrapped", "direct", "empty", "malformed"})
    void currentPlanContextCanBypassTheResourceButMalformedContextUsesDefault(String kind) throws Exception {
        Plan explicit = new Plan();
        explicit.setId("explicit-fixture");
        Context context = switch (kind) {
            case "wrapped" -> Context.of(PlanContext.KEY, new PlanContext(explicit));
            case "direct" -> Context.of(PlanContext.KEY, explicit);
            case "malformed" -> Context.of(PlanContext.KEY, "invalid-fixture");
            default -> Context.empty();
        };
        boolean bypass = kind.equals("wrapped") || kind.equals("direct");
        Map<String, byte[]> resources = Map.of("plans/" + DEFAULT + ".yaml", fixture(DEFAULT, 7));
        Plan observed = withResources(resources, applier -> applier.currentPlan(context),
                bypass ? List.of() : List.of("plans/" + DEFAULT + ".yaml"), bypass ? 0 : 1);
        if (bypass) assertSame(explicit, observed);
        else { assertEquals(DEFAULT, observed.getId()); assertEquals(7, observed.getCitationMin()); }
        System.out.printf("TBL07_HYPER_CONTEXT kind=%s bypass=%s protocolReads=%d%n", kind, bypass, bypass ? 0 : 1);
    }

    @ParameterizedTest(name = "hyper-missing:{0}")
    @ValueSource(booleans = {true, false})
    void missingRequestedResourceIsDistinguishedFromLoadedDefaultOrEmptyFallback(boolean defaultExists) throws Exception {
        String missing = "allocation-fixture-missing";
        Map<String, byte[]> resources = defaultExists
                ? Map.of("plans/" + DEFAULT + ".yaml", fixture(DEFAULT, 7)) : Map.of();
        List<String> attempts = new ArrayList<>(List.of("plans/" + missing + ".yaml",
                "plans/" + missing + ".yml", "plans/" + DEFAULT + ".yaml"));
        if (!defaultExists) attempts.add("plans/" + DEFAULT + ".yml");
        Plan observed = withResources(resources, applier -> applier.resolvePlan(missing, false),
                attempts, defaultExists ? 1 : 0);
        assertEquals(DEFAULT, observed.getId());
        assertEquals(defaultExists ? 7 : 3, observed.getCitationMin());
        assertNull(observed.getkAllocation());
        System.out.printf("TBL07_HYPER_MISSING defaultExists=%s emptyFallback=%s protocolReads=%d%n",
                defaultExists, !defaultExists, defaultExists ? 1 : 0);
    }

    @Test
    void braveSelectionOverridesRequestedIdAndReadsItsOwnResource() throws Exception {
        Plan result = withResources(Map.of("plans/brave.v1.yaml", fixture("brave.v1", 8)),
                applier -> applier.resolvePlan(ID, true), List.of("plans/brave.v1.yaml"), 1);
        assertEquals("brave.v1", result.getId());
        assertEquals(8, result.getCitationMin());
        System.out.println("TBL07_HYPER_BRAVE requestedOverridden=true protocolReads=1");
    }

    private static void assertHintDelta(ObjectNode base, ObjectNode input, String lane) throws Exception {
        ObjectNode expected = hints(base), actual = hints(input);
        String member = lane.equals("vector") ? "vecTopK" : lane + "TopK";
        assertEquals(9, expected.path("plan").path(member).asInt());
        assertNotEquals(expected, actual);
        ((ObjectNode) expected.get("plan")).put(member, 13);
        ((ObjectNode) expected.get(lane.equals("kg") ? "metadata" : "hints")).put(member, 13);
        if (!lane.equals("kg")) ((ObjectNode) expected.get("metadata")).put(member, "13");
        assertEquals(expected, actual, "only typed topK and its actual hint/metadata consumer member change");
    }

    private static ObjectNode authored() throws Exception {
        ObjectNode root = (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + ID + ".yaml")));
        assertEquals(ID, root.path("id").asText());
        assertTrue(root.path("llm").isObject(), "reused helper's diagnostic assertion remains applicable to this full resource");
        return root;
    }

    private static ObjectNode parent(ObjectNode root, String field) {
        String[] parts = field.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) root = (ObjectNode) root.path(parts[i]);
        return root;
    }

    private static JsonNode at(JsonNode root, String field) {
        for (String part : field.split("\\.")) root = root.path(part);
        return root;
    }

    private static ObjectNode hints(ObjectNode root) throws Exception {
        return JSON.valueToTree(ReflectionTestUtils.invokeMethod(
                Class.forName("com.example.lms.plan.PlanRootLlmBoundaryTest"), "apply", ID, root));
    }

    private static ObjectNode nova(ObjectNode root) throws Exception {
        return JSON.valueToTree(ReflectionTestUtils.invokeMethod(
                Class.forName("com.example.lms.plan.PlanRootLlmBoundaryTest"), "nova", ID, root));
    }

    private static ObjectNode protocol(ObjectNode root, String shape) throws Exception {
        String yaml = YAML.writeValueAsString(root);
        if (shape.equals("inline")) {
            JsonNode k = root.path("retrieval").path("k");
            String block = "  k:\n    web: " + k.path("web").asInt() + "\n    vector: " + k.path("vector").asInt()
                    + "\n    kg: " + k.path("kg").asInt() + "\n";
            String inline = "  k: { web: " + k.path("web").asInt() + ", vector: " + k.path("vector").asInt()
                    + ", kg: " + k.path("kg").asInt() + " }\n";
            assertTrue(yaml.contains(block));
            yaml = yaml.replace(block, inline);
            assertEquals(root, YAML.readTree(yaml), "inline shape preserves all semantic input fields");
        }
        Plan result = withResources(Map.of(RESOURCE, yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                applier -> applier.resolvePlan(ID, false), List.of(RESOURCE), 1);
        assertEquals(ID, result.getId());
        ObjectNode output = JSON.valueToTree(result);
        Set<String> keys = new TreeSet<>(); output.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("id", "citationMin", "kAllocation", "timeouts", "burst", "enableOverdrive"), keys);
        return output;
    }

    private static byte[] fixture(String id, int citation) {
        return ("id: " + id + "\ngates:\n  citationMin: " + citation + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Plan withResources(Map<String, byte[]> resources, Function<PlanApplier, Plan> call,
                                      List<String> expectedAttempts, int expectedReads) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        List<String> attempts = new ArrayList<>(), reads = new ArrayList<>();
        ClassLoader exact = new ClassLoader(previous) {
            @Override public InputStream getResourceAsStream(String name) {
                if (!name.startsWith("plans/")) return super.getResourceAsStream(name);
                attempts.add(name);
                byte[] bytes = resources.get(name);
                if (bytes == null) return null;
                reads.add(name);
                return new ByteArrayInputStream(bytes);
            }
        };
        NovaProperties props = new NovaProperties();
        props.setDefaultPlanId(DEFAULT);
        try {
            Thread.currentThread().setContextClassLoader(exact);
            Plan result = call.apply(new PlanApplier(new PlanLoader(), props));
            assertNotNull(result);
            assertEquals(expectedAttempts, attempts, "no alternate resource or hidden fallback");
            assertEquals(expectedReads, reads.size());
            return result;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
