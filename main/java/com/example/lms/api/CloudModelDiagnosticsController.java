package com.example.lms.api;

import com.example.lms.llm.gateway.CloudModelRouteClassifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/llm")
public class CloudModelDiagnosticsController {

    private final CloudModelRouteClassifier routeClassifier;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.llm.gateway.HybridLlmGatewayProbeService gatewayProbe;

    public CloudModelDiagnosticsController(CloudModelRouteClassifier routeClassifier) {
        this.routeClassifier = routeClassifier;
    }

    @GetMapping(value = "/cloud-models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cloudModels(
            @RequestParam(value = "stage", required = false, defaultValue = "chat") String stage) {
        String safeStage = safeStage(stage);
        List<CloudModelRouteClassifier.CloudModelRouteRow> rows = routeClassifier == null
                ? List.of()
                : routeClassifier.classifyDefaultCatalog(safeStage);
        long eligibleCount = rows.stream().filter(CloudModelRouteClassifier.CloudModelRouteRow::eligible).count();
        long fallbackOnlyCount = rows.stream().filter(CloudModelRouteClassifier.CloudModelRouteRow::fallbackOnly).count();
        long disabledCount = rows.size() - eligibleCount;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "awx.llm.cloud_models.v1");
        out.put("stage", safeStage);
        out.put("count", rows.size());
        out.put("eligibleCount", eligibleCount);
        out.put("fallbackOnlyCount", fallbackOnlyCount);
        out.put("disabledCount", disabledCount);
        out.put("rows", rows);
        if (gatewayProbe != null) out.put("failover", gatewayProbe.failoverDiagnostics());
        return out;
    }

    private static String safeStage(String rawStage) {
        if (rawStage == null || rawStage.isBlank()) {
            return "chat";
        }
        String normalized = rawStage.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        return normalized.replaceAll("[^a-z0-9._:-]", "_");
    }
}
