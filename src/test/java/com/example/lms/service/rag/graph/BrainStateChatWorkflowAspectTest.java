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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrainStateChatWorkflowAspectTest {
    private static GeneralGraphScope scope(long id) {
        var session = new com.example.lms.domain.ChatSession("synthetic", "owner", "ANON");
        session.setId(id);
        return GeneralGraphScope.authorize(session, null, "owner").orElseThrow().withPolicy(1, true);
    }

    private static GraphRagChunkingService.IngestReport captureReport(String status, java.util.Map<String, Object> backend) {
        return new GraphRagChunkingService.IngestReport(!"disabled".equals(status), "7", status,
                1, 2, 1, 0, "", "", backend);
    }

    @Test
    void nonThrowingReportsDoNotCertifyUnfinishedIndexing() {
        var service = mock(GraphRagChunkingService.class);
        var aspect = new BrainStateChatWorkflowAspect(new BrainStateProperties(), service, Runnable::run);
        for (String outcome : java.util.List.of("failed", "disabled", "skipped", "partial_indexed")) {
            TraceStore.clear();
            when(service.ingestFinalizedTurn(any(), anyLong())).thenReturn(captureReport(outcome, java.util.Map.of()));
            aspect.capture(scope(7), 11L);
            assertEquals(outcome, TraceStore.get("retrieval.kg.brainState.capture.outcome"));
            assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
        }
        TraceStore.clear();
        when(service.ingestFinalizedTurn(any(), anyLong())).thenReturn(null);
        aspect.capture(scope(7), 11L);
        assertEquals("failed", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
        TraceStore.clear();
    }

    @Test
    void mergedConversationReportPreservesPartialAndQueuedOutcomes() {
        var queued = captureReport("indexed", java.util.Map.of("vectorStatus", "queued", "brainStateStatus", "recorded", "neo4jStatus", "written"));
        var failed = captureReport("failed", java.util.Map.of());
        var partial = captureReport("indexed", java.util.Map.of("vectorStatus", "queued", "neo4jStatus", "failed"));
        assertEquals("queued", queued.captureOutcome());
        assertEquals("partial_indexed", partial.captureOutcome());
        for (String backendState : java.util.List.of("disabled", "unavailable", "no_report")) {
            assertEquals("partial_indexed", captureReport("indexed", java.util.Map.of("brainStateStatus", "recorded", "neo4jStatus", backendState)).captureOutcome());
        }
        assertEquals("failed", GraphRagChunkingService.IngestReport.merge("7", "chat-workflow", java.util.List.of(failed, failed)).captureOutcome());
        assertEquals("queued", GraphRagChunkingService.IngestReport.merge("7", "chat-workflow", java.util.List.of(queued, queued)).captureOutcome());
        assertEquals("partial_indexed", GraphRagChunkingService.IngestReport.merge("7", "chat-workflow", java.util.List.of(queued, failed)).captureOutcome());
        assertEquals("partial_indexed", GraphRagChunkingService.IngestReport.merge("7", "chat-workflow", java.util.List.of(partial, queued)).captureOutcome());
        assertEquals("skipped", GraphRagChunkingService.IngestReport.merge("7", "chat-workflow", java.util.List.of()).captureOutcome());
    }

    @Test
    void proceedsOnceAndCapturesOnlyAfterTranscriptCommit() throws Throwable {
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
            return captureReport("indexed", java.util.Map.of("brainStateStatus", "recorded", "neo4jStatus", "written"));
        }).when(service).ingestFinalizedTurn(any(), anyLong());
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
        org.mockito.Mockito.verifyNoInteractions(service);
        aspect.captureFinalized(scope(7), 11L);

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
            }).when(service).ingestFinalizedTurn(any(), anyLong());
            BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(
                    new BrainStateProperties(), service, controlledWorker);
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            ChatRequestDto request = ChatRequestDto.builder().sessionId(73L).message("synthetic-user-capture").build();
            ChatResult result = ChatResult.of("synthetic-answer-capture", "model", true);
            when(pjp.getArgs()).thenReturn(new Object[]{request, null});
            when(pjp.proceed()).thenReturn(result);

            assertSame(result, aspect.captureConversationTurn(pjp));
            aspect.captureFinalized(scope(73), 11L);
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
            aspect.captureFinalized(scope(73), 11L);
            assertEquals("rejected", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
            assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
            verify(pjp, times(2)).proceed();
            verify(service, times(1)).ingestFinalizedTurn(any(), anyLong());
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
        aspect.captureFinalized(scope(7), 11L);
        TraceStore.put("finalAnswer.memorySaveAllowed", false);
        aspect.captureConversationTurn(pjp);
        aspect.captureFinalized(scope(7), 11L);

        verify(pjp, times(2)).proceed();
        verify(service, never()).ingestFinalizedTurn(any(), anyLong());
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
        aspect.captureFinalized(scope(7), 11L);

        verify(service, never()).ingestFinalizedTurn(any(), anyLong());
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
                .when(service).ingestFinalizedTurn(any(), anyLong());
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service, Runnable::run);

        aspect.capture(scope(7), 11L);

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
                .when(service).ingestFinalizedTurn(any(), anyLong());
        BrainStateChatWorkflowAspect aspect = new BrainStateChatWorkflowAspect(props, service, Runnable::run);

        aspect.capture(scope(7), 11L);

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
        aspect.captureFinalized(scope(7), 11L);

        assertEquals("rejected", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
        assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
        verify(service, never()).ingestFinalizedTurn(any(), anyLong());
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
            aspect.captureFinalized(scope(73), 11L);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, submissions.get());
            assertEquals("cancelled", TraceStore.get("retrieval.kg.brainState.capture.outcome"));
            assertEquals(1L, TraceStore.getLong("retrieval.kg.brainState.capture.completionCount"));
            verify(service, never()).ingestFinalizedTurn(any(), anyLong());
        } finally {
            Thread.interrupted();
            TraceStore.clear();
        }
    }
}
