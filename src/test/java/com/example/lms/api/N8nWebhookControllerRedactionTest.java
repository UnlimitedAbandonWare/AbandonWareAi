package com.example.lms.api;

import com.example.lms.integrations.n8n.N8nIdempotencyRegistry;
import com.example.lms.jobs.JobService;
import com.example.lms.lifecycle.JsonlLifecycleReceiptStore;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class N8nWebhookControllerRedactionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void statusResponseDoesNotEchoRawPathJobId() throws Exception {
        String rawJobId = "n8n-job-C:\\Users\\nninn\\Desktop\\secret\\webhook-job-123";
        String secret = "webhook-secret";
        N8nWebhookController controller = new N8nWebhookController(secret, new StubJobService("NOT_FOUND"));

        ResponseEntity<?> response = controller.status(rawJobId, signature(rawJobId.getBytes(StandardCharsets.UTF_8), secret));
        String bodyText = String.valueOf(response.getBody());
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(200, response.getStatusCode().value());
        assertFalse(bodyText.contains(rawJobId));
        assertFalse(bodyText.contains("C:\\Users\\nninn"));
        assertEquals("NOT_FOUND", body.get("status"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue(rawJobId), body.get("jobIdHash"));
        assertEquals(rawJobId.length(), body.get("jobIdLength"));
        assertFalse(body.containsKey("jobId"));
        assertTrue(bodyText.contains("jobIdHash"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue(rawJobId),
                TraceStore.get("api.n8nWebhook.status.jobIdHash"));
        assertEquals(rawJobId.length(), TraceStore.get("api.n8nWebhook.status.jobIdLength"));
        assertEquals("NOT_FOUND", TraceStore.get("api.n8nWebhook.status.status"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawJobId));
    }

    @Test
    void statusResponseWithMissingJobMetadataReturnsStableResponse() throws Exception {
        String secret = "webhook-secret";
        N8nWebhookController controller = new N8nWebhookController(secret, new StubJobService(null));

        ResponseEntity<?> response = controller.status(null, signature(new byte[0], secret));
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("", body.get("jobIdHash"));
        assertEquals(0, body.get("jobIdLength"));
        assertEquals("UNKNOWN", body.get("status"));
    }

    @Test
    void statusRejectsMissingSignature() {
        N8nWebhookController controller = new N8nWebhookController("webhook-secret", new StubJobService("READY"));

        ResponseEntity<?> response = controller.status("job-1", null);
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(401, response.getStatusCode().value());
        assertEquals("INVALID_SIGNATURE", body.get("error"));
        assertFalse(String.valueOf(body).contains("job-1"));
        assertEquals(Boolean.TRUE, TraceStore.get("api.n8nWebhook.status.signatureRejected"));
        assertEquals(1L, TraceStore.get("api.n8nWebhook.status.signatureRejected.count"));
        assertEquals("invalid_signature", TraceStore.get("api.n8nWebhook.status.skipped.reason"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue("job-1"),
                TraceStore.get("api.n8nWebhook.status.jobIdHash"));
        assertEquals(5, TraceStore.get("api.n8nWebhook.status.jobIdLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("job-1"));
    }

    @Test
    void acceptReturnsServerErrorWhenEnqueueDoesNotReturnJobId() throws Exception {
        String secret = "webhook-secret";
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        N8nWebhookController controller = new N8nWebhookController(secret, new StubJobService("IGNORED", null));

        ResponseEntity<?> response = controller.accept(request, signature(body, secret), null);
        Map<?, ?> responseBody = (Map<?, ?>) response.getBody();

        assertEquals(500, response.getStatusCode().value());
        assertEquals("job_enqueue_failed", responseBody.get("error"));
        assertFalse(String.valueOf(responseBody).contains("hello"));
        assertFalse(String.valueOf(responseBody).contains("world"));
        assertEquals(Boolean.TRUE, TraceStore.get("api.n8nWebhook.accept.jobEnqueueFailed"));
        assertEquals(1L, TraceStore.get("api.n8nWebhook.accept.jobEnqueueFailed.count"));
        assertEquals("job_enqueue_failed", TraceStore.get("api.n8nWebhook.accept.skipped.reason"));
        assertEquals(body.length, TraceStore.get("api.n8nWebhook.accept.bodyLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("hello"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("world"));
    }

    @Test
    void durableRestartReplayReturnsAcceptedUnknownReceiptWithoutJobId() throws Exception {
        String secret = "webhook-secret";
        String privateKey = "private-idempotency-key-sentinel";
        byte[] body = "{\"private\":\"webhook-body-sentinel\"}".getBytes(StandardCharsets.UTF_8);
        Path receipts = tempDir.resolve("n8n-controller-lifecycle.jsonl");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CountingJobService jobs = new CountingJobService();
        N8nWebhookController first = new N8nWebhookController(
                secret,
                jobs,
                new N8nIdempotencyRegistry(new JsonlLifecycleReceiptStore(receipts, objectMapper)));

        ResponseEntity<?> accepted = first.accept(
                request(body),
                signature(body, secret),
                privateKey);
        N8nWebhookController restarted = new N8nWebhookController(
                secret,
                jobs,
                new N8nIdempotencyRegistry(new JsonlLifecycleReceiptStore(receipts, objectMapper)));
        ResponseEntity<?> replay = restarted.accept(
                request(body),
                signature(body, secret),
                privateKey);

        assertEquals(202, accepted.getStatusCode().value());
        assertEquals(202, replay.getStatusCode().value());
        Map<?, ?> replayBody = (Map<?, ?>) replay.getBody();
        assertEquals("accepted_unknown", replayBody.get("status"));
        assertTrue(String.valueOf(replayBody.get("receiptHash")).matches("[0-9a-f]{64}"));
        assertFalse(replayBody.containsKey("jobId"));
        assertFalse(String.valueOf(replayBody).contains(privateKey));
        assertFalse(String.valueOf(replayBody).contains("webhook-body-sentinel"));
        assertEquals(1, jobs.enqueueCalls.get());
    }

    @Test
    void acceptRejectsInvalidSignatureWithBodyLengthTraceOnly() throws Exception {
        byte[] body = "{\"secret\":\"n8n-body-token\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        N8nWebhookController controller = new N8nWebhookController("webhook-secret", new StubJobService("IGNORED"));

        ResponseEntity<?> response = controller.accept(request, "sha256=bad", null);
        Map<?, ?> responseBody = (Map<?, ?>) response.getBody();

        assertEquals(401, response.getStatusCode().value());
        assertEquals("INVALID_SIGNATURE", responseBody.get("error"));
        assertFalse(String.valueOf(responseBody).contains("n8n-body-token"));
        assertEquals(Boolean.TRUE, TraceStore.get("api.n8nWebhook.accept.signatureRejected"));
        assertEquals(1L, TraceStore.get("api.n8nWebhook.accept.signatureRejected.count"));
        assertEquals("invalid_signature", TraceStore.get("api.n8nWebhook.accept.skipped.reason"));
        assertEquals(body.length, TraceStore.get("api.n8nWebhook.accept.bodyLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("n8n-body-token"));
    }

    private record StubJobService(String status, String jobId) implements JobService {
        private StubJobService(String status) {
            this(status, "job-1");
        }

        @Override
        public String enqueue(String payload) {
            return jobId;
        }

        @Override
        public <T> void executeAsync(String jobId, Supplier<T> work, Consumer<T> onSuccess) {
        }

        @Override
        public String status(String jobId) {
            return status;
        }
    }

    private static final class CountingJobService implements JobService {
        private final AtomicInteger enqueueCalls = new AtomicInteger();

        @Override
        public String enqueue(String payload) {
            return "job-" + enqueueCalls.incrementAndGet();
        }

        @Override
        public <T> void executeAsync(String jobId, Supplier<T> work, Consumer<T> onSuccess) {
        }

        @Override
        public String status(String jobId) {
            return "UNKNOWN";
        }
    }

    private static MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        return request;
    }

    private static String signature(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder out = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(body)) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
