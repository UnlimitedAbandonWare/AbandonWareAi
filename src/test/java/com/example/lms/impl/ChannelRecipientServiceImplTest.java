package com.example.lms.impl;

import com.example.lms.dto.ChannelRecipients;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChannelOAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelRecipientServiceImplTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void fetchRecipientIdsTreatsNullProviderResponseAsDisabledEmptyResult() {
        ChannelOAuthService oauthService = mock(ChannelOAuthService.class);
        when(oauthService.recipients("private-token", 0, 50)).thenReturn(null);
        ChannelRecipientServiceImpl service = new ChannelRecipientServiceImpl(oauthService);

        assertEquals(List.of(), service.fetchRecipientIds("private-token", 0, 50));
        assertEquals(0, TraceStore.get("channel.recipients.returnedCount"));
        assertEquals("provider_empty_response", TraceStore.get("channel.recipients.skipped.reason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private-token"));
    }

    @Test
    void fetchRecipientIdsFiltersBlankAndNullIds() {
        ChannelRecipients.Element empty = new ChannelRecipients.Element();
        empty.setId(" ");
        ChannelRecipients.Element missing = new ChannelRecipients.Element();
        ChannelRecipients.Element valid = new ChannelRecipients.Element();
        valid.setId("recipient-1");
        ChannelRecipients recipients = new ChannelRecipients();
        recipients.setElements(List.of(empty, missing, valid));
        ChannelOAuthService oauthService = mock(ChannelOAuthService.class);
        when(oauthService.recipients("token", 0, 50)).thenReturn(recipients);
        ChannelRecipientServiceImpl service = new ChannelRecipientServiceImpl(oauthService);

        assertEquals(List.of("recipient-1"), service.fetchRecipientIds("token", 0, 50));
        assertEquals(1, TraceStore.get("channel.recipients.returnedCount"));
        assertEquals(3, TraceStore.get("channel.recipients.rawCount"));
    }
}
