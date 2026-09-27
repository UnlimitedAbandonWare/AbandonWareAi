package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanDescriptorBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    @TempDir Path tempDir;

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> controls() {
        var rows = new ArrayList<List<String>>();
        for (String plan : List.of("brave.v1", "document_evidence.v1", "safe_autorun.v1", "zero_break.v1")) rows.add(List.of(plan, "plan.description"));
        for (String plan : List.of("hyper_nova.v1", "safe.v1", "zero100.v1")) rows.add(List.of(plan, "description"));
        for (String key : List.of("label", "meta.description", "meta.kind")) rows.add(List.of("projection_agent.v1", key));
        assertEquals(10, rows.size());
        return rows.stream().flatMap(row -> Stream.of("authored", "changed", "absent")
                .map(mutation -> Arguments.of(row.get(0), row.get(1), mutation)));
    }

    @ParameterizedTest(name = "descriptor plan={0} key={1} mutation={2}")
    @MethodSource("controls")
    void descriptiveValuesDoNotReachNamedLoaderExecutionOutputs(String planId, String key, String mutation) throws Exception {
        var original = (ObjectNode) YAML.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml")));
        var modified = original.deepCopy(); String[] segments = key.split("\\.");
        ObjectNode parent = modified;
        for (int i = 0; i < segments.length - 1; i++) parent = (ObjectNode) parent.path(segments[i]);
        String leaf = segments[segments.length - 1]; var authored = parent.path(leaf).deepCopy(); assertTrue(authored.isTextual());
        if (mutation.equals("absent")) parent.remove(leaf);
        else if (mutation.equals("changed")) parent.put(leaf, "fixture_changed_descriptor");
        else assertEquals("authored", mutation);
        var restored = modified.deepCopy(); ObjectNode restoredParent = restored;
        for (int i = 0; i < segments.length - 1; i++) restoredParent = (ObjectNode) restoredParent.path(segments[i]);
        restoredParent.set(leaf, authored); assertEquals(original, restored, "only the declared descriptor field changes");

        var originalReads = new ArrayList<String>(); var modifiedReads = new ArrayList<String>();
        var baselineApplier = new PlanHintApplier(loader(planId, original, originalReads));
        var changedApplier = new PlanHintApplier(loader(planId, modified, modifiedReads));
        var baseline = baselineApplier.load(planId); var changed = changedApplier.load(planId);
        assertEquals(List.of(planId), originalReads); assertEquals(originalReads, modifiedReads);
        assertEquals(planId, baseline.planId()); assertEquals(planId, changed.planId()); assertFalse(baseline.isEmpty()); assertFalse(changed.isEmpty());
        var expectedKeys = new ArrayList<String>(); modified.fieldNames().forEachRemaining(expectedKeys::add);
        assertEquals(YAML.valueToTree(expectedKeys), YAML.valueToTree(changed.raw().get("rootKeys")));
        ObjectNode normalized = YAML.valueToTree(changed);
        ((ObjectNode) normalized.path("raw")).set("rootKeys", YAML.valueToTree(baseline.raw().get("rootKeys")));
        assertEquals(YAML.valueToTree(baseline), normalized, "only the separately asserted root-key-name diagnostic may differ");
        assertFalse(changed.raw().containsKey(key)); assertFalse(changed.raw().containsValue("fixture_changed_descriptor"));
        var beforeMeta = new LinkedHashMap<String, Object>(); var afterMeta = new LinkedHashMap<String, Object>();
        baselineApplier.applyToHintsAndMeta(baseline, OrchestrationHints.defaults(), beforeMeta);
        changedApplier.applyToHintsAndMeta(changed, OrchestrationHints.defaults(), afterMeta); assertEquals(beforeMeta, afterMeta);
        var beforeGuard = new GuardContext(); var afterGuard = new GuardContext();
        baselineApplier.applyToGuardContext(baseline, beforeGuard); changedApplier.applyToGuardContext(changed, afterGuard);
        assertEquals(beforeGuard.getPlanOverrides(), afterGuard.getPlanOverrides()); assertNull(afterGuard.getPlanOverride(key));

        var beforeNova = nova(planId, original); var afterNova = nova(planId, modified);
        assertTrue(beforeNova.enabled); assertTrue(afterNova.enabled); assertEquals(YAML.valueToTree(beforeNova), YAML.valueToTree(afterNova));
        if (planId.equals("projection_agent.v1")) {
            var beforeProjectionReads = new ArrayList<String>(); var afterProjectionReads = new ArrayList<String>();
            var before = new com.example.lms.service.rag.plan.PlanDslLoader(loader(planId, original, beforeProjectionReads)).loadProjectionAgent(planId);
            var after = new com.example.lms.service.rag.plan.PlanDslLoader(loader(planId, modified, afterProjectionReads)).loadProjectionAgent(planId);
            assertEquals(List.of(planId), beforeProjectionReads); assertEquals(beforeProjectionReads, afterProjectionReads);
            assertTrue(before.isPresent()); assertTrue(after.isPresent()); assertEquals(before, after, "all nested projection spec fields remain equal");
        }
        assertFalse(original.has("desc"), "legacy desc is a distinct,absent field in these resources");
        var beforeRegistry = legacy(original); var afterRegistry = legacy(modified);
        assertEquals(legacySnapshot(beforeRegistry.plans()), legacySnapshot(afterRegistry.plans()));
        boolean registryAdmitted = original.hasNonNull("id");
        if (registryAdmitted) assertNull(afterRegistry.plans().get(original.path("id").asText()).desc());
        System.out.printf("TBL07_DESCRIPTOR_BOUNDARY plan=%s key=%s mutation=%s fullHintsAndRequestEqual=true novaDtoEqual=true projectionOwnerChecked=%s registrySubjectAdmitted=%s registryTypedEqual=true rootKeyPresenceOnly=true globalDisplayClaim=false externalRequests=0%n",
                planId, key, mutation, planId.equals("projection_agent.v1"), registryAdmitted);
    }

    @ParameterizedTest(name = "legacy identity={0}")
    @ValueSource(strings = {"authored", "changed_id", "blank_id", "absent_id", "nonblank_malformed_id"})
    void alternateLoaderRegistersRootIdentityAndRegistrySelectsThatKey(String control) throws Exception {
        var original = resource("safe.v1"); var modified = original.deepCopy();
        if (control.equals("changed_id")) modified.put("id", "fixture_other_identity.v1");
        else if (control.equals("blank_id")) modified.put("id", " ");
        else if (control.equals("absent_id")) modified.remove("id");
        else if (control.equals("nonblank_malformed_id")) modified.put("id", "fixture invalid identity");
        else assertEquals("authored", control);
        var restored = modified.deepCopy(); restored.set("id", original.path("id")); assertEquals(original, restored);
        var loaded = legacy(modified); boolean admitted = !control.equals("blank_id") && !control.equals("absent_id");
        String selectedId = admitted ? modified.path("id").asText() : "fixture_neighbor.v1";
        var registry = new com.abandonware.ai.agent.service.plan.PlanRegistry(new com.abandonware.ai.agent.service.plan.PlanLoader());
        ReflectionTestUtils.setField(registry, "activeId", "safe.v1");
        ReflectionTestUtils.setField(registry, "plansPath", loaded.pattern()); registry.init();
        assertEquals(planSnapshot(loaded.plans().get(selectedId)), planSnapshot(registry.byId(selectedId)));
        var request = new MockHttpServletRequest(); request.addHeader("X-Plan", selectedId);
        assertEquals(planSnapshot(loaded.plans().get(selectedId)), planSnapshot(registry.current(request)));
        if (!admitted) assertEquals("fixture_neighbor.v1", registry.current().id());
        System.out.printf("TBL07_REGISTRY_IDENTITY control=%s subjectAdmitted=%s rootIdIsMapKey=%s registryByIdAndHeaderVerified=true defaultGlobClaim=false externalRequests=0%n", control, admitted, admitted);
    }

    @ParameterizedTest(name = "legacy desc={0}")
    @ValueSource(strings = {"authored", "changed", "absent"})
    void legacyDescHasAnIndependentPositiveValueDeliveryControl(String mutation) throws Exception {
        var original = resource("safe.v1"); assertFalse(original.has("desc")); assertTrue(original.path("description").isTextual());
        var baseline = original.deepCopy(); baseline.put("desc", "fixture_legacy_description"); var modified = baseline.deepCopy();
        if (mutation.equals("changed")) modified.put("desc", "fixture_legacy_changed");
        else if (mutation.equals("absent")) modified.remove("desc"); else assertEquals("authored", mutation);
        var restored = modified.deepCopy(); restored.set("desc", baseline.path("desc")); assertEquals(baseline, restored);
        var before = legacy(baseline).plans().get("safe.v1"); var after = legacy(modified).plans().get("safe.v1");
        assertEquals("fixture_legacy_description", before.desc());
        assertEquals(mutation.equals("absent") ? null : mutation.equals("changed") ? "fixture_legacy_changed" : "fixture_legacy_description", after.desc());
        var normalized = planSnapshot(after); normalized.set("desc", YAML.valueToTree(before.desc()));
        assertEquals(planSnapshot(before), normalized, "all other public RetrievalPlan fields remain equal");
        System.out.printf("TBL07_REGISTRY_LEGACY_DESC mutation=%s descOnlyProjection=true descriptionUnchanged=true externalRequests=0%n", mutation);
    }

    static Stream<Arguments> registryVersionControls() {
        return Stream.of("UAW_thumbnail.v1", "ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1", "hyper_nova.v1", "projection_agent.v1")
                .flatMap(plan -> Stream.of("authored", "changed", "absent").map(mutation -> Arguments.of(plan, mutation)));
    }

    @ParameterizedTest(name = "registry version plan={0} mutation={1}")
    @MethodSource("registryVersionControls")
    void newlyFoundRegistryOwnerDoesNotConsumeTheSevenPreviouslyMappedVersions(String planId, String mutation) throws Exception {
        var original = resource(planId); var modified = original.deepCopy(); var version = original.path("version"); assertFalse(version.isMissingNode());
        if (mutation.equals("absent")) modified.remove("version");
        else if (mutation.equals("changed")) { if (version.isIntegralNumber()) modified.put("version", 999); else modified.put("version", "fixture_v99"); }
        else assertEquals("authored", mutation);
        var restored = modified.deepCopy(); restored.set("version", version); assertEquals(original, restored);
        var before = legacy(original); var after = legacy(modified);
        assertTrue(before.plans().containsKey(original.path("id").asText()));
        assertEquals(legacySnapshot(before.plans()), legacySnapshot(after.plans()));
        System.out.printf("TBL07_REGISTRY_VERSION plan=%s mutation=%s subjectAdmitted=true completeRegistryDtoEqual=true externalRequests=0%n", planId, mutation);
    }

    private record LegacyLoad(Map<String, com.abandonware.ai.agent.service.plan.RetrievalPlan> plans, String pattern) {}

    private LegacyLoad legacy(ObjectNode root) throws Exception {
        Path directory = Files.createTempDirectory(tempDir, "controlled-");
        Files.write(directory.resolve("subject.yaml"), YAML.writeValueAsBytes(root));
        Files.writeString(directory.resolve("neighbor.yaml"), "id: fixture_neighbor.v1\ndesc: fixture_neighbor_description\nk:\n  web: 3\n");
        String pattern = directory.toUri() + "*.yaml"; TraceStore.clear();
        var plans = new com.abandonware.ai.agent.service.plan.PlanLoader().loadAll(pattern);
        String rootId = root.path("id").asText(""); boolean admitted = !rootId.isBlank();
        assertEquals(admitted ? Set.of(rootId, "fixture_neighbor.v1") : Set.of("fixture_neighbor.v1"), plans.keySet());
        assertEquals("fixture_neighbor_description", plans.get("fixture_neighbor.v1").desc()); assertEquals(3, plans.get("fixture_neighbor.v1").k().get("web"));
        if (admitted) assertNull(TraceStore.get("agent.planLoader.suppressed"));
        else assertEquals("plan.missingId", TraceStore.get("agent.planLoader.suppressed.stage"));
        return new LegacyLoad(plans, pattern);
    }

    private static ObjectNode planSnapshot(com.abandonware.ai.agent.service.plan.RetrievalPlan plan) {
        assertNotNull(plan); var value = YAML.createObjectNode();
        value.put("id", plan.id()); value.put("desc", plan.desc()); value.set("k", YAML.valueToTree(plan.k()));
        value.set("calibration", YAML.valueToTree(plan.calibration())); value.set("rrf", YAML.valueToTree(plan.rrf()));
        value.set("guard", YAML.valueToTree(plan.guard())); value.set("override", YAML.valueToTree(plan.override())); return value;
    }

    private static ObjectNode legacySnapshot(Map<String, com.abandonware.ai.agent.service.plan.RetrievalPlan> plans) {
        var value = YAML.createObjectNode(); plans.forEach((id, plan) -> value.set(id, planSnapshot(plan))); return value;
    }

    private static ObjectNode resource(String planId) throws Exception {
        return (ObjectNode) YAML.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml")));
    }

    private static DefaultResourceLoader loader(String planId, ObjectNode root, List<String> reads) throws Exception {
        byte[] bytes = YAML.writeValueAsBytes(root);
        return new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                if (location.equals("classpath:plans/" + planId + ".yaml")) {
                    reads.add(planId); return new ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
    }

    private static com.example.lms.nova.BravePlan nova(String planId, ObjectNode root) throws Exception {
        byte[] bytes = YAML.writeValueAsBytes(root); var reads = new ArrayList<String>();
        Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(new ClassLoader(previous) {
                @Override public java.io.InputStream getResourceAsStream(String name) {
                    if (name.equals("plans/" + planId + ".yaml")) { reads.add(name); return new java.io.ByteArrayInputStream(bytes); }
                    return super.getResourceAsStream(name);
                }
            });
            var result = new com.example.lms.nova.PlanDslLoader().load(planId); assertEquals(List.of("plans/" + planId + ".yaml"), reads); return result;
        } finally { thread.setContextClassLoader(previous); }
    }
}
