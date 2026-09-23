package com.example.lms.service;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChannelOAuthServiceImplTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void exchangeCodeLeavesProviderDisabledTraceWithoutRawCode() {
        String token = new ChannelOAuthServiceImpl().exchangeCodeForToken("private-oauth-code");

        assertNull(token);
        assertEquals(Boolean.TRUE, TraceStore.get("channel.oauth.providerDisabled"));
        assertEquals("channel_oauth_provider_not_configured", TraceStore.get("channel.oauth.disabledReason"));
        assertEquals("channel_oauth_provider_not_configured", TraceStore.get("channel.oauth.skipped.reason"));
        assertEquals("exchange_code", TraceStore.get("channel.oauth.operation"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private-oauth-code"));
    }

    @Test
    void memoAndRecipientNoopsLeaveProviderDisabledTraceWithoutRawTokenOrText() {
        ChannelOAuthServiceImpl service = new ChannelOAuthServiceImpl();

        service.sendMemoDefault("private-access-token", "private memo text", "https://example.test/private");
        assertTrue(service.recipients("private-access-token", 1, 2).getElements().isEmpty());
        assertTrue(service.fetchRecipientIds("private-access-token", 1, 2).isEmpty());

        assertEquals(Boolean.TRUE, TraceStore.get("channel.oauth.providerDisabled"));
        assertEquals("channel_oauth_provider_not_configured", TraceStore.get("channel.oauth.disabledReason"));
        assertEquals("fetch_recipient_ids", TraceStore.get("channel.oauth.operation"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private-access-token"));
        assertFalse(trace.contains("private memo text"));
        assertFalse(trace.contains("example.test/private"));
    }

    @Test
    void disabledProviderBeanNameDocumentsNoopRuntimeContract() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChannelOAuthServiceImpl.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("@Service(\"channelOAuthServiceDisabled\")"));
        assertTrue(source.contains("Disabled provider adapter"));
        assertTrue(source.contains("channel_oauth_provider_not_configured"));
    }
}
