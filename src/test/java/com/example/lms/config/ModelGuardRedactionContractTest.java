package com.example.lms.config;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.guard.KeyResolver;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGuardRedactionContractTest {

    @Test
    void apiKeyMissingExceptionDoesNotEchoProviderValue() {
        String provider = "openai-secret-provider";
        MockEnvironment env = new MockEnvironment();
        ModelGuard.LlmProps props = new ModelGuard.LlmProps();
        props.setProvider(provider);
        props.setChatModel("gpt-4o-mini");
        props.setBaseUrl("https://api.openai.com");

        ModelGuard guard = new ModelGuard(props, env, new KeyResolver(env), new DebugEventStore());

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::verify);
        String message = ex.getMessage();
        assertFalse(message.contains(provider));
        assertTrue(message.contains("providerHash=" + SafeRedactor.hashValue(provider)));
        assertTrue(message.contains("providerLength=" + provider.length()));
    }
}
