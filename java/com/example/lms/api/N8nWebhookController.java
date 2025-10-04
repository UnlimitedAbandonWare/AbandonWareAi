package com.example.lms.api;

import com.example.lms.jobs.JobService;
import com.example.lms.integrations.n8n.SignatureVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Webhook endpoint for n8n flows.  Incoming requests are authenticated via
 * HMAC signature verification and then enqueued for asynchronous
 * processing.  The controller emits a 401 response when the signature
 * validation fails or when the n8n integration is disabled.  A valid
 * request results in a 202 Accepted response containing the generated
 * job identifier.
 */
@RestController
@RequestMapping("/hooks/n8n")
@RequiredArgsConstructor
public class N8nWebhookController {

    private final JobService jobs;
    private final SignatureVerifier signatureVerifier;

    /**
     * Accept and enqueue an n8n webhook payload.  When signature
     * verification is enabled the request is authenticated using the
     * configured secret.  The body is passed verbatim to the job store
     * along with the request and session identifiers for correlation.
     *
     * @param flow   the n8n flow name encoded in the path
     * @param reqId  optional request correlation identifier from the caller
     * @param sid    optional session identifier propagated from upstream
     * @param sigHdr optional HMAC signature header supplied by n8n
     * @param body   request payload (may be empty)
     * @return an accepted response containing the new job identifier
     */
    @PostMapping("/{flow}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String flow,
            @RequestHeader(value = "X-Request-Id", required = false) String reqId,
            @RequestHeader(value = "X-Session-Id", required = false) String sid,
            @RequestHeader(value = "X-N8N-Signature", required = false) String sigHdr,
            @RequestBody(required = false) String body) {
        // Reject when the integration is disabled or signature fails
        if (signatureVerifier.isEnabled() && !signatureVerifier.verify(sigHdr, body)) {
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }
        // Enqueue the job; the flow name is used as the queue label
        String jobId = jobs.enqueue(flow, body, reqId, sid);
        return ResponseEntity.accepted()
                .body(Map.of("jobId", jobId, "status", "accepted"));
    }
}