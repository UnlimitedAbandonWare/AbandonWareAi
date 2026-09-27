package com.example.lms.service;

import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWorkflowProjectionBranchProfileTest {
    private static final String PLAN = "projection_agent.v1";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private record PromptPolicy(boolean free, GuardProfile guard, MemoryMode memory) { }
    private record CallPolicy(boolean free, GuardProfile guard, String guardLabel, String memoryLabel, String primaryPromptMemory) { }

    static Stream<Arguments> controls() {
        List<Arguments> rows = new ArrayList<>();
        for (String field : List.of("guard-profile", "memory-profile"))
            for (String branch : List.of("strict", "free"))
                for (String value : List.of("authored", "changed", "absent", "unknown"))
                    for (boolean explicitMemory : List.of(false, true))
                        rows.add(Arguments.of(field, branch, value, explicitMemory, false, false));
        for (String field : List.of("guard-profile", "memory-profile")) {
            for (boolean explicitMemory : List.of(false, true))
                rows.add(Arguments.of(field, "strict", "changed", explicitMemory, true, false));
            rows.add(Arguments.of(field, "free", "changed", false, false, true));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}/{1}/{2}/explicit={3}/labels={4}/fact={5}")
    @MethodSource("controls")
    void strictPoliciesAndFreeNonConsumptionAreDistinctAtActualCalls(
            String field, String branch, String value, boolean explicitMemory,
            boolean retainedLabels, boolean factMode) throws Exception {
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
            if ("changed".equals(value)) selected.put(field, "guard-profile".equals(field)
                    ? index == 0 ? "brave" : "strict" : index == 0 ? "none" : "projection");
            if ("absent".equals(value)) selected.remove(field);
            if ("unknown".equals(value)) selected.put(field, "fixture-unknown-profile");
            ObjectNode restored = input.deepCopy();
            ((ObjectNode) restored.path("pipeline").get(1).path("branches").get(index))
                    .set(field, authored.path("pipeline").get(1).path("branches").get(index).get(field));
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
            var loader = new PlanDslLoader(resources);
            var spec = loader.loadProjectionAgent(PLAN).orElseThrow();
            var parsedBranch = index == 0 ? spec.viewMemorySafe() : spec.viewFreeProjection();
            String authoredInput = selected.hasNonNull(field) ? selected.get(field).asText() : null;
            assertEquals(authoredInput, "guard-profile".equals(field) ? parsedBranch.guardProfile() : parsedBranch.memoryProfile());
            ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(
                    ChatWorkflowProjectionDefaultProfileTest.class, "workflowFixture");
            var mapper = new RecordingMapper();
            ReflectionTestUtils.setField(workflow, "planDslLoader", loader);
            ReflectionTestUtils.setField(workflow, "planHintApplier", new PlanHintApplier(resources));
            ReflectionTestUtils.setField(workflow, "planPolicyMapper", mapper);
            var preprocessor = (com.example.lms.service.rag.pre.QueryContextPreprocessor) ReflectionTestUtils.getField(workflow, "qcPreprocessor");
            when(preprocessor.inferIntent(anyString())).thenReturn("GENERAL");
            GuardContext context = GuardContext.defaultContext();
            context.setPlanId(PLAN);
            if (!retainedLabels) {
                context.setGuardLevel(null);
                context.setMemoryProfile(null);
            }
            GuardContextHolder.set(context);
            GuardProfileProps profiles = (GuardProfileProps) ReflectionTestUtils.getField(workflow, "guardProfileProps");
            var freeRoute = new AtomicBoolean();
            var modelCalls = new AtomicInteger();
            List<PromptPolicy> promptPolicies = new CopyOnWriteArrayList<>();
            List<CallPolicy> callPolicies = new CopyOnWriteArrayList<>();
            StandardPromptBuilder builder = spy(new StandardPromptBuilder());
            doAnswer(call -> {
                PromptContext ctx = call.getArgument(0);
                promptPolicies.add(new PromptPolicy(freeRoute.get(), ctx.guardProfile(), ctx.memoryMode()));
                return call.callRealMethod();
            }).when(builder).build(any(PromptContext.class));
            ReflectionTestUtils.setField(workflow, "promptBuilder", builder);
            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenAnswer(call -> {
                modelCalls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("unsupported draft")).build();
            });
            ModelRouter router = (ModelRouter) ReflectionTestUtils.getField(workflow, "modelRouter");
            when(router.route(anyString(), nullable(String.class), anyString(), anyInt(), anyString())).thenAnswer(call -> {
                freeRoute.set("FREE_IDEA".equals(call.getArgument(0, String.class)));
                return model;
            });
            when(router.resolveModelName(model)).thenReturn("release-gate-recording-fake");
            DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
            when(factory.lcWithTimeout(anyString(), nullable(Double.class), nullable(Double.class),
                    nullable(Double.class), nullable(Double.class), nullable(Integer.class), anyInt())).thenAnswer(call -> {
                        assertSame(context, GuardContextHolder.get(), "observe caller policy at the actual factory call");
                        assertEquals(InteractionEvidencePolicy.FeatureMode.OFF, context.getInteractionPolicyDecision().featureMode());
                        assertFalse(context.getInteractionPolicyDecision().defensive());
                        callPolicies.add(new CallPolicy(freeRoute.get(), profiles.currentProfile(), context.getGuardLevel(),
                                context.getMemoryProfile(), TraceStore.getString("prompt.memoryMode")));
                        return model;
                    });
            ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
            ChatRequestDto request = ChatRequestDto.builder().message("aurora lattice")
                    .model("release-gate-recording-fake").maxTokens(256).mode(factMode ? "FACT" : null)
                    .memoryMode(explicitMemory ? "EPHEMERAL" : null).searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            workflow.continueChat(request, ignored -> List.of());

            String strictGuard = index == 0 && "guard-profile".equals(field) ? authoredInput : "balanced";
            String strictMemory = index == 0 && "memory-profile".equals(field) ? authoredInput : "deep_memory";
            GuardProfile expectedGuard = "balanced".equals(strictGuard) ? GuardProfile.NORMAL
                    : "brave".equals(strictGuard) ? GuardProfile.BRAVE : GuardProfile.PROFILE_FREE;
            MemoryMode expectedMemory = explicitMemory ? MemoryMode.EPHEMERAL
                    : "deep_memory".equals(strictMemory) ? MemoryMode.FULL
                    : "none".equals(strictMemory) ? MemoryMode.EPHEMERAL : MemoryMode.HYBRID;
            String guardLabel = retainedLabels ? "BALANCED" : expectedGuard.name();
            String memoryLabel = retainedLabels ? "MEMORY" : expectedMemory == MemoryMode.EPHEMERAL ? "NONE" : "MEMORY";
            List<PromptPolicy> expectedPrompts = new ArrayList<>(List.of(new PromptPolicy(false, expectedGuard, expectedMemory)));
            List<CallPolicy> expectedCalls = new ArrayList<>(List.of(
                    new CallPolicy(false, expectedGuard, guardLabel, memoryLabel, expectedMemory.name())));
            if (!factMode) {
                expectedPrompts.add(new PromptPolicy(true, null, MemoryMode.HYBRID));
                expectedCalls.add(new CallPolicy(true, expectedGuard, guardLabel, memoryLabel, expectedMemory.name()));
            }
            assertEquals(expectedPrompts, promptPolicies);
            assertEquals(expectedCalls, callPolicies);
            assertEquals(factMode ? 1 : 2, modelCalls.get());
            assertEquals(1, mapper.guardCalls);
            assertEquals(strictGuard, mapper.guardInput);
            assertEquals(expectedGuard, mapper.guardResult);
            assertEquals(explicitMemory ? 0 : 1, mapper.memoryCalls);
            if (!explicitMemory) {
                assertEquals(strictMemory, mapper.memoryInput);
                assertEquals(MemoryMode.HYBRID, mapper.memoryFallback);
                assertEquals(expectedMemory, mapper.memoryResult);
            }
            boolean unknownStrict = index == 0 && "unknown".equals(value);
            if (unknownStrict && "guard-profile".equals(field))
                assertEquals(true, TraceStore.get("rag.planPolicy.suppressed.guardProfile"));
            if (unknownStrict && "memory-profile".equals(field) && !explicitMemory)
                assertEquals(true, TraceStore.get("rag.planPolicy.suppressed.memoryProfile"));
            for (String name : List.of("memoryHandler", "learningWriteInterceptor", "memoryWriteInterceptor"))
                assertTrue(mockingDetails(ReflectionTestUtils.getField(workflow, name)).isMock());
            assertSame(context, GuardContextHolder.get());
            System.out.printf("TBL07_PROJECTION_BRANCH_PROFILE field=%s branch=%s value=%s explicit=%s labels=%s fact=%s guard=%s primaryMemory=%s mapperMemoryCalls=%d factoryCalls=%d modelCalls=%d promptCalls=%d%n",
                    field, branch, value, explicitMemory, retainedLabels, factMode, expectedGuard, expectedMemory,
                    mapper.memoryCalls, callPolicies.size(), modelCalls.get(), promptPolicies.size());
        } finally {
            clearState();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static void clearState() {
        ReflectionTestUtils.invokeMethod(ChatWorkflowProjectionDefaultProfileTest.class, "clearState");
    }

    private static final class RecordingMapper extends PlanPolicyMapper {
        int guardCalls, memoryCalls;
        String guardInput, memoryInput;
        GuardProfile guardResult;
        MemoryMode memoryFallback, memoryResult;
        @Override public GuardProfile resolveGuardProfile(String input, GuardProfile fallback) {
            guardCalls++;
            guardInput = input;
            return guardResult = super.resolveGuardProfile(input, fallback);
        }
        @Override public MemoryMode resolveMemoryMode(String input, MemoryMode fallback) {
            memoryCalls++;
            memoryInput = input;
            memoryFallback = fallback;
            return memoryResult = super.resolveMemoryMode(input, fallback);
        }
    }
}
