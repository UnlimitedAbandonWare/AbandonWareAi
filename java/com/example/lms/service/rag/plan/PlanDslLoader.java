package com.example.lms.service.rag.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads pipeline-style plan DSLs under classpath:/plans/*.yaml.
 *
 * NOTE: At the moment we only need a narrow slice: the projection agent plan.
 */
@Service
public class PlanDslLoader {
    private static final Logger log = LoggerFactory.getLogger(PlanDslLoader.class);

    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper;
    private final Map<String, Optional<ProjectionAgentPlanSpec>> projectionCache = new ConcurrentHashMap<>();

    public PlanDslLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public Optional<ProjectionAgentPlanSpec> loadProjectionAgent(String planId) {
        if (!StringUtils.hasText(planId)) {
            return Optional.empty();
        }
        final String key = planId.trim();
        return projectionCache.computeIfAbsent(key, this::loadProjectionAgentInternal);
    }

    private Optional<ProjectionAgentPlanSpec> loadProjectionAgentInternal(String planId) {
        String filename = planId;
        if (!filename.endsWith(".yaml") && !filename.endsWith(".yml")) {
            filename = filename + ".yaml";
        }

        Resource res = resourceLoader.getResource("classpath:plans/" + filename);
        if (!res.exists()) {
            return Optional.empty();
        }

        try (InputStream is = res.getInputStream()) {
            JsonNode root = yamlMapper.readTree(is);
            if (root == null || !root.hasNonNull("id")) {
                return Optional.empty();
            }

            String id = root.path("id").asText("").trim();
            if (!"projection_agent.v1".equalsIgnoreCase(id)) {
                return Optional.empty();
            }

            ProjectionAgentPlanSpec.Defaults defaults = parseDefaults(root.path("defaults"));

            JsonNode pipeline = root.path("pipeline");
            if (!pipeline.isArray()) {
                return Optional.empty();
            }

            ProjectionAgentPlanSpec.Branch viewMemorySafe = null;
            ProjectionAgentPlanSpec.Branch viewFreeProjection = null;
            ProjectionAgentPlanSpec.Merge merge = new ProjectionAgentPlanSpec.Merge(true, false);
            ProjectionAgentPlanSpec.FinalAnswer finalAnswer = null;

            for (JsonNode step : pipeline) {
                String stepId = step.path("id").asText("");

                if ("draft_dual_view".equalsIgnoreCase(stepId)
                        && "parallel".equalsIgnoreCase(step.path("mode").asText(""))) {
                    JsonNode branches = step.path("branches");
                    if (branches.isArray()) {
                        for (JsonNode b : branches) {
                            String bid = b.path("id").asText("");
                            ProjectionAgentPlanSpec.Branch branch = parseBranch(b);
                            if ("view_memory_safe".equalsIgnoreCase(bid)) {
                                viewMemorySafe = branch;
                            } else if ("view_free_projection".equalsIgnoreCase(bid)) {
                                viewFreeProjection = branch;
                            }
                        }
                    }
                }

                if ("projection_merge".equalsIgnoreCase(stepId)) {
                    boolean keepNotes = step.path("config").path("keep-free-side-notes").asBoolean(true);
                    merge = new ProjectionAgentPlanSpec.Merge(keepNotes, false);
                }

                if ("final_answer".equalsIgnoreCase(stepId)) {
                    finalAnswer = parseFinal(step);
                }
            }

            // Defaults for missing parts
            if (viewMemorySafe == null) {
                viewMemorySafe = new ProjectionAgentPlanSpec.Branch("view_memory_safe", "auto", defaults.guardProfile(),
                        defaults.memoryProfile(), defaults.maxTokens(), null);
            }
            if (viewFreeProjection == null) {
                viewFreeProjection = new ProjectionAgentPlanSpec.Branch("view_free_projection", "auto",
                        defaults.guardProfile(), defaults.memoryProfile(), defaults.maxTokens(), null);
            }
            if (finalAnswer == null) {
                finalAnswer = new ProjectionAgentPlanSpec.FinalAnswer("auto", "projection.final", true, 1200);
            }

            return Optional.of(
                    new ProjectionAgentPlanSpec(id, defaults, viewMemorySafe, viewFreeProjection, merge, finalAnswer));
        } catch (IOException e) {
            log.warn("PlanDslLoader: failed to load pipeline plan {}", planId, e);
            return Optional.empty();
        }
    }

    private ProjectionAgentPlanSpec.Defaults parseDefaults(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return new ProjectionAgentPlanSpec.Defaults(null, null, null, null, null);
        }
        String model = textOrNull(n, "model");
        String guard = textOrNull(n, "guard-profile");
        String mem = textOrNull(n, "memory-profile");
        Boolean citations = n.hasNonNull("citations") ? n.get("citations").asBoolean() : null;
        Integer max = intOrNull(n, "max-tokens");
        return new ProjectionAgentPlanSpec.Defaults(model, guard, mem, citations, max);
    }

    private ProjectionAgentPlanSpec.Branch parseBranch(JsonNode b) {
        String id = textOrNull(b, "id");
        String model = textOrNull(b, "model");
        String guard = textOrNull(b, "guard-profile");
        String mem = textOrNull(b, "memory-profile");
        Integer max = intOrNull(b, "max-tokens");
        List<String> traits = stringList(b.path("traits"));
        return new ProjectionAgentPlanSpec.Branch(id, model, guard, mem, max, traits);
    }

    private ProjectionAgentPlanSpec.FinalAnswer parseFinal(JsonNode step) {
        String model = textOrNull(step, "model");
        String sys = textOrNull(step, "system-prompt");
        boolean citations = step.path("citations").asBoolean(true);
        Integer max = intOrNull(step, "max-tokens");
        return new ProjectionAgentPlanSpec.FinalAnswer(model, sys, citations, max != null ? max : 1200);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null)
            return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode())
            return null;
        String s = v.asText(null);
        if (!StringUtils.hasText(s))
            return null;
        return s.trim();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null)
            return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode())
            return null;
        if (v.isNumber())
            return v.asInt();
        String s = v.asText("").trim();
        if (!StringUtils.hasText(s))
            return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> stringList(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull())
            return List.of();
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            for (JsonNode x : n) {
                String s = x.asText("").trim();
                if (StringUtils.hasText(s))
                    out.add(s);
            }
        } else {
            String s = n.asText("").trim();
            if (StringUtils.hasText(s))
                out.add(s);
        }
        return List.copyOf(out);
    }
}
