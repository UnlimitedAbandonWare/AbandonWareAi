package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.plan.PlanDslLoader;
import com.example.lms.service.rag.plan.PlanPolicyMapper;
import com.example.lms.service.routing.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWorkflowProjectionTokenBoundaryTest {
    private static final String PLAN = "projection_agent.v1";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private record Capture(boolean free, Integer maxTokens) { }

    static Stream<Arguments> controls() {
        List<Arguments> rows = new ArrayList<>();
        for (String branch : List.of("strict", "free"))
            for (String value : List.of("authored", "changed", "absent", "zero", "negative"))
                rows.add(Arguments.of(branch, value, "none", false));
        rows.add(Arguments.of("strict", "changed", "llm.answer.max_tokens", false));
        rows.add(Arguments.of("strict", "changed", "llm.answer.maxTokens", false));
        rows.add(Arguments.of("free", "authored", "none", true));
        rows.add(Arguments.of("free", "changed", "none", true));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}/{1}/override={2}/fact={3}")
    @MethodSource("controls")
    void branchTokenCapsReachActualModelConstruction(
            String branch, String value, String overrideKey, boolean factMode) throws Exception {
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        clearState();
        try {
            ObjectNode authored = (ObjectNode) YAML.readTree(Files.readAllBytes(
                    Path.of("main/resources/plans/" + PLAN + ".yaml")));
            ObjectNode input = authored.deepCopy();
            int index = "strict".equals(branch) ? 0 : 1;
            ObjectNode selected = (ObjectNode) input.path("pipeline").get(1).path("branches").get(index);
            assertEquals(index == 0 ? "view_memory_safe" : "view_free_projection", selected.path("id").asText());
            assertEquals(1024, selected.path("max-tokens").asInt());
            switch (value) {
                case "changed" -> selected.put("max-tokens", index == 0 ? 1536 : 1792);
                case "absent" -> selected.remove("max-tokens");
                case "zero" -> selected.put("max-tokens", 0);
                case "negative" -> selected.put("max-tokens", -1);
                default -> { }
            }
            ObjectNode restored = input.deepCopy();
            ((ObjectNode) restored.path("pipeline").get(1).path("branches").get(index))
                    .set("max-tokens", authored.path("pipeline").get(1).path("branches").get(index).get("max-tokens"));
            assertEquals(authored, restored);
            byte[] bytes = YAML.writeValueAsBytes(input);
            var resources = new DefaultResourceLoader() {
                @Override public Resource getResource(String location) {
                    assertEquals("classpath:plans/" + PLAN + ".yaml", location);
                    return new ByteArrayResource(bytes) {
                        @Override public String getFilename() { return PLAN + ".yaml"; }
                    };
                }
            };
            ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(
                    ChatWorkflowProjectionDefaultProfileTest.class, "workflowFixture");
            ReflectionTestUtils.setField(workflow, "planDslLoader", new PlanDslLoader(resources));
            ReflectionTestUtils.setField(workflow, "planHintApplier", new PlanHintApplier(resources));
            ReflectionTestUtils.setField(workflow, "planPolicyMapper", new PlanPolicyMapper());
            var preprocessor = (com.example.lms.service.rag.pre.QueryContextPreprocessor) ReflectionTestUtils.getField(workflow, "qcPreprocessor");
            when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
            GuardContext context = GuardContext.defaultContext();
            context.setPlanId(PLAN);
            if (!"none".equals(overrideKey)) context.putPlanOverride(overrideKey, 640);
            GuardContextHolder.set(context);
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("unsupported draft")).build());
            var freeRoute = new AtomicBoolean();
            List<Integer> freeHints = new CopyOnWriteArrayList<>();
            List<Capture> captures = new CopyOnWriteArrayList<>();
            ModelRouter router = (ModelRouter) ReflectionTestUtils.getField(workflow, "modelRouter");
            when(router.route(anyString(), nullable(String.class), anyString(), anyInt(), anyString())).thenAnswer(call -> {
                boolean free = "FREE_IDEA".equals(call.getArgument(0, String.class));
                freeRoute.set(free);
                if (free) freeHints.add(call.getArgument(3, Integer.class));
                return model;
            });
            when(router.resolveModelName(model)).thenReturn("release-gate-recording-fake");
            DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
            when(factory.lcWithTimeout(anyString(), nullable(Double.class), nullable(Double.class),
                    nullable(Double.class), nullable(Double.class), nullable(Integer.class), anyInt()))
                    .thenAnswer(call -> {
                        captures.add(new Capture(freeRoute.get(), call.getArgument(5, Integer.class)));
                        return model;
                    });
            ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
            ChatRequestDto request = ChatRequestDto.builder().message("aurora lattice")
                    .model("release-gate-recording-fake").maxTokens(256).mode(factMode ? "FACT" : null)
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            workflow.continueChat(request, ignored -> List.of());
            int strictCap = index == 0 ? "changed".equals(value) ? 1536
                    : "authored".equals(value) ? 1024 : 256 : 1024;
            int primaryCap = "none".equals(overrideKey) ? strictCap : 640;
            int freeCap = index == 1 ? "changed".equals(value) ? 1792
                    : "authored".equals(value) ? 1024 : strictCap : 1024;
            assertEquals(List.of(primaryCap), captures.stream().filter(c -> !c.free()).map(Capture::maxTokens).toList());
            assertEquals(factMode ? List.of() : List.of(freeCap),
                    captures.stream().filter(Capture::free).map(Capture::maxTokens).toList());
            assertEquals(factMode ? 0 : 1, freeHints.size());
            if (!factMode) {
                if (index == 0 || "authored".equals(value) || "changed".equals(value)) assertEquals(freeCap, freeHints.get(0));
                else assertTrue(freeHints.get(0) >= 512, "nonpositive free cap uses the verbosity routing hint");
            }
            assertTrue(mockingDetails(factory).isMock());
            assertSame(context, GuardContextHolder.get());
            System.out.printf("TBL07_PROJECTION_TOKEN branch=%s value=%s override=%s fact=%s strictRequestCap=%d primaryFactoryCap=%d freeFactoryCap=%s freeRouteHint=%s factoryCalls=%d%n",
                    branch, value, overrideKey, factMode, strictCap, primaryCap,
                    factMode ? "not_called" : freeCap, freeHints, captures.size());
        } finally {
            clearState();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static void clearState() {
        ReflectionTestUtils.invokeMethod(ChatWorkflowProjectionDefaultProfileTest.class, "clearState");
    }
}
