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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionSelectorBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> RECOGNIZED = List.of("/pipeline/1/id", "/pipeline/1/mode",
            "/pipeline/1/branches/0/id", "/pipeline/1/branches/1/id", "/pipeline/2/id", "/pipeline/3/id");

    static Stream<Arguments> controls() {
        List<Arguments> rows = new ArrayList<>();
        for (String pointer : RECOGNIZED)
            for (String value : List.of("case", "unknown", "absent", "whitespace"))
                rows.add(Arguments.of(pointer, value));
        rows.add(Arguments.of("/pipeline/0/id", "unknown"));
        rows.add(Arguments.of("/pipeline/0/id", "absent"));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}:{1}")
    @MethodSource("controls")
    void selectorsChooseTypedSlotsAndFallbacksWithoutChangingResourceIdentity(String pointer, String value) throws Exception {
        ObjectNode authored = (ObjectNode) YAML.readTree(Files.readAllBytes(
                Path.of("main/resources/plans/projection_agent.v1.yaml")));
        assertEquals(4, authored.path("pipeline").size());
        assertEquals(2, authored.at("/pipeline/1/branches").size());
        assertEquals("analyze_intent", authored.at("/pipeline/0/id").asText());
        assertEquals("draft_dual_view", authored.at("/pipeline/1/id").asText());
        assertEquals("parallel", authored.at("/pipeline/1/mode").asText());
        assertEquals("view_memory_safe", authored.at("/pipeline/1/branches/0/id").asText());
        assertEquals("view_free_projection", authored.at("/pipeline/1/branches/1/id").asText());
        assertEquals("projection_merge", authored.at("/pipeline/2/id").asText());
        assertEquals("final_answer", authored.at("/pipeline/3/id").asText());
        ObjectNode baselineTree = authored.deepCopy();
        assertTrue(baselineTree.at("/pipeline/2/config/keep-free-side-notes").asBoolean());
        ((ObjectNode) baselineTree.at("/pipeline/2/config")).put("keep-free-side-notes", false);
        ObjectNode unmaskedRestored = baselineTree.deepCopy();
        ((ObjectNode) unmaskedRestored.at("/pipeline/2/config")).set("keep-free-side-notes",
                authored.at("/pipeline/2/config/keep-free-side-notes"));
        assertEquals(authored, unmaskedRestored, "only the declared fixed merge unmasker changes the baseline");
        ProjectionAgentPlanSpec baseline = load(baselineTree);
        assertFalse(baseline.merge().keepFreeSideNotes());
        assertEquals(2048, baseline.finalAnswer().maxTokens());
        assertEquals(1024, baseline.viewMemorySafe().maxTokens());
        assertEquals(1024, baseline.viewFreeProjection().maxTokens());
        ObjectNode input = baselineTree.deepCopy();
        String leaf = pointer.substring(pointer.lastIndexOf('/') + 1);
        String parentPointer = pointer.substring(0, pointer.lastIndexOf('/'));
        ObjectNode parent = (ObjectNode) input.at(parentPointer);
        String original = baselineTree.at(pointer).asText();
        assertFalse(original.isBlank());
        switch (value) {
            case "case" -> parent.put(leaf, original.toUpperCase(Locale.ROOT));
            case "unknown" -> parent.put(leaf, "fixture_unknown_selector");
            case "absent" -> parent.remove(leaf);
            case "whitespace" -> parent.put(leaf, " " + original + " ");
            default -> throw new AssertionError("undeclared selector control");
        }
        assertNotEquals(baselineTree, input);
        ObjectNode restored = input.deepCopy();
        ((ObjectNode) restored.at(parentPointer)).set(leaf, baselineTree.at(pointer));
        assertEquals(baselineTree, restored, "every non-selector value, including the fixed unmasker, is preserved");
        ProjectionAgentPlanSpec actual = load(input);
        var strict = baseline.viewMemorySafe();
        var free = baseline.viewFreeProjection();
        var merge = baseline.merge();
        var finalAnswer = baseline.finalAnswer();
        if ("case".equals(value)) {
            if (pointer.equals("/pipeline/1/branches/0/id")) strict = withId(strict, original.toUpperCase(Locale.ROOT));
            if (pointer.equals("/pipeline/1/branches/1/id")) free = withId(free, original.toUpperCase(Locale.ROOT));
        } else {
            if (pointer.equals("/pipeline/1/id") || pointer.equals("/pipeline/1/mode")
                    || pointer.equals("/pipeline/1/branches/0/id"))
                strict = synthesized("view_memory_safe", baseline.defaults());
            if (pointer.equals("/pipeline/1/id") || pointer.equals("/pipeline/1/mode")
                    || pointer.equals("/pipeline/1/branches/1/id"))
                free = synthesized("view_free_projection", baseline.defaults());
            if (pointer.equals("/pipeline/2/id")) merge = new ProjectionAgentPlanSpec.Merge(true, false);
            if (pointer.equals("/pipeline/3/id")) finalAnswer = new ProjectionAgentPlanSpec.FinalAnswer("auto", "projection.final", true, 1200);
        }
        assertEquals(new ProjectionAgentPlanSpec(baseline.id(), baseline.defaults(), strict, free, merge, finalAnswer), actual);
        boolean inactive = pointer.equals("/pipeline/0/id");
        boolean rawIdCase = "case".equals(value) && pointer.contains("/branches/");
        boolean semanticFallback = !inactive && !"case".equals(value);
        assertEquals(inactive || ("case".equals(value) && !rawIdCase), baseline.equals(actual));
        System.out.printf("TBL07_PROJECTION_SELECTOR path=%s value=%s inactive=%s semanticFallback=%s rawIdCase=%s mergeUnmasked=true resourceReads=2%n",
                pointer, value, inactive, semanticFallback, rawIdCase);
    }

    private static ProjectionAgentPlanSpec.Branch synthesized(String id, ProjectionAgentPlanSpec.Defaults defaults) {
        return new ProjectionAgentPlanSpec.Branch(id, "auto", defaults.guardProfile(),
                defaults.memoryProfile(), defaults.maxTokens(), null);
    }

    private static ProjectionAgentPlanSpec.Branch withId(ProjectionAgentPlanSpec.Branch branch, String id) {
        return new ProjectionAgentPlanSpec.Branch(id, branch.model(), branch.guardProfile(),
                branch.memoryProfile(), branch.maxTokens(), branch.traits());
    }

    private static ProjectionAgentPlanSpec load(ObjectNode resource) {
        return ReflectionTestUtils.invokeMethod(ProjectionStepProfileBoundaryTest.class, "load", resource);
    }
}
