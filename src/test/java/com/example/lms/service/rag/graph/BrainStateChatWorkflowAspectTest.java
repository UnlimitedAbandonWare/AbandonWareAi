package com.example.lms.service.rag.graph;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrainStateChatWorkflowAspectTest {

    @Test
    void proceedsOnceAndCapturesReturnedAnswer() throws Throwable {
        TraceStore.clear();
        GuardContextHolder.clear();
        TraceStore.put("finalAnswer.memorySaveAllowed", true);
        TraceStore.put("brain.test.contextHash", "ctx-hash-7");
        GuardContext expectedGuard = new GuardContext();
        expectedGuard.setOfficialOnly(true);
        GuardContextHolder.set(expectedGuard);
        BrainStateProperties props = new BrainStateProperties();
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch taskCompleted = new CountDownLatch(1);
        AtomicReference<Object> seenTrace = new AtomicReference<>();
        AtomicReference<GuardContext> seenGuard = new AtomicReference<>();
        doAnswer(invocation -> {
            seenTrace.set(TraceStore.get("brain.test.contextHash"));
            seenGuard.set(GuardContextHolder.get());
            captured.countDown();
            return null;
        }).when(service).ingestConversationTurn("7", "hello", "answer");
        Executor cleanWorker = command -> new Thread(() -> {
            try {
                command.run();
            } finally {
                taskCompleted.countDown();
            }
        }, "brain-capture-test-worker").start();
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service, cleanWorker);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatRequestDto req = ChatRequestDto.builder().sessionId(7L).message("hello").build();
        ChatResult result = ChatResult.of("answer", "model", true);
        when(pjp.getArgs()).thenReturn(new Object[]{req, null});
        when(pjp.proceed()).thenReturn(result);

        Object out = aspect.captureConversationTurn(pjp);

        assertSame(result, out);
        verify(pjp, times(1)).proceed();
        assertTrue(captured.await(5, TimeUnit.SECONDS), "brain capture did not complete");
        assertTrue(taskCompleted.await(5, TimeUnit.SECONDS), "brain capture terminal state was not recorded");
        assertEquals("ctx-hash-7", seenTrace.get());
        assertSame(expectedGuard, seenGuard.get());
        assertEquals("succeeded", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
        assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void asyncFailurePreservesContextAndShutdownRejectsFurtherCapture() throws Throwable {
        TraceStore.clear();
        GuardContextHolder.clear();
        AtomicReference<Thread> ownedThread = new AtomicReference<>();
        AtomicReference<Thread> observedThread = new AtomicReference<>();
        AtomicReference<Object> observedTrace = new AtomicReference<>();
        AtomicReference<GuardContext> observedGuard = new AtomicReference<>();
        CountDownLatch taskCompleted = new CountDownLatch(1);
        java.util.concurrent.ExecutorService ownedExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "brain-capture-failure-test-worker");
            worker.setDaemon(true);
            ownedThread.set(worker);
            return worker;
        });
        Executor controlledWorker = command -> ownedExecutor.execute(() -> {
            try {
                command.run();
            } finally {
                taskCompleted.countDown();
            }
        });
        try {
            TraceStore.put("finalAnswer.memorySaveAllowed", true);
            TraceStore.put("brain.test.contextHash", "ctx-hash-async-failure");
            GuardContext expectedGuard = new GuardContext();
            expectedGuard.setOfficialOnly(true);
            GuardContextHolder.set(expectedGuard);
            GraphRagChunkingService service = mock(GraphRagChunkingService.class);
            doAnswer(invocation -> {
                observedThread.set(Thread.currentThread());
                observedTrace.set(TraceStore.get("brain.test.contextHash"));
                observedGuard.set(GuardContextHolder.get());
                throw new IllegalStateException("synthetic-brain-capture-failure");
            }).when(service).ingestConversationTurn("73", "synthetic-user-capture", "synthetic-answer-capture");
            BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(
                    new BrainStateProperties(), service, controlledWorker);
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            ChatRequestDto request = ChatRequestDto.builder().sessionId(73L).message("synthetic-user-capture").build();
            ChatResult result = ChatResult.of("synthetic-answer-capture", "model", true);
            when(pjp.getArgs()).thenReturn(new Object[]{request, null});
            when(pjp.proceed()).thenReturn(result);

            assertSame(result, aspect.captureConversationTurn(pjp));
            assertTrue(taskCompleted.await(5, TimeUnit.SECONDS), "async failure did not reach terminal completion");
            assertSame(ownedThread.get(), observedThread.get());
            assertFalse(Thread.currentThread() == observedThread.get());
            assertEquals("ctx-hash-async-failure", observedTrace.get());
            assertSame(expectedGuard, observedGuard.get());
            assertEquals("failed", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
            assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
            assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.brainState.capture.failed"));
            assertEquals("IllegalStateException", TraceStore.get("retrieval.kg.brainState.capture.failureClass"));
            assertEquals("skip_chat_capture", TraceStore.get("retrieval.kg.brainState.capture.fallback"));
            assertEquals(BrainStateText.hash12("73"), TraceStore.get("retrieval.kg.brainState.capture.sessionHash"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("synthetic-brain-capture-failure"));
            assertFalse(trace.contains("synthetic-user-capture"));
            assertFalse(trace.contains("synthetic-answer-capture"));

            ownedExecutor.shutdown();
            assertTrue(ownedExecutor.awaitTermination(5, TimeUnit.SECONDS), "owned executor did not terminate");
            TraceStore.clear();
            TraceStore.put("finalAnswer.memorySaveAllowed", true);
            assertSame(result, aspect.captureConversationTurn(pjp));
            assertEquals("rejected", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
            assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
            verify(pjp, times(2)).proceed();
            verify(service, times(1)).ingestConversationTurn("73", "synthetic-user-capture", "synthetic-answer-capture");
        } finally {
            ownedExecutor.shutdownNow();
            try {
                assertTrue(ownedExecutor.awaitTermination(5, TimeUnit.SECONDS), "owned worker cleanup did not finish");
            } finally {
                GuardContextHolder.clear();
                TraceStore.clear();
            }
        }
    }

    @Test
    void skipsCaptureWhenFinalMemoryDecisionIsDeniedOrMissing() throws Throwable {
        TraceStore.clear();
        BrainStateProperties props = new BrainStateProperties();
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service, Runnable::run);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatRequestDto req = ChatRequestDto.builder().sessionId(7L).message("hello").build();
        when(pjp.getArgs()).thenReturn(new Object[]{req, null});
        when(pjp.proceed()).thenReturn(ChatResult.of("fallback answer", "model", true));

        aspect.captureConversationTurn(pjp);
        TraceStore.put("finalAnswer.memorySaveAllowed", false);
        aspect.captureConversationTurn(pjp);

        verify(pjp, times(2)).proceed();
        verify(service, never()).ingestConversationTurn(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        TraceStore.clear();
    }

    @Test
    void skipsCaptureWhenDisabled() throws Throwable {
        BrainStateProperties props = new BrainStateProperties();
        props.setEnabled(false);
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service, Runnable::run);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{ChatRequestDto.builder().sessionId(7L).message("hello").build(), null});
        when(pjp.proceed()).thenReturn(ChatResult.of("answer", "model", true));

        aspect.captureConversationTurn(pjp);

        verify(service, never()).ingestConversationTurn(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pointcutCoversSingleArgAndProviderOverloads() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/graph/BrainStateChatWorkflowAspect.java"));

        assertTrue(source.contains("continueChat(com.example.lms.dto.ChatRequestDto,..)"));
    }

    @Test
    void captureFailureLeavesTraceBreadcrumbWithoutRawSessionOrText() {
        TraceStore.clear();
        BrainStateProperties props = new BrainStateProperties();
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        doThrow(new IllegalStateException("ownerToken=raw-brain-capture"))
                .when(service).ingestConversationTurn(anyString(), anyString(), anyString());
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service, Runnable::run);

        aspect.capture("session ownerToken=raw-session", "private user text", "private assistant text");

        assertEquals("failed", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.kg.brainState.capture.failed"));
        assertEquals("IllegalStateException", TraceStore.get("retrieval.kg.brainState.capture.failureClass"));
        assertEquals("skip_chat_capture", TraceStore.get("retrieval.kg.brainState.capture.fallback"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-brain-capture"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-session"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private user text"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private assistant text"));
        TraceStore.clear();
    }

    @Test
    void captureCancellationRecordsCancelledWithoutRawPayload() {
        TraceStore.clear();
        BrainStateProperties props = new BrainStateProperties();
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        doThrow(new CancellationException("ownerToken=raw-cancelled-capture"))
                .when(service).ingestConversationTurn(anyString(), anyString(), anyString());
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service, Runnable::run);

        aspect.capture("session ownerToken=raw-session", "private user text", "private assistant text");

        assertEquals("cancelled", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
        assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-cancelled-capture"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-session"));
        TraceStore.clear();
    }

    @Test
    void rejectedSpringExecutorRecordsTerminalOutcomeWithoutIngesting() throws Throwable {
        TraceStore.clear();
        TraceStore.put("finalAnswer.memorySaveAllowed", true);
        BrainStateProperties props = new BrainStateProperties();
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        Executor rejecting = command -> {
            throw new RejectedExecutionException("ownerToken=raw-rejection");
        };
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service, rejecting);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{
                ChatRequestDto.builder().sessionId(7L).message("private user text").build(), null});
        when(pjp.proceed()).thenReturn(ChatResult.of("private assistant text", "model", true));

        aspect.captureConversationTurn(pjp);

        assertEquals("rejected", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
        assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
        verify(service, never()).ingestConversationTurn(anyString(), anyString(), anyString());
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-rejection"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private user text"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private assistant text"));
        TraceStore.clear();
    }

    @Test
    void callerCancellationSkipsSubmissionAndPreservesInterrupt() throws Throwable {
        TraceStore.clear();
        TraceStore.put("finalAnswer.memorySaveAllowed", true);
        BrainStateProperties props = new BrainStateProperties();
        GraphRagChunkingService service = mock(GraphRagChunkingService.class);
        AtomicInteger submissions = new AtomicInteger();
        Executor countingExecutor = command -> submissions.incrementAndGet();
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(
                props, service, countingExecutor);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatResult result = ChatResult.of("private assistant text", "model", true);
        when(pjp.getArgs()).thenReturn(new Object[]{
                ChatRequestDto.builder().sessionId(7L).message("private user text").build(), null});
        when(pjp.proceed()).thenReturn(result);

        Thread.currentThread().interrupt();
        try {
            assertSame(result, aspect.captureConversationTurn(pjp));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, submissions.get());
            assertEquals("cancelled", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
            assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
            verify(service, never()).ingestConversationTurn(anyString(), anyString(), anyString());
        } finally {
            Thread.interrupted();
            TraceStore.clear();
        }
    }
}
