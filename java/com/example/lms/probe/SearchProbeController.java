package com.example.lms.probe;

import com.example.lms.probe.dto.ProbeRequest;
import com.example.lms.probe.dto.ProbeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing a search probe endpoint for development and QA.
 *
 * <p>This endpoint replicates the hybrid retrieval chain to diagnose
 * how queries are processed and where noise originates. When enabled
 * via the {@code probe.search.enabled} property, POST requests to
 * {@code /api/probe/search} will execute a search using the same
 * retrieval pipeline as the chat API. The request body accepts
 * free‑form parameters including {@code useWebSearch}, {@code useRag},
 * {@code officialSourcesOnly}, {@code webTopK}, {@code searchMode} and
 * {@code intent}. All values are forwarded as String metadata to
 * downstream components.</p>
 *
 * <p>Access can be restricted by setting {@code probe.admin-token} in
 * application properties. When defined, callers must supply a matching
 * {@code X-Admin-Token} header or a 403 Forbidden response will be
 * returned.</p>
 */
@RestController
@RequestMapping("/api/probe")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "probe.search.enabled", havingValue = "true", matchIfMissing = false)
public class SearchProbeController {

    private final SearchProbeService service;
    private final SearchProbeSecurity security;

    /**
     * Execute a probe search using the hybrid retrieval chain. The caller
     * may specify optional metadata hints in the request body. When the
     * admin token is configured and the header does not match, a
     * {@code 403 FORBIDDEN} response is returned.
     *
     * @param token optional admin token header
     * @param req   the probe request
     * @return the probe result or an error status
     */
    @PostMapping(value = "/search", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ProbeResult> probe(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                             @RequestBody ProbeRequest req) {
        if (!security.permit(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // Validate intent if provided – restrict to a small set of recognised values
        if (req.getIntent() != null) {
            String intent = req.getIntent().trim().toUpperCase();
            java.util.Set<String> allowed = java.util.Set.of("FINANCE", "COMPANY", "GENERAL");
            if (!allowed.contains(intent)) {
                throw new IllegalArgumentException("intent must be FINANCE|COMPANY|GENERAL");
            }
            req.setIntent(intent);
        }
        return ResponseEntity.ok(service.run(req));
    }
}