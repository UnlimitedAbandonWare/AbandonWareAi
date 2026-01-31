package com.abandonware.ai.agent.nn;

import com.abandonware.ai.agent.nn.GradientVanishingAnalyzer.LayerHealth;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.List;




@RestController
@RequestMapping(path = "/diag/nn/gradients")
@ConditionalOnProperty(prefix = "diag.nn.gradient", name = "enabled", havingValue = "true")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.nn.GradientDebugController
 * Role: controller
 * Key Endpoints: POST /diag/nn/gradients, ANY /diag/nn/gradients/diag/nn/gradients
 * Dependencies: com.abandonware.ai.agent.nn.GradientVanishingAnalyzer.LayerHealth
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.nn.GradientDebugController
role: controller
api:
  - POST /diag/nn/gradients
  - ANY /diag/nn/gradients/diag/nn/gradients
*/
public class GradientDebugController {

    public record GradNorm(String layer, double norm) {}
    public record GradRequest(String model, List<GradNorm> layers) {}
    public record GradReport(String layer, double l2norm, double vanishProb, boolean flag) {}
    public record GradResponse(String model, List<GradReport> layers, double meanVanishProb) {}

    private final GradientDebugService service;

    public GradientDebugController(GradientDebugService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public GradResponse post(@RequestBody GradRequest req) {
        var layerNames = req.layers().stream().map(GradNorm::layer).toList();
        var norms = req.layers().stream().map(GradNorm::norm).toList();
        List<LayerHealth> result = service.record(req.model(), layerNames, norms);
        var reports = result.stream()
                .map(h -> new GradReport(h.layer(), h.l2norm(), h.vanishProb(), h.flag()))
                .toList();
        double mean = result.stream().mapToDouble(LayerHealth::vanishProb).average().orElse(0.0);
        return new GradResponse(req.model(), reports, mean);
    }
}