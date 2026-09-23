package com.example.lms.plan;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanFinalSigmoidPropertyBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> PLANS = List.of("brave.v1", "safe_autorun.v1", "zero_break.v1");

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> controls() throws Exception {
        var rows = new ArrayList<Arguments>();
        for (String plan : PLANS) {
            Set<String> keys = new TreeSet<>();
            properties(authored(plan)).fieldNames().forEachRemaining(key -> {
                if (key.startsWith("gate.finalSigmoid.")) keys.add(key);
            });
            assertEquals("safe_autorun.v1".equals(plan)
                    ? Set.of("gate.finalSigmoid.enabled", "gate.finalSigmoid.k", "gate.finalSigmoid.x0")
                    : Set.of("gate.finalSigmoid.k", "gate.finalSigmoid.x0"), keys);
            for (String key : keys) for (String value : List.of("changed", "absent"))
                rows.add(Arguments.of(plan, key, value));
        }
        assertEquals(14, rows.size());
        return rows.stream();
    }

    @ParameterizedTest(name = "sigmoid-property:{0}:{1}:{2}")
    @MethodSource("controls")
    void literalPlanPropertiesDoNotChangeCurrentPlanProjections(String plan, String key, String value) throws Exception {
        ObjectNode authored = authored(plan);
        ObjectNode input = authored.deepCopy();
        var old = properties(authored).get(key);
        assertTrue(old.isBoolean() || old.isNumber());
        if ("absent".equals(value)) properties(input).remove(key);
        else {
            if (old.isBoolean()) properties(input).put(key, !old.asBoolean());
            else properties(input).put(key, old.asDouble() + 0.25);
            assertEquals(old.getNodeType(), properties(input).get(key).getNodeType());
        }
        assertNotEquals(authored, input);
        ObjectNode restored = input.deepCopy();
        properties(restored).set(key, old);
        assertEquals(authored, restored, "literal dotted key is one leaf; every other field remains fixed");
        assertEquals(apply(plan, authored), apply(plan, input),
                "full typed/raw hints, orchestration hints, metadata and guard overrides without normalization");
        assertEquals(nova(plan, authored), nova(plan, input));
        System.out.printf("TBL07_SIGMOID_PROPERTY plan=%s key=%s value=%s fullEffectEqual=true novaEqual=true applierReads=2 novaReads=2%n",
                plan, key, value);
    }

    @ParameterizedTest(name = "mapped-property:{0}")
    @ValueSource(strings = {"brave.v1", "safe_autorun.v1", "zero_break.v1"})
    void mappedNeighborChangesOnlyExpectedTypedCitationMembers(String plan) throws Exception {
        ObjectNode authored = authored(plan);
        assertTrue(properties(authored).path("gate.citation.min").isIntegralNumber());
        int selected = properties(authored).path("gate.citation.min").asInt() + 1;
        ObjectNode input = authored.deepCopy();
        properties(input).put("gate.citation.min", selected);
        ObjectNode restored = input.deepCopy();
        properties(restored).set("gate.citation.min", properties(authored).get("gate.citation.min"));
        assertEquals(authored, restored);
        ObjectNode before = YAML.valueToTree(apply(plan, authored));
        ObjectNode after = YAML.valueToTree(apply(plan, input));
        assertNotEquals(before, after);
        ((ObjectNode) before.get("plan")).put("minCitations", selected);
        assertEquals(before, after, "every other typed/raw/metadata/override field stays equal");
        ObjectNode expectedNova = nova(plan, authored);
        ObjectNode actualNova = nova(plan, input);
        assertNotEquals(expectedNova, actualNova);
        expectedNova.put("minCitations", selected);
        assertEquals(expectedNova, actualNova);
        System.out.printf("TBL07_MAPPED_CITATION_PROPERTY plan=%s expectedTypedDelta=true expectedNovaDelta=true applierReads=2 novaReads=2%n", plan);
    }

    private static Object apply(String plan, ObjectNode root) {
        return ReflectionTestUtils.invokeMethod(PlanRootLlmBoundaryTest.class, "apply", plan, root);
    }

    private static ObjectNode nova(String plan, ObjectNode root) {
        return YAML.valueToTree(ReflectionTestUtils.invokeMethod(PlanRootLlmBoundaryTest.class, "nova", plan, root));
    }

    private static ObjectNode properties(ObjectNode root) {
        return (ObjectNode) root.path("plan").path("overrides").path("properties");
    }

    private static ObjectNode authored(String plan) throws Exception {
        return (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + plan + ".yaml")));
    }
}
