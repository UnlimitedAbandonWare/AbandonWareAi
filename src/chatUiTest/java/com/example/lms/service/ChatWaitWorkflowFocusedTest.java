package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.TimedChatModelCaller;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Answers.CALLS_REAL_METHODS;

class ChatWaitWorkflowFocusedTest {
    private ChatWorkflow workflow() {
        var workflow = mock(ChatWorkflow.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(workflow, "llmTimeoutSeconds", 1);
        ReflectionTestUtils.setField(workflow, "requestedModelTimeoutSeconds", 1);
        ReflectionTestUtils.setField(workflow, "llmMaxAttempts", 3);
        ReflectionTestUtils.setField(workflow, "llmRetryMaxTotalMs", 10000L);
        ReflectionTestUtils.setField(workflow, "llmBackoffMs", 0L);
        ReflectionTestUtils.setField(workflow, "llmFastBailoutOnTimeoutWithEvidence", false);
        ReflectionTestUtils.setField(workflow, "llmProvider", "local");
        return workflow;
    }
    private Object call(ChatWorkflow workflow, ChatModel model, Consumer<Object> success) {
        return ReflectionTestUtils.invokeMethod(workflow, "callWithRetryReportingSuccessCore", model,
                List.of(UserMessage.from("synthetic test")),
                ChatRequestDto.builder().message("synthetic test").model("gemma4:26b")
                        .strictModelSelection(false).build(), success, false, null);
    }
    @Test void evidenceCannotCauseReplayWhileTimedOutWorkerStillRuns() {
        var workflow = workflow();
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        ChatModel blocked = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                calls.incrementAndGet();
                try { release.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ChatResponse.builder().aiMessage(AiMessage.from("late answer")).build();
            }
        };
        TraceStore.put("chat.evidence.present", true);
        try {
            var failure = assertThrows(RuntimeException.class, () -> call(workflow, blocked, ignored -> {}));
            assertTrue(TimedChatModelCaller.isHardTimeout(failure));
            assertEquals(1, calls.get());
            assertEquals("not_observed", TraceStore.get("llm.call.timeout.workerTerminationEvidence"));
        } finally { release.countDown(); TimeBudgetContext.clear(); TraceStore.clear(); }
    }
    @Test void providerReadTimeoutCannotTriggerAnotherLocalGeneration() {
        var calls = new AtomicInteger();
        ChatModel timedOut = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                calls.incrementAndGet();
                throw new RuntimeException(new java.net.SocketTimeoutException("synthetic read timeout"));
            }
        };
        TraceStore.put("chat.evidence.present", true);
        try {
            assertThrows(RuntimeException.class, () -> call(workflow(), timedOut, ignored -> {}));
            assertEquals(1, calls.get(), "transport timeout must not start another physical request");
        } finally { TimeBudgetContext.clear(); TraceStore.clear(); }
    }

    @Test void failedCloudAdapterCannotRestartTheLocalPrimary() {
        var localCalls = new AtomicInteger();
        var cloudCalls = new AtomicInteger();
        ChatModel local = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                localCalls.incrementAndGet();
                throw new RuntimeException(new java.net.SocketTimeoutException("synthetic local timeout"));
            }
        };
        ChatModel cloud = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                cloudCalls.incrementAndGet();
                throw new IllegalArgumentException("synthetic unsupported request");
            }
        };
        var routed = new com.example.lms.llm.gateway.FallbackAwareChatModel(local,
                (failure, used) -> new com.example.lms.llm.gateway.FallbackAwareChatModel.ResolvedFallback(
                        cloud, "api3", null), null, null, "local", null, null, null, 1);
        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> call(workflow(), routed, ignored -> {}));
            assertTrue(com.example.lms.llm.gateway.LlmGatewayFailureClassifier.hasNonReplayableReason(failure));
            assertEquals(1, localCalls.get());
            assertEquals(1, cloudCalls.get());
        } finally { TimeBudgetContext.clear(); TraceStore.clear(); }
    }

    @Test void successfulFallbackReportsRespondingModel() {
        ChatModel response = new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from("answer"))
                        .metadata(ChatResponseMetadata.builder().modelName("configured-cloud-model").build()).build();
            }
        };
        AtomicReference<Object> success = new AtomicReference<>();
        try {
            assertEquals("answer", call(workflow(), response, success::set));
            assertEquals("configured-cloud-model", ReflectionTestUtils.invokeMethod(success.get(), "modelId"));
        } finally { TimeBudgetContext.clear(); TraceStore.clear(); }
    }
}
