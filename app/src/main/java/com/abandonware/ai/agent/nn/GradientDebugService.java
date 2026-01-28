package com.abandonware.ai.agent.nn;

import com.abandonware.ai.agent.nn.GradientVanishingAnalyzer.LayerHealth;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;




@Service
@EnableConfigurationProperties(DiagGradientProperties.class)
@ConditionalOnProperty(prefix = "diag.nn.gradient", name = "enabled", havingValue = "true")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.nn.GradientDebugService
 * Role: service
 * Feature Flags: sse
 * Dependencies: com.abandonware.ai.agent.nn.GradientVanishingAnalyzer.LayerHealth
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.nn.GradientDebugService
role: service
flags: [sse]
*/
public class GradientDebugService {
    private static final Logger log = LoggerFactory.getLogger(GradientDebugService.class);

    private final MeterRegistry registry;
    private final GradientVanishingAnalyzer analyzer;

    public GradientDebugService(MeterRegistry registry, DiagGradientProperties props) {
        this.registry = registry;
        this.analyzer = new GradientVanishingAnalyzer(
                props.getVanishThreshold(),
                props.getAlpha(),
                props.getBeta(),
                props.getEps()
        );
    }

    /**
     * Records per-layer gradient health. Returns computed health list.
     */
    public List<LayerHealth> record(String model, List<String> layers, List<Double> norms) {
        Objects.requireNonNull(layers, "layers");
        Objects.requireNonNull(norms, "norms");
        if (layers.size() != norms.size()) {
            throw new IllegalArgumentException("layers and norms length mismatch: " + layers.size() + " vs " + norms.size());
        }

        List<LayerHealth> result = analyzer.assess(layers, norms);

        if (registry != null) {
            for (var h : result) {
                Tags tags = Tags.of("model", String.valueOf(model), "layer", h.layer());
                // Create/update gauges
                Gauge.builder("agent.nn.grad.l2norm", h.l2norm())
                        .description("Per-layer gradient L2 norm")
                        .tags(tags)
                        .register(registry);
            }
            double meanProb = result.stream().mapToDouble(LayerHealth::vanishProb).average().orElse(0.0);
            Gauge.builder("agent.nn.grad.vanish_prob_mean", meanProb)
                    .description("Mean vanishing-risk probability over reported layers")
                    .tags(Tags.of("model", String.valueOf(model)))
                    .register(registry);
        }

        for (var h : result) {
            if (h.flag()) {
                log.warn("VanishingRisk model={} layer={} prob={} norm={}", model, h.layer(),
                        String.format("%.4f", h.vanishProb()), String.format("%.3e", h.l2norm()));
            } else {
                log.debug("GradientHealth model={} layer={} prob={} norm={}", model, h.layer(),
                        String.format("%.4f", h.vanishProb()), String.format("%.3e", h.l2norm()));
            }
        }
        return result;
    }
}