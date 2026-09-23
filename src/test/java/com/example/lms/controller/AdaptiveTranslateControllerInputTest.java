package com.example.lms.controller;

import com.example.lms.service.AdaptiveTranslationService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdaptiveTranslateControllerInputTest {

    @Test
    void translateRejectsNullPayloadWithoutCallingService() {
        AdaptiveTranslationService service = mock(AdaptiveTranslationService.class);
        AdaptiveTranslateController controller = new AdaptiveTranslateController(service);

        Map<String, String> body = controller.translate(null).block();

        assertEquals("missing_text", body.get("error"));
        verifyNoInteractions(service);
    }

    @Test
    void translateRejectsBlankTextWithoutCallingService() {
        AdaptiveTranslationService service = mock(AdaptiveTranslationService.class);
        AdaptiveTranslateController controller = new AdaptiveTranslateController(service);

        Map<String, String> body = controller.translate(Map.of("text", "   ")).block();

        assertEquals("missing_text", body.get("error"));
        verifyNoInteractions(service);
    }
}
