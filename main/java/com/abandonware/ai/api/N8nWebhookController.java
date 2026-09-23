package com.abandonware.ai.api;

import com.abandonware.ai.integrations.n8n.SignatureVerifier;
import com.abandonware.ai.jobs.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/hooks/n8n")
public class N8nWebhookController {
    private final SignatureVerifier verifier;
    private final JobService jobs;
    public N8nWebhookController(SignatureVerifier verifier, JobService jobs) { this.verifier = verifier; this.jobs = jobs; }

    @PostMapping
    public ResponseEntity<?> accept(@RequestHeader(value="X-Signature", required=false) String sig, @RequestBody String body) {
        if (!verifier.verify(sig, body)) return ResponseEntity.status(401).body("invalid signature");
        String id = jobs.enqueue(body);
        return ResponseEntity.accepted().body("{\"jobId\":\""+id+"\"}");
    }
}