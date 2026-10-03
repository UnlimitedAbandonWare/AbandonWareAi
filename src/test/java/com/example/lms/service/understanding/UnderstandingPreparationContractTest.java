package com.example.lms.service.understanding;

import com.example.lms.dto.answer.AnswerUnderstanding;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.service.chat.FinalizedMemoryPersistence;
import com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnderstandingPreparationContractTest {
    @Test
    void prepareIsSideEffectFreeAndExactCommitDoesNotGenerateAgain() {
        var service = mock(AnswerUnderstandingService.class);
        var memory = mock(MemoryReinforcementService.class);
        var history = mock(ChatHistoryService.class);
        var emitter = mock(ChatStreamEmitter.class);
        var interceptor = new UnderstandAndMemorizeInterceptor(service, memory, emitter, history);
        ReflectionTestUtils.setField(interceptor, "globalEnabled", true);
        var summary = new AnswerUnderstanding("synthetic summary", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), 0.9);
        when(service.understand("answer", "question")).thenReturn(summary);
        var prepared = interceptor.prepare("question", "answer", true);
        assertSame(summary, prepared);
        verifyNoInteractions(memory, history, emitter);
        var registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var run = registry.beginOrJoin(9102L).context();
        try {
            FinalizedMemoryPersistence.persist(run, CancellationException::new, (stage, failure) -> fail(failure),
                    new FinalizedMemoryPersistence.Stage("understanding",
                            () -> interceptor.commitPrepared("chat-9102", "question", prepared, run)));
            verify(service, times(1)).understand("answer", "question");
            verify(memory).reinforceWithSnippet("chat-9102", "question", "synthetic summary", "UNDERSTANDING", 0.9);
            verify(history).appendMessage(eq(9102L), eq("system"), startsWith("⎔USUM⎔"));
            verify(emitter).emitUnderstanding(run, summary);
            verify(emitter, never()).emitUnderstanding(anyString(), any());
        } finally { ReflectionTestUtils.invokeMethod(registry, "shutdown"); }
    }

    @Test
    void disabledAndBlankPreparationDoNotTouchAnyDownstreamService() {
        var service = mock(AnswerUnderstandingService.class);
        var memory = mock(MemoryReinforcementService.class);
        var history = mock(ChatHistoryService.class);
        var emitter = mock(ChatStreamEmitter.class);
        var interceptor = new UnderstandAndMemorizeInterceptor(service, memory, emitter, history);
        try {
            assertNull(interceptor.prepare("question", "answer", true));
            ReflectionTestUtils.setField(interceptor, "globalEnabled", true);
            assertNull(interceptor.prepare("question", "answer", false));
            assertNull(interceptor.prepare("question", " ", true));
            interceptor.commitPrepared("chat-9102", "question", null, null);
            verifyNoInteractions(service, memory, history, emitter);
        } finally { com.example.lms.search.TraceStore.clear(); }
    }
}
