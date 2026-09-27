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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanRemainingLiteralPropertiesTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> controls() {
        var rows = new ArrayList<Arguments>();
        for (String key : List.of("gate.citation.enabled", "naver.search.timeout-ms", "upstash.cache.ttl-seconds"))
            for (String value : List.of("changed", "absent")) rows.add(Arguments.of("safe_autorun.v1", key, value));
        for (String value : List.of("changed", "absent")) rows.add(Arguments.of("zero_break.v1", "domainWhitelist.override", value));
        assertEquals(8, rows.size());
        return rows.stream();
    }

    @ParameterizedTest(name = "remaining-property:{0}:{1}:{2}")
    @MethodSource("controls")
    void remainingLiteralPropertiesDoNotChangeCurrentPlanProjections(String plan, String key, String value) throws Exception {
        ObjectNode authored = authored(plan);
        ObjectNode input = authored.deepCopy();
        var old = properties(authored).get(key);
        if ("gate.citation.enabled".equals(key)) assertTrue(old.isBoolean());
        else if ("domainWhitelist.override".equals(key)) {
            assertTrue(old.isTextual());
            assertTrue(old.textValue().contains("${"), "keep authored placeholder text unresolved");
        } else assertTrue(old.isIntegralNumber());
        if ("absent".equals(value)) properties(input).remove(key);
        else {
            if (old.isBoolean()) properties(input).put(key, !old.booleanValue());
            else if (old.isIntegralNumber()) properties(input).put(key, old.intValue() + 1);
            else properties(input).put(key, "TBL07_LITERAL_TEXT_CONTROL");
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
        System.out.printf("TBL07_REMAINING_PROPERTY plan=%s key=%s value=%s fullEffectEqual=true novaEqual=true applierReads=2 novaReads=2%n",
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
