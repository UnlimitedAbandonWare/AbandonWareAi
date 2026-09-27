package com.example.lms.service;

import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.prompt.PromptAssetService;
import com.example.lms.service.rag.ProjectionMergeService;
import com.example.lms.service.rag.plan.PlanDslLoader;
import com.example.lms.service.rag.plan.PlanModelResolver;
import com.example.lms.service.rag.plan.PlanPolicyMapper;
import com.example.lms.service.routing.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWorkflowProjectionFinalFieldsTest {
    private static final String PLAN = "projection_agent.v1";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, String> ASSETS = Map.of(
            "projection.final", "TBL_FINAL_AUTHORED", "fixture.final", "TBL_FINAL_CHANGED",
            "fixture.caller", "TBL_FINAL_CALLER", "fixture.empty", " ");
    private static final Set<String> MARKERS = Set.of("TBL_FINAL_AUTHORED", "TBL_FINAL_CHANGED",
            "TBL_FINAL_CALLER", "fixture.missing.final", "TBL_FINAL_LITERAL", "../fixture.final", "fixture.empty");
    private record Route(String stage, String model, int cap) { }
    private record Build(String stage, String model, Integer cap) { }
    private record Prompt(Set<String> system, Set<String> user) { }

    static Stream<Arguments> controls() {
        List<Arguments> rows = new ArrayList<>();
        for (String value : List.of("authored", "changed", "blank", "absent", "bound", "unbound",
                "unknown_tier", "light", "gemma", "vision"))
            rows.add(Arguments.of("model", value, "none", "open", true));
        for (String value : List.of("light", "gemma", "vision"))
            rows.add(Arguments.of("model", value, "none", "open", false));
        for (String value : List.of("authored", "changed", "absent", "zero", "negative", "numeric_string", "invalid"))
            rows.add(Arguments.of("max-tokens", value, "none", "open", true));
        for (String key : List.of("llm.answer.max_tokens", "llm.answer.maxTokens"))
            for (String value : List.of("changed", "zero", "absent"))
                rows.add(Arguments.of("max-tokens", value, key, "open", true));
        for (String value : List.of("changed", "zero"))
            rows.add(Arguments.of("max-tokens", value, "snake_zero_camel_positive", "open", true));
        for (String value : List.of("authored", "changed", "blank", "absent", "missing_asset", "literal", "unsafe_id", "empty_asset"))
            rows.add(Arguments.of("system-prompt", value, "none", "open", true));
        for (String field : List.of("model", "max-tokens", "system-prompt"))
            for (String gate : List.of("fact", "no_merge"))
                rows.add(Arguments.of(field, "changed", "none", gate, true));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}/{1}/override={2}/gate={3}/configured={4}")
    @MethodSource("controls")
    void finalFieldsReachDistinctRouterFactoryAndPromptBoundaries(
            String field, String value, String overrideKey, String gate, boolean configured) throws Exception {
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        clearState();
        try {
            ObjectNode authored = (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + PLAN + ".yaml")));
            ObjectNode input = authored.deepCopy();
            ObjectNode selected = finalStep(input);
            assertTrue(selected.has(field));
            mutate(selected, field, value);
            ObjectNode restored = input.deepCopy();
            finalStep(restored).set(field, finalStep(authored).get(field));
            assertEquals(authored, restored, "the complete resource must differ only at the selected field");
            if (!"authored".equals(value)) assertNotEquals(authored, input);
            byte[] bytes = YAML.writeValueAsBytes(input);
            var resources = new DefaultResourceLoader() {
                @Override public Resource getResource(String location) {
                    if (location.equals("classpath:plans/" + PLAN + ".yaml"))
                        return new ByteArrayResource(bytes) {
                            @Override public String getFilename() { return PLAN + ".yaml"; }
                        };
                    for (var asset : ASSETS.entrySet())
                        if (location.equals("classpath:prompts/system/" + asset.getKey() + ".md"))
                            return new ByteArrayResource(asset.getValue().getBytes(StandardCharsets.UTF_8));
                    return new ByteArrayResource(new byte[0]) {
                        @Override public boolean exists() { return false; }
                    };
                }
            };
            var loader = new PlanDslLoader(resources);
            var spec = loader.loadProjectionAgent(PLAN).orElseThrow();
            int parsedCap = cap(field, value);
            assertEquals(parsedCap, spec.finalAnswer().maxTokens());
            String parsedSystem = "system-prompt".equals(field) ? switch (value) {
                case "changed" -> "fixture.final";
                case "blank", "absent" -> null;
                case "missing_asset" -> "fixture.missing.final";
                case "literal" -> "TBL_FINAL_LITERAL instruction";
                case "unsafe_id" -> "../fixture.final";
                case "empty_asset" -> "fixture.empty";
                default -> "projection.final";
            } : "projection.final";
            assertEquals(parsedSystem, spec.finalAnswer().systemPrompt());
            MockEnvironment env = new MockEnvironment().withProperty("fixture.bound-model", "fixture.bound-model");
            if (configured) env.withProperty("llm.fast.model", "fixture.fast-model")
                    .withProperty("llm.chat-model", "fixture.chat-model").withProperty("llm.vision.model", "fixture.vision-model");
            var resolver = spy(new PlanModelResolver(env));
            var assets = spy(new PromptAssetService(resources));
            ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(ChatWorkflowProjectionDefaultProfileTest.class, "workflowFixture");
            ReflectionTestUtils.setField(workflow, "planDslLoader", loader);
            ReflectionTestUtils.setField(workflow, "planHintApplier", new PlanHintApplier(resources));
            ReflectionTestUtils.setField(workflow, "planPolicyMapper", new PlanPolicyMapper());
            ReflectionTestUtils.setField(workflow, "planModelResolver", resolver);
            ReflectionTestUtils.setField(workflow, "promptAssetService", assets);
            ReflectionTestUtils.setField(workflow, "env", env);
            ReflectionTestUtils.setField(workflow, "conversationHarmonyMode", "off");
            var preprocessor = (com.example.lms.service.rag.pre.QueryContextPreprocessor) ReflectionTestUtils.getField(workflow, "qcPreprocessor");
            when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
            GuardContext context = GuardContext.defaultContext();
            context.setPlanId(PLAN);
            if ("snake_zero_camel_positive".equals(overrideKey)) {
                context.putPlanOverride("llm.answer.max_tokens", 0);
                context.putPlanOverride("llm.answer.maxTokens", 640);
            } else if (!"none".equals(overrideKey)) context.putPlanOverride(overrideKey, 640);
            GuardContextHolder.set(context);
            ProjectionMergeService merge = spy(new ProjectionMergeService());
            if (!"no_merge".equals(gate)) ReflectionTestUtils.setField(workflow, "projectionMergeService", merge);
            AtomicReference<String> stage = new AtomicReference<>("PRIMARY");
            List<Route> routes = new CopyOnWriteArrayList<>();
            List<Build> builds = new CopyOnWriteArrayList<>();
            List<Prompt> finalPrompts = new CopyOnWriteArrayList<>();
            List<Set<String>> finalContexts = new CopyOnWriteArrayList<>();
            List<String> finalAssetInputs = new CopyOnWriteArrayList<>();
            doAnswer(call -> {
                if ("FINAL_ANSWER".equals(stage.get())) finalAssetInputs.add(call.getArgument(0));
                return call.callRealMethod();
            }).when(assets).resolveTrustedSystemPromptText(nullable(String.class));
            StandardPromptBuilder builder = spy(new StandardPromptBuilder());
            doAnswer(call -> {
                PromptContext ctx = call.getArgument(0);
                if ("FINAL_ANSWER".equals(stage.get())) {
                    finalContexts.add(markers(ctx.systemInstruction()));
                    assertTrue(ctx.userQuery().contains("[MERGED ANSWER]"));
                    assertEquals(Set.of(), markers(ctx.userQuery()));
                    assertNull(ctx.guardProfile());
                    assertEquals(MemoryMode.HYBRID, ctx.memoryMode());
                }
                return call.callRealMethod();
            }).when(builder).build(any(PromptContext.class));
            ReflectionTestUtils.setField(workflow, "promptBuilder", builder);
            AtomicInteger modelCalls = new AtomicInteger();
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenAnswer(call -> {
                modelCalls.incrementAndGet();
                if ("FINAL_ANSWER".equals(stage.get())) {
                    List<ChatMessage> messages = call.getArgument(0);
                    String system = messages.stream().filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                            .map(SystemMessage::text).reduce("", (a, b) -> a + b);
                    String user = messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                            .flatMap(message -> message.contents().stream()).filter(TextContent.class::isInstance)
                            .map(TextContent.class::cast).map(TextContent::text).reduce("", (a, b) -> a + b);
                    assertTrue(user.contains("[MERGED ANSWER]"));
                    finalPrompts.add(new Prompt(markers(system), markers(user)));
                }
                String answer = "FREE_IDEA".equals(stage.get()) ? "fixture creative violet sail"
                        : "FINAL_ANSWER".equals(stage.get()) ? "fixture final sentinel" : "fixture grounded amber canoe";
                return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
            });
            ModelRouter router = (ModelRouter) ReflectionTestUtils.getField(workflow, "modelRouter");
            when(router.route(anyString(), nullable(String.class), anyString(), anyInt(), anyString())).thenAnswer(call -> {
                stage.set(call.getArgument(0));
                routes.add(new Route(stage.get(), call.getArgument(4), call.getArgument(3)));
                return model;
            });
            when(router.resolveModelName(model)).thenReturn("fixture.route-default");
            DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
            when(factory.canServeQuietly(anyString())).thenReturn(true);
            when(factory.lcWithTimeout(anyString(), nullable(Double.class), nullable(Double.class),
                    nullable(Double.class), nullable(Double.class), nullable(Integer.class), anyInt())).thenAnswer(call -> {
                        builds.add(new Build(stage.get(), call.getArgument(0), call.getArgument(5)));
                        assertEquals(InteractionEvidencePolicy.FeatureMode.OFF, context.getInteractionPolicyDecision().featureMode());
                        assertFalse(context.getInteractionPolicyDecision().defensive());
                        return model;
                    });
            ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
            ChatRequestDto request = ChatRequestDto.builder().message("aurora lattice").model("fixture.caller-model")
                    .systemPrompt("fixture.caller").maxTokens(256).mode("fact".equals(gate) ? "FACT" : null)
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            workflow.continueChat(request, ignored -> List.of());
            boolean finalReached = "open".equals(gate);
            int expectedCalls = finalReached ? 3 : "fact".equals(gate) ? 1 : 2;
            assertEquals(expectedCalls, modelCalls.get());
            assertEquals(expectedCalls, builds.size());
            assertEquals(expectedCalls, routes.size());
            String strict = configured ? "fixture.fast-model" : "llmrouter.light";
            String free = configured ? "fixture.chat-model" : "llmrouter.gemma";
            boolean positiveOverride = !"none".equals(overrideKey) && !"snake_zero_camel_positive".equals(overrideKey);
            assertEquals(strict, builds.get(0).model());
            assertEquals(positiveOverride ? 640 : 1024, builds.get(0).cap());
            if (!"fact".equals(gate)) assertEquals(new Build("FREE_IDEA", free, 1024), builds.get(1));
            if (finalReached) {
                Route finalRoute = routes.get(2);
                assertEquals("FINAL_ANSWER", finalRoute.stage());
                String resolved = resolvedFinal(field, value, configured);
                assertEquals(resolved == null ? "" : resolved, finalRoute.model());
                if (parsedCap > 0) assertEquals(parsedCap, finalRoute.cap());
                else assertTrue(finalRoute.cap() >= 768, "nonpositive cap uses a router budget, not the factory cap");
                int finalCap = positiveOverride ? 640 : parsedCap > 0 ? parsedCap : 1024;
                assertEquals(new Build("FINAL_ANSWER", resolved == null ? strict : resolved, finalCap), builds.get(2));
                Set<String> expectedMarkers = finalMarkers(field, value);
                assertEquals(List.of(expectedMarkers), finalContexts);
                assertEquals(List.of(new Prompt(Set.of(), expectedMarkers)), finalPrompts);
                assertEquals(1, finalAssetInputs.size());
                assertEquals(parsedSystem, finalAssetInputs.get(0));
            } else {
                assertEquals(List.of(), finalContexts);
                assertEquals(List.of(), finalPrompts);
                assertEquals(List.of(), finalAssetInputs);
                if ("no_merge".equals(gate)) assertEquals("no_merge_service", TraceStore.get("prompt.projection.creative.skipped"));
            }
            verify(merge, times(finalReached ? 1 : 0)).mergeDualView(anyString(), anyString());
            assertSame(context, GuardContextHolder.get());
            for (String name : List.of("memoryHandler", "learningWriteInterceptor", "memoryWriteInterceptor"))
                assertTrue(mockingDetails(ReflectionTestUtils.getField(workflow, name)).isMock());
            System.out.printf("TBL07_PROJECTION_FINAL field=%s value=%s override=%s gate=%s configured=%s parsedCap=%d finalCalls=%d routerCap=%s factoryCap=%s contextMarkers=%d userMarkers=%d modelCalls=%d factoryCalls=%d%n",
                    field, value, overrideKey, gate, configured, parsedCap, finalPrompts.size(),
                    finalReached ? routes.get(2).cap() : "not_called", finalReached ? builds.get(2).cap() : "not_called",
                    finalContexts.isEmpty() ? 0 : finalContexts.get(0).size(),
                    finalPrompts.isEmpty() ? 0 : finalPrompts.get(0).user().size(), modelCalls.get(), builds.size());
        } finally {
            clearState();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static ObjectNode finalStep(ObjectNode root) {
        for (var step : root.path("pipeline")) if ("final_answer".equals(step.path("id").asText())) return (ObjectNode) step;
        throw new AssertionError("authored final step required");
    }

    private static void mutate(ObjectNode selected, String field, String value) {
        if ("absent".equals(value)) { selected.remove(field); return; }
        if ("blank".equals(value)) { selected.put(field, " "); return; }
        if ("model".equals(field)) {
            switch (value) {
                case "changed" -> selected.put(field, "fixture.changed-model");
                case "bound" -> selected.put(field, "${fixture.bound-model}");
                case "unbound" -> selected.put(field, "${fixture.unbound-model}");
                case "unknown_tier" -> selected.put(field, "llmrouter.unknown");
                case "light", "gemma", "vision" -> selected.put(field, "llmrouter." + value);
                default -> { }
            }
        } else if ("max-tokens".equals(field)) {
            switch (value) {
                case "changed" -> selected.put(field, 1536);
                case "zero" -> selected.put(field, 0);
                case "negative" -> selected.put(field, -1);
                case "numeric_string" -> selected.put(field, "1792");
                case "invalid" -> selected.put(field, "fixture-invalid-number");
                default -> { }
            }
        } else {
            switch (value) {
                case "changed" -> selected.put(field, "fixture.final");
                case "missing_asset" -> selected.put(field, "fixture.missing.final");
                case "literal" -> selected.put(field, "TBL_FINAL_LITERAL instruction");
                case "unsafe_id" -> selected.put(field, "../fixture.final");
                case "empty_asset" -> selected.put(field, "fixture.empty");
                default -> { }
            }
        }
    }

    private static int cap(String field, String value) {
        if (!"max-tokens".equals(field)) return 2048;
        return switch (value) {
            case "changed" -> 1536;
            case "absent", "invalid" -> 1200;
            case "zero" -> 0;
            case "negative" -> -1;
            case "numeric_string" -> 1792;
            default -> 2048;
        };
    }

    private static String resolvedFinal(String field, String value, boolean configured) {
        if (!"model".equals(field)) return null;
        return switch (value) {
            case "changed" -> "fixture.changed-model";
            case "bound" -> "fixture.bound-model";
            case "unbound" -> "${fixture.unbound-model}";
            case "unknown_tier" -> "llmrouter.unknown";
            case "light" -> configured ? "fixture.fast-model" : "llmrouter.light";
            case "gemma" -> configured ? "fixture.chat-model" : "llmrouter.gemma";
            case "vision" -> configured ? "fixture.vision-model" : "llmrouter.vision";
            default -> null;
        };
    }

    private static Set<String> finalMarkers(String field, String value) {
        if (!"system-prompt".equals(field)) return Set.of("TBL_FINAL_AUTHORED");
        return switch (value) {
            case "changed" -> Set.of("TBL_FINAL_CHANGED");
            case "blank", "absent" -> Set.of();
            case "missing_asset" -> Set.of("fixture.missing.final");
            case "literal" -> Set.of("TBL_FINAL_LITERAL");
            case "unsafe_id" -> Set.of("../fixture.final");
            case "empty_asset" -> Set.of("fixture.empty");
            default -> Set.of("TBL_FINAL_AUTHORED");
        };
    }

    private static Set<String> markers(String text) {
        return text == null ? Set.of() : MARKERS.stream().filter(text::contains).collect(Collectors.toSet());
    }

    private static void clearState() {
        ReflectionTestUtils.invokeMethod(ChatWorkflowProjectionDefaultProfileTest.class, "clearState");
    }
}
