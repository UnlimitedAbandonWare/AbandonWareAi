package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A simplified model router implementation that selects between two
 * preconfigured chat models (mini and high) based on a few numeric
 * heuristics.  This implementation avoids any dynamic model factory or
 * profile‑specific behaviour and instead depends solely on beans defined in
 * {@link com.example.lms.config.ModelConfig}.  It is activated when the
 * {@code legacy-router} profile is not present.
 */
@Component
@Primary
@Profile("!legacy-router")
public class ModelRouterCore implements ModelRouter {

    private final ChatModel mini;
    private final ChatModel high;
    private final double threshold;
    private final double margin;

    public ModelRouterCore(
            @Qualifier("mini") ChatModel mini,
            @Qualifier("high") ChatModel high,
            @Value("${router.moe.threshold:0.62}") double threshold,
            @Value("${router.moe.margin:0.08}") double margin) {
        this.mini = mini;
        this.high = high;
        this.threshold = threshold;
        this.margin = margin;
    }

    /**
     * Route based on the provided {@link RouteSignal}.  The decision score is
     * computed as the sum of the complexity and uncertainty metrics.  If the
     * token budget exceeds 1024 tokens, the margin is added as a small
     * incentive to upgrade.  Preferences of QUALITY or COST adjust the score
     * by the margin in opposite directions.  When the final score meets or
     * exceeds the configured threshold, the high tier model is selected.
     */
    @Override
    public ChatModel route(RouteSignal s) {
        if (s == null) {
            return mini;
        }
        double score = s.complexity() + s.uncertainty();
        if (s.maxTokens() > 1024) {
            score += margin;
        }
        if (s.preferred() == RouteSignal.Preference.QUALITY) {
            score += margin;
        } else if (s.preferred() == RouteSignal.Preference.COST) {
            score -= margin;
        }
        return score >= threshold ? high : mini;
    }

    /**
     * Overloaded routing method accepting primitive hints.  This builds a
     * {@link RouteSignal} with reasonable defaults and delegates to the
     * primary {@link #route(RouteSignal)} logic.
     */
    @Override
    public ChatModel route(String intent, String riskLevel, String verbosityHint, Integer targetMaxTokens) {
        RouteSignal sig = new RouteSignal(
                0.5,
                0.0,
                "HIGH".equalsIgnoreCase(riskLevel) ? 0.3 : 0.1,
                0.0,
                RouteSignal.Intent.GENERAL,
                RouteSignal.Verbosity.NORMAL,
                targetMaxTokens == null ? 1024 : targetMaxTokens,
                RouteSignal.Preference.BALANCED,
                "router-core"
        );
        return route(sig);
    }

    /**
     * Escalation always returns the high tier model regardless of the signal.
     */
    @Override
    public ChatModel escalate(RouteSignal sig) {
        return high;
    }

    /**
     * Attempt to resolve the underlying model name.  For OpenAI chat models
     * expose the configured {@code modelName}.  Otherwise fall back to the
     * simple class name via reflection.
     */
    @Override
    public String resolveModelName(ChatModel model) {
        if (model instanceof OpenAiChatModel m) {
            try {
                java.lang.reflect.Field f = m.getClass().getDeclaredField("modelName");
                f.setAccessible(true);
                return String.valueOf(f.get(m));
            } catch (Exception e) {
                // ignore and fall through
            }
        }
        return model != null ? model.getClass().getSimpleName() : null;
    }
}