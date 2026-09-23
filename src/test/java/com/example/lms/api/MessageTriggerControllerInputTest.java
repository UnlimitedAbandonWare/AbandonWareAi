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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

class MessageTriggerControllerInputTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void triggerJsonRejectsNullPayloadWithoutCallingDelivery() {
        MessageDeliveryService delivery = mock(MessageDeliveryService.class);
        MessageTriggerController controller = new MessageTriggerController(delivery);

        var response = controller.triggerJson(null);

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().accepted());
        assertEquals("invalid_payload", response.getBody().disabledReason());
        verifyNoInteractions(delivery);
    }

    @Test
    void triggerJsonRedactsUnsafeDisabledReasonAndDiagnostics() {
        MessageDeliveryService delivery = mock(MessageDeliveryService.class);
        MessageTriggerController controller = new MessageTriggerController(delivery);
        String unsafeReason = "ownerToken=" + "sk-" + "messagetrigger1234567890";
        MessageFormDto dto = form();

        when(delivery.sendUrl(anyString(), anyString(), anyString()))
                .thenReturn(new MessageDeliveryResult(false, "kakao", "hash:target", unsafeReason,
                        Map.of(
                                "disabledReason", unsafeReason,
                                "accepted", false,
                                "targetHash", "channel-secret-raw",
                                "message", "hello api_key=abcdsecret",
                                "url", "https://example.test/callback?token=toksecret")));

        var response = controller.triggerJson(dto);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        String body = String.valueOf(response.getBody());
        assertTrue(response.getBody().disabledReason().startsWith("hash:"));
        assertEquals(response.getBody().disabledReason(), response.getBody().diagnostics().get("disabledReason"));
        assertFalse(body.contains("ownerToken"));
        assertFalse(body.contains("messagetrigger1234567890"));
        assertFalse(body.contains("channel-secret-raw"));
        assertFalse(body.contains("hello api_key=abcdsecret"));
        assertFalse(body.contains("toksecret"));
        assertTrue(String.valueOf(response.getBody().diagnostics().get("targetHash")).startsWith("hash:"));
        assertEquals(false, TraceStore.get("message.trigger.delivery.accepted"));
        assertEquals("json", TraceStore.get("message.trigger.delivery.surface"));
        assertEquals("kakao", TraceStore.get("message.trigger.delivery.provider"));
        assertEquals("hash:target", TraceStore.get("message.trigger.delivery.targetHash"));
        assertTrue(String.valueOf(TraceStore.get("message.trigger.delivery.disabledReason")).startsWith("hash:"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken"));
        assertFalse(trace.contains("messagetrigger1234567890"));
        assertFalse(trace.contains("channel-secret-raw"));
        assertFalse(trace.contains("toksecret"));
    }

    @Test
    void triggerFormRedactsUnsafeDisabledReasonInFlash() {
        MessageDeliveryService delivery = mock(MessageDeliveryService.class);
        MessageTriggerController controller = new MessageTriggerController(delivery);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        String unsafeReason = "ownerToken=" + "sk-" + "messagetriggerform123456";

        when(delivery.sendUrl(anyString(), anyString(), anyString()))
                .thenReturn(new MessageDeliveryResult(false, "kakao", "hash:target", unsafeReason, Map.of()));

        String view = controller.triggerForm(form(), redirect);

        Object result = redirect.getFlashAttributes().get("result");
        assertEquals("redirect:/messages/trigger", view);
        assertTrue(String.valueOf(result).startsWith("hash:"));
        assertFalse(String.valueOf(result).contains("ownerToken"));
        assertFalse(String.valueOf(result).contains("messagetriggerform123456"));
        assertEquals(false, TraceStore.get("message.trigger.delivery.accepted"));
        assertEquals("form", TraceStore.get("message.trigger.delivery.surface"));
        assertEquals("kakao", TraceStore.get("message.trigger.delivery.provider"));
        assertEquals("hash:target", TraceStore.get("message.trigger.delivery.targetHash"));
        assertTrue(String.valueOf(TraceStore.get("message.trigger.delivery.disabledReason")).startsWith("hash:"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("messagetriggerform123456"));
    }

    private static MessageFormDto form() {
        MessageFormDto dto = new MessageFormDto();
        dto.setTargetId("channel-user-1");
        dto.setMessage("message");
        dto.setUrl("https://example.test/upload");
        return dto;
    }
}
