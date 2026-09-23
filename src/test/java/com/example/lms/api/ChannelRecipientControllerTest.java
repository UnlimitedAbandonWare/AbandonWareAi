package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChannelRecipientService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChannelRecipientControllerTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void missingAccessTokenLeavesProviderDisabledTraceWithoutRawToken() {
        ChannelRecipientService recipientService = mock(ChannelRecipientService.class);
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("channelAccessToken")).thenReturn(null);
        ChannelRecipientController controller = new ChannelRecipientController(recipientService);
        ReflectionTestUtils.setField(controller, "pageSize", 10);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.showRecipients(1, session, "", "", model);

        assertEquals("channels/recipients", view);
        assertEquals(List.of(), model.getAttribute("recipients"));
        assertEquals(1, model.getAttribute("page"));
        assertEquals("channel recipient provider is not configured", model.getAttribute("error"));
        assertEquals(Boolean.TRUE, TraceStore.get("channel.recipients.controller.providerDisabled"));
        assertEquals("missing_access_token", TraceStore.get("channel.recipients.controller.skipped.reason"));
        assertEquals(0, TraceStore.get("channel.recipients.controller.returnedCount"));
        assertFalse(TraceStore.getAll().toString().contains("channelAccessToken"));
        verifyNoInteractions(recipientService);
    }

    @Test
    void hugePageCannotOverflowOffsetOrCallProvider() {
        ChannelRecipientService recipientService = mock(ChannelRecipientService.class);
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("channelAccessToken")).thenReturn("private-token");
        ChannelRecipientController controller = new ChannelRecipientController(recipientService);
        ReflectionTestUtils.setField(controller, "pageSize", 10);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.showRecipients(Integer.MAX_VALUE, session, "", "", model);

        assertEquals("channels/recipients", view);
        assertEquals(List.of(), model.getAttribute("recipients"));
        assertEquals("invalid recipient page", model.getAttribute("error"));
        assertEquals("invalid_pagination", TraceStore.get("channel.recipients.controller.skipped.reason"));
        assertEquals(0, TraceStore.get("channel.recipients.controller.returnedCount"));
        verifyNoInteractions(recipientService);
    }

    @Test
    void negativePageIsRejectedBeforeTokenOrProviderUse() {
        ChannelRecipientService recipientService = mock(ChannelRecipientService.class);
        HttpSession session = mock(HttpSession.class);
        ChannelRecipientController controller = new ChannelRecipientController(recipientService);
        ReflectionTestUtils.setField(controller, "pageSize", 10);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.showRecipients(-1, session, "", "", model);

        assertEquals("channels/recipients", view);
        assertEquals(List.of(), model.getAttribute("recipients"));
        assertEquals("invalid recipient page", model.getAttribute("error"));
        assertEquals("invalid_pagination", TraceStore.get("channel.recipients.controller.skipped.reason"));
        verifyNoInteractions(session, recipientService);
    }

    @Test
    void largestPageWhoseOffsetFitsPreservesTheProviderCall() {
        ChannelRecipientService recipientService = mock(ChannelRecipientService.class);
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("channelAccessToken")).thenReturn("private-token");
        when(recipientService.fetchRecipientIds("private-token", 2_147_483_640, 10))
                .thenReturn(List.of());
        ChannelRecipientController controller = new ChannelRecipientController(recipientService);
        ReflectionTestUtils.setField(controller, "pageSize", 10);
        ConcurrentModel model = new ConcurrentModel();
        int largestFittingPage = (Integer.MAX_VALUE / 10) + 1;

        String view = controller.showRecipients(largestFittingPage, session, "", "", model);

        assertEquals("channels/recipients", view);
        assertEquals(largestFittingPage, model.getAttribute("page"));
        verify(recipientService).fetchRecipientIds("private-token", 2_147_483_640, 10);
    }

    @Test
    void pageImmediatelyAfterLargestFittingPageIsRejected() {
        ChannelRecipientService recipientService = mock(ChannelRecipientService.class);
        HttpSession session = mock(HttpSession.class);
        ChannelRecipientController controller = new ChannelRecipientController(recipientService);
        ReflectionTestUtils.setField(controller, "pageSize", 10);
        ConcurrentModel model = new ConcurrentModel();
        int firstOverflowingPage = (Integer.MAX_VALUE / 10) + 2;

        String view = controller.showRecipients(firstOverflowingPage, session, "", "", model);

        assertEquals("channels/recipients", view);
        assertEquals("invalid recipient page", model.getAttribute("error"));
        verifyNoInteractions(session, recipientService);
    }

    @Test
    void configuredPageSizeOutsidePositiveBoundIsRejectedBeforeTokenUse() {
        ChannelRecipientService recipientService = mock(ChannelRecipientService.class);
        HttpSession session = mock(HttpSession.class);
        ChannelRecipientController controller = new ChannelRecipientController(recipientService);

        ReflectionTestUtils.setField(controller, "pageSize", 0);
        ConcurrentModel zeroModel = new ConcurrentModel();
        assertEquals("channels/recipients", controller.showRecipients(1, session, "", "", zeroModel));
        assertEquals("invalid recipient page", zeroModel.getAttribute("error"));

        ReflectionTestUtils.setField(controller, "pageSize", 101);
        ConcurrentModel excessiveModel = new ConcurrentModel();
        assertEquals("channels/recipients", controller.showRecipients(1, session, "", "", excessiveModel));
        assertEquals("invalid recipient page", excessiveModel.getAttribute("error"));
        verifyNoInteractions(session, recipientService);
    }

}
