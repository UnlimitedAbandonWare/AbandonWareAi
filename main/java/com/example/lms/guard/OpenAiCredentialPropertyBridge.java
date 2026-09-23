package com.example.lms.guard;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Projects the single OpenAI resolver decision to legacy property consumers
 * before Spring resolves their {@code @Value} expressions.
 */
public final class OpenAiCredentialPropertyBridge {

    static final String PROPERTY_SOURCE_NAME = "providerCredentialResolver.openai";

    private OpenAiCredentialPropertyBridge() {
    }

    public static void apply(ConfigurableEnvironment environment) {
        if (environment == null) {
            return;
        }

        environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
        ProviderCredentialResolver.Resolution resolution = new ProviderCredentialResolver(environment)
                .resolve(ProviderCredentialResolver.Provider.OPENAI);

        Map<String, Object> projected = new LinkedHashMap<>();
        if (resolution.enabled() && resolution.valueOrNull() != null) {
            for (String alias : ProviderCredentialResolver.openAiAliases()) {
                projected.put(alias, resolution.valueOrNull());
            }
        } else if ("conflicting-credential-aliases".equals(resolution.disabledReason())) {
            for (String alias : ProviderCredentialResolver.openAiAliases()) {
                projected.put(alias, "");
            }
            projected.put(ProviderCredentialResolver.OPENAI_FORCED_DISABLED_REASON,
                    resolution.disabledReason());
            projected.put(ProviderCredentialResolver.OPENAI_FORCED_ALIAS_COUNT,
                    resolution.configuredAliasCount());
        } else {
            return;
        }

        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, projected));
    }
}
