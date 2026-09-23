package com.example.lms.ai.moe;

import com.example.lms.llm.ModelCapabilities;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;




/**
 * Utility class for selecting an appropriate mixture-of-experts model from
 * a list of candidates.  This router consults {@link MoeRoutingProperties}
 * to enforce per-capability allow lists and tier ordering.  Bean
 * registration uses this distinct router type so it does not shadow primary
 * {@code ModelRouter} beans. When {@code forceHighest} is enabled the router
 * will return the highest ranked allowed candidate; otherwise it returns the
 * first allowed candidate. A forceModelId override takes precedence over all
 * other routing decisions. See {@link MoeRoutingProperties} for configuration
 * details.
 */
@Component
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
        List<String> sourceCandidates = candidates == null ? List.of() : candidates;
        String key = capabilityKey(capability);
        String bypassReason = "";
        if (!props.isEnabled()) {
            String selected = first(sourceCandidates);
            traceSelection(selected, key, sourceCandidates.size(), "disabled");
            return selected;
        }
        String forceId = props.getForceModelId();
        if (forceId != null && !forceId.isBlank()) {
            traceSelection(forceId, key, sourceCandidates.size(), "force_model");
            return forceId;
        }
        List<String> allowed = props.getAllow().getOrDefault(key,
                props.getAllow().getOrDefault(DEFAULT_KEY, List.of()));
        List<String> pool;
        if (allowed == null || allowed.isEmpty()) {
            pool = sourceCandidates;
        } else {
            pool = sourceCandidates.stream().filter(allowed::contains).toList();
            if (pool.isEmpty()) {
                pool = sourceCandidates;
                bypassReason = "allow_miss";
            }
        }
        if (props.isForceHighest()) {
            List<String> order = props.getTierOrder().getOrDefault(key,
                    props.getTierOrder().getOrDefault(DEFAULT_KEY, List.of()));
            for (String tier : order) {
                if (pool.contains(tier)) {
                    traceSelection(tier, key, sourceCandidates.size(), bypassReason);
                    return tier;
                }
            }
        }
        String selected = first(pool);
        traceSelection(selected, key, sourceCandidates.size(), bypassReason);
        return selected;
    }

    /**
     * Convenience overload that omits the capability hint.  Delegates to the
     * full {@link #selectModel(PromptContext, String, List)} method with a null capability.
     */
    public String selectModel(PromptContext ctx, List<String> candidates) {
        return selectModel(ctx, null, candidates);
    }

    private String first(List<String> list) {
        return (list == null || list.isEmpty()) ? ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL : list.get(0);
    }

    private static String capabilityKey(String capability) {
        return (capability != null && !capability.isBlank()) ? capability : DEFAULT_KEY;
    }

    private static void traceSelection(String selected, String capability, int totalCandidates, String bypassReason) {
        TraceStore.put("moe.candidate.selected",
                SafeRedactor.traceLabelOrFallback(selected, ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL));
        TraceStore.put("moe.candidate.capability",
                SafeRedactor.traceLabelOrFallback(capability, DEFAULT_KEY));
        TraceStore.put("moe.candidate.totalCandidates", Math.max(0, totalCandidates));
        TraceStore.put("moe.candidate.bypassReason",
                SafeRedactor.traceLabelOrFallback(bypassReason, ""));
    }

}
