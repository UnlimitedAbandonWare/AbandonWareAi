package com.example.lms.guard;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Strict API-key resolution helper.
 *
 * <p>
 * Policy: if multiple sources are set (even if equal), fail-fast so runtime
 * does not silently pick an arbitrary key source.
 * </p>
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
     * <li>Allowed sources: llm.api-key-openai OR llm.openai.api-key OR
     * OPENAI_API_KEY</li>
     * <li>If more than one source is configured (non-blank), throw
     * IllegalStateException</li>
     * <li>If none configured, return null</li>
     * </ul>
     */
    public String resolveOpenAiApiKeyStrict() {
        return resolveStrict(
                "OpenAI",
                src("llm.api-key-openai", trimToNull(env.getProperty("llm.api-key-openai"))),
                src("llm.openai.api-key", trimToNull(env.getProperty("llm.openai.api-key"))),
                src("OPENAI_API_KEY", trimToNull(env.getProperty("OPENAI_API_KEY"))));
    }

    /**
     * Alias for compatibility with OpenAiChatModelGuardAspect.
     * Returns OpenAI API key from property or environment.
     */
    public String getPropertyOrEnvOpenAiKey() {
        return resolveOpenAiApiKeyStrict();
    }

    /**
     * Resolve the local(OpenAI-compatible) API key.
     *
     * <p>
     * Sources: llm.api-key OR LLM_API_KEY
     * </p>
     */
    public String resolveLocalApiKeyStrict() {
        return resolveStrict(
                "Local(OpenAI-compatible)",
                src("llm.api-key", trimToNull(env.getProperty("llm.api-key"))),
                src("LLM_API_KEY", trimToNull(env.getProperty("LLM_API_KEY"))));
    }

    /**
     * Resolve Gemini API key.
     *
     * <p>
     * Sources: gemini.api-key OR gemini.api.key (compat) OR GEMINI_API_KEY
     * </p>
     */
    public String resolveGeminiApiKeyStrict() {
        return resolveStrict(
                "Gemini",
                src("gemini.api-key", trimToNull(env.getProperty("gemini.api-key"))),
                src("gemini.api.key", trimToNull(env.getProperty("gemini.api.key"))),
                src("GEMINI_API_KEY", trimToNull(env.getProperty("GEMINI_API_KEY"))));
    }

    private static Source src(String name, String value) {
        return new Source(name, value);
    }

    private static String resolveStrict(String label, Source... sources) {
        int count = 0;
        String picked = null;
        List<String> set = new ArrayList<>();

        if (sources != null) {
            for (Source s : sources) {
                if (s == null)
                    continue;
                if (s.value != null) {
                    count++;
                    picked = s.value;
                    set.add(s.name);
                }
            }
        }

        if (count == 0) {
            return null;
        }
        if (count > 1) {
            throw new IllegalStateException(
                    "Conflicting " + label + " API keys: set only ONE of " + set);
        }
        return picked;
    }

    private static String trimToNull(String v) {
        if (v == null)
            return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private record Source(String name, String value) {
    }
}
