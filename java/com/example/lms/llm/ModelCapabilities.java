package com.example.lms.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;



/** 모델별 제약(예: rigid temperature)을 중앙화 */
public final class ModelCapabilities {
    private static final Logger log = LoggerFactory.getLogger(ModelCapabilities.class);
    /** 기본값(1.0)만 허용하는 모델 집합 */
    private static final Set<String> RIGID_TEMPERATURE_MODELS = Set.of("gpt-5-mini");
    private ModelCapabilities() {}

    public static boolean requiresDefaultTemperature(String modelId) {
        if (modelId == null) return false;
        String m = modelId.trim().toLowerCase();
        return RIGID_TEMPERATURE_MODELS.contains(m) || m.contains("gpt-5-mini");
    }
    /** 모델이 rigid면 1.0으로 강제 */
    public static double sanitizeTemperature(String modelId, double requested) {
        if (requiresDefaultTemperature(modelId)) {
            if (requested != 1.0d) {
                log.debug("Clamping temperature {} → 1.0 for rigid-temp model '{}'", requested, modelId);
            }
            return 1.0d;
        }
        return requested;
    }
}