package com.example.lms.manifest;

import com.example.lms.config.ModelProperties;
import com.example.lms.trace.SafeRedactor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;




@Component
@DependsOn({"modelRegistry"})
public class ModelPropertiesBridge {
    private static final Logger log = LoggerFactory.getLogger(ModelPropertiesBridge.class);

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
            String defaultModel = props.getaDefault();
            String moeModel = props.getMoe();
            log.info("[ModelPropertiesBridge] bindings applied defaultPresent={} defaultHash12={} defaultLength={} moePresent={} moeHash12={} moeLength={}",
                    defaultModel != null && !defaultModel.isBlank(),
                    defaultModel == null ? "" : SafeRedactor.hash12(defaultModel),
                    defaultModel == null ? 0 : defaultModel.length(),
                    moeModel != null && !moeModel.isBlank(),
                    moeModel == null ? "" : SafeRedactor.hash12(moeModel),
                    moeModel == null ? 0 : moeModel.length());
        } catch (Exception e) {
            log.warn("[ModelPropertiesBridge] failed to apply bindings errorType={}", e.getClass().getSimpleName());
        }
    }
}
