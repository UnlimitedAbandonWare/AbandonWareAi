package com.example.lms.orchestrate.plan;

import org.yaml.snakeyaml.Yaml;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.io.InputStream;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.orchestrate.plan.PlanResolver
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.example.lms.orchestrate.plan.PlanResolver
role: config
*/
public class PlanResolver {
    private final PlanDslProperties props;
    private final Yaml yaml = new Yaml();
    private final Map<String, PlanModel> cache = new ConcurrentHashMap<>();

    public PlanResolver(PlanDslProperties props) { this.props = props; }

    public PlanModel current() { return cache.computeIfAbsent(props.getActive(), this::load); }

    private PlanModel load(String id) {
        String path = props.getDir()+"/"+id+".yaml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Plan file not found: "+path);
            return yaml.loadAs(in, PlanModel.class);
        } catch(Exception e) {
            throw new IllegalStateException("Plan load fail: "+id, e);
        }
    }
}