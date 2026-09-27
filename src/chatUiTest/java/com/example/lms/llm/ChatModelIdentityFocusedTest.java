package com.example.lms.llm;

import com.example.lms.guard.KeyResolver;
import com.example.lms.service.routing.PolicyBasedModelRouter;
import com.example.lms.service.routing.RouterPolicy;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatModelIdentityFocusedTest {
    @AfterEach void cleanup() { TraceStore.clear(); }
    @Test void wrappedModelsKeepDistinctActualIdsForCircuitKeys() {
        var factory = configuredFactory();
        var router = new PolicyBasedModelRouter(mock(ChatModel.class), null, null, mock(RouterPolicy.class), factory);
        var nativeModel = factory.lcWithTimeout("qwen3:8b", null, null, null, null, 64, 5);
        var compatModel = factory.lcWithTimeout("gemma4:26b", null, null, null, null, 64, 5);
        assertThat(router.resolveModelName(nativeModel)).isEqualTo("qwen3:8b");
        assertThat(router.resolveModelName(compatModel)).isEqualTo("gemma4:26b");
        assertThat(NightmareKeys.chatDraftKey(router.resolveModelName(nativeModel)))
                .isNotEqualTo(NightmareKeys.chatDraftKey(router.resolveModelName(compatModel)));
    }
    @Test void manualSelectionNeverEntersSharedFailoverEvenWhenEnabled() {
        var factory = configuredFactory();
        var probe = mock(HybridLlmGatewayProbeService.class);
        var failover = mock(LlmRouterAspect.class);
        when(probe.localFailoverEnabled()).thenReturn(true);
        when(probe.cloudFallbackEnabled()).thenReturn(true);
        when(probe.guardLocalModel(any(), anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        when(failover.routeLocalInference(any(), anyString(), anyString(), anyInt(),
                any(), any(), any(), any(), any(), anyString())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(factory, "localGatewayProbe", probe);
        ReflectionTestUtils.setField(factory, "localFailoverRouter", failover);
        for (String id : java.util.List.of("qwen3:8b", "gemma4:26b")) {
            RequestedModelSelection.begin(id);
            assertThat(factory.lcWithTimeout(id, null, null, null, null, 64, 5, 0)).isNotNull();
        }
        verifyNoInteractions(failover);
        verify(probe, times(2)).guardLocalModel(any(), anyString(), anyString());
    }
    private static DynamicChatModelFactory configuredFactory() {
        MockEnvironment env = new MockEnvironment().withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(env, new KeyResolver(env));
        ReflectionTestUtils.setField(factory, "defaultModelName", "gemma4:26b");
        ReflectionTestUtils.setField(factory, "localBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "judgeLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "visionLocalBaseUrl", "http://127.0.0.1:11434/v1");
        ReflectionTestUtils.setField(factory, "localApiKey", "ollama");
        ReflectionTestUtils.setField(factory, "ownerToken", "");
        ReflectionTestUtils.setField(factory, "allowedHosts", "");
        ReflectionTestUtils.setField(factory, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(factory, "requireAuthForRemote", true);
        ReflectionTestUtils.setField(factory, "dynamicMaxRetries", 0);
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);
        return factory;
    }


}
