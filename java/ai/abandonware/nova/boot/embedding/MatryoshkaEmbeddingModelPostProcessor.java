package ai.abandonware.nova.boot.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

import java.util.HashSet;
import java.util.Set;

/**
 * Wraps selected {@link EmbeddingModel} beans with {@link MatryoshkaEmbeddingNormalizer}.
 */
public class MatryoshkaEmbeddingModelPostProcessor implements BeanPostProcessor, PriorityOrdered {

    private final Environment env;
    private volatile Set<String> allowNames;

    public MatryoshkaEmbeddingModelPostProcessor(Environment env) {
        this.env = env;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof EmbeddingModel model)) {
            return bean;
        }

        boolean enabled = Boolean.parseBoolean(env.getProperty(
                "nova.orch.embedding.matryoshka-shield.enabled", "true"));
        if (!enabled) {
            return bean;
        }

        Set<String> allow = allowNames;
        if (allow == null) {
            allow = parseAllowNames(env.getProperty(
                    "nova.orch.embedding.matryoshka-shield.allow-names",
                    "embeddingModel"));
            allowNames = allow;
        }

        if (!allow.contains(beanName)) {
            return bean;
        }
        if (bean instanceof MatryoshkaEmbeddingNormalizer) {
            return bean;
        }

        int dim = safeInt(env.getProperty("embedding.dimensions"), 0);
        if (dim <= 0) {
            // If the target dim isn't configured, don't change behavior.
            return bean;
        }

        return new MatryoshkaEmbeddingNormalizer(model, dim);
    }

    private static Set<String> parseAllowNames(String s) {
        Set<String> out = new HashSet<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String part : s.split(",")) {
            if (part == null) continue;
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static int safeInt(String s, int def) {
        if (s == null) return def;
        try {
            String t = s.trim();
            if (t.isEmpty()) return def;
            return Integer.parseInt(t);
        } catch (Throwable ignore) {
            return def;
        }
    }

    @Override
    public int getOrder() {
        // Run very late so we wrap the final decorated EmbeddingModel (caching, fallback, etc.)
        return Ordered.LOWEST_PRECEDENCE - 20;
    }
}
