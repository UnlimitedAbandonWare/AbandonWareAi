package com.example.lms.api;

import com.example.lms.dto.MessageFormDto;
import com.example.lms.integrations.MessageDeliveryResult;
import com.example.lms.integrations.MessageDeliveryService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

class MessageAdminControllerTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void sendRedactsUnsafeDisabledReasonInFlash() {
        MessageDeliveryService delivery = mock(MessageDeliveryService.class);
        MessageAdminController controller = new MessageAdminController(delivery);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        String unsafeReason = "ownerToken=" + "sk-" + "messageadmin1234567890";

        when(delivery.sendUrl(anyString(), anyString(), anyString()))
                .thenReturn(new MessageDeliveryResult(false, "kakao", "hash:target", unsafeReason, Map.of()));

        String view = controller.send(form(), redirect);

        Object result = redirect.getFlashAttributes().get("result");
        assertEquals("redirect:/admin/messages", view);
        assertTrue(String.valueOf(result).startsWith("hash:"));
        assertFalse(String.valueOf(result).contains("ownerToken"));
        assertFalse(String.valueOf(result).contains("messageadmin1234567890"));
        assertEquals(false, TraceStore.get("message.admin.delivery.accepted"));
        assertEquals("kakao", TraceStore.get("message.admin.delivery.provider"));
        assertEquals("hash:target", TraceStore.get("message.admin.delivery.targetHash"));
        assertTrue(String.valueOf(TraceStore.get("message.admin.delivery.disabledReason")).startsWith("hash:"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken"));
        assertFalse(trace.contains("messageadmin1234567890"));
    }

    private static MessageFormDto form() {
        MessageFormDto dto = new MessageFormDto();
        dto.setTargetId("channel-user-1");
        dto.setMessage("message");
        dto.setUrl("https://example.test/upload");
        return dto;
    }
}
