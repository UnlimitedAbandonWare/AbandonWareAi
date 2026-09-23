package com.example.lms.service.rag.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

class ProjectionStructuralBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> OCCURRENCES = List.of(
            "/pipeline/0/agent", "/pipeline/2/agent", "/pipeline/3/agent",
            "/pipeline/1/branches/0/agent", "/pipeline/1/branches/1/agent",
            "/pipeline/2/impl", "/pipeline/2/inputs/0/as", "/pipeline/2/inputs/1/as",
            "/pipeline/2/inputs/0/from-branch", "/pipeline/2/inputs/1/from-branch",
            "/pipeline/0/output/0/slot", "/pipeline/0/output/1/slot", "/pipeline/0/output/2/slot",
            "/pipeline/0/use-plan");
    private static final Set<String> STRUCTURAL = Set.of(
            "/pipeline/[]/agent", "/pipeline/[]/branches/[]/agent", "/pipeline/[]/impl",
            "/pipeline/[]/inputs/[]/as", "/pipeline/[]/inputs/[]/from-branch",
            "/pipeline/[]/output/[]/slot", "/pipeline/[]/use-plan");

    static Stream<Arguments> controls() {
        return OCCURRENCES.stream().flatMap(path ->
                Stream.of("changed", "absent").map(value -> Arguments.of(path, value)));
    }

    @ParameterizedTest(name = "{0}:{1}")
    @MethodSource("controls")
    void allAuthoredStructuralOccurrencesAreExcludedFromTheActiveProjectionSpec(String pointer, String value) throws Exception {
        ObjectNode authored = (ObjectNode) YAML.readTree(Files.readAllBytes(
                Path.of("main/resources/plans/projection_agent.v1.yaml")));
        List<String> found = new ArrayList<>();
        collect(authored, "", found);
        assertEquals(14, found.size());
        assertEquals(new TreeSet<>(OCCURRENCES), new TreeSet<>(found), "freeze all occurrences, including new unexpected ones");
        assertEquals(7, found.stream().map(ProjectionStructuralBoundaryTest::structural).distinct().count());
        ObjectNode input = authored.deepCopy();
        int separator = pointer.lastIndexOf('/');
        String parentPointer = pointer.substring(0, separator);
        String leaf = pointer.substring(separator + 1);
        ObjectNode parent = (ObjectNode) input.at(parentPointer);
        assertTrue(parent.get(leaf).isTextual());
        if ("absent".equals(value)) parent.remove(leaf);
        else parent.put(leaf, switch (leaf) {
            case "impl" -> "java.lang.String";
            case "use-plan" -> "safe.v1";
            case "from-branch" -> "fixture.other_branch";
            case "as" -> "fixture.alias";
            case "slot" -> "fixture.slot";
            default -> "fixture_agent";
        });
        assertNotEquals(authored, input);
        ObjectNode restored = input.deepCopy();
        ((ObjectNode) restored.at(parentPointer)).set(leaf, authored.at(pointer));
        assertEquals(authored, restored, "all selectors and every unselected resource field are preserved");
        ProjectionAgentPlanSpec baseline = load(authored);
        ProjectionAgentPlanSpec actual = load(input);
        assertEquals(baseline, actual, "compare every typed default, branch, merge and final field");
        System.out.printf("TBL07_PROJECTION_STRUCTURAL path=%s value=%s matchedOccurrences=%d fullSpecEqual=true resourceReads=2%n",
                pointer, value, found.size());
    }

    private static String structural(String pointer) {
        return pointer.replaceAll("/[0-9]+", "/[]");
    }

    private static void collect(JsonNode node, String pointer, List<String> found) {
        if (STRUCTURAL.contains(structural(pointer))) found.add(pointer);
        if (node.isObject()) node.fields().forEachRemaining(field -> collect(field.getValue(),
                pointer + "/" + field.getKey().replace("~", "~0").replace("/", "~1"), found));
        else if (node.isArray())
            for (int i = 0; i < node.size(); i++) collect(node.get(i), pointer + "/" + i, found);
    }

    private static ProjectionAgentPlanSpec load(ObjectNode resource) {
        // Shared helper asserts one exact resource lookup/read using a fresh real loader.
        // Its two supported-field controls are rerun in the same verification batch.
        return ReflectionTestUtils.invokeMethod(ProjectionStepProfileBoundaryTest.class, "load", resource);
    }
}
