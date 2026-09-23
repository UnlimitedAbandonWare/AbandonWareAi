package com.example.lms.api;

import com.example.lms.service.ChannelOAuthService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelOAuthControllerTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void authorizeLeavesProviderDisabledTraceWithoutRawRedirectData() {
        ChannelOAuthController controller = new ChannelOAuthController(mock(ChannelOAuthService.class));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.authorize(redirect);

        assertEquals("redirect:/channels/recipients", view);
        assertEquals(Boolean.TRUE, TraceStore.get("channel.oauth.controller.providerDisabled"));
        assertEquals("channel_oauth_provider_not_configured",
                TraceStore.get("channel.oauth.controller.skipped.reason"));
        assertEquals("authorize", TraceStore.get("channel.oauth.controller.operation"));
        assertFalse(TraceStore.getAll().toString().contains("channel oauth provider is not configured"));
    }

    @Test
    void callbackErrorFlashDoesNotExposeProviderDescription() {
        ChannelOAuthController controller = new ChannelOAuthController(mock(ChannelOAuthService.class));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.callback(
                null,
                "access_denied",
                "provider rejected secret=oauth-callback-token",
                redirect);

        assertEquals("redirect:/channels/recipients", view);
        String error = String.valueOf(redirect.getFlashAttributes().get("error"));
        assertTrue(error.contains("channel oauth callback failed"));
        assertTrue(error.contains("errorHash="));
        assertTrue(error.contains("errorLength="));
        assertFalse(error.contains("oauth-callback-token"));
        assertFalse(error.contains("provider rejected"));
        assertFalse(error.contains("secret="));
        assertEquals(Boolean.TRUE, TraceStore.get("channel.oauth.controller.callbackRejected"));
        assertEquals("oauth_callback_error", TraceStore.get("channel.oauth.controller.skipped.reason"));
        assertEquals("access_denied", TraceStore.get("channel.oauth.controller.errorCode"));
        assertEquals(45, TraceStore.get("channel.oauth.controller.errorLength"));
        assertFalse(TraceStore.getAll().toString().contains("oauth-callback-token"));
        assertFalse(TraceStore.getAll().toString().contains("provider rejected"));
    }

    @Test
    void callbackMissingCodeLeavesStableSkipTraceWithoutCallingProvider() {
        ChannelOAuthService service = mock(ChannelOAuthService.class);
        ChannelOAuthController controller = new ChannelOAuthController(service);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.callback("  ", null, null, redirect);

        assertEquals("redirect:/channels/recipients", view);
        assertEquals("missing authorization code", redirect.getFlashAttributes().get("error"));
        assertEquals(Boolean.TRUE, TraceStore.get("channel.oauth.controller.missingCode"));
        assertEquals("missing_authorization_code", TraceStore.get("channel.oauth.controller.skipped.reason"));
        assertEquals("callback_missing_code", TraceStore.get("channel.oauth.controller.operation"));
        verify(service, never()).exchangeCodeForToken(any());
    }

    @Test
    void callbackDoesNotSendMemoWhenProviderReturnsNoToken() {
        ChannelOAuthService service = mock(ChannelOAuthService.class);
        when(service.exchangeCodeForToken("code-123")).thenReturn(null);
        ChannelOAuthController controller = new ChannelOAuthController(service);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.callback("code-123", null, null, redirect);

        assertEquals("redirect:/channels/recipients", view);
        assertEquals("channel oauth provider is not configured", redirect.getFlashAttributes().get("error"));
        assertEquals(Boolean.TRUE, TraceStore.get("channel.oauth.controller.providerDisabled"));
        assertEquals("channel_oauth_provider_not_configured",
                TraceStore.get("channel.oauth.controller.skipped.reason"));
        assertEquals("callback_exchange_token", TraceStore.get("channel.oauth.controller.operation"));
        assertFalse(TraceStore.getAll().toString().contains("code-123"));
        verify(service).exchangeCodeForToken("code-123");
        verify(service, never()).sendMemoDefault(any(), any(), any());
    }
}
