package com.example.lms.service.routing;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.ModelSelectionException;
import com.example.lms.llm.RequestedModelSelection;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExactRequestedModelTest {
    @AfterEach void cleanup() { TraceStore.clear(); }
    private PolicyBasedModelRouter router(DynamicChatModelFactory factory) {
        var router = new PolicyBasedModelRouter(mock(ChatModel.class), null, null, mock(RouterPolicy.class), factory);
        ReflectionTestUtils.setField(router, "requestedModelTimeoutSeconds", 5);
        ReflectionTestUtils.setField(router, "timeoutSeconds", 5);
        return router;
    }
    @Test void explicitSelectionUsesTheExactIdAndZeroRetriesWithoutBaseRouting() {
        var factory = mock(DynamicChatModelFactory.class);
        var answer = mock(ChatModel.class);
        when(factory.canServe("fixture:chat")).thenReturn(true);
        when(factory.lcWithTimeout(eq("fixture:chat"), anyDouble(), isNull(), isNull(), isNull(), eq(128), anyInt(), eq(0))).thenReturn(answer);
        RequestedModelSelection.begin("fixture:chat");
        assertThat(router(factory).route("qa", "low", "brief", 128, "fixture:chat")).isSameAs(answer);
        verify(factory).lcWithTimeout(eq("fixture:chat"), anyDouble(), isNull(), isNull(), isNull(), eq(128), anyInt(), eq(0));
        verifyNoInteractions(answer);
        assertThat(RequestedModelSelection.outputLimit("fixture:chat", null)).isEqualTo(128);
        assertThat(TraceStore.getAll()).doesNotContainKey("chat.internal.exactModelSelection");
    }
    @Test void unavailableOrFailedManualModelNeverReturnsBaseModel() {
        var factory = mock(DynamicChatModelFactory.class);
        RequestedModelSelection.begin("fixture:chat");
        assertThatThrownBy(() -> router(factory).route("qa","low","brief",128,"fixture:chat"))
                .isInstanceOf(ModelSelectionException.class).hasMessage("provider_not_configured");
        verify(factory, never()).lcWithTimeout(anyString(), any(), any(), any(), any(), any(), anyInt(), any());
        when(factory.canServe("fixture:chat")).thenReturn(true);
        when(factory.lcWithTimeout(anyString(), any(), any(), any(), any(), any(), anyInt(), any())).thenThrow(new IllegalStateException("synthetic upstream failure"));
        assertThatThrownBy(() -> router(factory).route("qa","low","brief",128,"fixture:chat"))
                .isInstanceOf(ModelSelectionException.class).hasMessage("model_unavailable");
    }
    @Test void aNewNonManualRequestClearsThePreviousSelection() {
        RequestedModelSelection.begin("fixture:chat");
        assertThat(RequestedModelSelection.matches("fixture:chat")).isTrue();
        assertThat(RequestedModelSelection.matches("other:chat")).isFalse();
        RequestedModelSelection.begin(null);
        assertThat(RequestedModelSelection.matches("fixture:chat")).isFalse();
    }

    @Test void constructionPreservesExplicitAndClassifiedFailureReasons() {
        var failures = java.util.Map.of(
                "backend_unavailable", new RuntimeException(new ModelSelectionException("backend_unavailable")),
                "backend_timeout", new ModelSelectionException("backend_timeout"),
                "provider_unauthorized", new com.example.lms.llm.gateway.LlmGatewayException("synthetic auth",
                        com.example.lms.llm.gateway.LlmFailureClass.AUTH_MISSING),
                "quota_exceeded", new com.example.lms.llm.gateway.LlmGatewayException("synthetic quota",
                        com.example.lms.llm.gateway.LlmFailureClass.RATE_LIMIT_COOLDOWN, "insufficient_quota"));
        failures.forEach((code, failure) -> {
            var factory = mock(DynamicChatModelFactory.class);
            when(factory.canServe("fixture:chat")).thenReturn(true);
            when(factory.lcWithTimeout(anyString(), any(), any(), any(), any(), any(), anyInt(), any())).thenThrow(failure);
            RequestedModelSelection.begin("fixture:chat");
            assertThatThrownBy(() -> router(factory).route("qa", "low", "brief", 128, "fixture:chat"))
                    .isInstanceOf(ModelSelectionException.class).hasMessage(code);
            assertThat(RequestedModelSelection.outputLimit("fixture:chat", null)).isNull();
        });
    }
}
