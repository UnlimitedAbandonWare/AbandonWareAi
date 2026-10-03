package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatGptWorkflowBillingContractTest {
    private static final String ROUTE = "chatgpt-oauth:fixture-gpt";
    @AfterEach void clear() { TraceStore.clear(); com.abandonware.ai.addons.budget.TimeBudgetContext.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"local", "openai"})
    void rebuildFailureNeverRestoresOriginalEvenWithoutStrictBrowserFlag(String provider) {
        var rebuilds = new AtomicInteger(); var originalCalls = new AtomicInteger();
        var factory = mock(DynamicChatModelFactory.class, invocation -> {
            if ("lcWithTimeout".equals(invocation.getMethod().getName())) {
                rebuilds.incrementAndGet();
                assertEquals(ROUTE, invocation.getArgument(0));
                throw new IllegalStateException("synthetic registration missing");
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        var workflow = workflow(factory, provider);
        assertThrows(RuntimeException.class, () -> invoke(workflow, model(originalCalls, null)));
        assertEquals(1, rebuilds.get());
        assertEquals(0, originalCalls.get());
        assertNull(TraceStore.get("llm.endpoint.compat.mismatch"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP 503 synthetic unavailable",
            "This is not a chat model and thus not supported in the v1/chat/completions endpoint. Did you mean to use v1/completions?",
            "Unsupported parameter: max_tokens"})
    void oauthTransportFailureNeverRetriesOrChangesEndpoint(String failure) {
        var calls = new AtomicInteger(); var original = new AtomicInteger();
        var factory = mock(DynamicChatModelFactory.class, invocation -> {
            if ("lcWithTimeout".equals(invocation.getMethod().getName())) {
                assertEquals(ROUTE, invocation.getArgument(0));
                assertEquals(0, (Integer)invocation.getArgument(7));
                return model(calls, failure);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        assertThrows(RuntimeException.class, () -> invoke(workflow(factory, "local"), model(original, null)));
        assertEquals(1, calls.get());
        assertEquals(0, original.get());
        assertNull(TraceStore.get("llm.endpoint.compat.mismatch"));
    }

    @Test void successfulExplicitOauthRetainsRouteWithLocalDefault() {
        var calls = new AtomicInteger(); var original = new AtomicInteger();
        var factory = mock(DynamicChatModelFactory.class, invocation -> {
            if ("lcWithTimeout".equals(invocation.getMethod().getName())) {
                assertEquals(ROUTE, invocation.getArgument(0));
                return model(calls, null);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        assertEquals("synthetic answer", invoke(workflow(factory, "local"), model(original, null)));
        assertEquals(1, calls.get()); assertEquals(0, original.get());
    }

    private static Object invoke(ChatWorkflow workflow, ChatModel original) {
        return ReflectionTestUtils.invokeMethod(workflow, "callWithRetryReportingSuccess", original,
                List.of(UserMessage.from("synthetic OAuth request")),
                ChatRequestDto.builder().message("synthetic OAuth request").model(ROUTE).maxTokens(64).build(),
                (Consumer<Object>)ignored -> {}, false);
    }
    private static ChatWorkflow workflow(DynamicChatModelFactory factory, String provider) {
        var workflow = mock(ChatWorkflow.class, org.mockito.Answers.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "dynamicChatModelFactory", factory);
        ReflectionTestUtils.setField(workflow, "llmProvider", provider);
        ReflectionTestUtils.setField(workflow, "defaultModel", "synthetic-local");
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 2);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 3);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 5000L);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToCompletions", true);
        ReflectionTestUtils.setField(workflow, "openAiFallbackToResponses", true);
        return workflow;
    }
    private static ChatModel model(AtomicInteger count, String failure) {
        return new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                count.incrementAndGet();
                if (failure != null) throw new IllegalStateException(failure);
                return ChatResponse.builder().aiMessage(AiMessage.from("synthetic answer")).build();
            }
        };
    }
}
