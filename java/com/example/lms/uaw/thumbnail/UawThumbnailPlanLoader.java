package com.example.lms.uaw.thumbnail;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * plans/*.yaml 에서 UawThumbnailPlanSpec를 읽어오는 로더.
 */
@Component
public class UawThumbnailPlanLoader {

    private static final Logger log = LoggerFactory.getLogger(UawThumbnailPlanLoader.class);

    private final ObjectMapper yamlMapper;

    private final Map<String, UawThumbnailPlanSpec> cache = new ConcurrentHashMap<>();

    public UawThumbnailPlanLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public UawThumbnailPlanSpec loadOrDefault(String planId, UawThumbnailProperties props) {
        String normalized = normalizePlanId(planId);
        return cache.computeIfAbsent(normalized, id -> loadInternal(id, props));
    }

    private UawThumbnailPlanSpec loadInternal(String normalizedPlanId, UawThumbnailProperties props) {
        String path = "plans/" + normalizedPlanId + ".yaml";
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) {
            log.warn("[UAW_THUMB] plan not found: {} -> using defaults", path);
            return defaultPlan(normalizedPlanId, props);
        }

        try (InputStream in = res.getInputStream()) {
            UawThumbnailPlanSpec plan = yamlMapper.readValue(in, UawThumbnailPlanSpec.class);
            if (plan == null) {
                return defaultPlan(normalizedPlanId, props);
            }
            return plan;
        } catch (Exception e) {
            log.warn("[UAW_THUMB] plan load failed: {} -> using defaults. err={}", path, e.toString());
            return defaultPlan(normalizedPlanId, props);
        }
    }

    private UawThumbnailPlanSpec defaultPlan(String normalizedPlanId, UawThumbnailProperties props) {
        return new UawThumbnailPlanSpec(
                stripExt(normalizedPlanId),
                1,
                "uaw_thumbnail",
                new UawThumbnailPlanSpec.Anchors(12, "fast", 256, 0.0),
                new UawThumbnailPlanSpec.Evidence(4, 2, 6, true),
                new UawThumbnailPlanSpec.Render("STRICT", "mini", 480, 0.2),
                new UawThumbnailPlanSpec.Persist(props.getKnowledgeDomain(), props.getEntityType(), props.getMinConfidence())
        );
    }

    private static String stripExt(String planId) {
        String p = planId;
        if (p.endsWith(".yaml")) p = p.substring(0, p.length() - 5);
        if (p.endsWith(".yml")) p = p.substring(0, p.length() - 4);
        return p;
    }

    private static String normalizePlanId(String planId) {
        if (planId == null || planId.isBlank()) return "UAW_thumbnail.v1";
        String p = planId.trim();
        if (p.endsWith(".yaml")) p = p.substring(0, p.length() - 5);
        if (p.endsWith(".yml")) p = p.substring(0, p.length() - 4);
        return p;
    }
}
