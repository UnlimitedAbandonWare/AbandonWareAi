package com.abandonware.ai.api;

import com.abandonware.ai.integrations.n8n.SignatureVerifier;
import com.abandonware.ai.jobs.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/hooks/n8n")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.api.N8nWebhookController
 * Role: controller
 * Key Endpoints: ANY /hooks/n8n/hooks/n8n
 * Dependencies: com.abandonware.ai.integrations.n8n.SignatureVerifier, com.abandonware.ai.jobs.JobService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.api.N8nWebhookController
role: controller
api:
  - ANY /hooks/n8n/hooks/n8n
*/
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