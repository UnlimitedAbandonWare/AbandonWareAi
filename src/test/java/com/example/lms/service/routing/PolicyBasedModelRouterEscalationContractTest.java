package com.example.lms.service.routing;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Characterizes selection only; returning a model does not establish invocation success. */
class PolicyBasedModelRouterEscalationContractTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void adapterEscalationBypassesTheServeabilityCheckUsedByNormalPromotion(boolean highServeable) {
        ChatModel base = mock(ChatModel.class);
        ChatModel high = mock(ChatModel.class);
        ObjectProvider<ChatModel> highProvider = mock(ObjectProvider.class);
        when(highProvider.getIfAvailable(any())).thenReturn(high);
        RouterPolicy policy = mock(RouterPolicy.class);
        DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
        PolicyBasedModelRouter router = new PolicyBasedModelRouter(base, null, highProvider, policy, factory);
        ReflectionTestUtils.setField(router, "fastConfiguredModel", "synthetic-base-chat");
        ReflectionTestUtils.setField(router, "defaultConfiguredModel", "synthetic-base-chat");
        ReflectionTestUtils.setField(router, "highConfiguredModel", "synthetic-high-chat");
        RouteSignal signal = new RouteSignal(0.8, 0.7, 0.6, 0.5,
                RouteSignal.Intent.GENERAL, RouteSignal.Verbosity.NORMAL, 128,
                RouteSignal.Preference.QUALITY, "synthetic_escalation");
        when(policy.shouldPromote(signal)).thenReturn(true);
        when(factory.canServe("synthetic-high-chat")).thenReturn(highServeable);
        when(factory.canServe("synthetic-base-chat")).thenReturn(true);
        ModelRouterAdapter adapter = new ModelRouterAdapter(router);
        assertThat(adapter.resolveModelName(high)).isEqualTo("synthetic-high-chat");

        ChatModel normalSelection = adapter.route(signal);
        assertThat(normalSelection).isSameAs(highServeable ? high : base);
        verify(factory).canServe("synthetic-high-chat");
        if (!highServeable) {
            verify(factory).canServe("synthetic-base-chat");
        }
        verifyNoInteractions(base, high);
        clearInvocations(factory, policy);
        TraceStore.clear();

        ChatModel escalationSelection = adapter.escalate(signal);
        assertThat(escalationSelection).isSameAs(high);
        verifyNoInteractions(factory, policy, base, high);
        System.out.println("PM09_SELECTION_COUNTS highServeable=" + highServeable
                + " normalSelectedHigh=" + (normalSelection == high)
                + " escalationSelectedHigh=" + (escalationSelection == high)
                + " normalHighChecks=1 escalationServeabilityChecks=0 modelCalls=0");
    }
}
