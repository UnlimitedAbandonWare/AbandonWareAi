package com.example.lms.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatInteractiveDisconnectTest {
    private ChatRunRegistry registry() {
        var registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        return registry;
    }
    @Test void onlyLastInteractiveDetachCancelsAcknowledgedWorkAndBridgeDoesNotCount() {
        var registry = registry(); var run = registry.beginOrJoin(501L).context();
        var cancellations = new AtomicInteger(); run.registerCancellationHandle(cancellations::incrementAndGet);
        registry.acknowledgeExact(501L, run.clientToken());
        var bridge = registry.attach(run).subscribe();
        var a = registry.attachInteractiveExact(501L, run.clientToken()).orElseThrow().subscribe();
        var b = registry.attachInteractiveExact(501L, run.clientToken()).orElseThrow().subscribe();
        a.dispose(); assertEquals(0, cancellations.get()); assertTrue(registry.isRunning(501L));
        b.dispose(); assertEquals(1, cancellations.get()); assertTrue(run.isCancellationRequested());
        bridge.dispose(); assertEquals(1, cancellations.get());
        assertEquals(ChatRunRegistry.Status.CANCELLED, registry.describeExact(501L, run.clientToken()).orElseThrow().status());
    }
    @Test void normalCompletionAndTerminalReplayDoNotBecomeCancellation() {
        var registry = registry(); var run = registry.beginOrJoin(502L).context();
        var cancellations = new AtomicInteger(); run.registerCancellationHandle(cancellations::incrementAndGet);
        var client = registry.attachInteractiveExact(502L, run.clientToken()).orElseThrow().subscribe();
        registry.markDone(run); client.dispose();
        registry.attachInteractiveExact(502L, run.clientToken()).orElseThrow().blockLast(Duration.ofSeconds(1));
        assertEquals(0, cancellations.get());
        assertEquals(ChatRunRegistry.Status.DONE, registry.describeExact(502L, run.clientToken()).orElseThrow().status());
    }
    @Test void disconnectBeforeSessionCreationCancelsLateBoundWorker() {
        var registry=registry();var client=registry.interactiveClient();client.disconnect();
        var run=registry.beginOrJoin(503L).context();var cancellations=new AtomicInteger();
        run.registerCancellationHandle(cancellations::incrementAndGet);client.bind(run);
        assertEquals(1,cancellations.get());assertTrue(run.isCancellationRequested());
    }
    @Test void idleConnectionGetsCommentHeartbeatWithinOneSecond() {
        var registry=registry();var run=registry.beginOrJoin(504L).context();
        var event=registry.attachInteractiveExact(504L,run.clientToken()).orElseThrow().blockFirst(Duration.ofSeconds(1));
        assertNotNull(event);assertNull(event.data());assertEquals("keepalive",event.comment());
        assertTrue(run.isCancellationRequested());
    }
}
