package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.guard.rulebreak.*;
import com.example.lms.search.TraceStore;
import com.example.lms.service.*;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatStreamRuleBreakContextTest {
    @AfterEach void cleanup() { RuleBreakContextHolder.clear(); TraceStore.clear(); }

    @Test void manualWorkerReceivesImmutableCopyAndCleansAfterFailSoftError() throws Exception {
        var original = context("request-A", Instant.now().plusSeconds(30));
        var result = probe(original, null, false);
        assertNotNull(result.worker());
        assertNotSame(original, result.worker());
        assertEquals("request-A", result.worker().getRequestId());
        assertEquals("synthetic-hash", result.worker().getTokenHash());
        assertEquals(RuleBreakPolicy.SAFE_EXPLORE, result.worker().getPolicy());
        assertTrue(result.worker().isActive());
        assertEquals(original.getExpiresAt(), result.worker().getExpiresAt());
        assertNull(result.after());
    }

    @Test void absentRequestClearsContaminationDuringWorkerAndRestoresOuterScope() throws Exception {
        var outer = context("worker-outer", Instant.now().plusSeconds(30));
        var result = probe(null, outer, false);
        assertNull(result.worker());
        assertSame(outer, result.after());
    }

    @Test void expiredSnapshotDoesNotExtendExpiry() throws Exception {
        var expiry = Instant.now().minusSeconds(60);
        var result = probe(context("expired", expiry), null, false);
        assertNotNull(result.worker());
        assertEquals(expiry, result.worker().getExpiresAt());
        assertNull(result.after());
    }

    @Test void cancelledSubscriberDoesNotLeakManualWorkerBinding() throws Exception {
        var result = probe(context("cancelled", Instant.now().plusSeconds(30)), null, true);
        assertEquals(2, result.effectiveCalls());
        assertNotNull(result.worker());
        assertEquals("cancelled", result.worker().getRequestId());
        assertNull(result.after());
    }

    private static RuleBreakContext context(String request, Instant expiry) {
        var value = new RuleBreakContext();
        value.setActive(true); value.setPolicy(RuleBreakPolicy.SAFE_EXPLORE);
        value.setTokenHash("synthetic-hash"); value.setRequestId(request);
        value.setSessionId("synthetic-session"); value.setExpiresAt(expiry);
        return value;
    }

    private record ProbeResult(RuleBreakContext worker, RuleBreakContext after, int effectiveCalls) {}

    private static ProbeResult probe(RuleBreakContext request, RuleBreakContext outer, boolean cancel) throws Exception {
        var history = mock(ChatHistoryService.class);
        var chat = mock(ChatService.class);
        var settings = mock(SettingsService.class);
        var owner = mock(ClientOwnerKeyResolver.class);
        when(settings.getAllSettings()).thenReturn(Map.of());
        when(owner.ownerKey()).thenReturn("owner-rulebreak-fixture");
        var registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var controller = new ChatApiController(history, chat, null, settings, null, null,
                null, null, null, null, null, null, null, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, registry, owner);
        var guard = spy(new PublicRequestBudgetGuard());
        var calls = new AtomicInteger();
        var observed = new AtomicReference<RuleBreakContext>();
        var workerEntered = new CountDownLatch(1);
        var finishCancelledWorker = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                observed.set(RuleBreakContextHolder.get());
                workerEntered.countDown();
                if (cancel) finishCancelledWorker.await(2, TimeUnit.SECONDS);
                throw new IllegalStateException("synthetic stop after worker observation");
            }
            return null;
        }).when(guard).validateChatEffective(any(ChatRequestDto.class));
        ReflectionTestUtils.setField(controller, "publicRequestBudgetGuard", guard);
        var single = Schedulers.newSingle("rulebreak-sse-fixture");
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var schedulerMock = mockStatic(Schedulers.class, CALLS_REAL_METHODS)) {
            schedulerMock.when(Schedulers::boundedElastic).thenReturn(single);
            single.schedule(() -> {
                RuleBreakContextHolder.set(outer);
                blocked.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(1, TimeUnit.SECONDS));
            RuleBreakContextHolder.set(request);
            var flux = controller.chatStream(ChatRequestDto.builder().message("synthetic context probe").build(),
                    false, false, null, new MockHttpServletRequest());
            if (request != null) request.setRequestId("mutated-after-capture");
            RuleBreakContextHolder.clear();
            if (cancel) {
                var subscription = flux.subscribe();
                release.countDown();
                assertTrue(workerEntered.await(2, TimeUnit.SECONDS));
                subscription.dispose();
                finishCancelledWorker.countDown();
            } else {
                release.countDown();
                assertNotNull(flux.collectList().block(Duration.ofSeconds(3)));
                assertEquals(2, calls.get());
            }
            var after = new CompletableFuture<RuleBreakContext>();
            single.schedule(() -> { after.complete(RuleBreakContextHolder.get()); RuleBreakContextHolder.clear(); });
            var result = new ProbeResult(observed.get(), after.get(2, TimeUnit.SECONDS), calls.get());
            verifyNoInteractions(chat);
            return result;
        } finally {
            finishCancelledWorker.countDown(); release.countDown(); single.dispose(); RuleBreakContextHolder.clear();
        }
    }
}
