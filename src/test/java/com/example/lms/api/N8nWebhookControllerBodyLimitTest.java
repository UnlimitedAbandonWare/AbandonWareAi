package com.example.lms.api;

import com.example.lms.jobs.JobService;
import com.example.lms.search.TraceStore;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class N8nWebhookControllerBodyLimitTest {

    private static final String SECRET = "webhook-body-limit-test-secret";
    private static final String SIGNATURE_SENTINEL = "sha256=oversize-signature-sentinel";
    private static final String IDEMPOTENCY_SENTINEL = "oversize-idempotency-sentinel";
    private static final String BODY_SENTINEL = "oversize-body-private-marker";

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void declaredOversizeRejectsBeforeOpeningRequestStream() throws Exception {
        RecordingJobService jobs = new RecordingJobService();
        N8nWebhookController controller = new N8nWebhookController(SECRET, jobs);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong())
                .thenReturn((long) N8nWebhookController.MAX_BODY_BYTES + 4096L);

        ResponseEntity<?> response =
                controller.accept(request, SIGNATURE_SENTINEL, IDEMPOTENCY_SENTINEL);

        assertPayloadTooLarge(response, jobs);
        verify(request, never()).getInputStream();
        assertEquals(N8nWebhookController.MAX_BODY_BYTES + 1,
                TraceStore.get("api.n8nWebhook.accept.bodyLength"));
        assertPrivateEvidence(response);
    }

    @Test
    void unknownLengthReadsOnlyLimitPlusOneBeforeRejecting() throws Exception {
        assertStreamingOversizeRejected(-1L);
    }

    @Test
    void falseShortDeclaredLengthCannotBypassObservedBodyLimit() throws Exception {
        assertStreamingOversizeRejected(0L);
    }

    @Test
    void exactLimitWithValidSignatureIsAccepted() throws Exception {
        byte[] body = new byte[N8nWebhookController.MAX_BODY_BYTES];
        Arrays.fill(body, (byte) 'a');
        CountingServletInputStream stream = new CountingServletInputStream(body);
        HttpServletRequest request = requestWith(stream, body.length);
        RecordingJobService jobs = new RecordingJobService();
        N8nWebhookController controller = new N8nWebhookController(SECRET, jobs);

        ResponseEntity<?> response = controller.accept(
                request,
                signature(body, SECRET),
                "exact-limit-idempotency-key");

        assertEquals(202, response.getStatusCode().value());
        assertEquals("job-body-limit-test", ((Map<?, ?>) response.getBody()).get("jobId"));
        assertEquals(1, jobs.enqueueCalls);
        assertEquals(N8nWebhookController.MAX_BODY_BYTES, jobs.lastPayload.length());
        assertEquals(N8nWebhookController.MAX_BODY_BYTES, stream.readCount());
    }

    @Test
    void sameIdempotencyKeyAndBodyReturnsOriginalJobWithoutSecondEnqueue() throws Exception {
        byte[] body = "{\"event\":\"same\"}".getBytes(StandardCharsets.UTF_8);
        RecordingJobService jobs = new RecordingJobService();
        N8nWebhookController controller = new N8nWebhookController(SECRET, jobs);

        ResponseEntity<?> first = controller.accept(
                request(body), signature(body, SECRET), "idem-same");
        ResponseEntity<?> replay = controller.accept(
                request(body), signature(body, SECRET), "idem-same");

        assertEquals(202, first.getStatusCode().value());
        assertEquals(202, replay.getStatusCode().value());
        assertEquals(((Map<?, ?>) first.getBody()).get("jobId"),
                ((Map<?, ?>) replay.getBody()).get("jobId"));
        assertEquals(1, jobs.enqueueCalls);
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyReturnsConflict() throws Exception {
        byte[] firstBody = "{\"event\":\"first\"}".getBytes(StandardCharsets.UTF_8);
        byte[] differentBody = "{\"event\":\"different\"}".getBytes(StandardCharsets.UTF_8);
        RecordingJobService jobs = new RecordingJobService();
        N8nWebhookController controller = new N8nWebhookController(SECRET, jobs);

        ResponseEntity<?> first = controller.accept(
                request(firstBody), signature(firstBody, SECRET), "idem-conflict");
        ResponseEntity<?> conflict = controller.accept(
                request(differentBody), signature(differentBody, SECRET), "idem-conflict");

        assertEquals(202, first.getStatusCode().value());
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("IDEMPOTENCY_CONFLICT", ((Map<?, ?>) conflict.getBody()).get("error"));
        assertEquals(1, jobs.enqueueCalls);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("idem-conflict"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("different"));
    }

    @Test
    void missingOrBlankIdempotencyKeyPreservesAtLeastOnceEnqueue() throws Exception {
        byte[] body = "{\"event\":\"at-least-once\"}".getBytes(StandardCharsets.UTF_8);
        RecordingJobService jobs = new RecordingJobService();
        N8nWebhookController controller = new N8nWebhookController(SECRET, jobs);

        ResponseEntity<?> missing = controller.accept(
                request(body), signature(body, SECRET), null);
        ResponseEntity<?> blank = controller.accept(
                request(body), signature(body, SECRET), "  ");

        assertEquals(202, missing.getStatusCode().value());
        assertEquals(202, blank.getStatusCode().value());
        assertEquals(2, jobs.enqueueCalls);
    }

    private static void assertStreamingOversizeRejected(long declaredLength) throws Exception {
        byte[] body = oversizeBody();
        CountingServletInputStream stream = new CountingServletInputStream(body);
        HttpServletRequest request = requestWith(stream, declaredLength);
        RecordingJobService jobs = new RecordingJobService();
        N8nWebhookController controller = new N8nWebhookController(SECRET, jobs);

        ResponseEntity<?> response =
                controller.accept(request, SIGNATURE_SENTINEL, IDEMPOTENCY_SENTINEL);

        assertPayloadTooLarge(response, jobs);
        assertEquals(N8nWebhookController.MAX_BODY_BYTES + 1, stream.readCount());
        assertEquals(N8nWebhookController.MAX_BODY_BYTES + 1,
                TraceStore.get("api.n8nWebhook.accept.bodyLength"));
        assertPrivateEvidence(response);
    }

    private static HttpServletRequest requestWith(
            CountingServletInputStream stream,
            long declaredLength) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(declaredLength);
        when(request.getInputStream()).thenReturn(stream);
        return request;
    }

    private static HttpServletRequest request(byte[] body) throws Exception {
        return requestWith(new CountingServletInputStream(body), body.length);
    }

    private static void assertPayloadTooLarge(
            ResponseEntity<?> response,
            RecordingJobService jobs) {
        assertEquals(413, response.getStatusCode().value());
        assertEquals("PAYLOAD_TOO_LARGE", ((Map<?, ?>) response.getBody()).get("error"));
        assertEquals(0, jobs.enqueueCalls);
        assertEquals(Boolean.TRUE, TraceStore.get("api.n8nWebhook.accept.payloadTooLarge"));
        assertEquals(1L, TraceStore.get("api.n8nWebhook.accept.payloadTooLarge.count"));
        assertEquals("payload_too_large",
                TraceStore.get("api.n8nWebhook.accept.skipped.reason"));
    }

    private static void assertPrivateEvidence(ResponseEntity<?> response) {
        String responseText = String.valueOf(response.getBody());
        String traceText = String.valueOf(TraceStore.getAll());
        for (String sentinel : new String[]{
                BODY_SENTINEL,
                SIGNATURE_SENTINEL,
                IDEMPOTENCY_SENTINEL}) {
            assertFalse(responseText.contains(sentinel));
            assertFalse(traceText.contains(sentinel));
        }
    }

    private static byte[] oversizeBody() {
        byte[] body = new byte[N8nWebhookController.MAX_BODY_BYTES + 8192];
        Arrays.fill(body, (byte) 'x');
        byte[] marker = BODY_SENTINEL.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, body, 0, marker.length);
        return body;
    }

    private static String signature(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static final class CountingServletInputStream extends ServletInputStream {
        private final byte[] data;
        private int offset;

        private CountingServletInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            if (offset >= data.length) {
                return -1;
            }
            return data[offset++] & 0xff;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (offset >= data.length) {
                return -1;
            }
            int count = Math.min(length, data.length - offset);
            System.arraycopy(data, offset, target, targetOffset, count);
            offset += count;
            return count;
        }

        @Override
        public boolean isFinished() {
            return offset >= data.length;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // Synchronous test stream; listener callbacks are unnecessary.
        }

        private int readCount() {
            return offset;
        }
    }

    private static final class RecordingJobService implements JobService {
        private int enqueueCalls;
        private String lastPayload = "";

        @Override
        public String enqueue(String payload) {
            enqueueCalls++;
            lastPayload = payload == null ? "" : payload;
            return "job-body-limit-test";
        }

        @Override
        public <T> void executeAsync(
                String jobId,
                Supplier<T> work,
                Consumer<T> onSuccess) {
        }

        @Override
        public String status(String jobId) {
            return "UNUSED";
        }
    }
}
