package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.ProjectionMergeService;
import com.example.lms.service.rag.plan.PlanDslLoader;
import com.example.lms.service.rag.plan.PlanPolicyMapper;
import com.example.lms.service.rag.plan.ProjectionAgentPlanSpec;
import com.example.lms.service.routing.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.langchain4j.data.message.AiMessage;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWorkflowProjectionMergeConfigTest {
    private static final String PLAN = "projection_agent.v1";
    private static final String CREATIVE = "fixture violet windmill creative member";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private record MergeObservation(boolean groundedNonblank, boolean creativeNonblank,
                                    boolean groundedRetained, boolean creativeRetained, int configSize) { }

    static Stream<Arguments> parserCases() {
        return Stream.of("strategy", "weights.grounded", "weights.creative", "inject-evidence", "keep-free-side-notes")
                .flatMap(field -> Stream.of("authored", "changed", "absent").map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest(name = "parser:{0}:{1}")
    @MethodSource("parserCases")
    void fullResourceConfigChangesIdentifyOnlyTheSupportedTypedField(String field, String value) throws Exception {
        ObjectNode authored = authored();
        ObjectNode input = mutate(authored, field, value);
        ProjectionAgentPlanSpec baseline = loader(authored).loadProjectionAgent(PLAN).orElseThrow();
        ProjectionAgentPlanSpec actual = loader(input).loadProjectionAgent(PLAN).orElseThrow();
        boolean supportedChange = "keep-free-side-notes".equals(field) && "changed".equals(value);
        ProjectionAgentPlanSpec expected = supportedChange
                ? new ProjectionAgentPlanSpec(baseline.id(), baseline.defaults(), baseline.viewMemorySafe(),
                        baseline.viewFreeProjection(), new ProjectionAgentPlanSpec.Merge(false, false), baseline.finalAnswer())
                : baseline;
        assertEquals(expected, actual, "all typed output members are compared");
        assertEquals(!supportedChange, baseline.equals(actual));
        System.out.printf("TBL07_PROJECTION_MERGE_PARSE field=%s value=%s fullSpecEqual=%s keepNotes=%s%n",
                field, value, baseline.equals(actual), actual.merge().keepFreeSideNotes());
    }

    static Stream<Arguments> workflowCases() {
        List<Arguments> rows = new ArrayList<>();
        for (boolean globalKeep : List.of(false, true)) {
            for (String value : List.of("authored", "changed", "absent"))
                rows.add(Arguments.of(value, globalKeep, false));
            rows.add(Arguments.of("changed", globalKeep, true));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "workflow:{0}:global={1}:fact={2}")
    @MethodSource("workflowCases")
    void realMergeReceivesNoPlanConfigAndUsesItsGlobalDefault(String value, boolean globalKeep, boolean fact) throws Exception {
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        clearState();
        try {
            ObjectNode input = mutate(authored(), "keep-free-side-notes", value);
            byte[] bytes = YAML.writeValueAsBytes(input);
            var resources = resources(bytes);
            var planLoader = new PlanDslLoader(resources);
            var spec = planLoader.loadProjectionAgent(PLAN).orElseThrow();
            assertEquals(!"changed".equals(value), spec.merge().keepFreeSideNotes());
            ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(
                    ChatWorkflowProjectionDefaultProfileTest.class, "workflowFixture");
            ReflectionTestUtils.setField(workflow, "planDslLoader", planLoader);
            ReflectionTestUtils.setField(workflow, "planHintApplier", new PlanHintApplier(resources));
            ReflectionTestUtils.setField(workflow, "planPolicyMapper", new PlanPolicyMapper());
            var preprocessor = (com.example.lms.service.rag.pre.QueryContextPreprocessor)
                    ReflectionTestUtils.getField(workflow, "qcPreprocessor");
            when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
            GuardContext context = GuardContext.defaultContext();
            context.setPlanId(PLAN);
            GuardContextHolder.set(context);
            ProjectionMergeService merge = spy(new ProjectionMergeService());
            ReflectionTestUtils.setField(merge, "keepFreeSideNotes", globalKeep);
            List<MergeObservation> merges = new CopyOnWriteArrayList<>();
            doAnswer(call -> {
                String grounded = call.getArgument(0);
                String creative = call.getArgument(1);
                Map<String, Object> config = call.getArgument(2);
                assertNotNull(grounded);
                assertFalse(grounded.isBlank(), "the grounded merge input must be reached and nonblank");
                assertTrue(creative.contains(CREATIVE), "the distinct free model output must reach real merge");
                assertFalse(grounded.contains(CREATIVE));
                assertNotNull(config);
                String result = (String) call.callRealMethod();
                merges.add(new MergeObservation(!grounded.isBlank(), !creative.isBlank(),
                        result.contains(grounded.trim()), result.contains(CREATIVE), config.size()));
                return result;
            }).when(merge).merge(anyString(), anyString(), anyMap());
            ReflectionTestUtils.setField(workflow, "projectionMergeService", merge);
            AtomicReference<String> stage = new AtomicReference<>("PRIMARY");
            AtomicInteger modelCalls = new AtomicInteger();
            AtomicInteger factoryCalls = new AtomicInteger();
            List<Boolean> finalCreativePresence = new CopyOnWriteArrayList<>();
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenAnswer(call -> {
                modelCalls.incrementAndGet();
                if ("FINAL_ANSWER".equals(stage.get())) {
                    List<dev.langchain4j.data.message.ChatMessage> messages = call.getArgument(0);
                    String text = messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                            .flatMap(message -> message.contents().stream()).filter(TextContent.class::isInstance)
                            .map(TextContent.class::cast).map(TextContent::text).reduce("", (a, b) -> a + b);
                    assertTrue(text.contains("[MERGED ANSWER]"));
                    finalCreativePresence.add(text.contains(CREATIVE));
                }
                String answer = "FREE_IDEA".equals(stage.get()) ? CREATIVE
                        : "FINAL_ANSWER".equals(stage.get()) ? "fixture final polish sentinel" : "fixture grounded amber canoe";
                return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
            });
            ModelRouter router = (ModelRouter) ReflectionTestUtils.getField(workflow, "modelRouter");
            when(router.route(anyString(), nullable(String.class), anyString(), anyInt(), anyString())).thenAnswer(call -> {
                stage.set(call.getArgument(0));
                return model;
            });
            when(router.resolveModelName(model)).thenReturn("release-gate-recording-fake");
            DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
            when(factory.lcWithTimeout(anyString(), nullable(Double.class), nullable(Double.class),
                    nullable(Double.class), nullable(Double.class), nullable(Integer.class), anyInt())).thenAnswer(call -> {
                        factoryCalls.incrementAndGet();
                        assertEquals(InteractionEvidencePolicy.FeatureMode.OFF, context.getInteractionPolicyDecision().featureMode());
                        assertFalse(context.getInteractionPolicyDecision().defensive());
                        return model;
                    });
            ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
            ChatRequestDto request = ChatRequestDto.builder().message("aurora lattice")
                    .model("release-gate-recording-fake").maxTokens(256).mode(fact ? "FACT" : null)
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            workflow.continueChat(request, ignored -> List.of());
            assertEquals(fact ? List.of() : List.of(new MergeObservation(true, true, true, globalKeep, 0)), merges);
            assertEquals(fact ? List.of() : List.of(globalKeep), finalCreativePresence);
            assertEquals(fact ? 1 : 3, modelCalls.get());
            assertEquals(modelCalls.get(), factoryCalls.get());
            verify(merge, times(fact ? 0 : 1)).mergeDualView(anyString(), anyString());
            ProjectionMergeService direct = new ProjectionMergeService();
            ReflectionTestUtils.setField(direct, "keepFreeSideNotes", globalKeep);
            assertFalse(direct.merge("fixture grounded", CREATIVE, Map.of("keep-free-side-notes", false)).contains(CREATIVE));
            assertTrue(direct.merge("fixture grounded", CREATIVE, Map.of("keep-free-side-notes", true)).contains(CREATIVE));
            for (String field : List.of("memoryHandler", "learningWriteInterceptor", "memoryWriteInterceptor"))
                assertTrue(mockingDetails(ReflectionTestUtils.getField(workflow, field)).isMock());
            assertSame(context, GuardContextHolder.get());
            System.out.printf("TBL07_PROJECTION_MERGE_WORKFLOW value=%s planKeep=%s globalKeep=%s fact=%s mergeCalls=%d emptyConfig=%s creativeRetained=%s finalPromptCreative=%s modelCalls=%d factoryCalls=%d directControls=2%n",
                    value, spec.merge().keepFreeSideNotes(), globalKeep, fact, merges.size(),
                    merges.stream().allMatch(row -> row.configSize() == 0),
                    merges.isEmpty() ? "not_called" : merges.get(0).creativeRetained(),
                    finalCreativePresence.isEmpty() ? "not_called" : finalCreativePresence.get(0),
                    modelCalls.get(), factoryCalls.get());
        } finally {
            clearState();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static ObjectNode authored() throws Exception {
        return (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + PLAN + ".yaml")));
    }

    private static ObjectNode config(ObjectNode root) {
        for (var step : root.path("pipeline"))
            if ("projection_merge".equals(step.path("id").asText())) return (ObjectNode) step.get("config");
        throw new AssertionError("authored projection merge step is required");
    }

    private static ObjectNode mutate(ObjectNode authored, String field, String value) {
        ObjectNode input = authored.deepCopy();
        String leaf = field.substring(field.lastIndexOf('.') + 1);
        ObjectNode parent = field.startsWith("weights.") ? (ObjectNode) config(input).get("weights") : config(input);
        assertTrue(parent.hasNonNull(leaf));
        if ("changed".equals(value)) {
            if ("strategy".equals(field)) parent.put(leaf, "fixture_strategy");
            else if (field.startsWith("weights.")) parent.put(leaf, 0.25);
            else parent.put(leaf, false);
        }
        if ("absent".equals(value)) parent.remove(leaf);
        ObjectNode restored = input.deepCopy();
        ObjectNode restoredParent = field.startsWith("weights.") ? (ObjectNode) config(restored).get("weights") : config(restored);
        ObjectNode originalParent = field.startsWith("weights.") ? (ObjectNode) config(authored).get("weights") : config(authored);
        restoredParent.set(leaf, originalParent.get(leaf));
        assertEquals(authored, restored, "only the selected leaf may change");
        if (!"authored".equals(value)) assertNotEquals(authored, input);
        return input;
    }

    private static PlanDslLoader loader(ObjectNode input) throws Exception {
        return new PlanDslLoader(resources(YAML.writeValueAsBytes(input)));
    }

    private static DefaultResourceLoader resources(byte[] bytes) {
        return new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                assertEquals("classpath:plans/" + PLAN + ".yaml", location);
                return new ByteArrayResource(bytes) {
                    @Override public String getFilename() { return PLAN + ".yaml"; }
                };
            }
        };
    }

    private static void clearState() {
        ReflectionTestUtils.invokeMethod(ChatWorkflowProjectionDefaultProfileTest.class, "clearState");
    }
}
