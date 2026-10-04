package com.example.lms.llm;

import com.example.lms.agent.GroqFreeTierGuard;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.search.TraceStore;
import com.example.lms.util.TokenCounter;
import dev.langchain4j.data.message.UserMessage;
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

    @Test void quotaReasonAndTokenReservationArePreservedWithoutNetwork() throws Exception {
        when(guard.reserve(anyString(), anyString(), anyLong(), eq(0L))).thenThrow(new IOException("groq_tpm_exhausted"));
        String body = "{\"model\":\"openai/gpt-oss-120b\",\"max_tokens\":512,\"messages\":[]}";
        var failure = assertThrows(LlmGatewayException.class, () -> reserve(body));
        assertEquals("groq_tpm_exhausted", failure.reasonCode());
        assertEquals("rate_limited", ModelSelectionException.failure(failure).code());
        verify(guard).reserve(eq("openai/gpt-oss-120b"), eq(""), eq(TokenCounter.estimateTextChatInput(java.util.List.of())+512), eq(0L));
        assertEquals(512L, TraceStore.get("llm.groq.admission.outputLimit"));
    }

    @Test void textWithinTokenBudgetIsNotRejectedBecauseOfJsonByteSize() throws Exception {
        String text = "evidence ".repeat(1200);
        String body = "{\"model\":\"openai/gpt-oss-120b\",\"max_completion_tokens\":1024,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + text + "\"}]}";
        long estimate = TokenCounter.estimateTextChatInput(java.util.List.of(UserMessage.from(text))) + 1024;
        assertTrue(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1024 > 8000);
        assertTrue(estimate < 8000);
        when(guard.reserve(anyString(), anyString(), anyLong(), eq(0L))).thenAnswer(invocation -> {
            if (invocation.<Long>getArgument(2) > 8000) throw new IOException("groq_tpm_exhausted");
            return null;
        });
        assertDoesNotThrow(() -> reserve(body));
        verify(guard).reserve(eq("openai/gpt-oss-120b"), eq(""), eq(estimate), eq(0L));
    }

    @Test void textPartsUseTheExistingMessageTokenEstimator() {
        String body = "{\"model\":\"openai/gpt-oss-120b\",\"max_tokens\":128,\"messages\":["
                + "{\"role\":\"system\",\"content\":\"answer briefly\"},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}]}";
        assertDoesNotThrow(() -> reserve(body));
        long estimate = TokenCounter.estimateTextChatInput(java.util.List.of(
                dev.langchain4j.data.message.SystemMessage.from("answer briefly"), UserMessage.from("hello"))) + 128;
        try { verify(guard).reserve(eq("openai/gpt-oss-120b"), eq(""), eq(estimate), eq(0L)); }
        catch (IOException unexpected) { fail(unexpected); }
    }

    @Test void unsupportedContentAndToolsNeverEnterReservation() {
        for (String message : java.util.List.of(
                "{\"role\":\"tool\",\"content\":\"result\"}",
                "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"synthetic\"}}]}",
                "{\"role\":\"assistant\",\"content\":\"answer\",\"tool_calls\":[{}]}",
                "{\"role\":\"system\",\"content\":\"\"}",
                "{\"role\":\"user\",\"content\":[]}")) {
            var failure = assertThrows(LlmGatewayException.class, () -> reserve(
                    "{\"model\":\"openai/gpt-oss-120b\",\"max_tokens\":128,\"messages\":[" + message + "]}"));
            assertEquals("groq_request_dimensions_invalid", failure.reasonCode());
        }
        verifyNoInteractions(guard);
    }

    @Test void arbitraryIoTextAndMalformedJsonNeverReachPublicReasons() throws Exception {
        when(guard.reserve(anyString(), anyString(), anyLong(), eq(0L))).thenThrow(new IOException("synthetic-private-path"));
        var failure = assertThrows(LlmGatewayException.class,
                () -> reserve("{\"model\":\"openai/gpt-oss-120b\",\"max_completion_tokens\":128,\"messages\":[]}"));
        assertEquals("groq_free_admission_denied", failure.reasonCode());
        assertFalse(failure.toString().contains("synthetic-private-path"));
        assertNull(failure.getCause());
        assertThrows(LlmGatewayException.class, () -> reserve("{invalid"));
        assertFalse(TraceStore.getAll().toString().contains("synthetic-private-path"));
    }
}
