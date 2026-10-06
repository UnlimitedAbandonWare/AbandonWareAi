package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.domain.enums.ExecutionMode;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Reuses the existing release-gate boundary fixture without altering its contracts. */
class ChatWorkflowDynamicAutoSocialTest {
    @ParameterizedTest
    @ValueSource(strings = {"안녕?", "고마워"})
    void pureSocialAutoReturnsBeforeGenerationRetrievalOrDurableMemory(String message) {
        Object fixture = ReflectionTestUtils.invokeMethod(
                ChatWorkflowFinalVerificationReleaseGateTest.class, "memoryHoldFixture");
        ChatWorkflow workflow = ReflectionTestUtils.invokeMethod(fixture, "workflow");
        ChatModel model = ReflectionTestUtils.invokeMethod(fixture, "model");
        AtomicInteger webAttempts = new AtomicInteger();
        clearState();
        try {
            ChatRequestDto request = ChatRequestDto.builder().message(message)
                    .model("llmrouter.auto").executionMode(ExecutionMode.AUTO)
                    .useWebSearch(true).useRag(true).memoryMode("OFF").build();
            ChatResult result = workflow.continueChat(request, query -> {
                webAttempts.incrementAndGet();
                return List.of();
            });
            assertNotNull(result);
            assertFalse(result.content().isBlank());
            assertFalse(result.content().contains("모델") || result.content().contains("HOLD"));
            assertEquals("local:social", result.modelUsed(), "local reply must not claim a selected model ran");
            assertFalse(result.ragUsed());
            assertTrue(result.evidence().isEmpty());
            assertEquals(0, webAttempts.get());
            verifyNoInteractions(model);
            for (String boundary : List.of("modelRouter", "qcPreprocessor", "subjectResolver", "domainDetector",
                    "verifier", "learningWriteInterceptor", "memoryWriteInterceptor",
                    "understandAndMemorizeInterceptor", "attachmentService")) {
                Object dependency = ReflectionTestUtils.getField(workflow, boundary);
                if (dependency != null) verifyNoInteractions(dependency);
            }
        } finally {
            clearState();
        }
    }

    private static void clearState() {
        GuardContextHolder.clear();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }
}
