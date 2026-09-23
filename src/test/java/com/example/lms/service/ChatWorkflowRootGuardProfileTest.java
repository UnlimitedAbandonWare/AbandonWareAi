package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.guard.GuardProfile;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.plan.PlanHints;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWorkflowRootGuardProfileTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String QUERY = "aurora lattice";
    private static final List<String> PLANS = List.of("brave.v1", "rulebreak.v1", "safe_autorun.v1");

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> controls() {
        return PLANS.stream().flatMap(plan -> Stream.of("changed", "absent").map(value -> Arguments.of(plan, value)));
    }

    @ParameterizedTest(name = "root-guard:{0}:{1}")
    @MethodSource("controls")
    void rootProfileDoesNotChangeEitherParserOrTheReachedRequestGuard(String plan, String value) throws Exception {
        ObjectNode authored = authored(plan);
        assertTrue(authored.path("guard").path("profile").isTextual());
        ObjectNode input = authored.deepCopy();
        ObjectNode guard = (ObjectNode) input.get("guard");
        if ("absent".equals(value)) guard.remove("profile");
        else guard.put("profile", "STRICT".equalsIgnoreCase(guard.path("profile").asText()) ? "PROFILE_FREE" : "STRICT");
        assertTrue(input.path("guard").isObject());
        assertNotEquals(authored, input);
        ObjectNode restored = input.deepCopy();
        ((ObjectNode) restored.get("guard")).set("profile", authored.path("guard").get("profile"));
        assertEquals(authored, restored, "only the exact root leaf changes");
        Class<?> llmContract = Class.forName("com.example.lms.plan.PlanRootLlmBoundaryTest");
        Object before = ReflectionTestUtils.invokeMethod(llmContract, "apply", plan, authored);
        Object after = ReflectionTestUtils.invokeMethod(llmContract, "apply", plan, input);
        assertEquals(before, after, "complete typed/raw hints, orchestration hints, metadata and overrides without normalization");
        PlanHints parsed = ReflectionTestUtils.invokeMethod(before, "plan");
        assertTrue(PlanHintApplier.dslUnwiredKeys(parsed).contains("guard"));
        assertFalse(parsed.raw().containsKey("guard"));
        assertEquals(YAML.valueToTree(ReflectionTestUtils.invokeMethod(llmContract, "nova", plan, authored)),
                YAML.valueToTree(ReflectionTestUtils.invokeMethod(llmContract, "nova", plan, input)));
        WorkflowResult baseline = workflow(plan, authored, null);
        WorkflowResult changed = workflow(plan, input, null);
        assertEquals(parsed, baseline.loaded(), "the actual workflow must load the same complete resource result");
        assertEquals(baseline, changed);
        assertEquals(GuardProfile.PROFILE_FREE, baseline.profile());
        System.out.printf("TBL07_ROOT_GUARD plan=%s value=%s fullParserEqual=true novaEqual=true workflowEqual=true profile=PROFILE_FREE parserReads=2 novaReads=2 workflowReads=2 gateObservations=2%n",
                plan, value);
    }

    @Test
    void actualRequestModeChangesTheReachedGuardWithFixedPlanBytes() throws Exception {
        ObjectNode root = authored("safe_autorun.v1");
        ObjectNode before = root.deepCopy();
        WorkflowResult domain = workflow("safe_autorun.v1", root, null);
        WorkflowResult explicit = workflow("safe_autorun.v1", root, "FACT");
        assertEquals(before, root, "both requests retain all plan bytes and root guard fields");
        assertEquals(domain.loaded(), explicit.loaded());
        assertEquals(GuardProfile.PROFILE_FREE, domain.profile());
        assertEquals(GuardProfile.STRICT, explicit.profile());
        assertNotEquals(domain.observation(), explicit.observation());
        System.out.println("TBL07_REQUEST_GUARD_CONTROL plan=safe_autorun.v1 fixedResource=true domain=GAME profiles=PROFILE_FREE,STRICT workflowReads=2 gateObservations=2 providerWire=false");
    }

    private record WorkflowResult(PlanHints loaded, Object observation, GuardProfile profile) {}

    private static WorkflowResult workflow(String plan, ObjectNode root, String mode) throws Exception {
        var previousMdc = org.slf4j.MDC.getCopyOfContextMap();
        ReflectionTestUtils.invokeMethod(ChatWorkflowGuardProfileIsolationTest.class, "clearWorkflowState");
        try {
            Class<?> gateType = Class.forName("com.example.lms.service.ChatWorkflowGuardProfileIsolationTest$LatchingGate");
            var constructor = gateType.getDeclaredConstructor(QueryDomain.class, QueryDomain.class, boolean.class);
            constructor.setAccessible(true);
            Object gate = constructor.newInstance(QueryDomain.GAME, QueryDomain.GENERAL, false);
            Object fixture = ReflectionTestUtils.invokeMethod(ChatWorkflowGuardProfileIsolationTest.class, "fixture", gate);
            ChatWorkflow flow = ReflectionTestUtils.invokeMethod(fixture, "workflow");
            assertEquals("off", ReflectionTestUtils.getField(flow, "interactionPolicyMode"));
            byte[] bytes = YAML.writeValueAsBytes(root);
            AtomicInteger lookups = new AtomicInteger(); AtomicInteger reads = new AtomicInteger();
            PlanHintApplier applier = spy(new PlanHintApplier(new DefaultResourceLoader() {
                @Override public Resource getResource(String location) {
                    assertEquals("classpath:plans/" + plan + ".yaml", location);
                    lookups.incrementAndGet();
                    return new ByteArrayResource(bytes) {
                        @Override public String getFilename() { return plan + ".yaml"; }
                        @Override public InputStream getInputStream() throws java.io.IOException {
                            reads.incrementAndGet(); return super.getInputStream();
                        }
                    };
                }
            }));
            ReflectionTestUtils.setField(flow, "planHintApplier", applier);
            GuardContext caller = GuardContext.defaultContext();
            caller.setPlanId(plan); caller.setGuardLevel(null); caller.setRequestGuardProfile(null);
            assertNull(caller.getGuardLevel()); assertNull(caller.getRequestGuardProfile());
            GuardContextHolder.set(caller);
            ChatRequestDto request = ChatRequestDto.builder().message(QUERY)
                    .model("release-gate-recording-fake").maxTokens(256).mode(mode)
                    .memoryMode("EPHEMERAL").searchMode(SearchMode.AUTO)
                    .useWebSearch(true).useRag(true).useVerification(true)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true, true)).build();
            flow.continueChat(request, ignored -> List.of());
            verify(applier).load(plan);
            ArgumentCaptor<PlanHints> loaded = ArgumentCaptor.forClass(PlanHints.class);
            ArgumentCaptor<GuardContext> context = ArgumentCaptor.forClass(GuardContext.class);
            verify(applier).applyToGuardContext(loaded.capture(), context.capture());
            PlanHints actual = loaded.getValue();
            assertFalse(actual.isEmpty()); assertEquals(plan, actual.planId());
            verify(applier).applyToHintsAndMeta(same(actual), any(OrchestrationHints.class), anyMap());
            assertEquals(1, lookups.get()); assertEquals(1, reads.get());
            Map<?, ?> observations = (Map<?, ?>) ReflectionTestUtils.getField(gate, "observations");
            assertEquals(Set.of(QUERY), observations.keySet(), "the same request must be recorded at the real gate");
            Object observation = observations.get(QUERY);
            GuardProfile observed = ReflectionTestUtils.invokeMethod(observation, "profile");
            assertEquals(QueryDomain.GAME, ReflectionTestUtils.invokeMethod(observation, "domain"));
            assertEquals(observed, context.getValue().getRequestGuardProfile());
            Object learning = ReflectionTestUtils.invokeMethod(fixture, "learningWriteInterceptor");
            Object memory = ReflectionTestUtils.invokeMethod(fixture, "memoryWriteInterceptor");
            verifyNoInteractions(learning, memory);
            return new WorkflowResult(actual, observation, observed);
        } finally {
            ReflectionTestUtils.invokeMethod(ChatWorkflowGuardProfileIsolationTest.class, "clearWorkflowState");
            if (previousMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previousMdc);
        }
    }

    private static ObjectNode authored(String plan) throws Exception {
        return (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + plan + ".yaml")));
    }
}
