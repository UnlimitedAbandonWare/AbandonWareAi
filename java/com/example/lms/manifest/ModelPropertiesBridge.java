package com.example.lms.manifest;

import com.example.lms.config.ModelProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;




@Component
@DependsOn({"modelRegistry"})
public class ModelPropertiesBridge {

    private final ModelRegistry registry;
    private final ModelProperties props;

    public ModelPropertiesBridge(ModelRegistry registry, ModelProperties props) {
        this.registry = registry;
        this.props = props;
    }

    @PostConstruct
    public void applyBindings() {
        try {
            String defId = registry.defaultId();
            String moeId = registry.moeId();

            boolean overrideDefault = (props.getaDefault() == null || props.getaDefault().isBlank());
            boolean overrideMoe     = (props.getMoe() == null || props.getMoe().isBlank());

            if (overrideDefault && defId != null) props.setaDefault(defId);
            if (overrideMoe && moeId != null)     props.setMoe(moeId);
            System.out.println("[ModelPropertiesBridge] a-default=" + props.getaDefault() + ", moe=" + props.getMoe());
        } catch (Exception e) {
            System.err.println("[ModelPropertiesBridge] failed to apply bindings: " + e.getMessage());
        }
    }
}