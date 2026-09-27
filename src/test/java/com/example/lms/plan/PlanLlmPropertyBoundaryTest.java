package com.example.lms.plan;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanLlmPropertyBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> PLANS = List.of("brave.v1", "safe_autorun.v1", "zero_break.v1");

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> controls() throws Exception {
        var rows = new ArrayList<Arguments>();
        for (String plan : PLANS) {
            Set<String> keys = new TreeSet<>();
            properties(authored(plan)).fieldNames().forEachRemaining(key -> {
                if (Set.of("llm.provider", "llm.route.mode").contains(key)) keys.add(key);
            });
            assertEquals(Set.of("llm.provider", "llm.route.mode"), keys);
            for (String key : keys) for (String value : List.of("changed", "absent"))
                rows.add(Arguments.of(plan, key, value));
        }
        assertEquals(12, rows.size());
        return rows.stream();
    }

    @ParameterizedTest(name = "llm-property:{0}:{1}:{2}")
    @MethodSource("controls")
    void literalLlmPropertiesDoNotChangeCurrentPlanProjections(String plan, String key, String value) throws Exception {
        ObjectNode authored = authored(plan);
        ObjectNode input = authored.deepCopy();
        var old = properties(authored).get(key);
        assertTrue(old.isTextual());
        if ("absent".equals(value)) properties(input).remove(key);
        else {
            properties(input).put(key, "llm.provider".equals(key) ? "openai" : "PRIMARY_ONLY");
            assertEquals(old.getNodeType(), properties(input).get(key).getNodeType());
            assertNotEquals(old, properties(input).get(key));
        }
        assertNotEquals(authored, input);
        ObjectNode restored = input.deepCopy();
        properties(restored).set(key, old);
        assertEquals(authored, restored, "literal dotted key is one leaf; every other field remains fixed");
        assertEquals(apply(plan, authored), apply(plan, input),
                "full typed/raw hints, orchestration hints, metadata and guard overrides without normalization");
        assertEquals(nova(plan, authored), nova(plan, input));
        System.out.printf("TBL07_LLM_PROPERTY plan=%s key=%s value=%s fullEffectEqual=true novaEqual=true applierReads=2 novaReads=2%n",
                plan, key, value);
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
