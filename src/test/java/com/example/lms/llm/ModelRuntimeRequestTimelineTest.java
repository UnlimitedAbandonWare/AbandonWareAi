package com.example.lms.llm;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ModelRuntimeRequestTimelineTest {

    private static final String RECEIPT_VERSION_HEADER = "X-AWX-Controlled-Provider-Receipt";
    private static final String RECEIPT_REQUEST_HASH_HEADER = "X-AWX-Provider-Request-SHA256";
    private static final String RECEIPT_REQUEST_BYTES_HEADER = "X-AWX-Provider-Request-Bytes";
    private static final String RECEIPT_RESPONSE_HASH_HEADER = "X-AWX-Provider-Response-SHA256";
    private static final String RECEIPT_RESPONSE_BYTES_HEADER = "X-AWX-Provider-Response-Bytes";

    @Test
    void routeSelectionKeepsAttemptSuccessSeparateFromFinalSemanticPromotion() {
        String rawRouteKey = "ROUTE_PRIVATE_81 owner-token=private-route-token";
        String rawEndpoint = "https://user:private-password@127.0.0.1:11434/v1/chat/completions"
                + "?api_key=sk-"" + ""privateEndpointabcdefghijklmnopqrstuvwxyz#private-fragment";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline(
                "semantic-request-private", "semantic-session-private");
        tracker.recordRequestPhase(timelineId, "dispatch", "qwen3:8b", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", "qwen3:8b", rawEndpoint, "none");
        tracker.recordRequestSelection(
                timelineId,
                "router",
                "local",
                rawRouteKey,
                "qwen3:8b",
                rawEndpoint,
                "openai_chat_completions",
                true,
                false);
        ModelRuntimeHealthTracker.RouteHealthKey route = tracker.requestRouteHealthKey(timelineId).orElseThrow();

        tracker.recordAttemptSuccess(route);

        assertTrue(tracker.snapshot(route).isEmpty());
        assertFalse(tracker.isPromotable(route));
        assertTrue(route.endpointHash().matches("hash:[0-9a-f]{12}"));
        assertTrue(route.context().matches(
                "router:openai_chat_completions:hash:[0-9a-f]{12}"), route.context());
        String publicTimeline = String.valueOf(tracker.redactedRequestTimeline(timelineId));
        String routeText = String.valueOf(route);
        for (String raw : List.of(
                rawRouteKey, "private-route-token", "private-password", "api_key",
                "privateEndpoint", "private-fragment")) {
            assertFalse(publicTimeline.contains(raw), publicTimeline);
            assertFalse(routeText.contains(raw), routeText);
        }

        boolean promoted = tracker.recordSemanticOutcome(
                timelineId,
                "qwen3:8b",
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.REQUIRED,
                        true,
                        true,
                        true,
                        true));

        assertTrue(promoted);
        assertTrue(tracker.isPromotable(route));
    }

    @Test
    void routeRequiredVerifierCannotBeDowngradedToNotRequiredPolicy() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("required-request", "required-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "model-a", null, "none");
        tracker.recordRequestPhase(
                timelineId, "pending", "model-a", "https://required.example.test/v1/responses", "none");
        tracker.recordRequestSelection(
                timelineId,
                "router",
                "openai",
                "required-route",
                "model-a",
                "https://required.example.test/v1/responses",
                "openai_responses",
                true,
                false);
        ModelRuntimeHealthTracker.RouteHealthKey route = tracker.requestRouteHealthKey(timelineId).orElseThrow();

        boolean promoted = tracker.recordSemanticOutcome(
                timelineId,
                "model-a",
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false,
                        true,
                        true,
                        true));

        assertFalse(promoted);
        assertFalse(tracker.isPromotable(route));
        assertTrue(tracker.snapshot(route).isEmpty());
    }

    @Test
    void explicitNotRequiredRoutePromotesOnlyAfterAcceptedFinalBoundary() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("optional-request", "optional-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "model-a", null, "none");
        tracker.recordRequestPhase(
                timelineId, "pending", "model-a", "https://optional.example.test/v1/chat/completions", "none");
        tracker.recordRequestSelection(
                timelineId,
                "router",
                "openai",
                "optional-route",
                "model-a",
                "https://optional.example.test/v1/chat/completions",
                "openai_chat_completions",
                false,
                false);
        ModelRuntimeHealthTracker.RouteHealthKey route = tracker.requestRouteHealthKey(timelineId).orElseThrow();

        boolean promoted = tracker.recordSemanticOutcome(
                timelineId,
                "model-a",
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                        false,
                        true,
                        true,
                        true));

        assertTrue(promoted);
        assertTrue(tracker.isPromotable(route));
    }

    @Test
    void finalModelMismatchAndAmbiguousFallbackNeverPromoteSelectedRoute() {
        ModelRuntimeHealthTracker mismatched = new ModelRuntimeHealthTracker();
        String mismatchedId = mismatched.beginRequestTimeline("mismatch-request", "mismatch-session");
        mismatched.recordRequestPhase(mismatchedId, "dispatch", "model-a", null, "none");
        mismatched.recordRequestPhase(
                mismatchedId, "pending", "model-a", "https://model-a.example.test/v1/responses", "none");
        mismatched.recordRequestSelection(
                mismatchedId,
                "router",
                "openai",
                "route-a",
                "model-a",
                "https://model-a.example.test/v1/responses",
                "openai_responses",
                true,
                false);
        ModelRuntimeHealthTracker.RouteHealthKey selected = mismatched
                .requestRouteHealthKey(mismatchedId).orElseThrow();

        assertFalse(mismatched.recordSemanticOutcome(
                mismatchedId,
                "model-b",
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.REQUIRED,
                        true, true, true, true)));
        assertFalse(mismatched.isPromotable(selected));

        ModelRuntimeHealthTracker ambiguous = new ModelRuntimeHealthTracker();
        String ambiguousId = ambiguous.beginRequestTimeline("ambiguous-request", "ambiguous-session");
        ambiguous.recordRequestPhase(ambiguousId, "dispatch", "model-a", null, "none");
        ambiguous.recordRequestPhase(
                ambiguousId, "pending", "model-a", "https://model-a.example.test/v1/responses", "none");
        ambiguous.recordRequestSelection(
                ambiguousId,
                "router",
                "openai",
                "route-a",
                "model-a",
                "https://model-a.example.test/v1/responses",
                "openai_responses",
                true,
                true);
        ModelRuntimeHealthTracker.RouteHealthKey ambiguousRoute = ambiguous
                .requestRouteHealthKey(ambiguousId).orElseThrow();

        assertFalse(ambiguous.recordSemanticOutcome(
                ambiguousId,
                "model-a",
                new ModelRuntimeHealthTracker.SemanticOutcome(
                        true,
                        ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                        ModelRuntimeHealthTracker.VerificationPolicy.REQUIRED,
                        true, true, true, true)));
        assertFalse(ambiguous.isPromotable(ambiguousRoute));
    }

    @Test
    void oneApplicationOwnedRequestKeepsOneRedactedIdentityAcrossDispatchPendingAndTerminal() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String rawRequestId = "request-raw-must-not-leak";
        String rawSessionId = "session-raw-must-not-leak";

        Method begin = requiredMethod(
                "beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method read = requiredMethod("redactedRequestTimeline", String.class);

        String timelineId = (String) begin.invoke(tracker, rawRequestId, rawSessionId);
        assertNotNull(timelineId);
        assertFalse(timelineId.isBlank());
        assertNotEquals(rawRequestId, timelineId);
        assertNotEquals(rawSessionId, timelineId);
        String secondTimelineId = (String) begin.invoke(
                tracker, "request-two-raw", "session-two-raw");
        assertNotEquals(timelineId, secondTimelineId,
                "two application requests must not collide on one timeline");

        record.invoke(tracker, timelineId, "dispatch", "qwen3:8b", null, "none");
        CompletableFuture.runAsync(() -> invokePhase(
                record, tracker, timelineId, "pending", "qwen3:8b", "http://127.0.0.1:11434/v1", "none"))
                .join();
        CompletableFuture.runAsync(() -> invokePhase(
                record, tracker, timelineId, "terminal", "qwen3:8b", null, "timeout"))
                .join();
        invokePhase(record, tracker, timelineId, "terminal", "qwen3:8b", null, "late_success");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) read.invoke(tracker, timelineId);
        assertEquals(List.of("dispatch", "pending", "terminal"),
                rows.stream().map(row -> String.valueOf(row.get("phase"))).toList());
        assertEquals(1, rows.stream().map(row -> row.get("timelineId")).distinct().count());
        assertEquals(1, rows.stream().map(row -> row.get("requestHash")).distinct().count());
        assertEquals(1, rows.stream().map(row -> row.get("sessionHash")).distinct().count());
        rows.forEach(row -> {
            assertNotNull(row.get("timelineId"));
            assertNotNull(row.get("requestHash"));
            assertNotNull(row.get("sessionHash"));
            assertFalse(String.valueOf(row.get("timelineId")).isBlank());
            assertFalse(String.valueOf(row.get("requestHash")).isBlank());
            assertFalse(String.valueOf(row.get("sessionHash")).isBlank());
        });
        assertEquals("127.0.0.1:11434", rows.get(1).get("endpointLabel"));
        assertEquals("timeout", rows.get(2).get("terminalClass"));

        String serialized = new ObjectMapper().writeValueAsString(rows);
        assertFalse(serialized.contains(rawRequestId));
        assertFalse(serialized.contains(rawSessionId));
        assertFalse(serialized.contains("qwen3:8b"));
    }

    @Test
    void controllerGeneratedPersistedDeliveryTaxonomyIsPreserved() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();

        for (String terminalClass : List.of(
                "generated_persisted_delivery_pending",
                "generated_persisted_delivery_failed")) {
            String timelineId = tracker.beginRequestTimeline(
                    "controller-terminal-request-" + terminalClass,
                    "controller-terminal-session-private");
            tracker.recordRequestPhase(timelineId, "dispatch", "controller-model-private", null, "none");
            tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
            tracker.recordRequestPhase(timelineId, "terminal", null, null, terminalClass);

            Map<String, Object> terminal = tracker.redactedRequestTimeline(timelineId).stream()
                    .filter(row -> "terminal".equals(row.get("phase")))
                    .findFirst()
                    .orElseThrow();
            assertEquals(terminalClass, terminal.get("terminalClass"),
                    "controller delivery state must not be collapsed to a generic error");
        }
    }

    @Test
    void controlledLoopbackReceiptAutomaticallyPromotesOneNativeAttemptBeforeFinalBoundary() throws Exception {
        HttpServer server = controlledReceiptServer(false);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline(
                "controlled-receipt-request-private",
                "controlled-receipt-session-private");
        tracker.recordRequestPhase(timelineId, "dispatch", "qwen3:8b-private", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        try {
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "qwen3:8b-private",
                    Duration.ofSeconds(5),
                    64,
                    0.0d,
                    null,
                    tracker,
                    false);

            ChatResponse response = model.chat(List.of(UserMessage.from("controlled-receipt-prompt-private")));
            String finalText = response.aiMessage().text();
            tracker.recordRequestPhase(
                    timelineId,
                    "final_boundary",
                    SafeRedactor.hashValue(finalText),
                    null,
                    "none");
            tracker.recordRequestPhase(
                    timelineId,
                    "terminal",
                    null,
                    null,
                    "generated_persisted_delivery_pending");

            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, rows.size());
            Map<String, Object> receipt = rows.get(0);
            assertEquals("provider_receive", receipt.get("evidenceBoundary"));
            assertEquals(Boolean.TRUE, receipt.get("providerReceiptObserved"));
            assertEquals("controlled_http_server", receipt.get("providerReceiptSource"));
            assertEquals(Boolean.TRUE, receipt.get("providerAttemptObserved"));
            assertEquals(Boolean.TRUE, receipt.get("wireAttemptObserved"));
            assertEquals("success", receipt.get("outcome"));
            assertEquals("none", receipt.get("failureClass"));
            assertEquals(1, receipt.get("logicalCallOrdinal"));
            assertEquals(1, receipt.get("attemptOrdinal"));
            Map<String, Object> finalBoundary = tracker.redactedRequestTimeline(timelineId).stream()
                    .filter(row -> "final_boundary".equals(row.get("phase")))
                    .findFirst()
                    .orElseThrow();
            assertEquals(receipt.get("responseHash"), finalBoundary.get("finalHash"));
            String redacted = rows + "\n" + tracker.redactedRequestTimeline(timelineId) + "\n" + TraceStore.getAll();
            assertFalse(redacted.contains("controlled-receipt-prompt-private"));
            assertFalse(redacted.contains("controlled-receipt-final-private"));
        } finally {
            TraceStore.clear();
            server.stop(0);
        }
    }

    @Test
    void tamperedControlledLoopbackReceiptCannotPromoteClientEvidence() throws Exception {
        HttpServer server = controlledReceiptServer(true);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline(
                "tampered-receipt-request-private",
                "tampered-receipt-session-private");
        tracker.recordRequestPhase(timelineId, "dispatch", "qwen3:8b-private", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        try {
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "qwen3:8b-private",
                    Duration.ofSeconds(5),
                    64,
                    0.0d,
                    null,
                    tracker,
                    false);

            model.chat(List.of(UserMessage.from("tampered-receipt-prompt-private")));

            Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
            assertEquals("client_http_response", row.get("evidenceBoundary"));
            assertEquals(Boolean.FALSE, row.get("providerReceiptObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
        } finally {
            TraceStore.clear();
            server.stop(0);
        }
    }

    @Test
    void untrustedLoopbackCannotSelfPromoteWithMatchingReceiptHeaders() throws Exception {
        HttpServer server = controlledReceiptServer(false);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline(
                "untrusted-loopback-request-private",
                "untrusted-loopback-session-private");
        tracker.recordRequestPhase(timelineId, "dispatch", "qwen3:8b-private", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        try {
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    "http://localhost:" + server.getAddress().getPort() + "/v1",
                    "qwen3:8b-private",
                    Duration.ofSeconds(5),
                    64,
                    0.0d,
                    null,
                    tracker,
                    false);

            model.chat(List.of(UserMessage.from("untrusted-loopback-prompt-private")));

            Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
            assertEquals("client_http_response", row.get("evidenceBoundary"));
            assertEquals(Boolean.FALSE, row.get("providerReceiptObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
        } finally {
            TraceStore.clear();
            server.stop(0);
        }
    }

    @Test
    void timeoutAndLateSuccessRaceProducesExactlyOneTerminal() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        Method begin = requiredMethod("beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method read = requiredMethod("redactedRequestTimeline", String.class);
        String timelineId = (String) begin.invoke(tracker, "race-request", "race-session");

        record.invoke(tracker, timelineId, "dispatch", "qwen3:8b", null, "none");
        record.invoke(tracker, timelineId, "pending", "qwen3:8b", "http://127.0.0.1:11434/v1", "none");
        CompletableFuture<Void> timeout = CompletableFuture.runAsync(() -> invokePhase(
                record, tracker, timelineId, "terminal", "qwen3:8b", null, "timeout"));
        CompletableFuture<Void> success = CompletableFuture.runAsync(() -> invokePhase(
                record, tracker, timelineId, "terminal", "qwen3:8b", null, "success"));
        CompletableFuture.allOf(timeout, success).join();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) read.invoke(tracker, timelineId);
        List<Map<String, Object>> terminals = rows.stream()
                .filter(row -> "terminal".equals(row.get("phase")))
                .toList();
        assertEquals(1, terminals.size());
        assertTrue(List.of("timeout", "success").contains(terminals.get(0).get("terminalClass")));
    }

    @Test
    void duplicatePhasesAreSuppressedAndOldestTimelineIsEvictedAtFixedCapacity() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        Method begin = requiredMethod("beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method read = requiredMethod("redactedRequestTimeline", String.class);

        String firstTimelineId = (String) begin.invoke(tracker, "first-request", "first-session");
        record.invoke(tracker, firstTimelineId, "dispatch", "qwen3:8b", null, "none");
        record.invoke(tracker, firstTimelineId, "dispatch", "qwen3:8b", null, "none");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> beforeEviction =
                (List<Map<String, Object>>) read.invoke(tracker, firstTimelineId);
        assertEquals(1, beforeEviction.size());

        for (int i = 1; i <= 256; i++) {
            begin.invoke(tracker, "request-" + i, "session-" + i);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evicted = (List<Map<String, Object>>) read.invoke(tracker, firstTimelineId);
        assertTrue(evicted.isEmpty(), "the oldest request timeline must be deterministically evicted");
    }

    @Test
    void internalTracePointerCarriesTheSameTimelineAcrossTheModelWorkerBoundary() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        Method begin = requiredMethod("beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method read = requiredMethod("redactedRequestTimeline", String.class);
        String timelineId = (String) begin.invoke(tracker, "worker-request", "worker-session");
        record.invoke(tracker, timelineId, "dispatch", "qwen3:8b", null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        Map<String, Object> capturedContext = TraceStore.context();

        CompletableFuture.runAsync(() -> {
            TraceStore.installContext(capturedContext);
            try {
                String propagated = String.valueOf(
                        TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY));
                invokePhase(record, tracker, propagated, "pending", "qwen3:8b", "127.0.0.1", "none");
                invokePhase(record, tracker, propagated, "terminal", "qwen3:8b", "127.0.0.1", "success");
            } finally {
                TraceStore.installContext(new ConcurrentHashMap<>());
            }
        }).join();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) read.invoke(tracker, timelineId);
        assertEquals(List.of("dispatch", "pending", "terminal"),
                rows.stream().map(row -> String.valueOf(row.get("phase"))).toList());
        assertFalse(TraceStore.getAll().containsKey(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY));
        TraceStore.clear();
    }

    @Test
    void nonUriEndpointLabelsCannotExposeSecretShapedInput() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        Method begin = requiredMethod("beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method read = requiredMethod("redactedRequestTimeline", String.class);
        String timelineId = (String) begin.invoke(tracker, "secret-request", "secret-session");
        String secretShapedEndpoint = "gsk_" + "endpoint_value_that_must_not_leak_123456789";

        record.invoke(tracker, timelineId, "dispatch", "qwen3:8b", null, "none");
        record.invoke(tracker, timelineId, "pending", "qwen3:8b", secretShapedEndpoint, "none");
        record.invoke(tracker, timelineId, "terminal", "qwen3:8b", null, "error");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) read.invoke(tracker, timelineId);
        String serialized = new ObjectMapper().writeValueAsString(rows);
        assertFalse(serialized.contains(secretShapedEndpoint));
        assertEquals("unknown", rows.get(1).get("endpointLabel"));
    }

    @Test
    void duplicatePendingEnrichesTheExistingRowWithoutCreatingAnotherPhase() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        Method begin = requiredMethod("beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method read = requiredMethod("redactedRequestTimeline", String.class);
        String timelineId = (String) begin.invoke(tracker, "enrich-request", "enrich-session");

        record.invoke(tracker, timelineId, "dispatch", null, null, "none");
        record.invoke(tracker, timelineId, "pending", null, null, "none");
        record.invoke(tracker, timelineId, "pending", "qwen3:8b", "http://127.0.0.1:11434/v1", "none");
        record.invoke(tracker, timelineId, "pending", "other-model", "http://127.0.0.1:11435/v1", "none");
        record.invoke(tracker, timelineId, "terminal", null, null, "success");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) read.invoke(tracker, timelineId);
        assertEquals(List.of("dispatch", "pending", "terminal"),
                rows.stream().map(row -> String.valueOf(row.get("phase"))).toList());
        assertEquals("127.0.0.1:11434", rows.get(1).get("endpointLabel"));
        assertNotEquals("unknown", rows.get(1).get("modelHash"));
        assertEquals(SafeRedactor.hashValue("qwen3:8b"), rows.get(1).get("modelHash"));
        assertEquals(rows.get(1).get("endpointLabel"), rows.get(2).get("endpointLabel"));
    }

    @Test
    void routerSelectionNeedsEndpointSpecificTerminalAndMatchingFreshProbesBeforeRepairIsReady()
            throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        Method begin = requiredMethod("beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method select = requiredMethod(
                "recordRequestSelection", String.class, String.class, String.class,
                String.class, String.class, boolean.class);
        Method probe = requiredMethod(
                "recordEndpointProbe", String.class, String.class, String.class,
                String.class, boolean.class, long.class);
        Method readiness = requiredMethod("endpointRepairReadiness", String.class);

        String timelineId = (String) begin.invoke(tracker, "repair-request", "repair-session");
        record.invoke(tracker, timelineId, "dispatch", "llmrouter.light", null, "none");
        record.invoke(tracker, timelineId, "pending", null, null, "none");
        select.invoke(tracker, timelineId, "router", "qwen3:8b",
                "http://127.0.0.1:11435/v1", "openai_chat_completions", false);

        assertRepairDecision(readiness.invoke(tracker, timelineId), "HOLD", "terminal_missing");

        record.invoke(tracker, timelineId, "terminal", null, null, "upstream_5xx");
        assertRepairDecision(readiness.invoke(tracker, timelineId), "HOLD", "probe_missing");

        long now = System.currentTimeMillis();
        probe.invoke(tracker, timelineId, "http://127.0.0.1:11435/v1", "qwen3:8b",
                "openai_chat_completions", false, now);
        probe.invoke(tracker, timelineId, "http://127.0.0.1:11434/v1", "qwen3:8b",
                "openai_chat_completions", true, now);

        Object decision = readiness.invoke(tracker, timelineId);
        assertRepairDecision(decision, "READY", "exact_evidence");
        assertEquals("127.0.0.1:11435", recordAccessor(decision, "endpointLabel"));
        String serialized = new ObjectMapper().writeValueAsString(tracker.redactedRequestTimeline(timelineId))
                + new ObjectMapper().writeValueAsString(decision);
        assertFalse(serialized.contains("repair-request"));
        assertFalse(serialized.contains("repair-session"));
        assertFalse(serialized.contains("qwen3:8b"));
        assertFalse(serialized.contains("http://127.0.0.1:11435/v1"));
    }

    @Test
    void ambiguousFallbackImplicitPortAndNonEndpointTerminalKeepRepairOnHold() throws Exception {
        Method begin = requiredMethod("beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method select = requiredMethod(
                "recordRequestSelection", String.class, String.class, String.class,
                String.class, String.class, boolean.class);
        Method readiness = requiredMethod("endpointRepairReadiness", String.class);

        ModelRuntimeHealthTracker ambiguous = new ModelRuntimeHealthTracker();
        String ambiguousId = (String) begin.invoke(ambiguous, "ambiguous-request", "ambiguous-session");
        record.invoke(ambiguous, ambiguousId, "dispatch", "llmrouter.auto", null, "none");
        record.invoke(ambiguous, ambiguousId, "pending", null, null, "none");
        select.invoke(ambiguous, ambiguousId, "router", "qwen3:8b",
                "http://127.0.0.1:11435/v1", "openai_chat_completions", true);
        record.invoke(ambiguous, ambiguousId, "terminal", null, null, "upstream_5xx");
        assertRepairDecision(readiness.invoke(ambiguous, ambiguousId), "HOLD", "ambiguous_route_attempts");

        ModelRuntimeHealthTracker implicitPort = new ModelRuntimeHealthTracker();
        String implicitId = (String) begin.invoke(implicitPort, "implicit-request", "implicit-session");
        record.invoke(implicitPort, implicitId, "dispatch", "llmrouter.light", null, "none");
        record.invoke(implicitPort, implicitId, "pending", null, null, "none");
        select.invoke(implicitPort, implicitId, "router", "remote-model",
                "https://models.example.test/v1", "openai_chat_completions", false);
        record.invoke(implicitPort, implicitId, "terminal", null, null, "upstream_5xx");
        assertRepairDecision(readiness.invoke(implicitPort, implicitId), "HOLD", "endpoint_port_missing");

        ModelRuntimeHealthTracker cancelled = new ModelRuntimeHealthTracker();
        String cancelledId = (String) begin.invoke(cancelled, "cancel-request", "cancel-session");
        record.invoke(cancelled, cancelledId, "dispatch", "llmrouter.light", null, "none");
        record.invoke(cancelled, cancelledId, "pending", null, null, "none");
        select.invoke(cancelled, cancelledId, "router", "qwen3:8b",
                "http://127.0.0.1:11435/v1", "openai_chat_completions", false);
        record.invoke(cancelled, cancelledId, "terminal", null, null, "cancelled");
        assertRepairDecision(readiness.invoke(cancelled, cancelledId), "HOLD", "terminal_not_endpoint_specific");
    }

    @Test
    void staleOrMismatchedProbeEvidenceCannotReleaseTheRepairGate() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        Method begin = requiredMethod("beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method select = requiredMethod(
                "recordRequestSelection", String.class, String.class, String.class,
                String.class, String.class, boolean.class);
        Method probe = requiredMethod(
                "recordEndpointProbe", String.class, String.class, String.class,
                String.class, boolean.class, long.class);
        Method readiness = requiredMethod("endpointRepairReadiness", String.class);

        String timelineId = (String) begin.invoke(tracker, "stale-request", "stale-session");
        record.invoke(tracker, timelineId, "dispatch", "llmrouter.light", null, "none");
        record.invoke(tracker, timelineId, "pending", null, null, "none");
        select.invoke(tracker, timelineId, "router", "qwen3:8b",
                "http://127.0.0.1:11435/v1", "openai_chat_completions", false);
        record.invoke(tracker, timelineId, "terminal", null, null, "model_unavailable");
        long stale = System.currentTimeMillis() - 600_000L;
        probe.invoke(tracker, timelineId, "http://127.0.0.1:11435/v1", "qwen3:8b",
                "openai_chat_completions", false, stale);
        probe.invoke(tracker, timelineId, "http://127.0.0.1:11434/v1", "other-model",
                "openai_chat_completions", true, System.currentTimeMillis());

        assertRepairDecision(readiness.invoke(tracker, timelineId), "HOLD", "probe_predates_terminal");
    }

    @Test
    void probesObservedBeforeTheEndpointSpecificTerminalCannotReleaseTheRepairGate() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        Method begin = requiredMethod("beginRequestTimeline", String.class, String.class);
        Method record = requiredMethod(
                "recordRequestPhase", String.class, String.class, String.class, String.class, String.class);
        Method select = requiredMethod(
                "recordRequestSelection", String.class, String.class, String.class,
                String.class, String.class, boolean.class);
        Method probe = requiredMethod(
                "recordEndpointProbe", String.class, String.class, String.class,
                String.class, boolean.class, long.class);
        Method readiness = requiredMethod("endpointRepairReadiness", String.class);

        String timelineId = (String) begin.invoke(tracker, "ordered-request", "ordered-session");
        record.invoke(tracker, timelineId, "dispatch", "llmrouter.light", null, "none");
        record.invoke(tracker, timelineId, "pending", null, null, "none");
        select.invoke(tracker, timelineId, "router", "qwen3:8b",
                "http://127.0.0.1:11435/v1", "openai_chat_completions", false);
        long beforeTerminal = System.currentTimeMillis() - 1_000L;
        probe.invoke(tracker, timelineId, "http://127.0.0.1:11435/v1", "qwen3:8b",
                "openai_chat_completions", false, beforeTerminal);
        probe.invoke(tracker, timelineId, "http://127.0.0.1:11434/v1", "qwen3:8b",
                "openai_chat_completions", true, beforeTerminal);
        record.invoke(tracker, timelineId, "terminal", null, null, "upstream_5xx");

        assertRepairDecision(readiness.invoke(tracker, timelineId), "HOLD", "probe_predates_terminal");
    }

    @Test
    void attemptLedgerIsBoundedAndRejectsWritesAfterTheControllerTerminalLatch() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                ModelRuntimeHealthTracker.class.getName() + ".requestProof");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = tracker.beginRequestTimeline(
                    "bounded-request-private", "bounded-session-private");
            tracker.recordRequestPhase(timelineId, "dispatch", "llmrouter.light", null, "none");
            tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
            ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                    "route-private",
                    "model-private",
                    "http://user:password@127.0.0.1:11435/v1?token=private",
                    "openai_chat_completions");

            for (int i = 0; i < 10; i++) {
                tracker.recordRequestAttempt(
                        timelineId,
                        i % 2 == 0 ? "primary" : "fallback",
                        route,
                        "failed",
                        "unknown",
                        "error",
                        i);
            }

            List<Map<String, Object>> beforeTerminal = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(8, beforeTerminal.size());
            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8),
                    beforeTerminal.stream().map(row -> row.get("sequence")).toList());
            assertTrue(beforeTerminal.stream().allMatch(row -> Integer.valueOf(10).equals(row.get("attemptTotal"))));
            assertTrue(beforeTerminal.stream().allMatch(row -> Integer.valueOf(2).equals(row.get("attemptDropped"))));

            String requestHash = String.valueOf(beforeTerminal.get(0).get("requestHash"));
            List<String> proofLines = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("[LLM_REQUEST_PROOF]"))
                    .filter(line -> line.contains("requestHash=" + requestHash))
                    .toList();
            List<String> acceptedMarkers = proofLines.stream()
                    .filter(line -> line.contains("rowAccepted=true"))
                    .toList();
            List<String> droppedMarkers = proofLines.stream()
                    .filter(line -> line.contains("rowAccepted=false"))
                    .toList();
            assertEquals(8, acceptedMarkers.size());
            assertEquals(2, droppedMarkers.size());
            assertTrue(droppedMarkers.get(1).contains("attemptTotal=10"));
            assertTrue(droppedMarkers.get(1).contains("attemptDropped=2"));
            assertTrue(droppedMarkers.stream().noneMatch(line -> line.contains("promptHash=")));
            assertTrue(droppedMarkers.stream().noneMatch(line -> line.contains("optionsHash=")));
            assertTrue(droppedMarkers.stream().noneMatch(line -> line.contains("responseHash=")));
            assertTrue(droppedMarkers.stream().noneMatch(line -> line.contains("httpRequestBodyHash=")));
            assertTrue(droppedMarkers.stream().noneMatch(line -> line.contains("httpResponseBodyHash=")));
            assertTrue(droppedMarkers.stream().noneMatch(line -> line.contains("role=")));
            assertTrue(droppedMarkers.stream().noneMatch(line -> line.contains("adapterAttempt=")));

            int markerCountBeforeRejectedWrites = proofLines.size();
            tracker.recordRequestAttempt(
                    timelineId, "invalid-role", route, "success", "none", "success", 1L);
            tracker.recordRequestPhase(timelineId, "terminal", null, null, "timeout");
            tracker.recordRequestAttempt(
                    timelineId,
                    "fallback",
                    route,
                    "success",
                    "none",
                    "success",
                    1L);

            long markerCountAfterRejectedWrites = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("[LLM_REQUEST_PROOF]"))
                    .filter(line -> line.contains("requestHash=" + requestHash))
                    .count();
            assertEquals(markerCountBeforeRejectedWrites, markerCountAfterRejectedWrites,
                    "invalid and terminal-latched writes must not emit any marker");
            assertEquals(beforeTerminal, tracker.redactedRequestAttemptLedger(timelineId));
            String serialized = beforeTerminal + "\n" + String.join("\n", proofLines);
            assertFalse(serialized.contains("bounded-request-private"));
            assertFalse(serialized.contains("bounded-session-private"));
            assertFalse(serialized.contains("route-private"));
            assertFalse(serialized.contains("model-private"));
            assertFalse(serialized.contains("user:password"));
            assertFalse(serialized.contains("/v1"));
            assertFalse(serialized.contains("token=private"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void finalBoundaryAcceptsOneProofHashWithoutReplacingTheSelectedModelIdentity() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("final-request-private", "final-session-private");
        tracker.recordRequestPhase(timelineId, "dispatch", "fixture-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");

        tracker.recordRequestPhase(timelineId, "final_boundary", "raw-final-answer", null, "none");
        assertEquals(List.of("dispatch", "pending"), tracker.redactedRequestTimeline(timelineId).stream()
                .map(row -> row.get("phase"))
                .toList(), "raw content is not a valid final-boundary proof hash");

        tracker.recordRequestPhase(timelineId, "final_boundary", "hash:333333333333", null, "none");
        tracker.recordRequestPhase(timelineId, "final_boundary", "hash:444444444444", null, "none");
        tracker.recordRequestPhase(timelineId, "terminal", null, null, "success");

        List<Map<String, Object>> rows = tracker.redactedRequestTimeline(timelineId);
        assertEquals(List.of("dispatch", "pending", "final_boundary", "terminal"), rows.stream()
                .map(row -> row.get("phase"))
                .toList());
        Map<String, Object> finalBoundary = rows.get(2);
        assertEquals("hash:333333333333", finalBoundary.get("finalHash"));
        assertEquals(rows.get(1).get("modelHash"), finalBoundary.get("modelHash"),
                "the selected answer hash must not be reinterpreted as a model identity");
        String serialized = new ObjectMapper().writeValueAsString(rows);
        assertFalse(serialized.contains("raw-final-answer"));
        assertFalse(serialized.contains("final-request-private"));
        assertFalse(serialized.contains("final-session-private"));
    }

    @Test
    void ordinaryClientHttpEvidenceCannotForgeAControlledProviderReceipt() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("receipt-request-private", "receipt-session-private");
        tracker.recordRequestPhase(timelineId, "dispatch", "fixture-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "fixture-route", "fixture-model", "http://127.0.0.1:11434/v1", "ollama_native");
        String requestBodyHash = "sha256:" + "1".repeat(64);
        String responseBodyHash = "sha256:" + "2".repeat(64);

        tracker.recordRequestAttemptEvidence(
                timelineId,
                "primary",
                route,
                "success",
                "none",
                "success",
                3L,
                "hash:aaaaaaaaaaaa",
                "hash:bbbbbbbbbbbb",
                1,
                1,
                "hash:cccccccccccc",
                2,
                10,
                20,
                2,
                requestBodyHash,
                30,
                responseBodyHash,
                40,
                true,
                true,
                true,
                true,
                true,
                true);

        Map<String, Object> clientOnly = tracker.redactedRequestAttemptLedger(timelineId).get(0);
        assertEquals(1, clientOnly.get("logicalCallOrdinal"));
        assertEquals(1, clientOnly.get("attemptOrdinal"));
        assertEquals("client_http_response", clientOnly.get("evidenceBoundary"));
        assertEquals(Boolean.FALSE, clientOnly.get("providerReceiptObserved"));
        assertEquals("not_observed", clientOnly.get("providerReceiptSource"));
        assertEquals(Boolean.FALSE, clientOnly.get("providerAttemptObserved"));
        assertEquals(Boolean.FALSE, clientOnly.get("wireAttemptObserved"));

        assertFalse(tracker.recordControlledProviderReceipt(
                timelineId, 2, 1, requestBodyHash, 30, responseBodyHash, 40));
        assertFalse(tracker.recordControlledProviderReceipt(
                timelineId, 1, 2, requestBodyHash, 30, responseBodyHash, 40));
        assertFalse(tracker.recordControlledProviderReceipt(
                timelineId, 1, 1, "sha256:" + "9".repeat(64), 30, responseBodyHash, 40));
        assertFalse(tracker.recordControlledProviderReceipt(
                timelineId, 1, 1, requestBodyHash, 30, responseBodyHash, 41));
        assertEquals(clientOnly, tracker.redactedRequestAttemptLedger(timelineId).get(0),
                "mismatched receipt evidence must not partially enrich the row");

        assertTrue(tracker.recordControlledProviderReceipt(
                timelineId, 1, 1, requestBodyHash, 30, responseBodyHash, 40));
        assertFalse(tracker.recordControlledProviderReceipt(
                timelineId, 1, 1, requestBodyHash, 30, responseBodyHash, 40),
                "one controlled receiver observation can enrich a row only once");

        List<Map<String, Object>> enrichedRows = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, enrichedRows.size(), "receipt enrichment must replace, not duplicate, the attempt row");
        Map<String, Object> enriched = enrichedRows.get(0);
        assertEquals(1, enriched.get("logicalCallOrdinal"));
        assertEquals(1, enriched.get("attemptOrdinal"));
        assertEquals("provider_receive", enriched.get("evidenceBoundary"));
        assertEquals(Boolean.TRUE, enriched.get("providerReceiptObserved"));
        assertEquals("controlled_http_server", enriched.get("providerReceiptSource"));
        assertEquals(Boolean.TRUE, enriched.get("providerAttemptObserved"));
        assertEquals(Boolean.TRUE, enriched.get("wireAttemptObserved"));
        assertEquals(1, enriched.get("attemptTotal"));
        assertEquals(0, enriched.get("attemptDropped"));
    }

    @Test
    void attestedVercelGatewayReceiptPromotesOnlyTheExactZaiResponsesRow() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline(
                "vercel-receipt-request-private", "vercel-receipt-session-private");
        tracker.recordRequestPhase(timelineId, "dispatch", "zai/glm-5.2", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "subagent_glm", "zai/glm-5.2",
                "https://ai-gateway.vercel.sh/v1/responses", "openai_responses");
        String requestBodyHash = "sha256:" + "5".repeat(64);
        String responseBodyHash = "sha256:" + "6".repeat(64);
        tracker.recordRequestAttemptEvidence(
                timelineId, "primary", route, "success", "none", "success", 4L,
                "hash:aaaaaaaaaaaa", "hash:bbbbbbbbbbbb", 1, 1,
                "hash:cccccccccccc", 2, 10, 20, 2,
                requestBodyHash, 30, responseBodyHash, 40,
                true, true, true, false, false, true);

        assertFalse(tracker.recordAttestedVercelGatewayReceipt(
                timelineId, "http://127.0.0.1:18080/v1/responses", "openai_responses",
                "zai", 200, 1, requestBodyHash, 30, responseBodyHash, 40));
        assertFalse(tracker.recordAttestedVercelGatewayReceipt(
                timelineId, "https://ai-gateway.vercel.sh/v1/responses", "openai_responses",
                "openai", 200, 1, requestBodyHash, 30, responseBodyHash, 40));
        assertFalse(tracker.recordAttestedVercelGatewayReceipt(
                timelineId, "https://ai-gateway.vercel.sh/v1/responses", "openai_responses",
                "zai", 201, 1, requestBodyHash, 30, responseBodyHash, 40));
        assertFalse(tracker.recordAttestedVercelGatewayReceipt(
                timelineId, "https://ai-gateway.vercel.sh/v1/responses", "openai_responses",
                "zai", 200, 9, requestBodyHash, 30, responseBodyHash, 40));
        assertEquals("client_http_response",
                tracker.redactedRequestAttemptLedger(timelineId).get(0).get("evidenceBoundary"));

        assertTrue(tracker.recordAttestedVercelGatewayReceipt(
                timelineId, "https://ai-gateway.vercel.sh/v1/responses", "openai_responses",
                "zai", 200, 2, requestBodyHash, 30, responseBodyHash, 40));
        Map<String, Object> enriched = tracker.redactedRequestAttemptLedger(timelineId).get(0);
        assertEquals("provider_receive", enriched.get("evidenceBoundary"));
        assertEquals(Boolean.TRUE, enriched.get("providerReceiptObserved"));
        assertEquals("vercel_ai_gateway_routing", enriched.get("providerReceiptSource"));
        assertEquals(Boolean.TRUE, enriched.get("providerAttemptObserved"));
        assertEquals(Boolean.TRUE, enriched.get("wireAttemptObserved"));
        assertFalse(tracker.recordAttestedVercelGatewayReceipt(
                timelineId, "https://ai-gateway.vercel.sh/v1/responses", "openai_responses",
                "zai", 200, 2, requestBodyHash, 30, responseBodyHash, 40));
    }

    @Test
    void anyDroppedAttemptBlocksControlledProviderReceiptEnrichment() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("drop-request-private", "drop-session-private");
        tracker.recordRequestPhase(timelineId, "dispatch", "fixture-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "fixture-route", "fixture-model", "http://127.0.0.1:11434/v1", "ollama_native");
        String requestBodyHash = "sha256:" + "3".repeat(64);
        String responseBodyHash = "sha256:" + "4".repeat(64);
        tracker.recordRequestAttemptEvidence(
                timelineId, "primary", route, "success", "none", "success", 1L,
                "hash:aaaaaaaaaaaa", "hash:bbbbbbbbbbbb", 1, 1,
                "hash:cccccccccccc", 2, 10, 20, 2,
                requestBodyHash, 30, responseBodyHash, 40,
                true, true, true, false, false, true);
        for (int i = 0; i < ModelRuntimeHealthTracker.REQUEST_ATTEMPT_LEDGER_CAPACITY; i++) {
            tracker.recordRequestAttempt(
                    timelineId, "fallback", route, "failed", "unknown", "error", i);
        }

        Map<String, Object> first = tracker.redactedRequestAttemptLedger(timelineId).get(0);
        assertEquals(9, first.get("attemptTotal"));
        assertEquals(1, first.get("attemptDropped"));
        assertFalse(tracker.recordControlledProviderReceipt(
                timelineId, 1, 1, requestBodyHash, 30, responseBodyHash, 40));
        assertEquals("client_http_response", tracker.redactedRequestAttemptLedger(timelineId)
                .get(0).get("evidenceBoundary"));
        assertEquals(Boolean.FALSE, tracker.redactedRequestAttemptLedger(timelineId)
                .get(0).get("providerReceiptObserved"));
    }

    @Test
    void acceptedAttemptRowsEmitOneCentralCountOnlyProofLineAndRejectedRowsEmitNone() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                ModelRuntimeHealthTracker.class.getName() + ".requestProof");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String rawRequestId = "proof-request-private-must-not-leak";
            String timelineId = tracker.beginRequestTimeline(rawRequestId, "proof-session-private-must-not-leak");
            tracker.recordRequestPhase(timelineId, "dispatch", "proof-model-private", null, "none");
            tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
            ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                    "proof-route-private",
                    "proof-model-private",
                    "http://user:private@127.0.0.1:11434/v1?token=private",
                    "openai_responses");

            tracker.recordRequestAttempt(
                    timelineId, "primary", route, "failed", "unknown", "error", 4L);
            tracker.recordRequestAttemptEvidence(
                    timelineId,
                    "fallback",
                    route,
                    "success",
                    "none",
                    "success",
                    7L,
                    "sha256:" + "a".repeat(64),
                    "hash:bbbbbbbbbbbb",
                    2,
                    3,
                    "sha256:" + "c".repeat(64),
                    11,
                    22,
                    33,
                    44,
                    "sha256:" + "d".repeat(64),
                    55,
                    "sha256:" + "e".repeat(64),
                    66,
                    true,
                    true,
                    true,
                    false,
                    false,
                    true);
            tracker.recordRequestAttempt(
                    timelineId, "invalid-role", route, "success", "none", "success", 1L);
            tracker.recordRequestPhase(timelineId, "terminal", null, null, "success");
            tracker.recordRequestAttempt(
                    timelineId, "fallback", route, "success", "none", "success", 1L);

            List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(2, attempts.size());
            assertEquals("hash:unknown", attempts.get(0).get("httpRequestBodyHash"),
                    "the compatibility overload must fail closed for non-HTTP callers");
            assertEquals(0, attempts.get(0).get("httpRequestBodyUtf8ByteCount"));
            assertEquals("hash:unknown", attempts.get(0).get("httpResponseBodyHash"));
            assertEquals(0, attempts.get(0).get("httpResponseBodyUtf8ByteCount"));
            assertEquals("sha256:" + "d".repeat(64), attempts.get(1).get("httpRequestBodyHash"));
            assertEquals(55, attempts.get(1).get("httpRequestBodyUtf8ByteCount"));
            assertEquals("sha256:" + "e".repeat(64), attempts.get(1).get("httpResponseBodyHash"));
            assertEquals(66, attempts.get(1).get("httpResponseBodyUtf8ByteCount"));
            String requestHash = String.valueOf(attempts.get(0).get("requestHash"));
            List<String> proofLines = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("[LLM_REQUEST_PROOF]"))
                    .filter(line -> line.contains("requestHash=" + requestHash))
                    .toList();

            assertEquals(2, proofLines.size(),
                    "one central line is required for each accepted row and none for rejected rows");
            assertTrue(proofLines.stream().allMatch(line -> line.contains("rowAccepted=true")));
            assertTrue(proofLines.get(0).contains("sequence=1"));
            assertTrue(proofLines.get(1).contains("sequence=2"));
            assertTrue(proofLines.get(1).contains("role=fallback"));
            assertTrue(proofLines.get(1).contains("attemptTotal=2"));
            assertTrue(proofLines.get(1).contains("attemptDropped=0"));
            assertTrue(proofLines.get(1).contains("promptHash=sha256:" + "a".repeat(64)));
            assertTrue(proofLines.get(1).contains("optionsHash=hash:bbbbbbbbbbbb"));
            assertTrue(proofLines.get(1).contains("responseHash=sha256:" + "c".repeat(64)));
            assertTrue(proofLines.get(1).contains("promptItems=2"));
            assertTrue(proofLines.get(1).contains("optionItems=3"));
            assertTrue(proofLines.get(1).contains("promptUtf8Bytes=22"));
            assertTrue(proofLines.get(1).contains("optionsUtf8Bytes=33"));
            assertTrue(proofLines.get(1).contains("responseUtf8Bytes=44"));
            assertTrue(proofLines.get(1).contains("httpRequestBodyHash=sha256:" + "d".repeat(64)));
            assertTrue(proofLines.get(1).contains("httpRequestBodyUtf8Bytes=55"));
            assertTrue(proofLines.get(1).contains("httpResponseBodyHash=sha256:" + "e".repeat(64)));
            assertTrue(proofLines.get(1).contains("httpResponseBodyUtf8Bytes=66"));
            assertTrue(proofLines.get(1).contains("adapterAttempt=true"));
            assertTrue(proofLines.get(1).contains("clientHttpExchange=true"));
            assertTrue(proofLines.get(1).contains("clientHttpResponse=true"));
            assertTrue(proofLines.get(1).contains("providerAttempt=false"));
            assertTrue(proofLines.get(1).contains("wireAttempt=false"));
            assertTrue(proofLines.get(1).contains("responseObserved=true"));
            String rendered = String.join("\n", proofLines);
            assertFalse(rendered.contains(rawRequestId));
            assertFalse(rendered.contains("proof-session-private"));
            assertFalse(rendered.contains("proof-route-private"));
            assertFalse(rendered.contains("proof-model-private"));
            assertFalse(rendered.contains("user:private"));
            assertFalse(rendered.contains("token=private"));
            assertFalse(rendered.contains("timelineId="));
            assertFalse(rendered.contains("sessionHash="));
            assertFalse(rendered.contains("endpoint"));
            assertFalse(rendered.contains("protocol="));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void applicationBoundaryDecoratorRecordsCanonicalCountAndHashOnlySuccessEvidence() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("request-fixture", "session-fixture");
        tracker.recordRequestPhase(timelineId, "dispatch", "fixture-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "fixture-route", "fixture-model", "http://127.0.0.1:11434/v1", "openai_chat_completions");
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from("assistant-fixture")).build();
            }
        };

        Method decorate = requiredMethod(
                "decorateRequestAttempt",
                ChatModel.class,
                String.class,
                ModelRuntimeHealthTracker.RequestAttemptRoute.class,
                Map.class);
        ChatModel decorated = (ChatModel) decorate.invoke(
                tracker,
                delegate,
                "primary",
                route,
                Map.of("temperature", 1, "maxTokens", 32));

        decorated.chat(List.of(SystemMessage.from("system-fixture"), UserMessage.from("user-fixture")));

        List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("success", row.get("outcome"));
        assertEquals("none", row.get("failureClass"));
        assertEquals(SafeRedactor.hashValue(
                "[{\"role\":\"system\",\"content\":\"system-fixture\"},{\"role\":\"user\",\"content\":\"user-fixture\"}]"),
                row.get("promptHash"));
        assertEquals(SafeRedactor.hashValue("{\"maxTokens\":32,\"temperature\":1}"), row.get("optionsHash"));
        assertEquals(SafeRedactor.hashValue("assistant-fixture"), row.get("responseHash"));
        assertEquals(2, row.get("promptItemCount"));
        assertEquals(2, row.get("optionItemCount"));
        assertEquals(Boolean.TRUE, row.get("modelAdapterAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("clientHttpExchangeObserved"));
        assertEquals(Boolean.FALSE, row.get("clientHttpResponseObserved"));
        assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
        assertEquals(Boolean.TRUE, row.get("responseObserved"));
        assertEquals(1, row.get("logicalCallOrdinal"));
        assertEquals(1, row.get("attemptOrdinal"));
        assertEquals("model_adapter", row.get("evidenceBoundary"));
        assertEquals(Boolean.FALSE, row.get("providerReceiptObserved"));
        assertEquals("not_observed", row.get("providerReceiptSource"));
        assertEquals("hash:unknown", row.get("httpRequestBodyHash"));
        assertEquals("hash:unknown", row.get("httpResponseBodyHash"));
        String serialized = new ObjectMapper().writeValueAsString(rows);
        assertFalse(serialized.contains("system-fixture"));
        assertFalse(serialized.contains("user-fixture"));
        assertFalse(serialized.contains("assistant-fixture"));
        TraceStore.clear();
    }

    @Test
    void applicationBoundaryDecoratorDoesNotClassifyMarkerTextAsDisabledAndKeepsNestedAttemptsSingle() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("disabled-request-fixture", "disabled-session-fixture");
        tracker.recordRequestPhase(timelineId, "dispatch", "fixture-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "fixture-route", "fixture-model", "http://127.0.0.1:11434/v1", "openai_chat_completions");
        ChatModel markerTextDelegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"))
                        .build();
            }
        };

        tracker.decorateRequestAttempt(markerTextDelegate, "primary", route, Map.of())
                .chat(List.of(UserMessage.from("disabled-fixture")));

        List<Map<String, Object>> markerRows = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, markerRows.size());
        Map<String, Object> markerRow = markerRows.get(0);
        assertEquals("success", markerRow.get("outcome"));
        assertEquals("none", markerRow.get("failureClass"));
        assertEquals(Boolean.FALSE, markerRow.get("clientHttpExchangeObserved"));
        assertEquals(Boolean.FALSE, markerRow.get("clientHttpResponseObserved"));
        assertEquals(Boolean.FALSE, markerRow.get("providerAttemptObserved"));
        assertEquals(Boolean.FALSE, markerRow.get("wireAttemptObserved"));
        assertFalse(new ObjectMapper().valueToTree(markerRows).toString()
                .contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"));

        String nestedTimelineId = tracker.beginRequestTimeline("nested-request-fixture", "nested-session-fixture");
        tracker.recordRequestPhase(nestedTimelineId, "dispatch", "fixture-model", null, "none");
        tracker.recordRequestPhase(nestedTimelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, nestedTimelineId);
        ChatModel nativeLikeDelegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                tracker.recordRequestAttempt(
                        nestedTimelineId, "primary", route, "success", "none", "success", 0L);
                return ChatResponse.builder().aiMessage(AiMessage.from("native-fixture")).build();
            }
        };

        tracker.decorateRequestAttempt(nativeLikeDelegate, "primary", route, Map.of())
                .chat(List.of(UserMessage.from("nested-fixture")));

        assertEquals(1, tracker.redactedRequestAttemptLedger(nestedTimelineId).size());
        TraceStore.clear();
    }

    @Test
    void applicationBoundaryDecoratorDoesNotObserveBlankAssistantText() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("blank-response-request", "blank-response-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "fixture-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "fixture-route", "fixture-model", null, "openai_chat_completions");
        ChatModel blankDelegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from("   ")).build();
            }
        };

        tracker.decorateRequestAttempt(blankDelegate, "primary", route, Map.of())
                .chat(List.of(UserMessage.from("blank-response-prompt")));

        Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
        assertEquals("failed", row.get("outcome"));
        assertEquals("hash:unknown", row.get("responseHash"));
        assertEquals(0, row.get("responseCharCount"));
        assertEquals(0, row.get("responseUtf8ByteCount"));
        assertEquals(Boolean.FALSE, row.get("responseObserved"));
        TraceStore.clear();
    }

    @Test
    void disabledAlternateRouteDoesNotReusePrimaryPendingOptionsOnTheSameTimeline() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("context-disabled-request", "context-disabled-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "primary-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        Map<String, Object> primaryOptions = ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                "openai", "primary-model", "openai_chat_completions",
                Map.of("timeoutMs", 2_000L, "maxRetries", 0));
        Map<String, Object> disabledOptions = ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                "openai", "disabled-fallback-model", "openai_responses",
                Map.of("timeoutMs", 5_000L, "maxRetries", 0, "fallbackEnabled", true,
                        "fallbackKey", "responses"));
        ModelRuntimeHealthTracker.RequestAttemptRoute primaryRoute = tracker.redactedRequestAttemptRoute(
                "primary-route", "primary-model", null, "openai_chat_completions");
        ModelRuntimeHealthTracker.ExpectedFailureAttemptEvidence disabledEvidence =
                tracker.expectedFailureAttemptEvidence(
                        "fallback",
                        tracker.redactedRequestAttemptRoute(
                                "disabled-route", "disabled-fallback-model", null, "openai_responses"),
                        disabledOptions);
        ExpectedFailureChatModel disabled = new ExpectedFailureChatModel(
                "local UX only", "hash:disabled", disabledEvidence);

        tracker.decorateRequestAttempt(disabled, "primary", primaryRoute, primaryOptions)
                .chat(List.of(UserMessage.from("context identity probe")));

        List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, rows.size());
        assertEquals("fallback", rows.get(0).get("role"));
        assertNotEquals(optionHash(primaryOptions), rows.get(0).get("optionsHash"));
        assertEquals(optionHash(disabledOptions), rows.get(0).get("optionsHash"));
    }

    private static String optionHash(Map<String, Object> options) {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return SafeRedactor.hashValue(mapper.writeValueAsString(options));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void applicationBoundaryDecoratorPreservesPrimaryBeforeFallbackOrderAfterFailure() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("fallback-request-fixture", "fallback-session-fixture");
        tracker.recordRequestPhase(timelineId, "dispatch", "fixture-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "fixture-route", "fixture-model", "http://127.0.0.1:11434/v1", "openai_chat_completions");
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                throw new IllegalStateException("fixture failure");
            }
        };
        ChatModel succeeding = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from("fallback-fixture")).build();
            }
        };

        assertThrows(IllegalStateException.class, () -> tracker
                .decorateRequestAttempt(failing, "primary", route, Map.of())
                .chat(List.of(UserMessage.from("fallback-prompt-fixture"))));
        tracker.decorateRequestAttempt(succeeding, "fallback", route, Map.of())
                .chat(List.of(UserMessage.from("fallback-prompt-fixture")));

        assertEquals(List.of("primary", "fallback"), tracker.redactedRequestAttemptLedger(timelineId)
                .stream().map(row -> String.valueOf(row.get("role"))).toList());
        TraceStore.clear();
    }

    @Test
    void applicationBoundaryDecoratorFailsSoftForMultimodalUserMessagesAndCallsDelegateOnce() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("multimodal-request-fixture", "multimodal-session-fixture");
        tracker.recordRequestPhase(timelineId, "dispatch", "fixture-model", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                "fixture-route", "fixture-model", "http://127.0.0.1:11434/v1", "openai_chat_completions");
        AtomicInteger delegateCalls = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                delegateCalls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("multimodal-result")).build();
            }
        };

        tracker.decorateRequestAttempt(delegate, "primary", route, Map.of())
                .chat(List.of(UserMessage.from(
                        TextContent.from("multimodal-text-fixture"),
                        ImageContent.from("data:image/png;base64,AA=="))));

        assertEquals(1, delegateCalls.get());
        assertEquals(1, tracker.redactedRequestAttemptLedger(timelineId).size());
        TraceStore.clear();
    }

    private static HttpServer controlledReceiptServer(boolean tamperRequestHash) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            byte[] responseBytes = ("{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"controlled-receipt-final-private\"},"
                    + "\"done\":true,\"done_reason\":\"stop\"}")
                    .getBytes(StandardCharsets.UTF_8);
            String requestHash = tamperRequestHash
                    ? "sha256:" + "0".repeat(64)
                    : exactSha256(requestBytes);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().add(RECEIPT_VERSION_HEADER, "v1;source=controlled_http_server");
            exchange.getResponseHeaders().add(RECEIPT_REQUEST_HASH_HEADER, requestHash);
            exchange.getResponseHeaders().add(
                    RECEIPT_REQUEST_BYTES_HEADER,
                    Integer.toString(requestBytes.length));
            exchange.getResponseHeaders().add(
                    RECEIPT_RESPONSE_HASH_HEADER,
                    exactSha256(responseBytes));
            exchange.getResponseHeaders().add(
                    RECEIPT_RESPONSE_BYTES_HEADER,
                    Integer.toString(responseBytes.length));
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(responseBytes);
            }
        });
        server.start();
        return server;
    }

    private static String exactSha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return "sha256:" + hex;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertRepairDecision(Object decision, String status, String reason) throws Exception {
        assertNotNull(decision);
        assertEquals(status, recordAccessor(decision, "status"));
        assertEquals(reason, recordAccessor(decision, "reason"));
    }

    private static Object recordAccessor(Object record, String name) throws Exception {
        return record.getClass().getMethod(name).invoke(record);
    }

    private static void invokePhase(
            Method method,
            ModelRuntimeHealthTracker tracker,
            String timelineId,
            String phase,
            String model,
            String endpoint,
            String terminalClass) {
        try {
            method.invoke(tracker, timelineId, phase, model, endpoint, terminalClass);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("request timeline phase invocation failed", ex);
        }
    }

    private static Method requiredMethod(String name, Class<?>... parameterTypes) {
        try {
            return ModelRuntimeHealthTracker.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException ex) {
            fail("RED: ModelRuntimeHealthTracker must expose request-scoped timeline method " + name);
            return null;
        }
    }
}
