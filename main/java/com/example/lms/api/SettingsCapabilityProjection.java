package com.example.lms.api;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Explicit read projection. Looking at a setting never constructs or calls its consumer. */
@Component
public class SettingsCapabilityProjection {
    public record Descriptor(String id, String kind, boolean editable, Object configuredValue,
            Object effectiveValue, Object observedValue, String sourceKind, String sourceKey,
            String appliedScope, Boolean runtimeAvailable, String applyTiming, String reasonCode) {}
    private final Environment environment;
    private final ConfigurableListableBeanFactory beans;
    public SettingsCapabilityProjection(Environment environment, ConfigurableListableBeanFactory beans) {
        this.environment = environment; this.beans = beans;
    }
    public List<Descriptor> project(Map<String,Object> metadata) {
        Map<String,Object> meta = metadata == null ? Map.of() : metadata;
        Boolean jevLive = beans.containsSingleton("jevChoiceAdvisor") ? Boolean.TRUE
                : beans.containsBeanDefinition("jevChoiceAdvisor") ? null : Boolean.FALSE;
        return List.of(signal("jev.main","demo.jev.choice.enabled",jevLive,null,null),
                signal("rerank.onnx","onnx.enabled",null,booleanValue(meta.get("onnx.enabled")),
                        meta.get("stage.onnx") instanceof Number ? true : null),
                signal("routing.roles","chat.settings.routing.enabled",null,null,null));
    }
    private Descriptor signal(String id, String key, Boolean available, Object effective, Object observed) {
        Boolean configured = booleanValue(environment.getProperty(key));
        return new Descriptor(id,"signal",false,configured,effective,observed,
                configured == null ? "unknown" : "existing_config",key,"server",available,
                "read_only",available == Boolean.FALSE ? "runtime_unavailable" : null);
    }
    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equals(value) ? Boolean.TRUE : "false".equals(value) ? Boolean.FALSE : null;
    }
}
