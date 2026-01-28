package com.example.lms.api;

import com.example.lms.integrations.n8n.SignatureVerifier;
import com.example.lms.jobs.JobService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

@RestController
public class N8nWebhookController {

    private final SignatureVerifier verifier;
    private final JobService jobs;

    public N8nWebhookController(@Value("${n8n.webhook.secret:}") String secret, JobService jobs) {
        this.verifier = new SignatureVerifier(secret);
        this.jobs = jobs;
    }

    @PostMapping(path="/hooks/n8n", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> accept(HttpServletRequest request,
                                    @RequestHeader(value="X-Signature", required=false) String sig,
                                    @RequestHeader(value="Idempotency-Key", required=false) String idemKey) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        if (!verifier.verify(body, sig)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","INVALID_SIGNATURE"));
        }
        String jobId = jobs.enqueue(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @GetMapping(path="/hooks/n8n/{jobId}", produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(@PathVariable String jobId) {
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", jobs.status(jobId)));
    }
}