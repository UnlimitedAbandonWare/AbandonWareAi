package com.example.lms.api;

import com.example.lms.service.rag.graph.BrainSnapshot;
import com.example.lms.service.rag.graph.BrainStateService;
import com.example.lms.service.rag.graph.DomainSummary;
import com.example.lms.service.rag.graph.KgEntityView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/rag/brain-state")
public class BrainStateDiagnosticsController {

    private final BrainStateService brainStateService;

    public BrainStateDiagnosticsController(BrainStateService brainStateService) {
        this.brainStateService = brainStateService;
    }

    @GetMapping(value = "/snapshot/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BrainSnapshot> snapshot(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "domain", defaultValue = "") String domain,
            @RequestParam(name = "recentLimit", defaultValue = "20") int recentLimit) {
        return ResponseEntity.ok(brainStateService.getBrainSnapshot(sessionId, domain, recentLimit));
    }

    @GetMapping(value = "/domains", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> domains() {
        List<DomainSummary> domains = brainStateService.listKnownDomains();
        return ResponseEntity.ok(Map.of("domains", domains, "count", domains.size()));
    }

    @GetMapping(value = "/entities/{domain}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> entities(
            @PathVariable("domain") String domain,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        List<KgEntityView> entities = brainStateService.listEntityNodes(domain, limit);
        return ResponseEntity.ok(Map.of("domain", domain, "items", entities, "count", entities.size()));
    }
}
