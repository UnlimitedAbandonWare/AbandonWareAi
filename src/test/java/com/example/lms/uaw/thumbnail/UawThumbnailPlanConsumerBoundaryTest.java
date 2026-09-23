package com.example.lms.uaw.thumbnail;

import com.example.lms.llm.ChatModel;
import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UawThumbnailPlanConsumerBoundaryTest {
    private static final String RESOURCE = "plans/UAW_thumbnail.v1.yaml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> UNUSED = Map.of(
            "anchors.model", "fixture-model", "anchors.max_tokens", 777, "anchors.temperature", 0.7,
            "render.mode", "fixture-mode", "render.model", "fixture-render",
            "persist.domain", "PLAN_DOMAIN", "persist.entity_type", "PLAN_ENTITY", "kind", "fixture-kind");
    private static final Map<String, Object> USED = Map.of(
            "anchors.count", 5, "evidence.web_topk_per_anchor", 7,
            "evidence.evidence_topk_per_anchor", 1, "evidence.final_k", 9,
            "evidence.require_unique_domains", false, "render.max_tokens", 720,
            "render.temperature", 0.7, "persist.min_confidence", 0.95);

    static Stream<Arguments> unused() { return pairs(UNUSED); }
    static Stream<Arguments> used() { return pairs(USED); }
    private static Stream<Arguments> pairs(Map<String, Object> values) {
        return values.keySet().stream().sorted().flatMap(k ->
                Stream.of("changed", "absent").map(v -> Arguments.of(k, v)));
    }

    @ParameterizedTest(name = "uaw-unused:{0}:{1}")
    @MethodSource("unused")
    void parsedFieldsWithoutServiceReadersPreserveAllObservedEffects(String field, String variant) throws Exception {
        ObjectNode base = authored();
        ObjectNode changed = change(base, field, variant.equals("absent") ? null : UNUSED.get(field));
        Snapshot before = run(base, 0.9, 0.8, false);
        Snapshot after = run(changed, 0.9, 0.8, false);
        assertParsedChange(base, changed, before, after, field);
        assertEquals(before.effect(), after.effect());
        assertTrue(after.effect().result().isPresent());
        assertEquals("PROP_DOMAIN", after.effect().events().get(0).knowledgeDomain());
        assertEquals("PROP_ENTITY", after.effect().events().get(0).entityType());
        assertEquals(0.0, after.effect().model().get(0).temperature());
        assertEquals(256, after.effect().model().get(0).tokens());
        System.out.printf("TBL07_UAW_UNUSED field=%s variant=%s typedDelta=true effectEqual=true loaderReads=2%n",
                field, variant);
    }

    @ParameterizedTest(name = "uaw-used:{0}:{1}")
    @MethodSource("used")
    void typedFieldsReachActualLocalServiceCollaborators(String field, String variant) throws Exception {
        ObjectNode base = authored();
        ObjectNode changed = change(base, field, variant.equals("absent") ? null : USED.get(field));
        Snapshot before = run(base, 0.9, 0.8, false);
        Snapshot after = run(changed, 0.9, 0.8, false);
        assertParsedChange(base, changed, before, after, field);
        assertAuthored(before.effect());
        boolean absent = variant.equals("absent");
        Effect actual = after.effect();
        switch (field) {
            case "anchors.count" -> assertEquals(absent ? 12 : 5, actual.search().size());
            case "evidence.web_topk_per_anchor" ->
                    assertTrue(actual.search().stream().allMatch(c -> c.topK() == (absent ? 4 : 7)));
            case "evidence.evidence_topk_per_anchor" ->
                    assertEquals(absent ? List.of("Anchor0/0", "Anchor0/1", "Anchor1/1", "Anchor2/1", "Anchor3/1", "Anchor4/1")
                            : List.of("Anchor0/0", "Anchor1/0", "Anchor2/0", "Anchor3/0", "Anchor4/0", "Anchor5/0"), paths(actual));
            case "evidence.final_k" -> assertEquals(absent ? 6 : 9, paths(actual).size());
            case "evidence.require_unique_domains" ->
                    assertEquals(absent ? List.of("Anchor0/0", "Anchor0/1", "Anchor1/1", "Anchor2/1", "Anchor3/1", "Anchor4/1")
                            : List.of("Anchor0/0", "Anchor0/1", "Anchor1/0", "Anchor1/1", "Anchor2/0", "Anchor2/1"), paths(actual));
            case "render.max_tokens" -> assertEquals(absent ? 480 : 720, actual.model().get(1).tokens());
            case "render.temperature" -> assertEquals(absent ? 0.2 : 0.7, actual.model().get(1).temperature());
            case "persist.min_confidence" -> assertTrue(actual.result().isEmpty());
            default -> fail(field);
        }
        if (!absent || field.equals("persist.min_confidence")) assertNotEquals(before.effect(), actual);
        else assertEquals(before.effect(), actual);
        System.out.printf("TBL07_UAW_USED field=%s variant=%s typedDelta=true expectedConsumer=true loaderReads=2%n",
                field, variant);
    }

    static Stream<Arguments> bounds() {
        return Stream.of(
                Arguments.of("anchors.count", 0, 3), Arguments.of("anchors.count", 100, 20),
                Arguments.of("evidence.web_topk_per_anchor", 0, 1), Arguments.of("evidence.web_topk_per_anchor", 100, 10),
                Arguments.of("evidence.evidence_topk_per_anchor", 0, 1), Arguments.of("evidence.evidence_topk_per_anchor", 100, 6),
                Arguments.of("evidence.final_k", 0, 2), Arguments.of("evidence.final_k", 100, 12));
    }

    @ParameterizedTest(name = "uaw-bound:{0}:{1}")
    @MethodSource("bounds")
    void serviceBoundsAreVisibleAfterRealLoading(String field, int input, int expected) throws Exception {
        Snapshot snapshot = run(change(authored(), field, input), 0.9, 0.8, false);
        assertEquals(input, at(JSON.valueToTree(snapshot.parsed()), field).intValue());
        Effect effect = snapshot.effect();
        switch (field) {
            case "anchors.count" -> assertEquals(expected, effect.search().size());
            case "evidence.web_topk_per_anchor" -> assertTrue(effect.search().stream().allMatch(c -> c.topK() == expected));
            case "evidence.final_k" -> assertEquals(expected, paths(effect).size());
            case "evidence.evidence_topk_per_anchor" ->
                    assertEquals(expected == 1 ? List.of("Anchor0/0", "Anchor1/0", "Anchor2/0", "Anchor3/0", "Anchor4/0", "Anchor5/0")
                            : List.of("Anchor0/0", "Anchor0/1", "Anchor0/2", "Anchor0/3", "Anchor0/4", "Anchor0/5"), paths(effect));
            default -> fail(field);
        }
        System.out.printf("TBL07_UAW_BOUND field=%s input=%d observed=%d loaderReads=1%n", field, input, expected);
    }

    static Stream<Arguments> renderNumbers() {
        return Stream.of(Arguments.of("render.max_tokens", -7), Arguments.of("render.max_tokens", 2000),
                Arguments.of("render.temperature", -1.0), Arguments.of("render.temperature", 4.0));
    }

    @ParameterizedTest(name = "uaw-render-number:{0}:{1}")
    @MethodSource("renderNumbers")
    void renderNumbersArePassedWithoutServiceClamping(String field, Number input) throws Exception {
        Snapshot snapshot = run(change(authored(), field, input), 0.9, 0.8, false);
        assertEquals(input.doubleValue(), at(JSON.valueToTree(snapshot.parsed()), field).doubleValue());
        ModelCall render = snapshot.effect().model().get(1);
        assertEquals(input.doubleValue(), field.endsWith("max_tokens") ? render.tokens() : render.temperature());
        System.out.printf("TBL07_UAW_RENDER_NUMBER field=%s input=%s unclamped=true loaderReads=1%n", field, input);
    }

    static Stream<Arguments> confidence() {
        return Stream.of(Arguments.of(0.55, 0.54, false), Arguments.of(0.55, 0.55, true),
                Arguments.of(-0.1, -4.0, true), Arguments.of(1.1, 4.0, false),
                Arguments.of(1.0, 4.0, true), Arguments.of(null, 0.89, false), Arguments.of(null, 0.9, true));
    }

    @ParameterizedTest(name = "uaw-confidence:{0}:{1}")
    @MethodSource("confidence")
    void planThresholdAndPropertyFallbackCompareAgainstClampedModelConfidence(
            Double minimum, double generated, boolean accepted) throws Exception {
        Snapshot snapshot = run(change(authored(), "persist.min_confidence", minimum), 0.9, generated, false);
        assertEquals(minimum, snapshot.parsed().persist().minConfidence());
        assertEquals(accepted, snapshot.effect().result().isPresent());
        if (accepted) assertEquals(Math.max(0, Math.min(1, generated)), snapshot.effect().result().orElseThrow().confidenceScore());
        System.out.printf("TBL07_UAW_CONFIDENCE minimum=%s generated=%s accepted=%s loaderReads=1%n",
                minimum, generated, accepted);
    }

    @Test
    void uniqueDomainPreferenceStillFillsFromRepeatedDomains() throws Exception {
        Effect effect = run(authored(), 0.9, 0.8, true).effect();
        assertEquals(6, paths(effect).size());
        assertEquals(1, effect.result().orElseThrow().evidence().stream().map(UawThumbnailService.EvidenceItem::domain).distinct().count());
        System.out.println("TBL07_UAW_DIVERSITY uniquePreference=true selected=6 domains=1 fallbackFill=true loaderReads=1");
    }

    private static void assertAuthored(Effect effect) {
        assertTrue(effect.result().isPresent());
        assertEquals(12, effect.search().size());
        assertTrue(effect.search().stream().allMatch(c -> c.topK() == 4));
        assertEquals(480, effect.model().get(1).tokens());
        assertEquals(0.2, effect.model().get(1).temperature());
        assertEquals(List.of("Anchor0/0", "Anchor0/1", "Anchor1/1", "Anchor2/1", "Anchor3/1", "Anchor4/1"), paths(effect));
    }

    private static List<String> paths(Effect effect) {
        return effect.result().orElseThrow().evidence().stream()
                .map(e -> java.net.URI.create(e.url()).getPath().substring(1)).toList();
    }

    private static ObjectNode authored() throws Exception {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            ObjectNode plan = (ObjectNode) YAML.readTree(in);
            assertEquals("UAW_thumbnail.v1", plan.path("id").asText());
            assertEquals("uaw_thumbnail", plan.path("kind").asText());
            return plan;
        }
    }

    private static ObjectNode change(ObjectNode original, String field, Object value) {
        ObjectNode changed = original.deepCopy();
        String[] parts = field.split("\\.");
        ObjectNode parent = changed;
        for (int i = 0; i < parts.length - 1; i++) parent = (ObjectNode) parent.path(parts[i]);
        if (value == null) parent.remove(parts[parts.length - 1]);
        else parent.set(parts[parts.length - 1], JSON.valueToTree(value));
        return changed;
    }

    private static JsonNode at(JsonNode value, String field) {
        for (String part : field.split("\\.")) value = value.path(part);
        return value;
    }

    private static void assertParsedChange(ObjectNode base, ObjectNode changed, Snapshot before, Snapshot after, String field) {
        assertNotEquals(base, changed);
        ObjectNode restored = change(changed, field, at(base, field));
        assertEquals(base, restored);
        JsonNode parsedBefore = at(JSON.valueToTree(before.parsed()), field);
        JsonNode parsedAfter = at(JSON.valueToTree(after.parsed()), field);
        assertEquals(at(base, field), parsedBefore);
        if (at(changed, field).isMissingNode()) assertTrue(parsedAfter.isNull());
        else assertEquals(at(changed, field), parsedAfter);
        assertNotEquals(parsedBefore, parsedAfter);
    }

    private record ModelCall(String prompt, double temperature, int tokens) {}
    private record SearchCall(String anchor, int topK) {}
    private record Effect(Optional<UawThumbnailService.ThumbnailResult> result, List<ModelCall> model,
                          List<SearchCall> search, List<List<Object>> prompts, List<List<Object>> finds,
                          List<List<Object>> writes, List<UawThumbnailPersistedEvent> events) {}
    private record Snapshot(UawThumbnailPlanSpec parsed, Effect effect) {}

    private static Snapshot run(ObjectNode plan, double propertyMinimum, double confidence, boolean sharedDomains) throws Exception {
        byte[] bytes = YAML.writeValueAsBytes(plan);
        AtomicInteger reads = new AtomicInteger(), lookups = new AtomicInteger(), loads = new AtomicInteger();
        AtomicReference<UawThumbnailPlanSpec> parsed = new AtomicReference<>();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader exact = new ClassLoader(previous) {
            @Override public URL getResource(String name) {
                if (!RESOURCE.equals(name)) return super.getResource(name);
                lookups.incrementAndGet();
                try {
                    return new URL(null, "fixture:/plans/UAW_thumbnail.v1.yaml", new URLStreamHandler() {
                        @Override protected URLConnection openConnection(URL url) {
                            return new URLConnection(url) {
                                @Override public void connect() {}
                                @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                            };
                        }
                    });
                } catch (Exception e) { throw new AssertionError(e); }
            }
            @Override public InputStream getResourceAsStream(String name) {
                if (!RESOURCE.equals(name)) return super.getResourceAsStream(name);
                reads.incrementAndGet();
                return new ByteArrayInputStream(bytes);
            }
        };
        UawThumbnailPlanLoader loader = new UawThumbnailPlanLoader() {
            @Override public UawThumbnailPlanSpec loadOrDefault(String id, UawThumbnailProperties props) {
                loads.incrementAndGet();
                UawThumbnailPlanSpec result = super.loadOrDefault(id, props);
                parsed.set(result);
                return result;
            }
        };
        UawThumbnailProperties props = new UawThumbnailProperties();
        props.setEnabled(true); props.setKnowledgeDomain("PROP_DOMAIN");
        props.setEntityType("PROP_ENTITY"); props.setMinConfidence(propertyMinimum);
        List<ModelCall> modelCalls = new ArrayList<>();
        List<SearchCall> searchCalls = new ArrayList<>();
        List<List<Object>> prompts = new ArrayList<>(), finds = new ArrayList<>(), writes = new ArrayList<>();
        List<UawThumbnailPersistedEvent> events = new ArrayList<>();
        QueryKeywordPromptBuilder builder = mock(QueryKeywordPromptBuilder.class);
        when(builder.buildKeywordVariantsPrompt(anyString(), anyString(), anyInt())).thenAnswer(i -> {
            prompts.add(Arrays.asList(i.getArguments().clone()));
            return "anchor-count:" + i.getArgument(2);
        });
        when(builder.buildUawThumbnailRenderPrompt(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(i -> {
                    prompts.add(Arrays.asList(i.getArguments().clone()));
                    return JSON.writeValueAsString(i.getArguments());
                });
        ChatModel model = mock(ChatModel.class);
        when(model.generate(anyString(), anyDouble(), anyInt())).thenAnswer(i -> {
            modelCalls.add(new ModelCall(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
            return modelCalls.size() == 1
                    ? String.join("\n", IntStream.range(0, 25).mapToObj(n -> "Anchor" + n).toList())
                    : "{\"attributes\":{\"caption\":\"Grounded fixture caption\"},\"confidenceScore\":" + confidence + "}";
        });
        WebSearchProvider web = mock(WebSearchProvider.class);
        when(web.search(anyString(), anyInt())).thenAnswer(i -> {
            String anchor = i.getArgument(0);
            searchCalls.add(new SearchCall(anchor, i.getArgument(1)));
            // Deliberately ample mock results: service per-anchor selection must not be masked by provider truncation.
            return IntStream.range(0, 14).mapToObj(n -> "Fixture title\nGrounded quote\nhttps://"
                    + (sharedDomains || n == 0 ? "shared" : anchor.toLowerCase() + "s" + n)
                    + ".example.test/" + anchor + "/" + n).toList();
        });
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.find(anyString(), anyString())).thenAnswer(i -> {
            finds.add(Arrays.asList(i.getArguments().clone())); return Optional.empty();
        });
        doAnswer(i -> { writes.add(Arrays.asList(i.getArguments().clone())); return null; })
                .when(knowledge).integrateVerifiedKnowledge(anyString(), anyString(), anyString(), anyList(), anyDouble());
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        doAnswer(i -> { events.add(assertInstanceOf(UawThumbnailPersistedEvent.class, i.getArgument(0))); return null; })
                .when(publisher).publishEvent(any(Object.class));
        try {
            Thread.currentThread().setContextClassLoader(exact);
            Optional<UawThumbnailService.ThumbnailResult> result =
                    new UawThumbnailService(props, loader, web, builder, model, knowledge, publisher)
                            .generateAndPersist("thumbnail fixture topic");
            assertEquals(1, loads.get()); assertEquals(1, reads.get()); assertTrue(lookups.get() >= 1);
            assertNotNull(parsed.get()); assertEquals("UAW_thumbnail.v1", parsed.get().id());
            assertEquals(2, modelCalls.size()); assertEquals(2, prompts.size()); assertEquals(1, finds.size());
            assertEquals(prompts.get(0).get(2), searchCalls.size());
            assertEquals(result.isPresent() ? 1 : 0, writes.size()); assertEquals(writes.size(), events.size());
            return new Snapshot(parsed.get(), new Effect(result, modelCalls, searchCalls, prompts, finds, writes, events));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
