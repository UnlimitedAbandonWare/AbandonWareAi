package com.example.lms.service.chat;

import com.example.lms.dto.ChatStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamEmitterRunIdentityTest {

    @Test
    void lateCancelledRunEmissionAndCleanupCannotContaminateReplacement() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatStreamEmitter emitter = new ChatStreamEmitter();

        ChatRunRegistry.BeginResult first = registry.beginOrJoin(42L);
        assertTrue(first.owner());
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> firstSink = Sinks.many().replay().limit(16);
        emitter.registerSink(first.context(), firstSink);

        assertTrue(registry.cancelExact(42L, first.context().clientToken()));

        ChatRunRegistry.BeginResult replacement = registry.beginOrJoin(42L);
        assertTrue(replacement.owner());
        assertNotSame(first.context(), replacement.context());
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> replacementSink = Sinks.many().replay().limit(16);
        List<String> replacementEvents = new CopyOnWriteArrayList<>();
        replacementSink.asFlux().subscribe(event -> {
            if (event.data() != null && event.data().data() != null) {
                replacementEvents.add(String.valueOf(event.data().data()));
            }
        });
        emitter.registerSink(replacement.context(), replacementSink);

        emitter.sendStatus(first.context(), "late-r1");
        assertTrue(emitter.unregisterSink(first.context(), firstSink));
        emitter.sendStatus(replacement.context(), "r2-own");

        assertEquals(List.of("r2-own"), replacementEvents);
    }

    @Test
    void beginOrJoinHasOneExecutionOwnerAndOneTerminalWinner() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;

        ChatRunRegistry.BeginResult owner = registry.beginOrJoin(77L);
        ChatRunRegistry.BeginResult joiner = registry.beginOrJoin(77L);

        assertTrue(owner.owner());
        assertFalse(joiner.owner());
        assertTrue(owner.context().sameRun(joiner.context()));
        assertFalse(joiner.context().tryBeginCommit(),
                "a joiner cannot acquire commit before the owner");
        assertTrue(owner.context().tryBeginCommit());
        assertFalse(owner.context().tryBeginCommit(),
                "the memory commit transition must be one-shot");
        assertTrue(owner.context().tryBeginTranscriptCommit(),
                "the owner may claim the later transcript commit exactly once");
        assertFalse(owner.context().tryBeginTranscriptCommit(),
                "the transcript commit claim must be one-shot");
        assertFalse(joiner.context().tryBeginCommit(),
                "a replay/attach joiner must never acquire the durable commit lease");
        assertFalse(registry.cancelExact(77L, owner.context().clientToken()),
                "cancel must lose after the commit boundary");
        assertFalse(registry.markDone(joiner.context()),
                "a replay/attach joiner must never terminate the owner run");
        assertTrue(registry.markDone(owner.context()));
    }

    @Test
    void joinerCannotClaimProducerOrCancellationHandle() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatRunRegistry.BeginResult owner = registry.beginOrJoin(78L);
        ChatRunRegistry.BeginResult joiner = registry.beginOrJoin(78L);
        ChatStreamEmitter emitter = new ChatStreamEmitter();
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> ownerSink = Sinks.many().replay().limit(8);
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> joinerSink = Sinks.many().replay().limit(8);

        assertTrue(emitter.registerSink(owner.context(), ownerSink));
        assertFalse(emitter.registerSink(joiner.context(), joinerSink));
        assertFalse(joiner.context().emitToProducer(
                ServerSentEvent.builder(ChatStreamEvent.status("joiner-event")).build()));
        assertFalse(emitter.unregisterSink(joiner.context(), ownerSink));

        AtomicInteger ownerDisposals = new AtomicInteger();
        AtomicInteger joinerDisposals = new AtomicInteger();
        assertTrue(owner.context().registerCancellationHandle(ownerDisposals::incrementAndGet));
        assertFalse(joiner.context().registerCancellationHandle(joinerDisposals::incrementAndGet));
        assertEquals(1, joinerDisposals.get(), "a rejected handle must be disposed immediately");

        assertTrue(registry.cancelExact(78L, owner.context().clientToken()));
        assertEquals(1, ownerDisposals.get());
        assertEquals(1, joinerDisposals.get());
    }

    @Test
    void lateOwnerHandleRegistrationDuringCommitDoesNotCancelWinningWorker() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatRunExecutionContext owner = registry.beginOrJoin(79L).context();
        AtomicInteger disposals = new AtomicInteger();

        assertTrue(owner.tryBeginCommit());
        assertFalse(owner.registerCancellationHandle(disposals::incrementAndGet));

        assertEquals(0, disposals.get(),
                "a late caller-side handshake must not dispose a worker that already won commit");
        assertTrue(owner.tryBeginTranscriptCommit());
        assertTrue(registry.markDone(owner));
    }

    @Test
    void delayedExactCancelForFirstRunCannotCancelReplacement() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;

        ChatRunRegistry.BeginResult first = registry.beginOrJoin(120L);
        String firstToken = first.context().clientToken();
        assertTrue(registry.cancelExact(120L, firstToken));

        ChatRunRegistry.BeginResult replacement = registry.beginOrJoin(120L);
        String replacementToken = replacement.context().clientToken();
        assertTrue(replacement.owner());
        assertFalse(firstToken.equals(replacementToken));

        assertFalse(registry.cancelExact(120L, firstToken),
                "a delayed R1 stop must be rejected after R2 becomes current");
        assertTrue(registry.isRunning(120L));
        assertTrue(registry.cancelExact(120L, replacementToken));
    }

    @Test
    void cancellingRunStaysCurrentUntilItsTerminalActionFinishes() throws Exception {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatRunRegistry.BeginResult first = registry.beginOrJoin(125L);
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> cancelled = worker.submit(() -> registry.cancelExact(
                    125L,
                    first.context().clientToken(),
                    () -> {
                        actionStarted.countDown();
                        try {
                            assertTrue(releaseAction.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }));

            assertTrue(actionStarted.await(3, TimeUnit.SECONDS));
            ChatRunRegistry.BeginResult duringCancel = registry.beginOrJoin(125L);
            ChatRunRegistry.RunView view = registry
                    .describeExact(125L, first.context().clientToken())
                    .orElseThrow();

            assertFalse(duringCancel.owner(), "R2 must not start while R1's terminal record is pending");
            assertTrue(duringCancel.context().sameRun(first.context()));
            assertEquals(ChatRunRegistry.Status.CANCELLING, view.status());
            assertTrue(view.current());

            releaseAction.countDown();
            assertTrue(cancelled.get(3, TimeUnit.SECONDS));
            ChatRunRegistry.BeginResult replacement = registry.beginOrJoin(125L);
            assertTrue(replacement.owner(), "R2 may start after R1 is durably terminal");
            assertFalse(replacement.context().sameRun(first.context()));
        } finally {
            releaseAction.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void exactCancelPublishesOneTerminalCancelledEventBeforeReplayCompletes() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatRunRegistry.BeginResult run = registry.beginOrJoin(123L);

        assertTrue(registry.cancelExact(123L, run.context().clientToken()));
        List<ServerSentEvent<ChatStreamEvent>> replay = registry
                .attachExact(123L, run.context().clientToken())
                .orElseThrow()
                .collectList()
                .block(java.time.Duration.ofSeconds(1));

        assertEquals(1, replay.size());
        assertEquals("status", replay.get(0).data().type());
        assertEquals("cancelled", replay.get(0).data().statusSignal().code());
        assertEquals(true, replay.get(0).data().statusSignal().cancelled());
        ChatRunRegistry.RunOutcomeView outcome = registry
                .describeExact(123L, run.context().clientToken()).orElseThrow().outcome();
        assertEquals("cancelled", outcome.generationOutcome());
        assertEquals("cancelled", outcome.terminalReason());
        assertEquals(1, outcome.terminalEventCount());
        assertEquals(0, outcome.persistenceCount());
    }

    @Test
    void exactRunOutcomeSeparatesGenerationPersistenceEmitAndFinalAck() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatRunExecutionContext run = registry.beginOrJoin(126L).context();
        String rawToken = run.clientToken();

        assertTrue(run.markGenerationSucceeded());
        assertTrue(run.tryBeginTranscriptCommit());
        assertTrue(run.markPersisted());
        assertFalse(run.markPersisted(), "persistence outcome is monotonic and exactly once");
        assertTrue(run.claimTerminalEvent("delivery_pending"));
        assertFalse(run.claimTerminalEvent("late_duplicate"));
        assertTrue(run.recordFinalEmit("OK", false));

        ChatRunRegistry.RunOutcomeView pending = registry
                .describeExact(126L, rawToken).orElseThrow().outcome();
        assertEquals("success", pending.generationOutcome());
        assertTrue(pending.generationSucceeded());
        assertTrue(pending.commitAttempted());
        assertTrue(pending.commitAccepted());
        assertFalse(pending.commitRejected());
        assertTrue(pending.persisted());
        assertEquals(1, pending.persistenceCount());
        assertEquals("ok", pending.finalEmitResult());
        assertFalse(pending.finalDeliveryAccepted());
        assertEquals("ack_pending", pending.finalDeliveryFailureReason());
        assertEquals(2, pending.duplicateSuppressed());
        assertEquals(1, pending.terminalEventCount());
        assertFalse(String.valueOf(pending).contains(rawToken), "outcome must not expose the run capability");
        assertTrue(pending.runIdentityHash().startsWith("hash:"));

        assertTrue(registry.acknowledgeFinalDeliveryExact(126L, rawToken));
        ChatRunRegistry.RunOutcomeView delivered = registry
                .describeExact(126L, rawToken).orElseThrow().outcome();
        assertTrue(delivered.finalDeliveryAccepted());
        assertEquals("none", delivered.finalDeliveryFailureReason());
        assertEquals("delivered", delivered.terminalReason());
        assertTrue(registry.markDone(run));
    }

    @Test
    void staleFinalAckUpdatesOnlyItsExactRetainedRun() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatRunExecutionContext first = registry.beginOrJoin(127L).context();
        String firstToken = first.clientToken();
        assertTrue(first.markGenerationSucceeded());
        assertTrue(first.tryBeginTranscriptCommit());
        assertTrue(first.markPersisted());
        assertTrue(first.claimTerminalEvent("delivery_pending"));
        assertTrue(first.recordFinalEmit("OK", false));
        assertTrue(registry.markDone(first));

        ChatRunExecutionContext replacement = registry.beginOrJoin(127L).context();
        String replacementToken = replacement.clientToken();
        assertTrue(registry.acknowledgeFinalDeliveryExact(127L, firstToken));

        ChatRunRegistry.RunView firstView = registry.describeExact(127L, firstToken).orElseThrow();
        ChatRunRegistry.RunView replacementView = registry.describeExact(127L, replacementToken).orElseThrow();
        assertFalse(firstView.current());
        assertTrue(firstView.outcome().finalDeliveryAccepted());
        assertTrue(replacementView.current());
        assertFalse(replacementView.outcome().generationSucceeded());
        assertFalse(replacementView.outcome().persisted());
        assertFalse(replacementView.outcome().finalDeliveryAccepted());
        assertTrue(registry.cancelExact(127L, replacementToken));
    }

    @Test
    void legacySessionEmitterNeverImplicitlyRoutesThroughThreadLocalRunIdentity() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatRunExecutionContext context = registry.beginOrJoin(124L).context();
        ChatStreamEmitter emitter = new ChatStreamEmitter();
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> producer = Sinks.many().replay().limit(8);
        List<String> events = new CopyOnWriteArrayList<>();
        producer.asFlux().subscribe(event -> events.add(event.data().data()));
        emitter.registerSink(context, producer);

        try (ChatRunExecutionContext.Scope ignored = ChatRunExecutionContext.bind(context)) {
            emitter.sendStatus("chat-124", "legacy-must-not-cross");
            emitter.sendStatus(context, "explicit-exact");
        }

        assertEquals(List.of("explicit-exact"), events);
    }

    @Test
    void terminalReplayRemainsAddressableByTokenAfterReplacementStarts() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;

        ChatRunRegistry.BeginResult first = registry.beginOrJoin(121L);
        assertTrue(registry.emit(first.context(), ServerSentEvent.builder(
                ChatStreamEvent.status("r1-terminal-event")).build()));
        assertTrue(registry.markDone(first.context()));

        ChatRunRegistry.BeginResult replacement = registry.beginOrJoin(121L);
        assertTrue(replacement.owner());

        List<ServerSentEvent<ChatStreamEvent>> replay = registry
                .attachExact(121L, first.context().clientToken())
                .orElseThrow()
                .collectList()
                .block(java.time.Duration.ofSeconds(1));

        assertEquals(1, replay.size());
        assertEquals("r1-terminal-event", replay.get(0).data().data());
        assertTrue(registry.isRunning(121L));
    }

    @Test
    void wrongThreadScopeCloseDoesNotConsumeOwnerCleanup() throws Exception {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatRunExecutionContext context = registry.beginOrJoin(122L).context();
        ChatRunExecutionContext.Scope scope = ChatRunExecutionContext.bind(context);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> wrongThread = worker.submit(() -> {
                try {
                    scope.close();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(wrongThread.get(1, TimeUnit.SECONDS) instanceof IllegalStateException);
            assertTrue(ChatRunExecutionContext.current().sameRun(context));

            scope.close();
            assertNull(ChatRunExecutionContext.current());
            assertThrows(IllegalStateException.class, () -> {
                try (ChatRunExecutionContext.Scope outer = ChatRunExecutionContext.bind(context);
                     ChatRunExecutionContext.Scope inner = ChatRunExecutionContext.bind(context)) {
                    outer.close();
                }
            });
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void concurrentBeginHasExactlyOneOwner() throws Exception {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ChatRunRegistry.BeginResult>> futures = IntStream.range(0, 8)
                    .mapToObj(ignored -> workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(3, TimeUnit.SECONDS));
                        return registry.beginOrJoin(99L);
                    }))
                    .toList();
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            List<ChatRunRegistry.BeginResult> results = futures.stream()
                    .map(ChatStreamEmitterRunIdentityTest::get)
                    .toList();

            assertEquals(1L, results.stream().filter(ChatRunRegistry.BeginResult::owner).count());
            ChatRunExecutionContext expected = results.get(0).context();
            assertTrue(results.stream().allMatch(result -> expected.sameRun(result.context())));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentCancelAndCommitHaveExactlyOneWinner() throws Exception {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        ChatRunExecutionContext context = registry.beginOrJoin(100L).context();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> commit = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(3, TimeUnit.SECONDS));
                return context.tryBeginCommit();
            });
            Future<Boolean> cancel = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(3, TimeUnit.SECONDS));
                return registry.cancelExact(100L, context.clientToken());
            });
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();

            boolean commitWon = commit.get(3, TimeUnit.SECONDS);
            boolean cancelWon = cancel.get(3, TimeUnit.SECONDS);
            assertTrue(commitWon ^ cancelWon, "commit and cancel must have exactly one winner");
            assertEquals(commitWon, registry.markDone(context));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "understanding,OK,false,unused", "send,OK,false,unused",
            "understanding,FAIL_ZERO_SUBSCRIBER,true,fail_zero_subscriber",
            "send,FAIL_ZERO_SUBSCRIBER,true,fail_zero_subscriber",
            "understanding,FAIL_OVERFLOW,true,fail_overflow", "send,FAIL_OVERFLOW,true,fail_overflow",
            "understanding,FAIL_CANCELLED,true,fail_cancelled", "send,FAIL_CANCELLED,true,fail_cancelled",
            "understanding,FAIL_NON_SERIALIZED,true,fail_non_serialized",
            "send,FAIL_NON_SERIALIZED,true,fail_non_serialized",
            "understanding,FAIL_TERMINATED,true,fail_terminated", "send,FAIL_TERMINATED,true,fail_terminated"})
    @org.junit.jupiter.api.Timeout(5)
    @SuppressWarnings("unchecked")
    void legacyHelperRecordsBoundedFailureReasonWithoutRetry(String helper, Sinks.EmitResult result,
                                                             boolean failure, String expectedReason) throws Exception {
        ChatStreamEmitter emitter = new ChatStreamEmitter();
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink = org.mockito.Mockito.mock(Sinks.Many.class);
        var attempted = new java.util.concurrent.atomic.AtomicReference<ServerSentEvent<ChatStreamEvent>>();
        org.mockito.Mockito.when(sink.tryEmitNext(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            return result;
        });
        String sessionKey = "synthetic-emission-key";
        emitter.registerSink(sessionKey, sink);
        com.example.lms.search.TraceStore.clear();
        try {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
                if ("understanding".equals(helper)) {
                    emitter.emitUnderstanding(sessionKey, new com.example.lms.dto.answer.AnswerUnderstanding(
                            "synthetic-summary", List.of(), List.of(), List.of(), List.of(), List.of(),
                            List.of(), List.of(), List.of(), 0.5));
                } else {
                    emitter.sendStatus(sessionKey, "synthetic-status");
                }
            });
            org.mockito.Mockito.verify(sink).tryEmitNext(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verifyNoMoreInteractions(sink);
            assertEquals("understanding".equals(helper) ? "understanding" : "status", attempted.get().event());
            if ("understanding".equals(helper)) {
                assertEquals("synthetic-summary", new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(attempted.get().data().data()).get("tldr").asText());
            } else {
                assertEquals("synthetic-status", attempted.get().data().data());
            }
            Object count = com.example.lms.search.TraceStore.get("chat.stream.emitter.failure.count");
            int failures = count instanceof Number number ? number.intValue() : 0;
            Object reason = com.example.lms.search.TraceStore.get("chat.stream.emitter.failure.reason");
            Object stage = com.example.lms.search.TraceStore.get("chat.stream.emitter.failure.stage");
            System.out.println("R06_EMIT_COUNTS helper=" + helper + " result=" + result.name()
                    + " attempts=1 observedFailureCount=" + failures
                    + " reasonPresent=" + (reason != null) + " stagePresent=" + (stage != null));
            assertEquals(failure ? 1 : 0, failures, "non-OK result must remain observable as one bounded failure");
            if (failure) {
                assertEquals(expectedReason, reason);
                assertEquals(helper, stage);
            } else {
                assertNull(reason);
                assertNull(stage);
            }
            assertTrue(emitter.unregisterSink(sessionKey, sink), "failure diagnostics must not replace sink ownership");
        } finally {
            emitter.unregisterSink(sessionKey);
            com.example.lms.search.TraceStore.clear();
        }
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
