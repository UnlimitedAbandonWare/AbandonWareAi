package com.example.lms.llm;

import com.example.lms.api.ChatCancellationCommandHandler;
import com.example.lms.llm.gateway.FallbackAwareChatModel;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.search.TraceStore;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback receiver and the exact Stop command; no external provider or application boot. */
class LlmInFlightCancellationTest {
    @ParameterizedTest
    @CsvSource({"in_flight,false", "resolver,false", "none,false", "in_flight,true", "observe,false",
            "late_success,false", "native_retry,false", "native_retry_control,false", "reactor_owner,false"})
    void receivedPrimaryThenExactStopPreventsNewFallback(String cancelPoint, boolean interruptWorker) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerExited = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger fallbackResolutions = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ChatResponse> response = new AtomicReference<>();
        AtomicReference<String> timelineId = new AtomicReference<>();
        ExecutorService receiver = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(receiver);
        server.createContext("/api/chat", exchange -> {
            int ordinal = requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            received.countDown();
            try {
                if (ordinal == 1 && !release.await(10, TimeUnit.SECONDS)) throw new AssertionError("receiver not released");
                boolean failFirst = ordinal == 1 && !"late_success".equals(cancelPoint);
                byte[] body = (failFirst ? (cancelPoint.startsWith("native_retry")
                        ? "{\"error\":\"llama_prepare_model_devices: invalid value for main_gpu: 0 (available devices: 0)\"}"
                        : "{\"error\":\"controlled failure\"}")
                        : "{\"message\":{\"content\":\"controlled answer\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failFirst ? 500 : 200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        ChatRunRegistry registry = new ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        ChatRunExecutionContext run = registry.beginOrJoin(71001L).context();
        Runnable stop = () -> assertTrue(new ChatCancellationCommandHandler().cancel(
                71001L, run.clientToken(), () -> true, registry, () -> {}).cancelled());
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        var proofLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                ModelRuntimeHealthTracker.class.getName() + ".requestProof");
        var proof = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        proof.list = new CopyOnWriteArrayList<>();
        proof.start();
        proofLogger.addAppender(proof);
        ChatModel primary = OllamaNativeChatModel.forRoutedRequest(base, "qwen3:fixture", Duration.ofSeconds(8),
                16, 0.0, null, 0, tracker, "primary");
        ChatModel fallback = OllamaNativeChatModel.forRoutedRequest(base, "qwen3:fixture", Duration.ofSeconds(8),
                16, 0.0, null, 0, tracker, "fallback");
        ChatModel routed = new FallbackAwareChatModel(primary, () -> {
            fallbackResolutions.incrementAndGet();
            if ("resolver".equals(cancelPoint)) stop.run();
            return fallback;
        }, new LlmGatewayFailureClassifier(), null, "primary", "fallback");
        ChatModel selected = cancelPoint.startsWith("native_retry")
                ? new OllamaNativeChatModel(base, "qwen3:fixture", Duration.ofSeconds(8), 16, 0.0, null, tracker, true)
                : routed;
        ChatModel model = tracker.decorateRequestAttempt(selected, "primary",
                tracker.redactedRequestAttemptRoute("fixture", "qwen3:fixture", base, "ollama_native"), java.util.Map.of());
        Thread caller = new Thread(() -> {
            try (var scope = ChatRunExecutionContext.bind(run)) {
                String timeline = tracker.beginRequestTimeline("synthetic-request", "synthetic-session");
                timelineId.set(timeline);
                tracker.recordRequestPhase(timeline, "dispatch", null, null, "none");
                tracker.recordRequestPhase(timeline, "pending", null, null, "none");
                TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timeline);
                response.set(model.chat(List.of(UserMessage.from("synthetic fixture"))));
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                Thread.interrupted();
                TraceStore.clear();
                workerExited.countDown();
            }
        }, "llm-in-flight-fixture");
        try {
            server.start();
            if (interruptWorker) run.registerCancellationHandle(caller::interrupt);
            if ("reactor_owner".equals(cancelPoint)) {
                // The production SSE controller owns this single boundedElastic subscription.
                reactor.core.Disposable owner = reactor.core.publisher.Mono.fromRunnable(caller::run)
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).subscribe();
                assertTrue(run.registerCancellationHandle(owner));
            } else caller.start();
            assertTrue(received.await(10, TimeUnit.SECONDS), "receiver must confirm an actual request before Stop");
            assertEquals(1, workerExited.getCount(), "response is held while Stop is applied");
            if ("reactor_owner".equals(cancelPoint)) {
                stop.run();
                assertTrue(workerExited.await(3, TimeUnit.SECONDS),
                        "disposing the actual Reactor worker must end block() before receiver release or model timeout");
                assertEquals(1, release.getCount(), "receiver is still held; client termination is independent");
            }
            if ("observe".equals(cancelPoint)) {
                assertTrue(proof.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .anyMatch(line -> line.contains("[LLM_REQUEST_LIFECYCLE]")
                                && line.contains("event=http_client_started")),
                        "client start must be observable before the held response completes");
            }
            if ("in_flight".equals(cancelPoint) || "late_success".equals(cancelPoint)
                    || "native_retry".equals(cancelPoint)) stop.run();
            release.countDown();
            caller.join(15_000);
            assertTrue(workerExited.await(5, TimeUnit.SECONDS));
            assertFalse(caller.isAlive());
            if ("none".equals(cancelPoint) || "observe".equals(cancelPoint) || "native_retry_control".equals(cancelPoint)) {
                assertNull(failure.get());
                assertEquals("controlled answer", response.get().aiMessage().text());
                assertEquals(2, requests.get(), "internal failure must retain fallback");
            } else if ("late_success".equals(cancelPoint)) {
                assertEquals(1, requests.get());
                assertEquals(0, fallbackResolutions.get());
                assertNull(response.get(), "Stop now interrupts the held HTTP call before the late success is released");
                assertNotNull(failure.get());
            } else {
                assertAll(
                        () -> assertEquals(1, requests.get(), "no new HTTP fallback after accepted Stop"),
                        () -> assertNull(response.get()),
                        () -> assertNotNull(failure.get()),
                        () -> assertEquals("resolver".equals(cancelPoint) ? 1 : 0, fallbackResolutions.get()));
            }
            var lifecycle = tracker.redactedRequestLifecycle(timelineId.get());
            assertTrue(lifecycle.stream().filter(row -> "http_client_started".equals(row.get("event")))
                    .allMatch(row -> Boolean.FALSE.equals(row.get("afterCancel"))));
            assertTrue(lifecycle.stream().allMatch(row -> Boolean.FALSE.equals(row.get("providerReceiptObserved"))));
        } finally {
            release.countDown();
            caller.join(15_000);
            server.stop(0);
            receiver.shutdownNow();
            assertTrue(receiver.awaitTermination(10, TimeUnit.SECONDS));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry, "shutdown");
            proofLogger.detachAppender(proof);
            proof.stop();
        }
    }
}
