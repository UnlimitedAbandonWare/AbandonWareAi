package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.prompt.PromptAssetService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWorkflowProjectionModelTraitsTest {
    private static final String PLAN = "projection_agent.v1";
    private static final String CALLER = "fixture.caller-model";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, String> TRAITS = Map.of(
            "stuff11_ko", "TBL_TRAIT_STRICT", "stuff2_ko", "TBL_TRAIT_FREE",
            "fixture.changed", "TBL_TRAIT_CHANGED", "fixture.caller", "TBL_TRAIT_CALLER");
    private record Route(boolean free, String requested) { }
    private record Build(boolean free, String model) { }
    private record Prompt(boolean free, Set<String> systemMarkers, Set<String> userMarkers) { }

    static Stream<Arguments> controls() {
        List<Arguments> rows = new ArrayList<>();
        for (String branch : List.of("strict", "free")) {
            for (String value : List.of("authored", "changed", "blank", "auto", "absent",
                    "bound", "unbound", "unknown_tier"))
                rows.add(Arguments.of("model", branch, value, "open", true));
            rows.add(Arguments.of("model", branch, "authored", "open", false));
            for (String value : List.of("authored", "changed", "empty", "absent", "missing_asset"))
                rows.add(Arguments.of("traits", branch, value, "open", true));
        }
        for (String value : List.of("changed", "absent"))
            rows.add(Arguments.of("model", "strict", value, "vision", true));
        for (String field : List.of("model", "traits"))
            for (String value : List.of("authored", "changed"))
                rows.add(Arguments.of(field, "free", value, "fact", true));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}/{1}/{2}/gate={3}/configured={4}")
    @MethodSource("controls")
    void modelsAndTraitsReachRealWorkflowBoundaries(
            String field, String branch, String value, String gate, boolean configured) throws Exception {
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        clearState();
        try {
            ObjectNode authored = (ObjectNode) YAML.readTree(Files.readAllBytes(
                    Path.of("main/resources/plans/" + PLAN + ".yaml")));
            ObjectNode input = authored.deepCopy();
            int index = "strict".equals(branch) ? 0 : 1;
            ObjectNode selected = (ObjectNode) input.path("pipeline").get(1).path("branches").get(index);
            assertEquals(index == 0 ? "view_memory_safe" : "view_free_projection", selected.path("id").asText());
            assertTrue(selected.has(field));
            if ("model".equals(field)) {
                switch (value) {
                    case "changed" -> selected.put(field, "fixture.changed-model");
                    case "blank" -> selected.put(field, " ");
                    case "auto" -> selected.put(field, "auto");
                    case "absent" -> selected.remove(field);
                    case "bound" -> selected.put(field, "${fixture.bound-model}");
                    case "unbound" -> selected.put(field, "${fixture.unbound-model}");
                    case "unknown_tier" -> selected.put(field, "llmrouter.unknown");
                    default -> { }
                }
            } else {
                switch (value) {
                    case "changed" -> selected.putArray(field).add("fixture.changed");
                    case "empty" -> selected.putArray(field);
                    case "absent" -> selected.remove(field);
                    case "missing_asset" -> selected.putArray(field).add("fixture.missing");
                    default -> { }
                }
            }
            ObjectNode restored = input.deepCopy();
            ((ObjectNode) restored.path("pipeline").get(1).path("branches").get(index))
                    .set(field, authored.path("pipeline").get(1).path("branches").get(index).get(field));
            assertEquals(authored, restored);
            byte[] bytes = YAML.writeValueAsBytes(input);
            var resources = new DefaultResourceLoader() {
                @Override public Resource getResource(String location) {
                    if (location.equals("classpath:plans/" + PLAN + ".yaml"))
                        return new ByteArrayResource(bytes) {
                            @Override public String getFilename() { return PLAN + ".yaml"; }
                        };
                    for (var trait : TRAITS.entrySet())
                        if (location.equals("classpath:prompts/traits/" + trait.getKey() + ".md"))
                            return new ByteArrayResource(trait.getValue().getBytes(StandardCharsets.UTF_8));
                    return new ByteArrayResource(new byte[0]) {
                        @Override public boolean exists() { return false; }
                    };
                }
            };
            MockEnvironment env = new MockEnvironment().withProperty("fixture.bound-model", "fixture.bound-model")
                    .withProperty("llm.vision.model", "fixture.vision-model");
            if (configured) env.withProperty("llm.fast.model", "fixture.fast-model")
                    .withProperty("llm.chat-model", "fixture.chat-model");
            var resolver = spy(new PlanModelResolver(env));
            var assets = spy(new PromptAssetService(resources));
            ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(
                    ChatWorkflowProjectionDefaultProfileTest.class, "workflowFixture");
            ReflectionTestUtils.setField(workflow, "planDslLoader", new PlanDslLoader(resources));
            ReflectionTestUtils.setField(workflow, "planHintApplier", new PlanHintApplier(resources));
            ReflectionTestUtils.setField(workflow, "planPolicyMapper", new PlanPolicyMapper());
            ReflectionTestUtils.setField(workflow, "planModelResolver", resolver);
            ReflectionTestUtils.setField(workflow, "promptAssetService", assets);
            ReflectionTestUtils.setField(workflow, "env", env);
            ReflectionTestUtils.setField(workflow, "conversationHarmonyMode", "vision".equals(gate) ? "enforce" : "off");
            var preprocessor = (com.example.lms.service.rag.pre.QueryContextPreprocessor) ReflectionTestUtils.getField(workflow, "qcPreprocessor");
            when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
            GuardContext context = GuardContext.defaultContext();
            context.setPlanId(PLAN);
            GuardContextHolder.set(context);
            var freeRoute = new AtomicBoolean();
            List<Route> routes = new CopyOnWriteArrayList<>();
            List<Build> builds = new CopyOnWriteArrayList<>();
            List<Prompt> prompts = new CopyOnWriteArrayList<>();
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenAnswer(call -> {
                List<ChatMessage> messages = call.getArgument(0);
                Set<String> markers = TRAITS.values().stream().filter(marker -> messages.stream()
                        .filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                        .anyMatch(message -> message.text().contains(marker))).collect(Collectors.toSet());
                Set<String> userMarkers = TRAITS.values().stream().filter(marker -> messages.stream()
                        .filter(dev.langchain4j.data.message.UserMessage.class::isInstance)
                        .map(dev.langchain4j.data.message.UserMessage.class::cast).flatMap(message -> message.contents().stream())
                        .filter(dev.langchain4j.data.message.TextContent.class::isInstance)
                        .map(dev.langchain4j.data.message.TextContent.class::cast)
                        .anyMatch(content -> content.text().contains(marker))).collect(Collectors.toSet());
                prompts.add(new Prompt(freeRoute.get(), markers, userMarkers));
                return ChatResponse.builder().aiMessage(AiMessage.from("unsupported draft")).build();
            });
            ModelRouter router = (ModelRouter) ReflectionTestUtils.getField(workflow, "modelRouter");
            when(router.route(anyString(), nullable(String.class), anyString(), anyInt(), anyString())).thenAnswer(call -> {
                boolean free = "FREE_IDEA".equals(call.getArgument(0, String.class));
                freeRoute.set(free);
                routes.add(new Route(free, call.getArgument(4, String.class)));
                return model;
            });
            when(router.resolveModelName(model)).thenReturn("fixture.route-default");
            DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
            when(factory.canServeQuietly(anyString())).thenReturn(true);
            when(factory.lcWithTimeout(anyString(), nullable(Double.class), nullable(Double.class),
                    nullable(Double.class), nullable(Double.class), nullable(Integer.class), anyInt())).thenAnswer(call -> {
                        builds.add(new Build(freeRoute.get(), call.getArgument(0, String.class)));
                        return model;
                    });
            ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
            ChatRequestDto request = ChatRequestDto.builder().message("aurora lattice")
                    .model(CALLER).traits(List.of("fixture.caller")).maxTokens(256)
                    .mode("fact".equals(gate) ? "FACT" : null).memoryMode("EPHEMERAL")
                    .imageBase64("vision".equals(gate) ? "AA==" : null).imageMediaType("image/png")
                    .searchMode(SearchMode.AUTO).useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            workflow.continueChat(request, ignored -> List.of());

            String strict = resolved("strict", field, branch, value, configured);
            String free = resolved("free", field, branch, value, configured);
            String primary = "vision".equals(gate) ? "fixture.vision-model" : strict == null ? CALLER : strict;
            List<Route> expectedRoutes = new ArrayList<>(List.of(new Route(false, primary)));
            List<Build> expectedBuilds = new ArrayList<>(List.of(new Build(false, primary)));
            List<Prompt> expectedPrompts = new ArrayList<>(List.of(
                    new Prompt(false, expectedMarkers("strict", field, branch, value), Set.of())));
            if (!"fact".equals(gate)) {
                expectedRoutes.add(new Route(true, free == null ? "" : free));
                expectedBuilds.add(new Build(true, free == null ? primary : free));
                expectedPrompts.add(new Prompt(true, Set.of(), expectedMarkers("free", field, branch, value)));
            }
            assertEquals(expectedRoutes, routes);
            assertEquals(expectedBuilds, builds);
            assertEquals(expectedPrompts, prompts);
            long resolverCalls = mockingDetails(resolver).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("resolveRequestedModel")).count();
            long rendererCalls = mockingDetails(assets).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("renderTraits")).count();
            assertEquals("fact".equals(gate) ? 1 : "vision".equals(gate) ? 3 : 2, resolverCalls);
            assertEquals("fact".equals(gate) ? 1 : 2, rendererCalls);
            assertTrue(mockingDetails(factory).isMock());
            assertSame(context, GuardContextHolder.get());
            System.out.printf("TBL07_PROJECTION_MODEL_TRAITS field=%s branch=%s value=%s gate=%s configured=%s resolverCalls=%d renderCalls=%d factoryCalls=%d promptCalls=%d%n",
                    field, branch, value, gate, configured, resolverCalls, rendererCalls, builds.size(), prompts.size());
        } finally {
            clearState();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static String resolved(String forBranch, String field, String branch, String value, boolean configured) {
        if ("model".equals(field) && branch.equals(forBranch)) {
            switch (value) {
                case "changed": return "fixture.changed-model";
                case "blank", "auto", "absent": return null;
                case "bound": return "fixture.bound-model";
                case "unbound": return "${fixture.unbound-model}";
                case "unknown_tier": return "llmrouter.unknown";
                default: break;
            }
        }
        return configured ? "strict".equals(forBranch) ? "fixture.fast-model" : "fixture.chat-model"
                : "strict".equals(forBranch) ? "llmrouter.light" : "llmrouter.gemma";
    }

    private static Set<String> expectedMarkers(String forBranch, String field, String branch, String value) {
        if ("traits".equals(field) && branch.equals(forBranch)) {
            if ("changed".equals(value)) return Set.of("TBL_TRAIT_CHANGED");
            if ("missing_asset".equals(value)) return Set.of();
            if ("empty".equals(value) || "absent".equals(value))
                return "strict".equals(forBranch) ? Set.of("TBL_TRAIT_CALLER") : Set.of();
        }
        return Set.of("strict".equals(forBranch) ? "TBL_TRAIT_STRICT" : "TBL_TRAIT_FREE");
    }

    private static void clearState() {
        ReflectionTestUtils.invokeMethod(ChatWorkflowProjectionDefaultProfileTest.class, "clearState");
    }
}
