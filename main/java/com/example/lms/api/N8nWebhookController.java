package com.example.lms.api;

import com.example.lms.integrations.n8n.SignatureVerifier;
import com.example.lms.integrations.n8n.N8nIdempotencyRegistry;
import com.example.lms.jobs.JobService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@RestController
public class N8nWebhookController {

    static final int MAX_BODY_BYTES = 1_048_576;

    private final SignatureVerifier verifier;
    private final JobService jobs;
    private final N8nIdempotencyRegistry idempotencyRegistry;

    @Autowired
    public N8nWebhookController(
            @Value("${n8n.webhook.secret:}") String secret,
            JobService jobs,
            N8nIdempotencyRegistry idempotencyRegistry) {
        this.verifier = new SignatureVerifier(secret);
        this.jobs = jobs;
        this.idempotencyRegistry = idempotencyRegistry;
    }

    public N8nWebhookController(@Value("${n8n.webhook.secret:}") String secret, JobService jobs) {
        this(secret, jobs, new N8nIdempotencyRegistry());
    }

    @PostMapping(path="/hooks/n8n", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> accept(HttpServletRequest request,
                                    @RequestHeader(value="X-Signature", required=false) String sig,
                                    @RequestHeader(value="Idempotency-Key", required=false) String idemKey) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            traceAcceptPayloadTooLarge(MAX_BODY_BYTES + 1);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "PAYLOAD_TOO_LARGE"));
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            traceAcceptPayloadTooLarge(body.length);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "PAYLOAD_TOO_LARGE"));
        }
        if (!verifier.verify(body, sig)) {
            traceAcceptSignatureRejected(body.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","INVALID_SIGNATURE"));
        }
        String normalizedIdempotencyKey = idemKey == null ? "" : idemKey.trim();
        String keyHash = normalizedIdempotencyKey.isEmpty()
                ? ""
                : sha256(normalizedIdempotencyKey.getBytes(StandardCharsets.UTF_8));
        String bodyHash = normalizedIdempotencyKey.isEmpty() ? "" : sha256(body);
        N8nIdempotencyRegistry.Decision decision = idempotencyRegistry.accept(
                keyHash,
                bodyHash,
                () -> jobs.enqueue(new String(body, StandardCharsets.UTF_8)));
        if (decision.conflict()) {
            traceAcceptIdempotencyConflict(body.length);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "IDEMPOTENCY_CONFLICT"));
        }
        if (decision.durableReplay()) {
            String receiptHash = decision.receiptHash() == null ? "" : decision.receiptHash();
            traceAcceptDurableReplay(receiptHash);
            return ResponseEntity.accepted().body(Map.of(
                    "status", "accepted_unknown",
                    "receiptHash", receiptHash));
        }
        String jobId = decision.jobId();
        if (jobId == null || jobId.isBlank()) {
            traceAcceptEnqueueFailed(body.length);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "job_enqueue_failed"));
        }
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @GetMapping(path="/hooks/n8n/{jobId}", produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(@PathVariable String jobId,
                                    @RequestHeader(value="X-Signature", required=false) String sig) {
        String id = jobId == null ? "" : jobId;
        if (!verifier.verify(id.getBytes(StandardCharsets.UTF_8), sig)) {
            traceStatusSignatureRejected(id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","INVALID_SIGNATURE"));
        }
        String jobIdHash = SafeRedactor.hashValue(id);
        String status = jobs.status(jobId);
        String safeStatus = status == null || status.isBlank() ? "UNKNOWN" : status;
        traceStatus(jobIdHash, id.length(), safeStatus);
        return ResponseEntity.ok(Map.of(
                "jobIdHash", jobIdHash == null ? "" : jobIdHash,
                "jobIdLength", id.length(),
                "status", safeStatus
        ));
    }

    private static void traceStatus(String jobIdHash, int jobIdLength, String status) {
        TraceStore.put("api.n8nWebhook.status.jobIdHash", jobIdHash == null ? "" : jobIdHash);
        TraceStore.put("api.n8nWebhook.status.jobIdLength", jobIdLength);
        TraceStore.put("api.n8nWebhook.status.status", SafeRedactor.traceLabelOrFallback(status, "UNKNOWN"));
    }

    private static void traceAcceptEnqueueFailed(int bodyLength) {
        TraceStore.put("api.n8nWebhook.accept.jobEnqueueFailed", true);
        TraceStore.inc("api.n8nWebhook.accept.jobEnqueueFailed.count");
        TraceStore.put("api.n8nWebhook.accept.skipped.reason", "job_enqueue_failed");
        TraceStore.put("api.n8nWebhook.accept.bodyLength", Math.max(0, bodyLength));
    }

    private static void traceAcceptPayloadTooLarge(int observedLength) {
        TraceStore.put("api.n8nWebhook.accept.payloadTooLarge", true);
        TraceStore.inc("api.n8nWebhook.accept.payloadTooLarge.count");
        TraceStore.put("api.n8nWebhook.accept.skipped.reason", "payload_too_large");
        TraceStore.put("api.n8nWebhook.accept.bodyLength",
                Math.max(0, Math.min(observedLength, MAX_BODY_BYTES + 1)));
    }

    private static void traceAcceptSignatureRejected(int bodyLength) {
        TraceStore.put("api.n8nWebhook.accept.signatureRejected", true);
        TraceStore.inc("api.n8nWebhook.accept.signatureRejected.count");
        TraceStore.put("api.n8nWebhook.accept.skipped.reason", "invalid_signature");
        TraceStore.put("api.n8nWebhook.accept.bodyLength", Math.max(0, bodyLength));
    }

    private static void traceAcceptIdempotencyConflict(int bodyLength) {
        TraceStore.put("api.n8nWebhook.accept.idempotencyConflict", true);
        TraceStore.inc("api.n8nWebhook.accept.idempotencyConflict.count");
        TraceStore.put("api.n8nWebhook.accept.skipped.reason", "idempotency_conflict");
        TraceStore.put("api.n8nWebhook.accept.bodyLength", Math.max(0, bodyLength));
    }

    private static void traceAcceptDurableReplay(String receiptHash) {
        TraceStore.put("api.n8nWebhook.accept.durableReplay", true);
        TraceStore.inc("api.n8nWebhook.accept.durableReplay.count");
        TraceStore.put("api.n8nWebhook.accept.status", "accepted_unknown");
        TraceStore.put("api.n8nWebhook.accept.receiptHash", receiptHash == null ? "" : receiptHash);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static void traceStatusSignatureRejected(String jobId) {
        String id = jobId == null ? "" : jobId;
        TraceStore.put("api.n8nWebhook.status.signatureRejected", true);
        TraceStore.inc("api.n8nWebhook.status.signatureRejected.count");
        TraceStore.put("api.n8nWebhook.status.skipped.reason", "invalid_signature");
        TraceStore.put("api.n8nWebhook.status.jobIdHash", SafeRedactor.hashValue(id));
        TraceStore.put("api.n8nWebhook.status.jobIdLength", id.length());
    }
}
