package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nova.protocol.config.NovaProperties;
import com.nova.protocol.plan.PlanApplier;
import com.nova.protocol.plan.PlanLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class LastThreePlanProjectionBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper(), YAML = new ObjectMapper(new YAMLFactory());
    private static final String KG = "kg_first.v1", AP = "ap11_finance_special.v1";
    @AfterEach void clear() { TraceStore.clear(); }

    static Stream<Arguments> pairs() {
        return Stream.of("budgets.reranker_ms", "fusion.score_calibrator", "params.domain")
                .flatMap(f -> Stream.of("changed", "absent").map(v -> Arguments.of(f, v)));
    }

    @ParameterizedTest(name = "last-three-exact:{0}:{1}")
    @MethodSource("pairs")
    void lastLeavesHaveExactExclusionOrRawStorageDeltas(String field, String variant) throws Exception {
        String id = field.equals("params.domain") ? AP : KG, raw = authored(id);
        boolean absent = variant.equals("absent");
        String edited = edit(raw, field, absent);
        String[] parts = field.split("\\.");
        ObjectNode restored = tree(edited);
        ((ObjectNode) restored.path(parts[0])).set(parts[1], tree(raw).path(parts[0]).path(parts[1]));
        assertEquals(tree(raw), restored, "all unrelated semantic values and parent maps preserved");
        ObjectNode before = hints(id, raw), after = hints(id, edited), expected = before.deepCopy();
        ObjectNode pb = protocol(id, raw), pa = protocol(id, edited), pe = pb.deepCopy();
        if (field.equals("params.domain")) {
            change((ObjectNode) expected.path("plan").path("raw").path("params"), "domain", absent, "research");
            change((ObjectNode) pe.path("burst"), "domain", absent, "research");
            assertFalse(after.path("metadata").has("domain"));
            assertFalse(after.path("overrides").has("domain"));
            assertEquals(before.path("guardDomainProfile"), after.path("guardDomainProfile"));
            ObjectNode mb = protocolMap(id, raw), ma = protocolMap(id, edited), me = mb.deepCopy();
            change((ObjectNode) me.path("burst"), "domain", absent, "research");
            assertEquals(me, ma, "whole serialized protocol map changes only selected stored domain");
            assertEquals(pa.path("burst"), ma.path("burst"));
            assertNotEquals(before, after); assertNotEquals(pb, pa);
        }
        assertEquals(expected, after, "complete PlanHint, hints, metadata and guard projection");
        assertEquals(pe, pa, "complete selected protocol Plan");
        assertEquals(nova(id, raw), nova(id, edited));
        if (id.equals(KG)) {
            assertTrue(after.path("plan").path("raw").path("dslUnwiredKeys").toString().contains("fusion"));
            assertEquals(before, after); assertEquals(pb, pa);
        }
        System.out.printf("TBL07_LAST_THREE field=%s variant=%s expectedHintDelta=true expectedProtocolDelta=true novaEqual=true rawShapePreserved=true semanticConsumerClaim=false hintReads=2 protocolReads=%d novaReads=2%n",
                field, variant, field.equals("params.domain") ? 4 : 2);
    }

    @Test
    void supportedTotalBudgetChangesVectorFallbackWhileSpecificWebBudgetStaysFixed() throws Exception {
        String raw = authored(KG), edited = once(raw, "  total_ms: 3500", "  total_ms: 7000");
        ObjectNode before = hints(KG, raw), expected = before.deepCopy(), actual = hints(KG, edited);
        for (String owner : List.of("plan", "hints", "metadata")) {
            assertEquals(1200L, before.path(owner).path("webBudgetMs").longValue());
            assertEquals(3500L, before.path(owner).path("vecBudgetMs").longValue());
            ((ObjectNode) expected.path(owner)).put("vecBudgetMs", 7000L);
        }
        assertNotEquals(before, actual); assertEquals(expected, actual);
        assertEquals(protocol(KG, raw), protocol(KG, edited));
        assertEquals(nova(KG, raw), nova(KG, edited));
        System.out.println("TBL07_LAST_POSITIVE field=budgets.total_ms expectedFullDelta=true specificWebPreserved=true hintReads=2 protocolReads=2 novaReads=2");
    }

    @Test
    void supportedOfficialFlagChangesTypedGuardAndMetadataButKeepsDomainRawOnly() throws Exception {
        String raw = authored(AP), edited = once(raw, "  officialSourcesOnly: true", "  officialSourcesOnly: false");
        ObjectNode before = hints(AP, raw), expected = before.deepCopy(), actual = hints(AP, edited);
        ((ObjectNode) expected.path("plan")).put("officialSourcesOnly", false);
        ((ObjectNode) expected.path("plan").path("raw").path("params")).put("officialSourcesOnly", false);
        ((ObjectNode) expected.path("metadata")).put("officialOnly", false);
        expected.put("guardOfficialOnly", false);
        assertTrue(before.path("guardOfficialOnly").booleanValue()); assertNotEquals(before, actual); assertEquals(expected, actual);
        assertEquals("finance", actual.path("plan").path("raw").path("params").path("domain").textValue());
        assertFalse(actual.path("metadata").has("domain")); assertFalse(actual.path("overrides").has("domain"));
        ObjectNode pb = protocol(AP, raw), pe = pb.deepCopy();
        ((ObjectNode) pe.path("burst")).put("officialSourcesOnly", false);
        assertEquals(pe, protocol(AP, edited));
        assertEquals(nova(AP, raw), nova(AP, edited));
        System.out.println("TBL07_LAST_POSITIVE field=params.officialSourcesOnly expectedFullDelta=true domainRawOnly=true hintReads=2 protocolReads=2 novaReads=2");
    }

    private record Effect(PlanHints plan, JsonNode hints, Map<String, Object> metadata,
                          Map<String, Object> overrides, boolean guardOfficialOnly, String guardDomainProfile) {}
    private static ObjectNode hints(String id, String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8); AtomicInteger lookups = new AtomicInteger(), reads = new AtomicInteger();
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                assertEquals("classpath:plans/" + id + ".yaml", location); lookups.incrementAndGet();
                return new ByteArrayResource(bytes) {
                    @Override public String getFilename() { return id + ".yaml"; }
                    @Override public InputStream getInputStream() throws IOException { reads.incrementAndGet(); return super.getInputStream(); }
                };
            }
        });
        PlanHints loaded = applier.load(id); assertEquals(id, loaded.planId()); assertFalse(loaded.isEmpty());
        assertTrue(PlanHintApplier.dslUnwiredKeys(loaded).contains("llm"));
        OrchestrationHints hints = OrchestrationHints.defaults(); Map<String, Object> meta = new LinkedHashMap<>();
        GuardContext guard = new GuardContext();
        applier.applyToHintsAndMeta(loaded, hints, meta); applier.applyToGuardContext(loaded, guard);
        assertEquals(1, lookups.get()); assertEquals(1, reads.get());
        return JSON.valueToTree(new Effect(loaded, JSON.valueToTree(hints), meta,
                new LinkedHashMap<>(guard.getPlanOverrides()), guard.isOfficialOnly(), guard.getDomainProfile()));
    }
    private static ObjectNode protocol(String id, String raw) {
        return exact(id, raw, () -> {
            NovaProperties props = new NovaProperties(); props.setDefaultPlanId("forbidden-fallback");
            var plan = new PlanApplier(new PlanLoader(), props).resolvePlan(id, false);
            assertNotNull(plan); assertEquals(id, plan.getId()); return JSON.valueToTree(plan);
        });
    }
    private static ObjectNode protocolMap(String id, String raw) {
        return exact(id, raw, () -> JSON.valueToTree(new PlanLoader().get(id)));
    }
    private static ObjectNode nova(String id, String raw) {
        return exact(id, raw, () -> {
            var plan = new com.example.lms.nova.PlanDslLoader().load(id);
            assertNotNull(plan); assertTrue(plan.enabled); return JSON.valueToTree(plan);
        });
    }
    private static <T> T exact(String id, String raw, Supplier<T> body) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8); AtomicInteger reads = new AtomicInteger();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = new ClassLoader(previous) {
            @Override public InputStream getResourceAsStream(String name) {
                if (!name.startsWith("plans/")) return super.getResourceAsStream(name);
                assertEquals("plans/" + id + ".yaml", name); reads.incrementAndGet(); return new ByteArrayInputStream(bytes);
            }
        };
        try { Thread.currentThread().setContextClassLoader(loader); T result = body.get(); assertEquals(1, reads.get()); return result; }
        finally { Thread.currentThread().setContextClassLoader(previous); }
    }
    private static String edit(String raw, String field, boolean absent) {
        String nl = raw.contains("\r\n") ? "\r\n" : "\n";
        return switch (field) {
            case "budgets.reranker_ms" -> once(raw, "  reranker_ms: 1000" + nl, absent ? "" : "  reranker_ms: 2000" + nl);
            case "fusion.score_calibrator" -> once(raw, "fusion:" + nl + "  score_calibrator: isotonic@v1",
                    absent ? "fusion: {}" : "fusion:" + nl + "  score_calibrator: linear@v2");
            case "params.domain" -> once(raw, "  domain: finance" + nl, absent ? "" : "  domain: research" + nl);
            default -> throw new AssertionError(field);
        };
    }
    private static void change(ObjectNode node, String key, boolean absent, String value) {
        if (absent) node.remove(key); else node.put(key, value);
    }
    private static String once(String raw, String old, String replacement) {
        assertTrue(raw.contains(old)); assertEquals(raw.indexOf(old), raw.lastIndexOf(old)); return raw.replace(old, replacement);
    }
    private static String authored(String id) throws Exception { return Files.readString(Path.of("main/resources/plans/" + id + ".yaml"), StandardCharsets.UTF_8); }
    private static ObjectNode tree(String raw) throws Exception { return (ObjectNode) YAML.readTree(raw); }
}

