package com.example.lms.llm;

import com.example.lms.agent.GroqFreeTierGuard;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.search.TraceStore;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRuntimeGroqAdmissionTest {
    @AfterEach void clear() { TraceStore.clear(); }
    private final ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
    private final GroqFreeTierGuard guard = mock(GroqFreeTierGuard.class);

    private Object reserve(String body) {
        ReflectionTestUtils.setField(tracker, "groqFreeTierGuard", guard);
        return ReflectionTestUtils.invokeMethod(tracker, "reserveGroq", HttpRequest.builder()
                .method(HttpMethod.POST).url("https://api.groq.com/openai/v1/chat/completions")
                .body(body).build());
    }

    @Test void missingOutputBoundNeverEntersReservationOrTransport() {
        var failure = assertThrows(LlmGatewayException.class, () -> reserve("{\"model\":\"openai/gpt-oss-120b\"}"));
        assertEquals("groq_output_bound_required", failure.reasonCode());
        assertEquals("model_request_invalid", ModelSelectionException.failure(failure).code());
        verifyNoInteractions(guard);
        assertEquals(0L, TraceStore.get("llm.groq.admission.outputLimit"));
    }

    @Test void quotaReasonAndConservativeAmountArePreservedWithoutNetwork() throws Exception {
        when(guard.reserve(anyString(), anyString(), anyLong(), eq(0L))).thenThrow(new IOException("groq_tpm_exhausted"));
        String body = "{\"model\":\"openai/gpt-oss-120b\",\"max_tokens\":512,\"messages\":[]}";
        var failure = assertThrows(LlmGatewayException.class, () -> reserve(body));
        assertEquals("groq_tpm_exhausted", failure.reasonCode());
        assertEquals("rate_limited", ModelSelectionException.failure(failure).code());
        verify(guard).reserve(eq("openai/gpt-oss-120b"), eq(""), eq((long)body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+512), eq(0L));
        assertEquals(512L, TraceStore.get("llm.groq.admission.outputLimit"));
    }

    @Test void arbitraryIoTextAndMalformedJsonNeverReachPublicReasons() throws Exception {
        when(guard.reserve(anyString(), anyString(), anyLong(), eq(0L))).thenThrow(new IOException("synthetic-private-path"));
        var failure = assertThrows(LlmGatewayException.class,
                () -> reserve("{\"model\":\"openai/gpt-oss-120b\",\"max_completion_tokens\":128}"));
        assertEquals("groq_free_admission_denied", failure.reasonCode());
        assertFalse(failure.toString().contains("synthetic-private-path"));
        assertNull(failure.getCause());
        assertThrows(LlmGatewayException.class, () -> reserve("{invalid"));
        assertFalse(TraceStore.getAll().toString().contains("synthetic-private-path"));
    }
}
