package com.example.lms.probe;

import com.example.lms.probe.dto.SearchProbeRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/probe")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="probe.search.enabled", havingValue="true")
public class SearchProbeController {
    private final SearchProbeService service;
    private final boolean enabled;
    private final String adminToken;

    public SearchProbeController(
            SearchProbeService service,
            @Value("${probe.search.enabled:false}") boolean enabled,
            @Value("${probe.admin-token:}") String adminToken) {
        this.service = service;
        this.enabled = enabled;
        this.adminToken = adminToken;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchProbeRequest req,
                                    @RequestHeader(value = "X-Probe-Token", required = false) String token) {
        if (!enabled) {
            return ResponseEntity.status(404).body(err("PROBE_DISABLED"));
        }
        if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
            return ResponseEntity.status(401).body(err("UNAUTHORIZED"));
        }
        return ResponseEntity.ok(service.run(req));
    }

    private static java.util.Map<String, String> err(String code) {
        return java.util.Map.of("error", code, "code", code);
    }
}