package com.example.lms.llm;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.example.lms.api.ChatCancellationCommandHandler;
import com.example.lms.search.TraceStore;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.trace.TraceSnapshotStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class LlmRequestLifecycleTest {
    @AfterEach void clear() { TraceStore.clear(); Thread.interrupted(); }

    @ParameterizedTest
    @ValueSource(strings = {"none", "before_retry", "after_retry_selection"})
    void sdkRetryHasItsOwnAttemptAndCancellationNeverReachesReceiverAgain(String stopAt) throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int ordinal = received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = (ordinal == 1 ? "{\"error\":{\"message\":\"controlled failure\"}}"
                    : "{\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"fixture answer\"},\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(ordinal == 1 ? 500 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ChatRunRegistry registry = registry();
        ChatRunExecutionContext run = registry.beginOrJoin(72001L).context();
        AtomicBoolean stopped = new AtomicBoolean();
        Logger logger = (Logger) LoggerFactory.getLogger(ModelRuntimeHealthTracker.class.getName() + ".requestProof");
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                String line = event.getFormattedMessage();
                boolean boundary = "before_retry".equals(stopAt) && line.contains("event=http_client_failed")
                        || "after_retry_selection".equals(stopAt) && line.contains("attemptSequence=2 ")
                        && line.contains("event=application_call_intent");
                if (boundary && stopped.compareAndSet(false, true)) {
                    assertTrue(new ChatCancellationCommandHandler().cancel(72001L, run.clientToken(),
                            () -> true, registry, () -> {}).cancelled());
                }
            }
        };
        appender.start(); logger.addAppender(appender);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        try (var scope = ChatRunExecutionContext.bind(run)) {
            server.start();
            String timeline = timeline(tracker);
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ChatModel client = OpenAiChatModel.builder().baseUrl(base).modelName("fixture").apiKey("fixture")
                    .maxRetries(1).timeout(Duration.ofSeconds(5))
                    .httpClientBuilder(tracker.observedHttpClientBuilder("primary")).build();
            ChatModel model = tracker.decorateRequestAttempt(client, "primary",
                    tracker.redactedRequestAttemptRoute("fixture", "fixture", base, "openai"), Map.of());
            if ("none".equals(stopAt)) {
                assertEquals("fixture answer", model.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
                assertEquals(2, received.get());
            } else {
                assertThrows(RuntimeException.class, () -> model.chat(List.of(UserMessage.from("synthetic"))));
                assertTrue(stopped.get());
                assertEquals(1, received.get(), "the retried HTTP client invocation must not reach the receiver");
            }
            var rows = tracker.redactedRequestLifecycle(timeline);
            var starts = rows.stream().filter(r -> "http_client_started".equals(r.get("event"))).toList();
            assertEquals("none".equals(stopAt) ? 2 : 1, starts.size());
            assertEquals(starts.size(), starts.stream().map(r -> r.get("attemptSequence")).distinct().count());
            assertTrue(starts.stream().allMatch(r -> Boolean.FALSE.equals(r.get("afterCancel"))));
            assertTrue(starts.stream().allMatch(r -> "http_client_execute".equals(r.get("boundary"))));
            assertTrue(rows.stream().allMatch(r -> Boolean.FALSE.equals(r.get("providerReceiptObserved"))));
            assertEquals(1, starts.stream().map(r -> r.get("logicalCallOrdinal")).distinct().count());
        } finally {
            logger.detachAppender(appender); appender.stop();
            server.stop(0); ReflectionTestUtils.invokeMethod(registry, "shutdown");
        }
    }

    @Test
    void pendingSnapshotRetainsStartAndLaterSnapshotSeparatesLateCompletionAfterRealStop() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        ChatRunRegistry registry = registry();
        var run = registry.beginOrJoin(72002L).context();
        TraceSnapshotStore snapshots = snapshots();
        try (var scope = ChatRunExecutionContext.bind(run)) {
            String timeline = timeline(tracker);
            try (var attempt = tracker.beginClientAttempt("primary")) {
                attempt.started("spring_client_request_commit");
                String beforeId = snapshots.captureCurrent("llm_failure", "POST", "/api/chat/stream", 500, null);
                assertNotNull(beforeId);
                Object before = snapshots.get(beforeId).orElseThrow().trace().get(ModelRuntimeHealthTracker.REQUEST_LIFECYCLE_TRACE_KEY);
                assertInstanceOf(List.class, before, "snapshot must include the pending client start, before attempt close");
                assertTrue(before.toString().contains("http_client_started"));
                assertTrue(new ChatCancellationCommandHandler().cancel(72002L, run.clientToken(), () -> true,
                        registry, () -> {}).cancelled());
                tracker.recordRequestPhase(timeline, "terminal", null, null, "cancelled");
                attempt.finished(null); // already-started client completion, not a fresh dispatch
                var rows = tracker.redactedRequestLifecycle(timeline);
                var cancel = rows.stream().filter(r -> "cancel_accepted".equals(r.get("event"))).findFirst().orElseThrow();
                var terminal = rows.stream().filter(r -> "http_client_completed".equals(r.get("event"))).findFirst().orElseThrow();
                assertTrue((Integer) terminal.get("eventSequence") > (Integer) cancel.get("eventSequence"));
                assertEquals(true, terminal.get("afterCancel"));
                assertFalse(before.toString().contains("cancel_accepted"), "captured snapshot must be immutable");
            }
        } finally { ReflectionTestUtils.invokeMethod(registry, "shutdown"); }
    }

    @Test
    void snapshotRejectsPrivateFieldsAndUntrustedReceiptClaimsAndBoundsRows() {
        TraceSnapshotStore snapshots = snapshots();
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("event", "http_client_started");
        row.put("boundary", "http_client_execute");
        row.put("requestHash", "synthetic-private-marker");
        row.put("authorization", "synthetic-private-marker");
        row.put("prompt", "synthetic-private-marker");
        row.put("response", "synthetic-private-marker");
        row.put("attemptSequence", -1);
        row.put("providerReceiptObserved", true);
        var rows = new java.util.ArrayList<Object>();
        rows.add(null);
        rows.add(Map.of("event", "synthetic-private-marker", "boundary", "http_client_execute"));
        for (int i = 0; i < 140; i++) rows.add(row);
        TraceStore.put(ModelRuntimeHealthTracker.REQUEST_LIFECYCLE_TRACE_KEY, rows);

        String id = snapshots.captureCurrent("llm_failure", "POST", "/api/chat/stream", 500, null);
        Object value = snapshots.get(id).orElseThrow().trace().get(ModelRuntimeHealthTracker.REQUEST_LIFECYCLE_TRACE_KEY);

        List<?> safeRows = assertInstanceOf(List.class, value);
        assertEquals(128, safeRows.size());
        assertFalse(value.toString().contains("synthetic-private-marker"));
        assertTrue(safeRows.stream().allMatch(item -> Boolean.FALSE.equals(((Map<?, ?>) item).get("providerReceiptObserved"))));
        assertTrue(safeRows.stream().noneMatch(item -> ((Map<?, ?>) item).containsKey("attemptSequence")));
    }

    @Test
    void inFlightOverflowPublishesDropCountAndCapturedCounterDoesNotMutate() {
        var tracker = new ModelRuntimeHealthTracker();
        String timeline = timeline(tracker);
        var snapshots = snapshots();
        List<ModelRuntimeHealthTracker.ClientAttempt> pending = new ArrayList<>();
        try {
            for (int i=0; i<65; i++) {
                var attempt = tracker.beginClientAttempt("primary");
                pending.add(attempt);
                attempt.started("http_client_execute");
            }
            assertEquals(128, tracker.redactedRequestLifecycle(timeline).size());
            assertEquals(2, TraceStore.getLong("llm.request.lifecycleDropped"), "no attempt has closed yet");
            String id = snapshots.captureCurrent("llm_failure", "POST", "/api/chat/stream", 500, null);
            var before = snapshots.get(id).orElseThrow().trace();
            assertEquals(2, ((Number)before.get("llm.request.lifecycleDropped")).intValue());
            var last = tracker.beginClientAttempt("primary");
            pending.add(last);
            last.started("http_client_execute");
            assertEquals(4, TraceStore.getLong("llm.request.lifecycleDropped"));
            assertEquals(2, ((Number)before.get("llm.request.lifecycleDropped")).intValue(), "captured numeric counter must be detached");
        } finally {
            Collections.reverse(pending);
            pending.forEach(ModelRuntimeHealthTracker.ClientAttempt::close);
        }
    }

    private static TraceSnapshotStore snapshots() {
        TraceSnapshotStore snapshots = new TraceSnapshotStore(new StaticListableBeanFactory()
                .getBeanProvider(com.example.lms.service.trace.TraceHtmlBuilder.class));
        ReflectionTestUtils.setField(snapshots, "enabled", true);
        ReflectionTestUtils.setField(snapshots, "htmlEnabled", false);
        ReflectionTestUtils.setField(snapshots, "maxSize", 20);
        ReflectionTestUtils.setField(snapshots, "maxValueLen", 2000);
        ReflectionTestUtils.setField(snapshots, "maxEntries", 100);
        for (String field : List.of("allowReasonsCsv", "denyReasonsCsv", "allowKeysCsv", "denyKeysCsv")) {
            ReflectionTestUtils.setField(snapshots, field, "");
        }
        ReflectionTestUtils.setField(snapshots, "allowKeysMode", "any");
        ReflectionTestUtils.setField(snapshots, "captureSample", 1.0d);
        ReflectionTestUtils.setField(snapshots, "httpStatusMin", 400);
        ReflectionTestUtils.setField(snapshots, "maxPerTrace", 10);
        ReflectionTestUtils.setField(snapshots, "budgetWindowMs", 600_000L);
        return snapshots;
    }

    private static String timeline(ModelRuntimeHealthTracker tracker) {
        String id = tracker.beginRequestTimeline("synthetic-request", "synthetic-session");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, id);
        tracker.recordRequestPhase(id, "dispatch", null, null, "none");
        tracker.recordRequestPhase(id, "pending", null, null, "none");
        return id;
    }

    private static ChatRunRegistry registry() {
        ChatRunRegistry registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 32);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        return registry;
    }
}
