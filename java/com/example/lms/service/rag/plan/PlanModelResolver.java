package com.example.lms.service.rag.plan;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Resolves plan DSL model ids into a concrete model name that
 * {@link com.example.lms.service.routing.ModelRouter} can serve.
 */
@Component
public class PlanModelResolver {

    private final Environment env;

    public PlanModelResolver(Environment env) {
        this.env = env;
    }

    /**
     * @return concrete model name, or null if "auto" should be used
     */
    public String resolveRequestedModel(String planModel) {
        if (!StringUtils.hasText(planModel)) {
            return null;
        }

        String raw = env.resolvePlaceholders(planModel.trim());
        String key = raw.toLowerCase(Locale.ROOT);

        if ("auto".equals(key)) {
            return null;
        }

        if (key.startsWith("llmrouter.")) {
            String tier = key.substring("llmrouter.".length());
            return switch (tier) {
                case "light", "fast" -> firstNonBlank(
                        env.getProperty("llm.fast.model"),
                        env.getProperty("llm.chat-model"),
                        raw
                );
                case "gemma", "chat", "default" -> firstNonBlank(
                        env.getProperty("llm.chat-model"),
                        env.getProperty("llm.fast.model"),
                        raw
                );
                case "high" -> firstNonBlank(
                        env.getProperty("llm.high.model"),
                        env.getProperty("llm.chat-model"),
                        env.getProperty("llm.fast.model"),
                        raw
                );
                default -> firstNonBlank(
                        env.getProperty("llm.chat-model"),
                        env.getProperty("llm.fast.model"),
                        raw
                );
            };
        }

        return raw;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
