package com.example.lms.ai.moe;

import com.example.lms.prompt.PromptContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Simple mixture-of-experts router used to select a chat model from a list of
 * candidates.  The router consults {@link MoeRoutingProperties} to enforce
 * per-capability allow lists and tier ordering.  When forceHighest is
 * enabled the router will always pick the first allowed candidate in the
 * configured tier order; otherwise it returns the first candidate.  An
 * optional {@code forceModelId} may override all routing decisions.
 */
/**
 * Expose the MOE router as a distinct bean.  Without an explicit bean name
 * Spring would derive "modelRouter" from the class name, which conflicts
 * with other unrelated {@code ModelRouter} beans in the system.  Assign
 * a unique name to avoid {@link org.springframework.beans.factory.
 * ConflictingBeanDefinitionException} when the application context is
 * initialised.
 */
@Component("moeModelRouter")
@RequiredArgsConstructor
@EnableConfigurationProperties(MoeRoutingProperties.class)
public class ModelRouter {

    private static final String DEFAULT_KEY = "default";

    private final MoeRoutingProperties props;

    /**
     * Select a model from the provided candidates using optional context and capability hints.
     * The context is currently unused but reserved for future heuristics.  When the router is
     * disabled the first candidate is returned.  When a forceModelId is set it takes priority.
     * If a capability is provided the router will consult the per-capability allow and tier
     * configurations.  When forceHighest is true the highest ranked allowed candidate is
     * returned; otherwise the first allowed candidate is returned.
     *
     * @param ctx prompt context (unused)
     * @param capability optional capability key (e.g. "chat", "rag")
     * @param candidates candidate model identifiers in preferred order
     * @return the selected model identifier
     */
    public String selectModel(PromptContext ctx, String capability, List<String> candidates) {
        if (!props.isEnabled()) {
            return first(candidates);
        }
        String forceId = props.getForceModelId();
        if (forceId != null && !forceId.isBlank()) {
            return forceId;
        }
        // Determine the effective capability key or fall back to default
        String key = (capability != null && !capability.isBlank()) ? capability : DEFAULT_KEY;
        List<String> allowed = props.getAllow().getOrDefault(key,
                props.getAllow().getOrDefault(DEFAULT_KEY, List.of()));
        List<String> pool;
        if (allowed == null || allowed.isEmpty()) {
            pool = candidates;
        } else {
            pool = candidates.stream().filter(allowed::contains).toList();
            if (pool.isEmpty()) {
                pool = candidates;
            }
        }
        if (props.isForceHighest()) {
            List<String> order = props.getTierOrder().getOrDefault(key,
                    props.getTierOrder().getOrDefault(DEFAULT_KEY, List.of()));
            for (String tier : order) {
                if (pool.contains(tier)) {
                    return tier;
                }
            }
        }
        return first(pool);
    }

    /**
     * Convenience overload that omits the capability hint.  Delegates to the
     * full {@link #selectModel(PromptContext, String, List)} method with a null capability.
     */
    public String selectModel(PromptContext ctx, List<String> candidates) {
        return selectModel(ctx, null, candidates);
    }

    private String first(List<String> list) {
        return (list == null || list.isEmpty()) ? "gpt-5-chat-latest" : list.get(0);
    }
}