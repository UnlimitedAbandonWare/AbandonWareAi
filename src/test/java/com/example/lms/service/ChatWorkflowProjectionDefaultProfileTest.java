package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.plan.PlanDslLoader;
import com.example.lms.service.rag.plan.PlanPolicyMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWorkflowProjectionDefaultProfileTest {
    private static final String PLAN = "projection_agent.v1";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static Stream<Arguments> controls() {
        List<Arguments> rows = new ArrayList<>();
        for (String field : List.of("guard-profile", "memory-profile"))
            for (String value : List.of("authored", "changed", "absent"))
                for (String branch : List.of("shipped", "fields_absent", "branch_absent"))
                    for (boolean explicitMemory : List.of(false, true))
                        rows.add(Arguments.of(field, value, branch, explicitMemory));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}/{1}/{2}/explicitMemory={3}")
    @MethodSource("controls")
    void defaultProfilesReachRealWorkflowOnlyThroughMissingWholeBranch(
            String field, String value, String branch, boolean explicitMemory) throws Exception {
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        clearState();
        try {
            ObjectNode authored = (ObjectNode) YAML.readTree(Files.readAllBytes(
                    Path.of("main/resources/plans/" + PLAN + ".yaml")));
            ObjectNode input = authored.deepCopy();
            ObjectNode defaults = (ObjectNode) input.path("defaults");
            assertTrue(defaults.has(field));
            if ("changed".equals(value)) defaults.put(field, "guard-profile".equals(field) ? "strict" : "none");
            if ("absent".equals(value)) defaults.remove(field);
            ArrayNode branches = (ArrayNode) input.path("pipeline").get(1).path("branches");
            assertEquals("draft_dual_view", input.path("pipeline").get(1).path("id").asText());
            ObjectNode strict = (ObjectNode) branches.get(0);
            assertEquals("view_memory_safe", strict.path("id").asText());
            if ("fields_absent".equals(branch)) {
                strict.remove("guard-profile");
                strict.remove("memory-profile");
            }
            if ("branch_absent".equals(branch)) branches.remove(0);

            ObjectNode restored = input.deepCopy();
            ((ObjectNode) restored.path("defaults")).set(field, authored.path("defaults").get(field));
            ((ObjectNode) restored.path("pipeline").get(1)).set("branches",
                    authored.path("pipeline").get(1).path("branches").deepCopy());
            assertEquals(authored, restored, "all unrelated resource fields must remain intact");

            byte[] bytes = YAML.writeValueAsBytes(input);
            var resources = new DefaultResourceLoader() {
                @Override public Resource getResource(String location) {
                    assertEquals("classpath:plans/" + PLAN + ".yaml", location);
                    return new ByteArrayResource(bytes) {
                        @Override public String getFilename() { return PLAN + ".yaml"; }
                    };
                }
            };
            var mapper = new RecordingMapper();
            var workflow = workflowFixture();
            ReflectionTestUtils.setField(workflow, "planDslLoader", new PlanDslLoader(resources));
            ReflectionTestUtils.setField(workflow, "planPolicyMapper", mapper);
            ReflectionTestUtils.setField(workflow, "planHintApplier", new PlanHintApplier(resources));
            GuardProfileProps profiles = (GuardProfileProps) ReflectionTestUtils.getField(workflow, "guardProfileProps");
            GuardContext context = GuardContext.defaultContext();
            context.setPlanId(PLAN);
            context.setGuardLevel(null);
            context.setMemoryProfile(null);
            GuardContextHolder.set(context);

            String guardInput = "shipped".equals(branch) ? "balanced"
                    : "fields_absent".equals(branch) ? null : text(defaults, "guard-profile");
            String memoryInput = "shipped".equals(branch) ? "deep_memory"
                    : "fields_absent".equals(branch) ? null : text(defaults, "memory-profile");
            GuardProfile expectedGuard = guardInput == null ? GuardProfile.PROFILE_FREE
                    : "strict".equals(guardInput) ? GuardProfile.STRICT : GuardProfile.NORMAL;
            MemoryMode expectedMemory = explicitMemory ? MemoryMode.EPHEMERAL
                    : "deep_memory".equals(memoryInput) ? MemoryMode.FULL
                    : "none".equals(memoryInput) ? MemoryMode.EPHEMERAL : MemoryMode.HYBRID;
            HybridRetriever hybrid = (HybridRetriever) ReflectionTestUtils.getField(workflow, "hybridRetriever");
            var retrievalCalls = new AtomicInteger();
            when(hybrid.retrieveAll(anyList(), anyInt(), any(), any())).thenAnswer(call -> {
                retrievalCalls.incrementAndGet();
                assertSame(context, GuardContextHolder.get());
                assertEquals(PLAN, context.getPlanId());
                assertEquals(1, mapper.guardCalls);
                assertEquals(guardInput, mapper.guardInput);
                assertEquals(GuardProfile.PROFILE_FREE, mapper.guardFallback);
                assertEquals(expectedGuard, mapper.guardResult);
                assertEquals(explicitMemory ? 0 : 1, mapper.memoryCalls);
                if (!explicitMemory) {
                    assertEquals(memoryInput, mapper.memoryInput);
                    assertEquals(MemoryMode.HYBRID, mapper.memoryFallback);
                    assertEquals(expectedMemory, mapper.memoryResult);
                }
                var decision = context.getInteractionPolicyDecision();
                assertEquals(InteractionEvidencePolicy.FeatureMode.OFF, decision.featureMode());
                assertFalse(decision.defensive());
                assertEquals(expectedGuard, decision.enforceGuardProfile(mapper.guardResult));
                assertEquals(expectedGuard, profiles.currentProfile());
                assertEquals(expectedGuard.name(), context.getGuardLevel());
                assertEquals(expectedMemory == MemoryMode.EPHEMERAL ? "NONE" : "MEMORY", context.getMemoryProfile());
                return List.of(dev.langchain4j.rag.content.Content.from("marble canoe velvet"));
            });
            for (String collaborator : List.of("memoryHandler", "learningWriteInterceptor", "memoryWriteInterceptor", "modelRouter"))
                assertTrue(mockingDetails(ReflectionTestUtils.getField(workflow, collaborator)).isMock());
            ChatRequestDto request = ChatRequestDto.builder().message("aurora lattice")
                    .model("release-gate-recording-fake").maxTokens(256).mode(null)
                    .memoryMode(explicitMemory ? "EPHEMERAL" : null).searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            workflow.continueChat(request, ignored -> List.of());
            assertTrue(retrievalCalls.get() > 0, "real workflow must reach the observed retrieval boundary");
            assertEquals(expectedMemory.name(), TraceStore.get("prompt.memoryMode"));
            assertSame(context, GuardContextHolder.get());
            System.out.printf("TBL07_PROJECTION_DEFAULT field=%s value=%s branch=%s explicitMemory=%s guardInput=%s guard=%s memoryInput=%s memory=%s mapperMemoryCalls=%d retrievalCalls=%d policy=OFF%n",
                    field, value, branch, explicitMemory, guardInput, expectedGuard,
                    memoryInput, expectedMemory, mapper.memoryCalls, retrievalCalls.get());
        } finally {
            clearState();
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static ChatWorkflow workflowFixture() throws Exception {
        // Reuse the established offline fixture without modifying its existing proof identity.
        Class<?> gateType = Class.forName(ChatWorkflowGuardProfileIsolationTest.class.getName() + "$LatchingGate");
        var constructor = gateType.getDeclaredConstructor(QueryDomain.class, QueryDomain.class, boolean.class);
        constructor.setAccessible(true);
        Object gate = constructor.newInstance(QueryDomain.GAME, QueryDomain.GENERAL, false);
        Object fixture = ReflectionTestUtils.invokeMethod(ChatWorkflowGuardProfileIsolationTest.class, "fixture", gate);
        return ReflectionTestUtils.invokeMethod(fixture, "workflow");
    }

    private static void clearState() {
        GuardContextHolder.clear();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    private static final class RecordingMapper extends PlanPolicyMapper {
        int guardCalls, memoryCalls;
        String guardInput, memoryInput;
        GuardProfile guardFallback, guardResult;
        MemoryMode memoryFallback, memoryResult;

        @Override public GuardProfile resolveGuardProfile(String input, GuardProfile fallback) {
            guardCalls++;
            guardInput = input;
            guardFallback = fallback;
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
