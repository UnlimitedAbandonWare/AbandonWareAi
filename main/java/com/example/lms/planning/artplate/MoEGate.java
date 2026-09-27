
package com.example.lms.planning.artplate;

import com.example.lms.planning.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.*;




@Component
@ConditionalOnProperty(name="strategy.moe.enabled", havingValue = "true", matchIfMissing = true)
public class MoEGate implements StrategyGate, InitializingBean {

    private final Map<String, ArtPlate> byId = new HashMap<>();

    @Value("${strategy.moe.default:AP3_VEC_DENSE}")
    private String defaultId;

    @Override
    public void afterPropertiesSet() throws Exception {
        // Load artplate manifests from classpath: artplate/*.yaml
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:artplate/*.yml");
        Yaml yaml = new Yaml();
        for (Resource r : resources) {
            try (InputStream is = r.getInputStream()) {
                Map<String,Object> m = yaml.load(is);
                String id = (String)m.getOrDefault("id", UUID.randomUUID().toString());
                String name = (String)m.getOrDefault("name", id);
                byId.put(id, new ArtPlate(id, name, m));
            }
        }
    }

    @Override
    public ArtPlate pick(ComplexityScore score, StrategyTelemetry telemetry) {
        ComplexityScore.Label l = score.getLabel();
        if (l == ComplexityScore.Label.WEB_RECENT) {
            return byId.getOrDefault("AP1_AUTH_WEB", byId.getOrDefault(defaultId, any()));
        } else if (l == ComplexityScore.Label.COMPLEX) {
            return byId.getOrDefault("AP3_VEC_DENSE", byId.getOrDefault(defaultId, any()));
        } else {
            return byId.getOrDefault("AP9_COST_SAVER", byId.getOrDefault(defaultId, any()));
        }
    }

    private ArtPlate any() {
        return byId.values().stream().findFirst().orElse(new ArtPlate(defaultId, defaultId, Collections.emptyMap()));
    }
}