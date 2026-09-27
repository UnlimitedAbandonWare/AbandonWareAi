package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanIdentityBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> AP = List.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1");
    private static final List<String> ROOT_IDS = List.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1", "hyper_nova.v1", "kg_first.v1", "projection_agent.v1", "recency_first.v1", "safe.v1", "UAW_thumbnail.v1", "zero100.v1");
    private static final List<String> NESTED_IDS = List.of("brave.v1", "document_evidence.v1", "safe_autorun.v1", "zero_break.v1");

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> shippedControls() {
        var rows = new ArrayList<List<String>>();
        ROOT_IDS.forEach(p -> rows.add(List.of(p, "id")));
        NESTED_IDS.forEach(p -> rows.add(List.of(p, "plan.id")));
        AP.forEach(p -> rows.add(List.of(p, "name")));
        rows.add(List.of("rulebreak.v1", "name"));
        assertEquals(20, rows.size());
        return rows.stream().flatMap(row -> Stream.of("authored", "other_valid", "malformed", "absent")
                .map(mutation -> Arguments.of(row.get(0), row.get(1), mutation)));
    }

    @ParameterizedTest(name = "identity plan={0} key={1} mutation={2}")
    @MethodSource("shippedControls")
    void shippedIdentityControlsAdmissionWithoutRebindingTheRequestedPlan(String resourceId, String key, String mutation) throws Exception {
        var original = resource(resourceId); var modified = original.deepCopy();
        ObjectNode holder = key.equals("plan.id") ? (ObjectNode) modified.path("plan") : modified;
        String leaf = key.equals("plan.id") ? "id" : key;
        assertTrue(holder.path(leaf).isTextual());
        var originalValue = holder.path(leaf).deepCopy();
        if (mutation.equals("absent")) holder.remove(leaf);
        else if (mutation.equals("other_valid")) holder.put(leaf, "fixture_other_identity.v1");
        else if (mutation.equals("malformed")) holder.put(leaf, "fixture invalid identity");
        else assertEquals("authored", mutation);
        var restored = modified.deepCopy();
        (key.equals("plan.id") ? (ObjectNode) restored.path("plan") : restored).set(leaf, originalValue);
        assertEquals(original, restored, "only the selected identity field changes");
        boolean shadowedName = key.equals("name") && AP.contains(resourceId);
        boolean accepted = mutation.equals("authored") || mutation.equals("other_valid") || shadowedName
                || (mutation.equals("absent") && key.equals("id") && AP.contains(resourceId));
        String reason = mutation.equals("malformed") ? "malformed_id" : "missing_id";
        verifyAdmission(resourceId, original, modified, accepted, reason);

        var originalNova = nova(resourceId, original); var changedNova = nova(resourceId, modified);
        assertTrue(originalNova.enabled); assertTrue(changedNova.enabled);
        assertEquals(YAML.valueToTree(originalNova), YAML.valueToTree(changedNova), "Nova has a separate contract with no identity admission check");
        if (resourceId.equals("projection_agent.v1")) {
            var before = new com.example.lms.service.rag.plan.PlanDslLoader(loader(resourceId, original, new ArrayList<>())).loadProjectionAgent(resourceId);
            var after = new com.example.lms.service.rag.plan.PlanDslLoader(loader(resourceId, modified, new ArrayList<>())).loadProjectionAgent(resourceId);
            assertTrue(before.isPresent()); assertEquals("projection_agent.v1", before.orElseThrow().id());
            if (mutation.equals("authored")) assertEquals(before, after);
            else assertTrue(after.isEmpty(), "projection owner requires its exact declared ID even when PlanHint accepts another safe token");
        }
        System.out.printf("TBL07_IDENTITY_BOUNDARY plan=%s key=%s mutation=%s admitted=%s nameShadowed=%s novaDtoEqual=true projectionOwnerChecked=%s externalRequests=0%n",
                resourceId, key, mutation, accepted, shadowedName, resourceId.equals("projection_agent.v1"));
    }

    static Stream<Arguments> fallbackNameControls() {
        return AP.stream().flatMap(plan -> Stream.of("malformed", "absent").map(mutation -> Arguments.of(plan, mutation)));
    }

    @ParameterizedTest(name = "name fallback plan={0} mutation={1}")
    @MethodSource("fallbackNameControls")
    void fallbackNameBecomesAnAdmissionGateOnlyAfterHigherIdentityIsRemoved(String resourceId, String mutation) throws Exception {
        var original = resource(resourceId); var baseline = original.deepCopy(); baseline.remove("id");
        assertTrue(baseline.path("name").isTextual()); var modified = baseline.deepCopy();
        if (mutation.equals("malformed")) modified.put("name", "fixture invalid name");
        else { assertEquals("absent", mutation); modified.remove("name"); }
        var restored = modified.deepCopy(); restored.set("name", original.path("name")); restored.set("id", original.path("id"));
        assertEquals(original, restored, "only higher-ID removal and selected fallback-name control change");
        verifyAdmission(resourceId, baseline, modified, false, mutation.equals("malformed") ? "malformed_id" : "missing_id");
        System.out.printf("TBL07_IDENTITY_NAME_FALLBACK plan=%s mutation=%s higherIdRemoved=true admitted=false fallback=safe.v1 externalRequests=0%n", resourceId, mutation);
    }

    @ParameterizedTest(name = "nested identity precedence={0}")
    @ValueSource(strings = {"nested_valid_root_invalid", "nested_invalid_root_valid", "nested_blank_root_valid"})
    void nestedIdentityHasPrecedenceWithoutRebindingTheSelectedResource(String control) throws Exception {
        String resourceId = "ap1_auth_web.v1"; var original = resource(resourceId); var modified = original.deepCopy();
        assertFalse(modified.has("plan"));
        var nested = modified.putObject("plan");
        if (control.equals("nested_valid_root_invalid")) { nested.put("id", "fixture_nested.v1"); modified.put("id", "fixture invalid root"); }
        else if (control.equals("nested_invalid_root_valid")) nested.put("id", "fixture invalid nested");
        else { assertEquals("nested_blank_root_valid", control); nested.put("id", " "); }
        var restored = modified.deepCopy(); restored.remove("plan"); restored.set("id", original.path("id")); assertEquals(original, restored);
        boolean accepted = !control.equals("nested_invalid_root_valid");
        verifyAdmission(resourceId, original, modified, accepted, "malformed_id");
        System.out.printf("TBL07_IDENTITY_PRECEDENCE control=%s admitted=%s requestedIdPreserved=%s externalRequests=0%n", control, accepted, accepted);
    }

    static Stream<Arguments> versionControls() {
        return Stream.of("UAW_thumbnail.v1", "ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1", "hyper_nova.v1", "projection_agent.v1")
                .flatMap(plan -> Stream.of("authored", "changed", "absent").map(mutation -> Arguments.of(plan, mutation)));
    }

    @ParameterizedTest(name = "version plan={0} mutation={1}")
    @MethodSource("versionControls")
    void rootVersionValuesDoNotChangeTheThreeNamedLoaderContracts(String resourceId, String mutation) throws Exception {
        var original = resource(resourceId); var modified = original.deepCopy(); var version = original.path("version");
        boolean numeric = resourceId.equals("UAW_thumbnail.v1") || resourceId.equals("projection_agent.v1");
        if (numeric) assertTrue(version.isIntegralNumber()); else assertTrue(version.isTextual());
        if (mutation.equals("absent")) modified.remove("version");
        else if (mutation.equals("changed")) {
            if (numeric) modified.put("version", 999); else modified.put("version", "fixture_v99");
        } else assertEquals("authored", mutation);
        var restored = modified.deepCopy(); restored.set("version", version); assertEquals(original, restored, "only exact root version changes");
        verifyAdmission(resourceId, original, modified, true, "unused");
        var beforeNova = nova(resourceId, original); var afterNova = nova(resourceId, modified);
        assertTrue(beforeNova.enabled); assertTrue(afterNova.enabled); assertEquals(YAML.valueToTree(beforeNova), YAML.valueToTree(afterNova));
        if (resourceId.equals("projection_agent.v1")) {
            var before = new com.example.lms.service.rag.plan.PlanDslLoader(loader(resourceId, original, new ArrayList<>())).loadProjectionAgent(resourceId);
            var after = new com.example.lms.service.rag.plan.PlanDslLoader(loader(resourceId, modified, new ArrayList<>())).loadProjectionAgent(resourceId);
            assertTrue(before.isPresent()); assertEquals(before, after, "the actual projection owner preserves its complete spec");
        }
        System.out.printf("TBL07_VERSION_BOUNDARY plan=%s mutation=%s valueType=%s typedAndRequestEqual=true novaDtoEqual=true projectionOwnerChecked=%s rootKeyPresenceOnly=true externalRequests=0%n",
                resourceId, mutation, numeric ? "integer" : "string", resourceId.equals("projection_agent.v1"));
    }


    private static void verifyAdmission(String resourceId, ObjectNode original, ObjectNode modified, boolean accepted, String reason) throws Exception {
        String requestedId = resourceId.toLowerCase(Locale.ROOT);
        var originalReads = new ArrayList<String>(); var modifiedReads = new ArrayList<String>();
        var baselineApplier = new PlanHintApplier(loader(resourceId, original, originalReads));
        var changedApplier = new PlanHintApplier(loader(resourceId, modified, modifiedReads));
        TraceStore.clear(); var baseline = baselineApplier.load(resourceId);
        assertEquals(requestedId, baseline.planId()); assertFalse(baseline.isEmpty()); assertEquals(List.of(requestedId), originalReads);
        TraceStore.clear(); var changed = changedApplier.load(resourceId);
        if (accepted) {
            assertEquals(requestedId, changed.planId()); assertEquals(List.of(requestedId), modifiedReads);
            assertNull(TraceStore.get("plan.schema.invalid.last")); assertNull(TraceStore.get("plan.schema.fallback"));
            var expectedRootKeys = new ArrayList<String>(); modified.fieldNames().forEachRemaining(expectedRootKeys::add);
            assertEquals(YAML.valueToTree(expectedRootKeys), YAML.valueToTree(changed.raw().get("rootKeys")));
            ObjectNode normalized = YAML.valueToTree(changed);
            ((ObjectNode) normalized.path("raw")).set("rootKeys", YAML.valueToTree(baseline.raw().get("rootKeys")));
            assertEquals(YAML.valueToTree(baseline), normalized, "only exact root-key-name metadata may change");
            var beforeMeta = new LinkedHashMap<String, Object>(); var afterMeta = new LinkedHashMap<String, Object>();
            baselineApplier.applyToHintsAndMeta(baseline, OrchestrationHints.defaults(), beforeMeta);
            changedApplier.applyToHintsAndMeta(changed, OrchestrationHints.defaults(), afterMeta); assertEquals(beforeMeta, afterMeta);
            var beforeGuard = new GuardContext(); var afterGuard = new GuardContext();
            baselineApplier.applyToGuardContext(baseline, beforeGuard); changedApplier.applyToGuardContext(changed, afterGuard);
            assertEquals(beforeGuard.getPlanOverrides(), afterGuard.getPlanOverrides());
        } else {
            assertEquals(requestedId + ":" + reason, TraceStore.get("plan.schema.invalid.last"));
            assertEquals("safe.v1", changed.planId());
            if (requestedId.equals("safe.v1")) {
                assertEquals(PlanHints.empty("safe.v1"), changed); assertEquals(List.of("safe.v1"), modifiedReads);
                assertNull(TraceStore.get("plan.schema.fallback"), "invalid safe plan ends without recursion");
            } else {
                assertEquals(List.of(requestedId, "safe.v1"), modifiedReads);
                assertEquals(requestedId + "->safe.v1", TraceStore.get("plan.schema.fallback"));
                var safe = new PlanHintApplier(loader("safe.v1", resource("safe.v1"), new ArrayList<>())).load("safe.v1");
                assertEquals(safe, changed, "fallback must be the complete safe plan,not only its ID");
            }
        }
    }

    private static ObjectNode resource(String resourceId) throws Exception {
        return (ObjectNode) YAML.readTree(Files.readString(Path.of("main/resources/plans", resourceId + ".yaml")));
    }

    private static DefaultResourceLoader loader(String resourceId, ObjectNode root, List<String> reads) throws Exception {
        var contents = new LinkedHashMap<String, byte[]>();
        contents.put("safe.v1", YAML.writeValueAsBytes(resource("safe.v1")));
        contents.put(resourceId.toLowerCase(Locale.ROOT), YAML.writeValueAsBytes(root));
        return new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                for (var entry : contents.entrySet()) if (location.equals("classpath:plans/" + entry.getKey() + ".yaml")) {
                    reads.add(entry.getKey()); return new ByteArrayResource(entry.getValue()) {
                        @Override public String getFilename() { return entry.getKey() + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
    }

    private static com.example.lms.nova.BravePlan nova(String resourceId, ObjectNode root) throws Exception {
        byte[] yaml = YAML.writeValueAsBytes(root); var reads = new ArrayList<String>();
        Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(new ClassLoader(previous) {
                @Override public java.io.InputStream getResourceAsStream(String name) {
                    if (name.equals("plans/" + resourceId + ".yaml")) { reads.add(name); return new java.io.ByteArrayInputStream(yaml); }
                    return super.getResourceAsStream(name);
                }
            });
            var result = new com.example.lms.nova.PlanDslLoader().load(resourceId);
            assertEquals(List.of("plans/" + resourceId + ".yaml"), reads); return result;
        } finally { thread.setContextClassLoader(previous); }
    }
}
