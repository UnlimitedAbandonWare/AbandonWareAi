package com.example.lms.service.routing;

import com.example.lms.llm.*;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdaptiveRouteDecisionTest {
    @Test void earlyDtoCloneUsesMainDecisionButProjectionKeepsItsOwnModel() {
        var original=com.example.lms.dto.ChatRequestDto.builder().model("fixture-plan").build();
        var clone=original.toBuilder().build();
        var decision=new com.example.lms.llm.gateway.LlmRouteDecision(null,OAUTH,null,null,false,"complex_main_oauth",128);
        original.bindMainRouteDecision(decision);
        assertEquals(OAUTH,org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                com.example.lms.service.ChatWorkflow.class,"requestedModelForAttempt",clone,decision));
        var auxiliary=original.toBuilder().model("fixture-rewrite").build();
        assertEquals("fixture-rewrite",org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                com.example.lms.service.ChatWorkflow.class,"requestedModelForAttempt",auxiliary,null));
    }
    static final String OAUTH = "chatgpt-oauth:fixture-main";
    final ChatModel base = mock(ChatModel.class), selected = mock(ChatModel.class);
    final DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class, invocation ->
            "automaticMainRoute".equals(invocation.getMethod().getName()) ? OAUTH :
                    org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    final RouterPolicy policy = new RouterPolicy(new com.example.lms.config.MoeRoutingProps());
    final PolicyBasedModelRouter router = new PolicyBasedModelRouter(base, null, null, policy, factory);
    @BeforeEach void setup() {
        RequestedModelSelection.begin(null);
        ReflectionTestUtils.setField(router, "defaultConfiguredModel", "fixture-light");
        ReflectionTestUtils.setField(policy, "complexityThreshold", .55);
        ReflectionTestUtils.setField(policy, "tokensThreshold", 5000);
        ReflectionTestUtils.setField(policy, "uncertaintyThreshold", 1.0);
        ReflectionTestUtils.setField(policy, "webEvidenceThreshold", 1.0);
        ReflectionTestUtils.setField(policy, "upgradeThreshold", 1.0);
        when(factory.canServe(anyString())).thenReturn(true);
        when(factory.canServeQuietly(anyString())).thenReturn(true);
        when(factory.lcWithTimeout(anyString(),any(),any(),any(),any(),any(),anyInt(),eq(0))).thenReturn(selected);
    }
    @AfterEach void clear() { TraceStore.clear(); }
    ChatModel route(String query, String requested, boolean tools) throws Exception {
        try {
            return (ChatModel) router.getClass().getMethod("routeMain", String.class, String.class,
                    String.class, Integer.class, String.class, String.class, boolean.class)
                    .invoke(router, "GENERAL", "LOW", "standard", 128, requested, query, tools);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r; throw e;
        }
    }
    @Test void T12ComplexFirstAttemptUsesExactOauthWithoutLightInvocation() throws Exception {
        assertSame(selected, route("A와 B를 비교하고 차이를 설명해 줘", null, false));
        verify(factory, times(1)).lcWithTimeout(eq(OAUTH),any(),any(),any(),any(),eq(128),anyInt(),eq(0));
        verifyNoInteractions(base, selected);
    }
    @Test void T13ExplicitModelIsNeverReplaced() throws Exception {
        RequestedModelSelection.begin("fixture-user");
        assertSame(selected, route("A vs B 비교", "fixture-user", false));
        verify(factory).lcWithTimeout(eq("fixture-user"),any(),any(),any(),any(),eq(128),anyInt(),eq(0));
        verify(factory,never()).lcWithTimeout(eq(OAUTH),any(),any(),any(),any(),any(),anyInt(),any());
    }
    @Test void T14OneImmutableDecisionKeepsRouteAndOutputBudget() throws Exception {
        route("A vs B", null, false);
        Object decision = RequestedModelSelection.class.getMethod("mainDecision").invoke(null);
        assertEquals(OAUTH, decision.getClass().getMethod("selectedKey").invoke(decision));
        assertEquals(128, decision.getClass().getMethod("outputLimit").invoke(decision));
        assertEquals(128, RequestedModelSelection.outputLimit(OAUTH, 256));
    }
    @Test void T15GreetingAndSingleFactKeepExistingModel() throws Exception {
        assertSame(base, route("안녕?", null, false));
        RequestedModelSelection.begin(null);
        assertSame(base, route("물의 끓는점 정의", null, false));
        verifyNoInteractions(selected);
    }
    @Test void T16CodeAnalysisUsesSameDecisionBeforePromptAndTransmission() throws Exception {
        assertSame(selected, route("코드를 분석하고 오류를 찾아줘", null, false));
        Object d = RequestedModelSelection.class.getMethod("mainDecision").invoke(null);
        var dto = new com.example.lms.dto.ChatRequestDto();
        var bind = dto.getClass().getMethod("bindMainRouteDecision", d.getClass());
        bind.invoke(dto,d);
        assertSame(d, dto.getClass().getMethod("getMainRouteDecision").invoke(dto));
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/com/example/lms/service/ChatWorkflow.java"));
        assertTrue(source.indexOf("modelRouter.routeMain(") < source.indexOf("promptBuilder.build(ctx)"));
    }
    @Test void T17ThresholdBoundaryIsConsumed() throws Exception {
        ReflectionTestUtils.setField(policy,"complexityThreshold",.8);
        assertSame(base,route("A vs B",null,false));
        RequestedModelSelection.begin(null);
        ReflectionTestUtils.setField(policy,"complexityThreshold",.69);
        assertSame(selected,route("A vs B",null,false));
    }
    @Test void toolsRequiredCannotBeRemovedToFitOauth() throws Exception {
        assertSame(base,route("A vs B",null,true));
        RequestedModelSelection.begin(OAUTH);
        assertThrows(ModelSelectionException.class,()->route("A vs B",OAUTH,true));
        verifyNoInteractions(selected);
    }
}
