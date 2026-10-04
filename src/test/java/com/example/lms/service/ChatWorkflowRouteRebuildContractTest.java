package com.example.lms.service;

import com.example.lms.dto.GenerationObservation;
import com.example.lms.llm.NamedChatModel;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatWorkflowRouteRebuildContractTest {
    @AfterEach void clear() { TraceStore.clear(); }

    @Test void rebuildReceiptKeepsSelectedAndRetainedClientIdentity() {
        NamedChatModel client = mock(NamedChatModel.class);
        when(client.resolvedModelName()).thenReturn("actual:fixture");
        ReflectionTestUtils.invokeMethod(ChatWorkflow.class, "recordModelRebuildFallback",
                client, "selected:fixture", new LlmGatewayException("route missing", LlmFailureClass.DISABLED, "route_unknown"));
        var receipt = (Map<?, ?>) TraceStore.get("llm.call.model.rebuildFallback");
        assertEquals("selected:fixture", receipt.get("requestedModelName"));
        assertEquals("actual:fixture", receipt.get("actualModelName"));
        assertEquals("route_unknown", receipt.get("reason"));
        assertEquals(receipt, com.example.lms.trace.SafeRedactor.diagnosticValue("llm.call.model.rebuildFallback", receipt));
    }

    @Test void successfulResponseKeepsObservedModelAndCarriesFallbackMetadata() {
        TraceStore.put("llm.call.model.rebuildFallback", Map.of("reason", "route_unknown"));
        var observed = new GenerationObservation("groq", "actual-response", "api3", 2, null, null);
        GenerationObservation decorated = ReflectionTestUtils.invokeMethod(ChatWorkflow.class, "withModelRebuildFallback", observed);
        assertEquals("actual-response", decorated.observedModel());
        assertEquals("groq", decorated.observedProvider());
        assertEquals("api3", decorated.routeId());
        assertEquals(3, decorated.fallbackCount());
        assertEquals("route_unknown", decorated.fallbackReason());
        decorated.publish();
        assertEquals("actual-response", GenerationObservation.from(TraceStore.getAll()).observedModel());
        assertEquals("route_unknown", GenerationObservation.from(TraceStore.getAll()).fallbackReason());
    }

    @Test void fallbackNeverInventsAResponseModelWhenItIsMissing() {
        TraceStore.put("llm.call.model.rebuildFallback", Map.of("reason", "route_unknown"));
        var missing = new GenerationObservation(null, null, null, 0, null, "response_not_observed");
        GenerationObservation decorated = ReflectionTestUtils.invokeMethod(ChatWorkflow.class, "withModelRebuildFallback", missing);
        assertNull(decorated.observedModel());
        assertNull(decorated.observedProvider());
        assertEquals("response_not_observed", decorated.observedReason());
    }
}
