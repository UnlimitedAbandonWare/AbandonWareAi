package com.example.lms.api;

import ai.abandonware.nova.orch.web.WebFailSoftDomainStageReportService;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight diagnostics for Nova WEB fail-soft staging.
 *
 * <p>This endpoint is meant to support "regression recovery" patches by surfacing
 * misrouted domains captured by {@code web.failsoft.domainStagePairs}.</p>
 */
@RestController
@RequestMapping("/api/diagnostics/web-failsoft")
public class WebFailSoftDiagnosticsController {

    private final ObjectProvider<WebFailSoftDomainStageReportService> reportProvider;
    private final ObjectProvider<DomainProfileLoader> domainProfileLoaderProvider;

    public WebFailSoftDiagnosticsController(
            ObjectProvider<WebFailSoftDomainStageReportService> reportProvider,
            ObjectProvider<DomainProfileLoader> domainProfileLoaderProvider) {
        this.reportProvider = reportProvider;
        this.domainProfileLoaderProvider = domainProfileLoaderProvider;
    }

    @GetMapping("/domain-stage-report")
    public Map<String, Object> domainStageReport(
            @RequestParam(value = "topN", required = false, defaultValue = "30") int topN,
            @RequestParam(value = "minCount", required = false, defaultValue = "3") int minCount) {

        WebFailSoftDomainStageReportService svc = reportProvider.getIfAvailable();
        if (svc == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", "report service not enabled");
            return out;
        }
        return svc.snapshot(topN, minCount);
    }

    @PostMapping("/domain-stage-report/reset")
    public Map<String, Object> resetDomainStageReport(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {

        WebFailSoftDomainStageReportService svc = reportProvider.getIfAvailable();
        if (svc == null) {
            return Map.of("ok", false, "reason", "report service not enabled");
        }

        // Optional guard: if an admin-token is configured, require it.
        DomainProfileLoader loader = domainProfileLoaderProvider.getIfAvailable();
        String expected = loader == null ? null : loader.getAdminToken();
        if (expected != null && !expected.isBlank()) {
            if (adminToken == null || !adminToken.equals(expected)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin token required");
            }
        }

        svc.reset();
        return Map.of("ok", true);
    }
}
