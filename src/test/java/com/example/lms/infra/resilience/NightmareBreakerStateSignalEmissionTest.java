package com.example.lms.infra.resilience;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightmareBreakerStateSignalEmissionTest {

    @Test
    @Timeout(10)
    void onlyCasWinnerPublishesOpenedSignal() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        NightmareBreaker breaker = breakerWithPublisher(publisher);
        String key = "test:nightmare:state-signal-cas-winner";
        int callers = 32;
        List<NightmareBreaker.CallPermit> permits = new ArrayList<>(callers);
        for (int i = 0; i < callers; i++) {
            permits.add(breaker.acquire(key));
        }

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> completions = new ArrayList<>(callers);
        try {
            for (NightmareBreaker.CallPermit permit : permits) {
                completions.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    permit.completeFailure(
                            NightmareBreaker.FailureKind.HTTP_5XX,
                            new IllegalStateException("provider failure"),
                            "state-signal-test");
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> completion : completions) {
                completion.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        List<NightmareBreaker.StateSignal> opened = publisher.signalsOf(
                NightmareBreaker.SignalType.OPENED);
        assertEquals(1, opened.size(), "only the successful OPEN transition CAS may publish");
        assertSame(breaker, opened.get(0).sourceBreaker());
        assertTrue(breaker.isOpen(key));
    }

    @Test
    void staleAndDuplicateTerminalsPublishNoSecondOpenedSignal() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        NightmareBreaker breaker = breakerWithPublisher(publisher);
        String key = "test:nightmare:state-signal-stale-terminal";
        NightmareBreaker.CallPermit stale = breaker.acquire(key);
        NightmareBreaker.CallPermit winner = breaker.acquire(key);

        winner.completeFailure(
                NightmareBreaker.FailureKind.HTTP_5XX,
                new IllegalStateException("winner"),
                "winner");
        assertEquals(1, publisher.signalsOf(NightmareBreaker.SignalType.OPENED).size());

        winner.completeFailure(
                NightmareBreaker.FailureKind.HTTP_5XX,
                new IllegalStateException("duplicate"),
                "duplicate");
        stale.completeFailure(
                NightmareBreaker.FailureKind.HTTP_5XX,
                new IllegalStateException("stale generation"),
                "stale");

        assertEquals(1, publisher.signalsOf(NightmareBreaker.SignalType.OPENED).size(),
                "duplicate and stale completions must not publish another OPEN event");
        assertTrue(breaker.isOpen(key));
    }

    @Test
    void dynamicKeyAndThrowableMessageNeverAppearInStateSignal() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        NightmareBreaker breaker = breakerWithPublisher(publisher);
        String rawKey = "tenant:private raw-state-signal-key-7f19b2";
        String rawMessage = "private-error-message-91dc7a query failed";

        NightmareBreaker.CallPermit permit = breaker.acquire(rawKey, "private-stage");
        permit.completeFailure(
                NightmareBreaker.FailureKind.HTTP_5XX,
                new IllegalStateException(rawMessage),
                "private-context");

        List<NightmareBreaker.StateSignal> opened = publisher.signalsOf(
                NightmareBreaker.SignalType.OPENED);
        assertEquals(1, opened.size());
        NightmareBreaker.StateSignal signal = opened.get(0);
        String serialized = signal.toString();

        assertTrue(signal.diagnosticKey().matches("hash:[0-9a-f]{12}"), signal.diagnosticKey());
        assertFalse(signal.diagnosticKey().contains(rawKey));
        assertFalse(serialized.contains(rawKey), serialized);
        assertFalse(serialized.contains("raw-state-signal-key"), serialized);
        assertFalse(serialized.contains(rawMessage), serialized);
        assertFalse(serialized.contains("private-error-message-91dc7a"), serialized);
        assertEquals(NightmareBreaker.FailureKind.HTTP_5XX, signal.lastKind());
    }

    private static NightmareBreaker breakerWithPublisher(CapturingPublisher publisher) throws Exception {
        NightmareBreakerProperties properties = new NightmareBreakerProperties();
        properties.setEnabled(true);
        properties.setFailureThreshold(1);
        properties.setOpenDuration(Duration.ofMinutes(1));
        NightmareBreaker breaker = new NightmareBreaker(properties);
        Field field = NightmareBreaker.class.getDeclaredField("eventPublisher");
        field.setAccessible(true);
        field.set(breaker, publisher);
        return breaker;
    }

    private static final class CapturingPublisher implements ApplicationEventPublisher {
        private final CopyOnWriteArrayList<Object> events = new CopyOnWriteArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        private List<NightmareBreaker.StateSignal> signalsOf(NightmareBreaker.SignalType type) {
            return events.stream()
                    .filter(NightmareBreaker.StateSignal.class::isInstance)
                    .map(NightmareBreaker.StateSignal.class::cast)
                    .filter(signal -> signal.signalType() == type)
                    .toList();
        }
    }
}
