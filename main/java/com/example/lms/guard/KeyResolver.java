package com.example.lms.guard;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.routing.ApiRoutingDebug;
import com.example.lms.service.search.NaverCredentialBridge;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility facade for the central provider credential resolver.
 *
 * <p>
 * Equal duplicate aliases resolve to the established precedence order. Distinct
 * values fail closed for only that provider and are returned as {@code null}.
 * </p>
 */
@Component
public class KeyResolver {

    private final Environment env;
    private final ProviderCredentialResolver providerCredentialResolver;

    public KeyResolver(Environment env) {
        this.env = env;
        this.providerCredentialResolver = new ProviderCredentialResolver(env);
    }

    /**
     * Resolve the effective OpenAI API key.
     *
     * <ul>
     * <li>Allowed sources: llm.api-key-openai OR llm.openai.api-key OR
     * OPENAI_API_KEY</li>
     * <li>Equal duplicate values are accepted; distinct values disable OpenAI</li>
     * <li>If none configured, return null</li>
     * </ul>
     */
    public String resolveOpenAiApiKeyStrict() {
        return resolveOpenAiCredential().valueOrNull();
    }

    public ProviderCredentialResolver.Resolution resolveOpenAiCredential() {
        ProviderCredentialResolver.Resolution resolution =
                providerCredentialResolver.resolve(ProviderCredentialResolver.Provider.OPENAI);
        ApiRoutingDebug.decision(
                "llm",
                "openai",
                "n/a",
                "openai.api",
                resolution != null && resolution.enabled() && ApiRoutingDebug.keyPresent(resolution.valueOrNull()),
                resolution == null || resolution.sourceName() == null || resolution.sourceName().isBlank()
                        ? "OPENAI_API_KEY"
                        : resolution.sourceName());
        return resolution;
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
        return resolveLocalLlmCredential().valueOrNull();
    }

    public ProviderCredentialResolver.Resolution resolveLocalLlmCredential() {
        ProviderCredentialResolver.Resolution resolution =
                providerCredentialResolver.resolve(ProviderCredentialResolver.Provider.LOCAL_LLM);
        ApiRoutingDebug.decision(
                "llm",
                "local",
                "n/a",
                "llm.local",
                resolution != null && resolution.enabled() && ApiRoutingDebug.keyPresent(resolution.valueOrNull()),
                resolution == null || resolution.sourceName() == null || resolution.sourceName().isBlank()
                        ? "LLM_API_KEY"
                        : resolution.sourceName());
        return resolution;
    }

    /**
     * Resolve Gemini API key.
     *
     * <p>
     * Sources: gemini.api-key OR gemini.api.key (compat) OR GEMINI_API_KEY
     * </p>
     */
    public String resolveGeminiApiKeyStrict() {
        return providerCredentialResolver
                .resolve(ProviderCredentialResolver.Provider.GEMINI)
                .valueOrNull();
    }

    /**
     * Resolve Groq API key without falling back to generic local/OpenAI keys.
     */
    public String resolveGroqApiKeyStrict() {
        String key = resolveStrict(
                "Groq",
                externalSrc("llm.groq.api-key", env.getProperty("llm.groq.api-key")),
                externalSrc("GROQ_API_KEY", env.getProperty("GROQ_API_KEY")));
        ApiRoutingDebug.decision(
                "llm",
                "groq",
                "n/a",
                "api.groq.com",
                ApiRoutingDebug.keyPresent(key),
                ApiRoutingDebug.keyPresent(env.getProperty("llm.groq.api-key")) ? "llm.groq.api-key" : "GROQ_API_KEY");
        return key;
    }

    /**
     * Resolve Cerebras API key without falling back to generic local/OpenAI keys.
     */
    public String resolveCerebrasApiKeyStrict() {
        return resolveStrict(
                "Cerebras",
                externalSrc("llm.cerebras.api-key", env.getProperty("llm.cerebras.api-key")),
                externalSrc("CEREBRAS_API_KEY", env.getProperty("CEREBRAS_API_KEY")));
    }

    /**
     * Resolve OpenRouter API key without falling back to generic local/OpenAI keys.
     */
    public String resolveOpenRouterApiKeyStrict() {
        return resolveStrict(
                "OpenRouter",
                externalSrc("llm.openrouter.api-key", env.getProperty("llm.openrouter.api-key")),
                externalSrc("OPENROUTER_API_KEY", env.getProperty("OPENROUTER_API_KEY")));
    }

    /**
     * Resolve OpenCode Zen API key without falling back to generic local/OpenAI keys.
     */
    public String resolveOpenCodeApiKeyStrict() {
        return resolveStrict(
                "OpenCode",
                externalSrc("llm.opencode.api-key", env.getProperty("llm.opencode.api-key")),
                externalSrc("OPENCODE_API_KEY", env.getProperty("OPENCODE_API_KEY")));
    }

    /**
     * Resolve Naver credentials through the standard fail-soft ladder:
     * naver.keys, NAVER_KEYS, then naver.client-id/naver.client-secret.
     */
    public String resolveNaverKeysCsvSafe() {
        String clientId = firstTrimmed("naver.client-id", "NAVER_CLIENT_ID");
        String clientSecret = firstTrimmed("naver.client-secret", "NAVER_CLIENT_SECRET");
        ProviderCredentialResolver.Resolution resolution = providerCredentialResolver
                .resolve(ProviderCredentialResolver.Provider.NAVER);
        String resolved = resolution.valueOrNull();

        boolean keysPresent = resolution.enabled() && !ConfigValueGuards.isMissing(resolved);
        traceNaverCredentialResolution(
                naverSourceName(resolution),
                keysPresent,
                !ConfigValueGuards.isMissing(clientId) && !ConfigValueGuards.isMissing(clientSecret),
                NaverCredentialBridge.countCredentialPairs(resolved),
                keysPresent ? "" : naverDisabledReason(resolution));
        return resolved == null ? "" : resolved;
    }

    public static String resolveNaverKeysCsvSafe(ObjectProvider<KeyResolver> keyResolverProvider) {
        if (keyResolverProvider == null) {
            return "";
        }
        try {
            KeyResolver keyResolver = keyResolverProvider.getIfAvailable();
            return keyResolver == null ? "" : keyResolver.resolveNaverKeysCsvSafe();
        } catch (BeansException ex) {
            TraceStore.put("naver.cred.keyResolver.failureClass", "key_resolver_unavailable");
            TraceStore.put("naver.credential.keyResolver.failureClass", "key_resolver_unavailable");
            traceNaverCredentialResolution("none", false, false, 0, "key_resolver_unavailable");
            return "";
        }
    }

    private static Source src(String name, String value) {
        return new Source(name, value);
    }

    private static Source externalSrc(String name, String value) {
        return new Source(name, externalTrimToNull(value));
    }

    private static Source localSrc(String name, String value) {
        String t = trimToNull(value);
        return new Source(name, ConfigValueGuards.isMissingLocalOpenAiCompatKey(t) ? null : t);
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

    private String firstTrimmed(String propertyName, String envName) {
        String value = trimToNull(env.getProperty(propertyName));
        return value != null ? value : trimToNull(env.getProperty(envName));
    }

    private static String naverSourceName(ProviderCredentialResolver.Resolution resolution) {
        if (resolution == null || !resolution.enabled()) {
            return "none";
        }
        String sourceName = resolution.sourceName();
        if (sourceName == null || sourceName.isBlank()) {
            return "unknown";
        }
        return sourceName.toLowerCase().contains("client-pair") ? "client-pair" : sourceName;
    }

    private static String naverDisabledReason(ProviderCredentialResolver.Resolution resolution) {
        if (resolution == null || resolution.disabledReason() == null || resolution.disabledReason().isBlank()
                || "missing-credential".equals(resolution.disabledReason())) {
            return "no_valid_keys_found";
        }
        return resolution.disabledReason();
    }

    private static void traceNaverCredentialResolution(
            String sourceName,
            boolean keysPresent,
            boolean clientPairPresent,
            int parsedCount,
            String disabledReason) {
        int safeParsedCount = Math.max(0, parsedCount);
        TraceStore.put("naver.cred.sourceName", sourceName);
        TraceStore.put("naver.cred.keysPresent", keysPresent);
        TraceStore.put("naver.cred.clientPairPresent", clientPairPresent);
        TraceStore.put("naver.cred.parsedCount", safeParsedCount);
        TraceStore.put("naver.cred.disabledReason", disabledReason);
        TraceStore.put("naver.credential.sourceName", sourceName);
        TraceStore.put("naver.credential.source", sourceName);
        TraceStore.put("naver.credential.keysPresent", keysPresent);
        TraceStore.put("naver.credential.clientPairPresent", clientPairPresent);
        TraceStore.put("naver.credential.parsedCount", safeParsedCount);
        TraceStore.put("naver.credential.disabledReason", disabledReason);
        TraceStore.put("naver.sourceName", sourceName);
        TraceStore.put("naver.keysPresent", keysPresent);
        TraceStore.put("naver.clientPairPresent", clientPairPresent);
        TraceStore.put("naver.parsedCount", safeParsedCount);
        TraceStore.put("naver.disabledReason", disabledReason);
    }

    private static String externalTrimToNull(String v) {
        String t = trimToNull(v);
        return ConfigValueGuards.isMissing(t) ? null : t;
    }

    private record Source(String name, String value) {
    }
}
