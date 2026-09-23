package com.example.lms.config;

import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderGuardConfigTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void loopbackUrlAllowsLocalPlaceholderToken() {
        ProviderGuardConfig guard = guard(
                "local",
                "http://localhost:11434/v1",
                "sk-local",
                "",
                false,
                "",
                true,
                false);

        assertDoesNotThrow(guard::validate);
    }

    @Test
    void requireLocalRejectsProviderWithoutEchoingValue() {
        String provider = "openai-secret-provider";
        ProviderGuardConfig guard = guard(
                provider,
                "http://localhost:11434/v1",
                "sk-local",
                "",
                false,
                "",
                true,
                true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::validate);
        String message = ex.getMessage();
        assertFalse(message.contains(provider));
        assertTrue(message.contains("providerHash=" + SafeRedactor.hashValue(provider)));
        assertTrue(message.contains("providerLength=" + provider.length()));
    }

    @Test
    void remoteLocalUrlIsBlockedByDefault() {
        ProviderGuardConfig guard = guard(
                "local",
                "https://macmini-ollama.internal/v1",
                "proxy-secret-value",
                "",
                false,
                "macmini-ollama.internal",
                true,
                false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::validate);
        assertFalse(ex.getMessage().contains("proxy-secret-value"));
    }

    @Test
    void remoteLocalUrlRequiresAllowlistAndUsableAuth() {
        ProviderGuardConfig allowed = guard(
                "local",
                "https://macmini-ollama.internal/v1",
                "dummy",
                "owner-proxy-secret-value",
                true,
                "macmini-ollama.internal,192.168.1.40",
                true,
                false);

        assertDoesNotThrow(allowed::validate);

        ProviderGuardConfig missingAuth = guard(
                "local",
                "https://macmini-ollama.internal/v1",
                "sk-local",
                "",
                true,
                "macmini-ollama.internal",
                true,
                false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, missingAuth::validate);
        assertFalse(ex.getMessage().contains("sk-local"));
    }

    @Test
    void placeholderRemoteAuthValuesAreRejected() {
        for (String placeholder : List.of("dummy", "sk-local", "test", "changeme", "${LLM_API_KEY:}", "")) {
            ProviderGuardConfig guard = guard(
                    "local",
                    "https://macmini-ollama.internal/v1",
                    placeholder,
                    "",
                    true,
                    "macmini-ollama.internal",
                    true,
                    false);

            assertThrows(IllegalStateException.class, guard::validate, placeholder);
        }
    }

    @Test
    void remoteGuardCanFailSoftWhenExplicitlyConfigured() {
        ProviderGuardConfig guard = guard(
                "local",
                "https://macmini-ollama.internal/v1",
                "sk-local",
                "",
                true,
                "macmini-ollama.internal",
                true,
                false);
        ReflectionTestUtils.setField(guard, "failFast", false);

        assertDoesNotThrow(guard::validate);
        assertEquals(Boolean.TRUE, TraceStore.get("llm.providerGuard.disabled"));
        assertEquals("provider-disabled", TraceStore.get("llm.providerGuard.failureClass"));
        assertEquals("remote_auth_missing", TraceStore.get("llm.providerGuard.disabledReason"));
    }

    @Test
    void roleSpecificRemoteUrlsUseSameFailClosedGuard() {
        for (String url : List.of(
                "https://macmini-fast.internal/v1",
                "https://macmini-high.internal/v1",
                "https://macmini-judge.internal/v1",
                "https://macmini-coder.internal/v1",
                "https://macmini-vision.internal/v1")) {
            IllegalStateException blocked = assertThrows(IllegalStateException.class,
                    () -> LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                            url,
                            false,
                            host(url),
                            true,
                            "proxy-secret-value",
                            ""));
            assertFalse(blocked.getMessage().contains("proxy-secret-value"));

            assertDoesNotThrow(() -> LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                    url,
                    true,
                    host(url),
                    true,
                    "sk-local",
                    "owner-proxy-secret-value"));
        }
    }

    private static ProviderGuardConfig guard(
            String provider,
            String baseUrl,
            String apiKey,
            String ownerToken,
            boolean allowPrivateRemote,
            String allowedHosts,
            boolean requireAuthForRemote,
            boolean requireLocal) {
        ProviderGuardConfig guard = new ProviderGuardConfig();
        ReflectionTestUtils.setField(guard, "provider", provider);
        ReflectionTestUtils.setField(guard, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(guard, "apiKey", apiKey);
        ReflectionTestUtils.setField(guard, "ownerToken", ownerToken);
        ReflectionTestUtils.setField(guard, "allowPrivateRemote", allowPrivateRemote);
        ReflectionTestUtils.setField(guard, "allowedHosts", allowedHosts);
        ReflectionTestUtils.setField(guard, "requireAuthForRemote", requireAuthForRemote);
        ReflectionTestUtils.setField(guard, "requireLocal", requireLocal);
        return guard;
    }

    private static String host(String url) {
        return java.net.URI.create(url).getHost();
    }
}
