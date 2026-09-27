package com.example.lms.plan;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HyperNovaRemainingProjectionBoundaryTest {
    private static final String ID = "hyper_nova.v1";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> leaves() {
        List<String> fields = List.of("fusion.rrf", "fusion.post", "fusion.grandas_tail.max_adjustment",
                "fusion.grandas_tail.p_max", "fusion.grandas_tail.bode_c", "rerank.pass1", "rerank.pass2",
                "rerank.dpp.enabled", "rerank.dpp.sigma", "gates.final_sigmoid.k",
                "gates.final_sigmoid.x0", "gates.final_sigmoid.pass", "whitening.enabled");
        assertEquals(13, fields.size());
        return fields.stream().flatMap(f -> Stream.of("changed", "absent").map(v -> Arguments.of(f, v)));
    }

    @ParameterizedTest(name = "hyper-remaining:{0}:{1}")
    @MethodSource("leaves")
    void exactLeavesPreserveCompleteRuntimeProjectionsAndRootDiagnostics(String field, String variant) throws Exception {
        ObjectNode base = authored(), input = base.deepCopy();
        String[] parts = field.split("\\.");
        JsonNode old = at(base, field);
        assertFalse(old.isMissingNode());
        assertTrue(old.isBoolean() || old.isNumber() || old.isTextual());
        ObjectNode parent = parent(input, parts);
        String leaf = parts[parts.length - 1];
        if (variant.equals("absent")) parent.remove(leaf);
        else if (old.isBoolean()) parent.put(leaf, !old.booleanValue());
        else if (old.isIntegralNumber()) parent.put(leaf, old.intValue() + 7);
        else if (old.isNumber()) parent.put(leaf, old.doubleValue() + 0.125);
        else parent.put(leaf, "fixture-alternate-value");
        if (!variant.equals("absent")) assertEquals(old.getNodeType(), at(input, field).getNodeType());
        assertNotEquals(base, input);
        ObjectNode restored = input.deepCopy(); parent(restored, parts).set(leaf, old);
        assertEquals(base, restored, "all unrelated values and parent containers are retained");
        ObjectNode before = hints(base), after = hints(input);
        assertEquals(before, after, "full typed/raw/hints/metadata/guard projection without normalization");
        assertTrue(before.path("plan").path("raw").path("dslUnwiredKeys").toString().contains("\"fusion\""));
        assertTrue(after.path("plan").path("raw").path("dslUnwiredKeys").toString().contains("\"whitening\""));
        assertEquals(protocol(base), protocol(input));
        assertEquals(nova(base), nova(input));
        System.out.printf("TBL07_HYPER_REMAINING field=%s variant=%s fullHintEqual=true diagnosticRetained=true protocolEqual=true novaEqual=true hintReads=2 protocolReads=2 novaReads=2%n", field, variant);
    }

    @ParameterizedTest(name = "hyper-root-diagnostic:{0}")
    @ValueSource(strings = {"fusion", "whitening"})
    void RemovingWholeRootChangesOnlyItsTwoRawDiagnosticMemberships(String root) throws Exception {
        ObjectNode base = authored(), input = base.deepCopy(); input.remove(root);
        ObjectNode expected = hints(base), actual = hints(input);
        assertNotEquals(expected, actual);
        ObjectNode raw = (ObjectNode) expected.path("plan").path("raw");
        removeExactlyOne(raw, "rootKeys", root); removeExactlyOne(raw, "dslUnwiredKeys", root);
        assertEquals(expected, actual, "only root-key and diagnostic-list membership changes");
        assertEquals(protocol(base), protocol(input));
        assertEquals(nova(base), nova(input));
        System.out.printf("TBL07_HYPER_ROOT_DIAGNOSTIC root=%s expectedTwoRawChanges=true protocolEqual=true novaEqual=true hintReads=2 protocolReads=2 novaReads=2%n", root);
    }

    private static void removeExactlyOne(ObjectNode raw, String member, String value) {
        ArrayNode before = (ArrayNode) raw.get(member), after = JSON.createArrayNode();
        int removed = 0;
        for (JsonNode entry : before) { if (entry.asText().equals(value)) removed++; else after.add(entry); }
        assertEquals(1, removed); raw.set(member, after);
    }

    private static ObjectNode parent(ObjectNode root, String[] parts) {
        for (int i = 0; i < parts.length - 1; i++) root = (ObjectNode) root.path(parts[i]);
        return root;
    }
    private static JsonNode at(JsonNode root, String field) {
        for (String part : field.split("\\.")) root = root.path(part);
        return root;
    }
    private static ObjectNode authored() throws Exception {
        return (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + ID + ".yaml")));
    }
    private static ObjectNode hints(ObjectNode root) {
        return JSON.valueToTree(ReflectionTestUtils.invokeMethod(PlanRootLlmBoundaryTest.class, "apply", ID, root));
    }
    private static ObjectNode nova(ObjectNode root) {
        return JSON.valueToTree(ReflectionTestUtils.invokeMethod(PlanRootLlmBoundaryTest.class, "nova", ID, root));
    }
    private static ObjectNode protocol(ObjectNode root) throws Exception {
        return ReflectionTestUtils.invokeMethod(Class.forName("com.nova.protocol.plan.HyperNovaAllocationBoundaryTest"),
                "protocol", root, "block");
    }
}
