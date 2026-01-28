package com.example.lms.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Environment;

/**
 * Creates the OpenAI embedding fallback bean only when an API key is actually present.
 *
 * <p>This is intentionally stricter than {@code @ConditionalOnProperty} because we must
 * treat blank/sentinel values as "missing" to avoid accidental external calls with an
 * empty token.</p>
 */
public final class EmbeddingFallbackKeyPresentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (context == null) {
            return false;
        }
        Environment env = context.getEnvironment();
        if (env == null) {
            return false;
        }

        // Explicit embedding fallback key first.
        String k = env.getProperty("embedding.fallback.api-key");
        if (!ConfigValueGuards.isMissing(k)) {
            return true;
        }

        // Common OpenAI key property.
        k = env.getProperty("openai.api.key");
        if (!ConfigValueGuards.isMissing(k)) {
            return true;
        }

        // Environment variables are exposed as properties too.
        k = env.getProperty("OPENAI_API_KEY");
        return !ConfigValueGuards.isMissing(k);
    }
}
