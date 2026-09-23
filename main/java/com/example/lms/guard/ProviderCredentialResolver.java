package com.example.lms.guard;

import com.example.lms.config.ConfigValueGuards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Resolves one effective credential per provider without exposing its value. */
@Component
public class ProviderCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialResolver.class);
    static final String OPENAI_FORCED_DISABLED_REASON =
            "provider.credentials.openai.forced-disabled-reason";
    static final String OPENAI_FORCED_ALIAS_COUNT =
            "provider.credentials.openai.configured-alias-count";
    private static final List<String> OPENAI_ALIASES = List.of(
            "llm.api-key-openai",
            "llm.openai.api-key",
            "OPENAI_API_KEY",
            "openai.api.key",
            "openai.api-key",
            "spring.ai.openai.api-key",
            "openai.image.api-key",
            "embedding.fallback.api-key",
            "OPENAI_EMBED_FALLBACK_KEY");

    private final Environment environment;

    public ProviderCredentialResolver(Environment environment) {
        this.environment = environment;
    }

    public Resolution resolve(Provider provider) {
        return switch (provider) {
            case GEMINI -> resolveExternal(provider,
                    "gemini.api-key",
                    "gemini.api.key",
                    "GEMINI_API_KEY");
            case OPENAI -> resolveOpenAi();
            case BRAVE -> resolveBrave();
            case NAVER -> resolveNaver();
            case TAVILY -> resolveExternal(provider,
                    "tavily.api.key",
                    "TAVILY_API_KEY");
            case SERPAPI -> resolveExternal(provider,
                    "gpt-search.serpapi.api-key",
                    "search.serpapi.api-key",
                    "GPT_SEARCH_SERPAPI_API_KEY",
                    "SERPAPI_API_KEY");
            case LOCAL_LLM -> resolveLocal(provider,
                    "llm.api-key",
                    "LLM_API_KEY");
        };
    }

    private Resolution resolveBrave() {
        // Base lane only. Retired BRAVE_SUBSCRIPTION_TOKEN is never an input.
        String name = environment.containsProperty("BRAVE_API_KEY")
                ? "BRAVE_API_KEY" : "gpt-search.brave.api-key";
        return resolveExternal(Provider.BRAVE, name);
    }

    public Resolution resolveBraveFree() {
        return resolveExternal(Provider.BRAVE,
                "BRAVE_API_KEY_FREE",
                "gpt-search.brave.api-key-free",
                "GPT_SEARCH_BRAVE_API_KEY_FREE");
    }

    private Resolution resolveOpenAi() {
        String forcedReason = trimToNull(environment.getProperty(OPENAI_FORCED_DISABLED_REASON));
        if ("conflicting-credential-aliases".equals(forcedReason)) {
            int configuredAliasCount = parseNonNegativeInt(
                    environment.getProperty(OPENAI_FORCED_ALIAS_COUNT));
            return Resolution.disabled(
                    Provider.OPENAI,
                    configuredAliasCount > 0,
                    "conflict",
                    configuredAliasCount,
                    forcedReason);
        }
        return resolveExternal(Provider.OPENAI, OPENAI_ALIASES.toArray(String[]::new));
    }

    static List<String> openAiAliases() {
        return OPENAI_ALIASES;
    }

    private Resolution resolveExternal(Provider provider, String... aliases) {
        List<Candidate> configured = new ArrayList<>();
        if (aliases != null) {
            for (String alias : aliases) {
                String value = trimToNull(environment.getProperty(alias));
                if (!ConfigValueGuards.isMissing(value)) {
                    configured.add(new Candidate(alias, value));
                }
            }
        }

        return finish(provider, configured);
    }

    private Resolution resolveLocal(Provider provider, String... aliases) {
        List<Candidate> configured = new ArrayList<>();
        if (aliases != null) {
            for (String alias : aliases) {
                String value = trimToNull(environment.getProperty(alias));
                if (!ConfigValueGuards.isMissingLocalOpenAiCompatKey(value)) {
                    configured.add(new Candidate(alias, value));
                }
            }
        }
        return finish(provider, configured);
    }

    private Resolution resolveNaver() {
        List<Candidate> configured = new ArrayList<>();
        addExternalCandidate(configured, "naver.keys", environment.getProperty("naver.keys"));
        addExternalCandidate(configured, "NAVER_KEYS", environment.getProperty("NAVER_KEYS"));

        String propertyId = externalValue("naver.client-id");
        String propertySecret = externalValue("naver.client-secret");
        String environmentId = externalValue("NAVER_CLIENT_ID");
        String environmentSecret = externalValue("NAVER_CLIENT_SECRET");
        if (propertyId != null && propertySecret != null) {
            configured.add(new Candidate("naver.client-pair", propertyId + ":" + propertySecret));
        }
        if (environmentId != null && environmentSecret != null) {
            configured.add(new Candidate("NAVER_CLIENT_PAIR", environmentId + ":" + environmentSecret));
        }
        if ((propertyId == null || propertySecret == null)
                && (environmentId == null || environmentSecret == null)) {
            String mixedId = propertyId != null ? propertyId : environmentId;
            String mixedSecret = propertySecret != null ? propertySecret : environmentSecret;
            if (mixedId != null && mixedSecret != null) {
                configured.add(new Candidate("naver.client-pair-mixed", mixedId + ":" + mixedSecret));
            }
        }
        return finish(Provider.NAVER, configured);
    }

    private void addExternalCandidate(List<Candidate> configured, String name, String rawValue) {
        String value = trimToNull(rawValue);
        if (!ConfigValueGuards.isMissing(value)) {
            configured.add(new Candidate(name, value));
        }
    }

    private String externalValue(String name) {
        String value = trimToNull(environment.getProperty(name));
        return ConfigValueGuards.isMissing(value) ? null : value;
    }

    private Resolution finish(Provider provider, List<Candidate> configured) {
        if (configured.isEmpty()) {
            return Resolution.disabled(provider, false, "none", 0, "missing-credential");
        }

        LinkedHashSet<String> distinctValues = new LinkedHashSet<>();
        configured.forEach(candidate -> distinctValues.add(candidate.value()));
        if (distinctValues.size() > 1) {
            return Resolution.disabled(
                    provider,
                    true,
                    "conflict",
                    configured.size(),
                    "conflicting-credential-aliases");
        }

        Candidate selected = configured.get(0);
        boolean duplicateAlias = configured.size() > 1;
        if (duplicateAlias) {
            log.warn("[provider-credential] equal duplicate aliases provider={} aliasCount={}",
                    provider.id(), configured.size());
        }
        return Resolution.enabled(provider, selected.value(), selected.name(), configured.size(), duplicateAlias);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int parseNonNegativeInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value == null ? "0" : value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public enum Provider {
        GEMINI("gemini"),
        OPENAI("openai"),
        BRAVE("brave"),
        NAVER("naver"),
        TAVILY("tavily"),
        SERPAPI("serpapi"),
        LOCAL_LLM("local_llm");

        private final String id;

        Provider(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record Diagnostics(
            String provider,
            String sourceName,
            boolean credentialPresent,
            int configuredAliasCount,
            boolean duplicateAlias,
            String disabledReason) {
    }

    public record Resolution(
            String provider,
            String valueOrNull,
            boolean enabled,
            boolean credentialPresent,
            String sourceName,
            int configuredAliasCount,
            boolean duplicateAlias,
            String disabledReason) {

        private static Resolution enabled(
                Provider provider,
                String value,
                String sourceName,
                int configuredAliasCount,
                boolean duplicateAlias) {
            return new Resolution(provider.id(), value, true, true, sourceName, configuredAliasCount, duplicateAlias,
                    "");
        }

        private static Resolution disabled(
                Provider provider,
                boolean credentialPresent,
                String sourceName,
                int configuredAliasCount,
                String disabledReason) {
            return new Resolution(provider.id(), null, false, credentialPresent, sourceName, configuredAliasCount,
                    false,
                    disabledReason);
        }

        public Diagnostics diagnostics() {
            return new Diagnostics(
                    provider,
                    sourceName,
                    credentialPresent,
                    configuredAliasCount,
                    duplicateAlias,
                    disabledReason);
        }

    }

    private record Candidate(String name, String value) {
    }
}
