package com.example.lms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Provider‑aware model guard.  Combines the allowlists from OpenAI and Groq
 * configuration properties and selects an appropriate fallback model based on
 * the active provider.  When no allowlist is provided for either provider a
 * sensible default is used.  Consumers should prefer {@link #requireAllowedOrFallback(String)}
 * to ensure invalid model identifiers fall back to the configured default.
 */
@Component
public class ModelGuard {

    private final Set<String> allowed;
    private final String fallback;
    // Provide built-in allowlists for OpenAI and Groq when no configuration is available.
    // Built-in allowlists for OpenAI and Groq.  Duplicates are removed and
    // GPT‑5 series identifiers are included.  Only a minimal Groq model is defined.
    private static final java.util.Set<String> OPENAI = java.util.Set.of(
            "gpt-4o","gpt-4o-mini","gpt-4.1","gpt-4.1-mini","o4-mini","o3-mini",
            "gpt-5","gpt-5-mini","gpt-5-chat-latest"
    );
    private static final java.util.Set<String> GROQ = java.util.Set.of(
            "llama-3.1-8b-instant"
    );

    public ModelGuard(
            @Value("${openai.allowed-models:}") String openaiAllowed,
            @Value("${groq.allowed-models:}") String groqAllowed,
            @Value("${openai.default-model:gpt-4o-mini}") String openaiFallback,
            @Value("${groq.default-model:}") String groqFallback,
            @Value("${llm.provider:openai}") String provider
    ) {
        this.allowed = normalize(openaiAllowed, groqAllowed);
        // When no allowlist is provided at all, populate with a default set of
        // common OpenAI identifiers.  Using a LinkedHashSet preserves order.
        if (this.allowed.isEmpty()) {
            this.allowed.addAll(Arrays.asList(
                    "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini",
                    "o4-mini", "o3-mini"
            ));
        }
        // Compute the fallback based on the current provider.  Use the
        // provider-specific fallback when supplied; otherwise fall back to
        // the OpenAI fallback.  If both are blank use a safe default.
        String fb;
        if ("groq".equalsIgnoreCase(provider) && groqFallback != null && !groqFallback.isBlank()) {
            fb = groqFallback.trim();
        } else {
            fb = openaiFallback;
        }
        this.fallback = (fb == null || fb.isBlank()) ? "gpt-4o-mini" : fb.trim();
    }

    private static Set<String> normalize(String... csvs) {
        Set<String> s = new LinkedHashSet<>();
        if (csvs != null) {
            for (String csv : csvs) {
                if (csv == null) continue;
                for (String t : csv.split(",")) {
                    String v = t.trim();
                    if (!v.isEmpty()) {
                        s.add(v);
                    }
                }
            }
        }
        return s;
    }

    /**
     * Test if the given model identifier is in the allowed list.  When the
     * allowlist is empty this returns {@code true} for any non‑null id.
     *
     * @param id model identifier
     * @return true if the id is allowed or the allowlist is empty
     */
    public boolean allowed(String id) {
        return id != null && (allowed.isEmpty() || allowed.contains(id.trim()));
    }

    /**
     * Return the given identifier when allowed, otherwise return the
     * configured fallback.  The returned identifier is trimmed.
     *
     * @param id model identifier to test
     * @return the original id when allowed or the fallback model id
     */
    public String requireAllowedOrFallback(String id) {
        // Delegate to the provider-aware fallback using the default provider (openai)
        return requireAllowedOrFallback(id, "openai");
    }

    /**
     * Return the requested model when it is allowed for the selected provider.
     * Otherwise return the provider-specific fallback.  The set of allowed
     * models is derived from built-in defaults rather than configuration
     * properties.  Use this method when the provider is known at runtime.
     *
     * @param requested the requested model identifier (may be null)
     * @param provider  the active provider ("openai", "groq", or other)
     * @return the requested model when allowed or the fallback model
     */
    public String requireAllowedOrFallback(String requested, String provider) {
        String m = requested == null ? null : requested.trim();
        if (m == null || m.isBlank()) {
            return defaultFallback(provider);
        }
        if ("groq".equalsIgnoreCase(provider)) {
            return GROQ.contains(m) ? m : defaultFallback(provider);
        }
        return OPENAI.contains(m) ? m : defaultFallback(provider);
    }

    /**
     * Enforce that the given model identifier is allowed.  An
     * {@link IllegalArgumentException} is thrown when the id is not in the allowlist.
     *
     * @param id model identifier to validate
     */
    public void requireAllowedOrThrow(String id) {
        if (!allowed(id)) {
            throw new IllegalArgumentException("unsupported_model: " + id + " (allowed: " + String.join(", ", allowed) + ")");
        }
    }

    /**
     * Return a list of all allowed model identifiers.
     *
     * @return list of allowed model ids
     */
    public List<String> allowedList() {
        return new ArrayList<>(allowed);
    }

    /**
     * Return the built-in allowed set for the given provider.  This exposes
     * the static allowlist rather than the dynamic values derived from
     * configuration.  The returned set is unmodifiable.
     *
     * @param provider provider id ("openai" or "groq")
     * @return unmodifiable set of allowed model identifiers
     */
    public java.util.Set<String> allowedFor(String provider) {
        return java.util.Collections.unmodifiableSet(
                "groq".equalsIgnoreCase(provider) ? GROQ : OPENAI
        );
    }

    /**
     * Return the configured fallback model identifier.
     *
     * @return fallback model id
     */
    public String fallback() {
        return fallback;
    }

    /**
     * Compute a provider-specific default fallback.  When the provider is Groq
     * the llama-3.1-8b-instant model is returned; otherwise gpt-4o-mini is used
     * unless a fallback property has been explicitly configured.
     *
     * @param provider the provider id ("openai", "groq", or other)
     * @return the default fallback model id
     */
    private String defaultFallback(String provider) {
        if ("groq".equalsIgnoreCase(provider)) {
            return "llama-3.1-8b-instant";
        }
        return (fallback == null || fallback.isBlank()) ? "gpt-4o-mini" : fallback;
    }

    /**
     * Compute a fallback model identifier for the given provider.  When the
     * provider is Groq the llama-3.1-8b-instant model is returned; otherwise
     * gpt-4o-mini is used.  This method does not consult dynamic
     * configuration and provides a sensible default when no explicit model
     * has been configured.
     *
     * @param provider the provider id ("openai", "groq" or other)
     * @return a default model identifier for the provider
     */
    public String fallbackFor(String provider) {
        if ("groq".equalsIgnoreCase(provider)) {
            return "llama-3.1-8b-instant";
        }
        return "gpt-4o-mini";
    }

    // Legacy provider-aware fallback method retained for compatibility.  Prefer
    // {@link #requireAllowedOrFallback(String, String)} instead.
}