package com.example.lms.ai.moe;

import com.example.lms.prompt.PromptContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.util.List;




/**
 * Utility class for selecting an appropriate mixture-of-experts model from
 * a list of candidates.  This router consults {@link MoeRoutingProperties}
 * to enforce per-capability allow lists and tier ordering.  Bean
 * registration is intentionally omitted to avoid name collisions with the
 * primary {@code ModelRouter} beans.  When {@code forceHighest} is
 * enabled the router will return the highest ranked allowed candidate; otherwise
 * it returns the first allowed candidate.  A forceModelId override takes
 * precedence over all other routing decisions.  See {@link MoeRoutingProperties}
 * for configuration details.
 */
@RequiredArgsConstructor
@EnableConfigurationProperties(MoeRoutingProperties.class)
public class MoeCandidateRouter {

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
        return (list == null || list.isEmpty()) ? "qwen2.5-7b-instruct" : list.get(0);
    }


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}