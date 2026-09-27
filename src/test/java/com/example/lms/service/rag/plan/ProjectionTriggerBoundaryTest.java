package com.example.lms.service.rag.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionTriggerBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static Stream<Arguments> controls() {
        return IntStream.range(0, 3).boxed().flatMap(index ->
                Stream.of("when", "weight").flatMap(field ->
                        Stream.of("changed", "absent").map(value -> Arguments.of(index, field, value))));
    }

    @ParameterizedTest(name = "{0}:{1}:{2}")
    @MethodSource("controls")
    void authoredTriggerLeavesAreExcludedFromTheCompleteProjectionSpec(int index, String field, String value) throws Exception {
        ObjectNode authored = (ObjectNode) YAML.readTree(Files.readAllBytes(
                Path.of("main/resources/plans/projection_agent.v1.yaml")));
        var entries = authored.at("/triggers/by-intent");
        assertTrue(entries.isArray());
        assertEquals(3, entries.size(), "freeze every authored trigger entry");
        for (var entry : entries) {
            assertTrue(entry.path("when").isTextual());
            assertTrue(entry.path("weight").isNumber());
        }
        String parentPointer = "/triggers/by-intent/" + index;
        String pointer = parentPointer + "/" + field;
        ObjectNode input = authored.deepCopy();
        ObjectNode selected = (ObjectNode) input.at(parentPointer);
        if ("absent".equals(value)) {
            selected.remove(field);
            assertTrue(input.at(pointer).isMissingNode());
        } else {
            if ("when".equals(field)) selected.put(field, "chat.fixture.trigger_" + index);
            else selected.put(field, new double[]{0.81, 0.91, 0.71}[index]);
            assertEquals(authored.at(pointer).getNodeType(), input.at(pointer).getNodeType());
            assertNotEquals(authored.at(pointer), input.at(pointer));
        }
        assertNotEquals(authored, input);
        ObjectNode restored = input.deepCopy();
        ((ObjectNode) restored.at(parentPointer)).set(field, authored.at(pointer));
        assertEquals(authored, restored, "all selectors and unrelated YAML fields remain fixed");
        ProjectionAgentPlanSpec baseline = load(authored);
        ProjectionAgentPlanSpec actual = load(input);
        assertEquals(baseline, actual, "compare every typed default, branch, merge and final field");
        System.out.printf("TBL07_PROJECTION_TRIGGER path=%s value=%s fullSpecEqual=true resourceReads=2%n",
                pointer, value);
    }

    private static ProjectionAgentPlanSpec load(ObjectNode resource) {
        // The existing helper creates a fresh real loader for each supplied resource
        // and asserts one exact lookup and one read, excluding cache-only comparisons.
        // Its positive controls run separately; selector APIs do not receive this YAML.
        return ReflectionTestUtils.invokeMethod(ProjectionStepProfileBoundaryTest.class, "load", resource);
    }
}
