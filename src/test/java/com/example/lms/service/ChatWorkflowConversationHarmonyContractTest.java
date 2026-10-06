package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.guard.ConversationFrameResolver;

class ChatWorkflowConversationHarmonyContractTest {
    @Test void requiredNativeRouteVerifiesActualResponseModelAndPreservesGrounding() throws Throwable {
        var fixture = Class.forName("ai.abandonware.nova.orch.aop.LlmRouterGatewaySecurityTest");
        var env = org.springframework.test.util.ReflectionTestUtils.<org.springframework.mock.env.MockEnvironment>invokeMethod(fixture, "baseEnv");
        var props = org.springframework.test.util.ReflectionTestUtils.<ai.abandonware.nova.config.LlmRouterProperties>invokeMethod(
            fixture, "props", "gemini-pro", "gemini-3.8-flash", "http://127.0.0.1:65534/v1beta/openai");
        var config = props.getModels().get("gemini-pro");
        config.setProvider("gemini");
        config.setResponseModelAliases(List.of("gemini-3.8-flash-approved-fixture"));
        var gateway = org.mockito.Mockito.mock(com.example.lms.learning.gemini.GeminiGateway.class);
        var delegated = org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        org.mockito.Mockito.when(gateway.buildOpenAiCompatibleChatModel(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(true),
            org.mockito.ArgumentMatchers.eq(true))).thenReturn(delegated);
        var status = new com.example.lms.learning.gemini.GeminiGateway.ProviderStatus("gemini", "router", "gemini-3.8-flash",
            true, true, 0, null, 0, false, "not-attempted", "", "");
        org.mockito.Mockito.when(gateway.latestStatus()).thenReturn(status);
        org.springframework.beans.factory.ObjectProvider<com.example.lms.guard.KeyResolver> keys = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var aspect = new ai.abandonware.nova.orch.aop.LlmRouterAspect(env, props, new ai.abandonware.nova.orch.router.LlmRouterBandit(props),
            new ai.abandonware.nova.config.NovaModelGuardProperties(), keys, null, null,
            new com.example.lms.llm.gateway.LlmGatewayFailureClassifier(), null, gateway);
        var pjpConstructor = Class.forName(fixture.getName() + "$FakePjp").getDeclaredConstructor(Object.class, Object[].class);
        pjpConstructor.setAccessible(true);
        var metadataConstructor = com.example.lms.learning.gemini.GeminiGateway.GroundedChatMetadata.class.getDeclaredConstructor(
            com.example.lms.learning.gemini.GeminiGateway.GenerationResult.class);
        metadataConstructor.setAccessible(true);
        try {
            for (String actual : List.of("gemini-2.5-flash", "", "gemini-3.8-flash", "gemini-3.8-flash-approved-fixture")) {
                com.example.lms.search.TraceStore.clear();
                com.example.lms.llm.RequestedModelSelection.begin("llmrouter.gemini-pro");
                var generation = new com.example.lms.learning.gemini.GeminiGateway.GenerationResult("synthetic original", status,
                    null, true, List.of("synthetic original"), actual);
                var response = dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(generation.text()))
                    .metadata(metadataConstructor.newInstance(generation)).build();
                org.mockito.Mockito.when(delegated.chat(org.mockito.ArgumentMatchers.anyList())).thenReturn(response);
                org.mockito.Mockito.when(delegated.chat(org.mockito.ArgumentMatchers.any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(response);
                var pjp = (org.aspectj.lang.ProceedingJoinPoint) pjpConstructor.newInstance(delegated,
                    new Object[]{"llmrouter.gemini-pro", 0.2, 0.9, null, null, 500, 60, 0, null, null, true, true});
                var routed = (dev.langchain4j.model.chat.ChatModel) aspect.aroundLcWithTimeout(pjp);
                if (actual.equals("gemini-2.5-flash") || actual.isEmpty()) {
                    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> routed.chat("synthetic"));
                } else {
                    org.junit.jupiter.api.Assertions.assertSame(response.metadata(), routed.chat(List.of(dev.langchain4j.data.message.UserMessage.from("synthetic"))).metadata());
                }
            }
            org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(4)).buildOpenAiCompatibleChatModel(
                org.mockito.ArgumentMatchers.argThat(spec -> spec.model().equals("gemini-3.8-flash")),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(true));
        } finally {ai.abandonware.nova.orch.router.LlmRouterContext.clear(); com.example.lms.search.TraceStore.clear();}
    }
    @Test void actualRouterDeliversFocusPermissionToSelectedGeminiGateway() throws Throwable {
        var fixture=Class.forName("ai.abandonware.nova.orch.aop.LlmRouterGatewaySecurityTest");
        var env=org.springframework.test.util.ReflectionTestUtils.<org.springframework.mock.env.MockEnvironment>invokeMethod(fixture,"baseEnv");
        var props=org.springframework.test.util.ReflectionTestUtils.<ai.abandonware.nova.config.LlmRouterProperties>invokeMethod(fixture,"props","gemini-pro","gemini-selected-fixture","http://127.0.0.1:65534/v1beta/openai");
        props.getModels().get("gemini-pro").setProvider("gemini");
        var gateway=org.mockito.Mockito.mock(com.example.lms.learning.gemini.GeminiGateway.class);
        var delegated=org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        org.mockito.Mockito.when(gateway.buildOpenAiCompatibleChatModel(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(false),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq(true))).thenReturn(delegated);
        org.mockito.Mockito.when(gateway.latestStatus()).thenReturn(new com.example.lms.learning.gemini.GeminiGateway.ProviderStatus("gemini","router","gemini-selected-fixture",true,true,0,null,0,false,"not-attempted","",""));
        org.springframework.beans.factory.ObjectProvider<com.example.lms.guard.KeyResolver> keys=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var aspect=new ai.abandonware.nova.orch.aop.LlmRouterAspect(env,props,new ai.abandonware.nova.orch.router.LlmRouterBandit(props),new ai.abandonware.nova.config.NovaModelGuardProperties(),keys,null,null,new com.example.lms.llm.gateway.LlmGatewayFailureClassifier(),null,gateway);
        var pjpType=Class.forName(fixture.getName()+"$FakePjp");var constructor=pjpType.getDeclaredConstructor(Object.class,Object[].class);constructor.setAccessible(true);
        var pjp=(org.aspectj.lang.ProceedingJoinPoint)constructor.newInstance(delegated,new Object[]{"llmrouter.gemini-pro",0.2,0.9,null,null,500,60,0,null,null,true});
        try {
            aspect.aroundLcWithTimeout(pjp);
            var spec=org.mockito.ArgumentCaptor.forClass(com.example.lms.learning.gemini.GeminiGateway.RouterSpec.class);
            org.mockito.Mockito.verify(gateway).buildOpenAiCompatibleChatModel(spec.capture(),org.mockito.ArgumentMatchers.eq(false),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq(true));
            org.junit.jupiter.api.Assertions.assertEquals("gemini-selected-fixture",spec.getValue().model());
        }finally{ai.abandonware.nova.orch.router.LlmRouterContext.clear();com.example.lms.search.TraceStore.clear();}
    }
    @Test void focusPermissionSurvivesRetryClamping() throws Exception {
        var type=Class.forName("ai.abandonware.nova.orch.aop.LlmRouterAspect$CallArgs");
        var parse=type.getDeclaredMethod("parse",Object[].class);parse.setAccessible(true);
        var copy=ai.abandonware.nova.orch.aop.LlmRouterAspect.class.getDeclaredMethod("withoutLibraryRetries",type);copy.setAccessible(true);
        for(boolean allowed:List.of(false,true)){
            var args=parse.invoke(null,(Object)new Object[]{"gemini-fixture",0.2,0.9,null,null,500,60,2,null,null,allowed});
            var field=type.getDeclaredField("focusGoogleSearchAllowed");field.setAccessible(true);
            org.junit.jupiter.api.Assertions.assertEquals(allowed,field.get(copy.invoke(null,args)));
            var requiredArgs=parse.invoke(null,(Object)new Object[]{"gemini-fixture",0.2,0.9,null,null,500,60,2,null,null,allowed,true});
            var required=type.getDeclaredField("requireNativeGoogleSearch");required.setAccessible(true);
            org.junit.jupiter.api.Assertions.assertTrue((boolean)required.get(copy.invoke(null,requiredArgs)));
        }
    }
    @Test void actualPrimaryModelResponseCarriesGroundingOnlyInServerFocusContext() throws Exception {
        var workflow=org.springframework.test.util.ReflectionTestUtils.<ChatWorkflow>invokeMethod(ChatWorkflowProjectionDefaultProfileTest.class,"workflowFixture");
        var router=(com.example.lms.service.routing.ModelRouter)org.springframework.test.util.ReflectionTestUtils.getField(workflow,"modelRouter");
        var model=org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        var metadata=new com.example.lms.learning.gemini.GeminiGateway.GroundingMetadata(List.of("query"),List.of(),List.of(),
                new com.example.lms.learning.gemini.GeminiGateway.SearchEntryPoint("<div>Suggestions</div>"));
        var generation=new com.example.lms.learning.gemini.GeminiGateway.GenerationResult("synthetic original",null,metadata,true,List.of("synthetic original"),"gemini-fixture");
        var constructor=com.example.lms.learning.gemini.GeminiGateway.GroundedChatMetadata.class.getDeclaredConstructor(com.example.lms.learning.gemini.GeminiGateway.GenerationResult.class);
        constructor.setAccessible(true);
        var response=dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from(generation.text())).metadata(constructor.newInstance(generation)).build();
        org.mockito.Mockito.when(model.chat(org.mockito.ArgumentMatchers.anyList())).thenReturn(response);
        org.mockito.Mockito.when(router.route(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString())).thenReturn(model);
        org.mockito.Mockito.when(router.resolveModelName(model)).thenReturn("gemini-fixture");
        org.mockito.Mockito.when(router.routeMain(org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.nullable(Integer.class),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(model);
        var factory=org.mockito.Mockito.mock(com.example.lms.llm.DynamicChatModelFactory.class);
        org.mockito.Mockito.when(factory.lcWithTimeout(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.nullable(Double.class),org.mockito.ArgumentMatchers.nullable(Double.class),
            org.mockito.ArgumentMatchers.nullable(Double.class),org.mockito.ArgumentMatchers.nullable(Double.class),org.mockito.ArgumentMatchers.nullable(Integer.class),org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),org.mockito.ArgumentMatchers.nullable(com.example.lms.llm.spec.ModelSpecSnapshot.class),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq(true))).thenReturn(model);
        org.springframework.test.util.ReflectionTestUtils.setField(workflow,"dynamicChatModelFactory",factory);
        org.mockito.Mockito.when(factory.lcWithTimeout(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.nullable(Double.class),org.mockito.ArgumentMatchers.nullable(Double.class),
            org.mockito.ArgumentMatchers.nullable(Double.class),org.mockito.ArgumentMatchers.nullable(Double.class),org.mockito.ArgumentMatchers.nullable(Integer.class),org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.nullable(Integer.class),org.mockito.ArgumentMatchers.nullable(com.example.lms.llm.spec.ModelSpecSnapshot.class))).thenReturn(model);
        try {
            var request=com.example.lms.dto.ChatRequestDto.builder().message("aurora lattice").model("gemini-fixture").maxTokens(256).mode("FACT")
                .memoryMode("EPHEMERAL").searchMode(com.example.lms.gptsearch.dto.SearchMode.OFF).useWebSearch(false).useRag(false).useVerification(false).build();
            var context=ChatConversationContext.empty().withFocusGoogleSearch(true);
            assertFalse(context.focusGoogleSearchAllowed());
            context=new ChatConversationContext(List.of(),"",List.of(),true,List.of(),List.of(),400).withFocusGoogleSearch(true);
            var result=workflow.continueChat(request,ignored->List.of(),context);
            org.junit.jupiter.api.Assertions.assertNotNull(result.grounding(),"model="+result.modelUsed()+", primary="+com.example.lms.search.TraceStore.get("conversation.frame.primaryModelCallCount")+", factoryCalls="+org.mockito.Mockito.mockingDetails(factory).getInvocations().stream().map(call->call.getMethod().getName()+":"+call.getArguments().length).toList());
            org.junit.jupiter.api.Assertions.assertEquals("synthetic original",result.grounding().originalText());
            org.junit.jupiter.api.Assertions.assertEquals("synthetic original",result.content());
            org.junit.jupiter.api.Assertions.assertEquals("gemini-fixture",result.grounding().model());
            var ordinary=workflow.continueChat(request,ignored->List.of());
            org.junit.jupiter.api.Assertions.assertNull(ordinary.grounding(),"ordinary public route must discard Focus-only metadata");
        } finally {com.example.lms.service.guard.GuardContextHolder.clear();com.abandonware.ai.addons.budget.TimeBudgetContext.clear();com.example.lms.search.TraceStore.clear();}
    }

    @Test
    void offShadowAndEnforcedStandardPreserveLegacyOptionalPaths() {
        for (ConversationFrameV1 frame : List.of(
                frame(ConversationFrameV1.Mode.OFF, ConversationFrameV1.Stance.REPAIR),
                frame(ConversationFrameV1.Mode.SHADOW, ConversationFrameV1.Stance.REPAIR),
                frame(ConversationFrameV1.Mode.ENFORCE, ConversationFrameV1.Stance.STANDARD))) {
            assertTrue(ChatWorkflow.allowsConversationShortCircuit(true, frame), frame.toString());
            assertTrue(ChatWorkflow.allowsConversationRefinement(true, frame), frame.toString());
            assertTrue(ChatWorkflow.allowsConversationExpansion(frame), frame.toString());
            assertFalse(ChatWorkflow.deniesConversationMemoryWrite(false, frame), frame.toString());
        }
    }

    @Test
    void enforcedNonStandardStancesSuppressOptionalPathsAndMemoryWrites() {
        for (ConversationFrameV1.Stance stance : List.of(
                ConversationFrameV1.Stance.REPAIR,
                ConversationFrameV1.Stance.SUPPORTIVE_CHECK_IN,
                ConversationFrameV1.Stance.SAFETY_FIRST)) {
            ConversationFrameV1 frame = frame(ConversationFrameV1.Mode.ENFORCE, stance);

            assertFalse(ChatWorkflow.allowsConversationShortCircuit(true, frame), stance.name());
            assertFalse(ChatWorkflow.allowsConversationRefinement(true, frame), stance.name());
            assertFalse(ChatWorkflow.allowsConversationExpansion(frame), stance.name());
            assertTrue(ChatWorkflow.deniesConversationMemoryWrite(false, frame), stance.name());
        }
    }

    @Test
    void existingInteractionAndFeatureGatesRemainAuthoritative() {
        ConversationFrameV1 standard = frame(
                ConversationFrameV1.Mode.ENFORCE,
                ConversationFrameV1.Stance.STANDARD);

        assertFalse(ChatWorkflow.allowsConversationShortCircuit(false, standard));
        assertFalse(ChatWorkflow.allowsConversationRefinement(false, standard));
        assertTrue(ChatWorkflow.deniesConversationMemoryWrite(true, standard));
    }

    @Test
    void nullFrameUsesTheSafeOffCompatibilityDefault() {
        assertTrue(ChatWorkflow.allowsConversationShortCircuit(true, null));
        assertTrue(ChatWorkflow.allowsConversationRefinement(true, null));
        assertTrue(ChatWorkflow.allowsConversationExpansion(null));
        assertFalse(ChatWorkflow.deniesConversationMemoryWrite(false, null));
    }

    @Test
    void wireCoverageRequiresAnObservedAttemptLedgerRow() {
        assertFalse(ChatWorkflow.hasObservedConversationWireAttempt(List.of()));
        assertFalse(ChatWorkflow.hasObservedConversationWireAttempt(List.of(
                Map.of("providerAttemptObserved", true, "wireAttemptObserved", false))));
        assertTrue(ChatWorkflow.hasObservedConversationWireAttempt(List.of(
                Map.of("wireAttemptObserved", true))));
    }

    @Test
    void missingResolverInLegacyManualFixtureUsesTheDeterministicFallback() {
        ConversationFrameV1 off = ChatWorkflow.resolveConversationFrame(
                null,
                "그만해",
                false,
                ConversationFrameV1.Mode.OFF);
        ConversationFrameV1 enforce = ChatWorkflow.resolveConversationFrame(
                new ConversationFrameResolver(),
                "그만해",
                false,
                ConversationFrameV1.Mode.ENFORCE);

        assertTrue(off.mode() == ConversationFrameV1.Mode.OFF);
        assertTrue(enforce.stance() == ConversationFrameV1.Stance.REPAIR);
    }

    private static ConversationFrameV1 frame(
            ConversationFrameV1.Mode mode,
            ConversationFrameV1.Stance stance) {
        return new ConversationFrameV1(
                mode,
                stance,
                ConversationFrameV1.LightweightRole.OBSERVE_ONLY,
                false,
                false,
                false,
                ConversationFrameV1.ReasonCode.DEFAULT);
    }
}
