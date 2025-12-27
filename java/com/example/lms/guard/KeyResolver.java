package com.example.lms.guard;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Strict API-key resolution helper.
 *
 * <p>Policy (UAW): if multiple sources are set (even if equal), fail-fast so
 * runtime does not silently pick an arbitrary key source.</p>
 */
@Component
public class KeyResolver {

    private final Environment env;

    public KeyResolver(Environment env) {
        this.env = env;
    }

    /**
     * Resolve OpenAI API key with strict conflict rules.
     *
     * <ul>
     *   <li>Allowed sources: llm.api-key-openai OR llm.openai.api-key OR OPENAI_API_KEY</li>
     *   <li>If more than one source is configured (non-blank), throw IllegalStateException</li>
     *   <li>If none configured, return null</li>
     * </ul>
     */
    public String resolveOpenAiApiKeyStrict() {
        String k1 = trimToNull(env.getProperty("llm.api-key-openai"));
        String k2 = trimToNull(env.getProperty("llm.openai.api-key"));
        String k3 = trimToNull(env.getProperty("OPENAI_API_KEY"));

        int sources = (k1 != null ? 1 : 0) + (k2 != null ? 1 : 0) + (k3 != null ? 1 : 0);
        if (sources == 0) {
            return null;
        }
        if (sources > 1) {
            throw new IllegalStateException(
                    "Conflicting OpenAI API keys: set only ONE of 'llm.api-key-openai' or 'llm.openai.api-key' or 'OPENAI_API_KEY'.");
        }

        if (k1 != null) return k1;
        if (k2 != null) return k2;
        return k3;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
