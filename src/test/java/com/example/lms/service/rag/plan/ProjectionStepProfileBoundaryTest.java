package com.example.lms.service.rag.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionStepProfileBoundaryTest {
    private static final String PLAN = "projection_agent.v1";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static Stream<Arguments> profileCases() {
        return Stream.of("projection_merge", "final_answer").flatMap(step ->
                Stream.of("guard-profile", "memory-profile").flatMap(field ->
                        Stream.of("authored", "changed", "absent").map(value -> Arguments.of(step, field, value))));
    }

    @ParameterizedTest(name = "{0}:{1}:{2}")
    @MethodSource("profileCases")
    void stepProfileFieldsDoNotChangeTheCompleteProjectionSpec(String stepId, String field, String value) throws Exception {
        ObjectNode authored = authored();
        ObjectNode input = authored.deepCopy();
        ObjectNode selected = step(input, stepId);
        assertTrue(selected.hasNonNull(field));
        if ("changed".equals(value)) selected.put(field, "guard-profile".equals(field) ? "strict" : "none");
        if ("absent".equals(value)) selected.remove(field);
        ObjectNode restored = input.deepCopy();
        step(restored, stepId).set(field, step(authored, stepId).get(field));
        assertEquals(authored, restored, "all unselected resource fields are preserved");
        if (!"authored".equals(value)) assertNotEquals(authored, input);
        ProjectionAgentPlanSpec baseline = load(authored);
        ProjectionAgentPlanSpec actual = load(input);
        assertEquals(baseline, actual, "compare all typed fields, including branches and defaults");
        System.out.printf("TBL07_PROJECTION_STEP_PROFILE step=%s field=%s value=%s fullSpecEqual=true resourceReads=2%n",
                stepId, field, value);
    }

    @ParameterizedTest(name = "supported:{0}")
    @ValueSource(strings = {"projection_merge", "final_answer"})
    void supportedFieldsAtTheSameStepsChangeTheExpectedTypedMember(String stepId) throws Exception {
        ObjectNode authored = authored();
        ObjectNode input = authored.deepCopy();
        if ("projection_merge".equals(stepId)) {
            assertTrue(step(input, stepId).path("config").path("keep-free-side-notes").asBoolean());
            ((ObjectNode) step(input, stepId).get("config")).put("keep-free-side-notes", false);
        } else {
            assertEquals(2048, step(input, stepId).path("max-tokens").asInt());
            step(input, stepId).put("max-tokens", 1536);
        }
        ObjectNode restored = input.deepCopy();
        if ("projection_merge".equals(stepId)) {
            ((ObjectNode) step(restored, stepId).get("config")).set("keep-free-side-notes",
                    step(authored, stepId).path("config").get("keep-free-side-notes"));
        } else {
            step(restored, stepId).set("max-tokens", step(authored, stepId).get("max-tokens"));
        }
        assertEquals(authored, restored);
        assertNotEquals(authored, input);
        ProjectionAgentPlanSpec baseline = load(authored);
        ProjectionAgentPlanSpec actual = load(input);
        ProjectionAgentPlanSpec.Merge merge = "projection_merge".equals(stepId)
                ? new ProjectionAgentPlanSpec.Merge(false, baseline.merge().keepConflictFlags()) : baseline.merge();
        ProjectionAgentPlanSpec.FinalAnswer f = baseline.finalAnswer();
        ProjectionAgentPlanSpec.FinalAnswer finalAnswer = "final_answer".equals(stepId)
                ? new ProjectionAgentPlanSpec.FinalAnswer(f.model(), f.systemPrompt(), f.citations(), 1536) : f;
        assertEquals(new ProjectionAgentPlanSpec(baseline.id(), baseline.defaults(), baseline.viewMemorySafe(),
                baseline.viewFreeProjection(), merge, finalAnswer), actual);
        assertNotEquals(baseline, actual);
        System.out.printf("TBL07_PROJECTION_STEP_CONTROL step=%s fullSpecEqual=false resourceReads=2%n", stepId);
    }

    private static ObjectNode authored() throws Exception {
        return (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + PLAN + ".yaml")));
    }

    private static ObjectNode step(ObjectNode root, String id) {
        ObjectNode found = null;
        for (var node : root.path("pipeline")) {
            if (id.equals(node.path("id").asText())) {
                assertNull(found, "selected step must be unique");
                found = (ObjectNode) node;
            }
        }
        assertNotNull(found, "selected step must exist");
        return found;
    }

    private static ProjectionAgentPlanSpec load(ObjectNode resource) throws Exception {
        byte[] bytes = YAML.writeValueAsBytes(resource);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        var resources = new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                assertEquals("classpath:plans/" + PLAN + ".yaml", location);
                requests.incrementAndGet();
                return new ByteArrayResource(bytes) {
                    @Override public InputStream getInputStream() throws java.io.IOException {
                        reads.incrementAndGet();
                        return super.getInputStream();
                    }
                    @Override public String getFilename() { return PLAN + ".yaml"; }
                };
            }
        };
        ProjectionAgentPlanSpec result = new PlanDslLoader(resources).loadProjectionAgent(PLAN).orElseThrow();
        assertEquals(1, requests.get(), "no fallback resource lookup");
        assertEquals(1, reads.get(), "a fresh loader must read the supplied bytes");
        return result;
    }
}
