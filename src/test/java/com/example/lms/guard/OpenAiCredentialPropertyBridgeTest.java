package com.example.lms.guard;

import com.example.lms.boot.RuntimeConfigGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiCredentialPropertyBridgeTest {

    @Test
    void singleLegacyAliasIsNormalizedAndProjectedToEveryConsumerAlias(CapturedOutput output) {
        String credential = "openai-single-bridge-contract-value";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("openai.api.key", "  " + credential + "  ");

        OpenAiCredentialPropertyBridge.apply(environment);

        for (String alias : ProviderCredentialResolver.openAiAliases()) {
            assertEquals(credential, environment.getProperty(alias), alias);
        }
        assertEquals(OpenAiCredentialPropertyBridge.PROPERTY_SOURCE_NAME,
                environment.getPropertySources().iterator().next().getName());
        assertFalse(output.getAll().contains(credential));
    }

    @Test
    void equalDuplicateAliasesProjectOneValueWithoutSecretDiagnostics(CapturedOutput output) {
        String credential = "openai-equal-bridge-contract-value";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OPENAI_API_KEY", credential)
                .withProperty("embedding.fallback.api-key", credential);

        OpenAiCredentialPropertyBridge.apply(environment);

        for (String alias : ProviderCredentialResolver.openAiAliases()) {
            assertEquals(credential, environment.getProperty(alias), alias);
        }
        ProviderCredentialResolver.Resolution resolution = new ProviderCredentialResolver(environment)
                .resolve(ProviderCredentialResolver.Provider.OPENAI);
        assertTrue(resolution.enabled());
        assertFalse(resolution.diagnostics().toString().contains(credential));
        assertFalse(output.getAll().contains(credential));
    }

    @Test
    void conflictingAliasesAreBlankedAndRemainConflictDisabledWithoutSecretExposure(CapturedOutput output) {
        String first = "openai-first-bridge-contract-value";
        String second = "openai-second-bridge-contract-value";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("openai.api.key", first)
                .withProperty("OPENAI_EMBED_FALLBACK_KEY", second);

        OpenAiCredentialPropertyBridge.apply(environment);

        for (String alias : ProviderCredentialResolver.openAiAliases()) {
            assertEquals("", environment.getProperty(alias), alias);
        }
        ProviderCredentialResolver.Resolution resolution = new ProviderCredentialResolver(environment)
                .resolve(ProviderCredentialResolver.Provider.OPENAI);
        assertFalse(resolution.enabled());
        assertTrue(resolution.credentialPresent());
        assertEquals(2, resolution.configuredAliasCount());
        assertEquals("conflicting-credential-aliases", resolution.disabledReason());
        assertFalse(resolution.diagnostics().toString().contains(first));
        assertFalse(resolution.diagnostics().toString().contains(second));
        assertFalse(output.getAll().contains(first));
        assertFalse(output.getAll().contains(second));
    }

    @Test
    void missingCredentialLeavesEnvironmentUnchanged() {
        MockEnvironment environment = new MockEnvironment();

        OpenAiCredentialPropertyBridge.apply(environment);

        assertFalse(environment.getPropertySources().contains(OpenAiCredentialPropertyBridge.PROPERTY_SOURCE_NAME));
        for (String alias : ProviderCredentialResolver.openAiAliases()) {
            assertNull(environment.getProperty(alias), alias);
        }
    }

    @Test
    void registeredRuntimeGuardRunsBridgeBeforeBeanValueResolution(CapturedOutput output) {
        String credential = "openai-runtime-guard-bridge-contract-value";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OPENAI_EMBED_FALLBACK_KEY", credential);

        new RuntimeConfigGuard().postProcessEnvironment(environment, new SpringApplication(Object.class));

        for (String alias : ProviderCredentialResolver.openAiAliases()) {
            assertEquals(credential, environment.getProperty(alias), alias);
        }
        assertFalse(output.getAll().contains(credential));
    }
}
