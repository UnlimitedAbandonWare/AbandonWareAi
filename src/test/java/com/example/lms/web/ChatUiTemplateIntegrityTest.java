package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUiTemplateIntegrityTest {

    @Test
    void activeChatUiCopyDoesNotExposeMojibakeOrMalformedControls() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String activeUi = html.substring(html.indexOf("<nav class=\"app-menu-bar\""), html.indexOf("<!-- === Scripts"));

        assertTrue(activeUi.contains("무엇을 함께 생각해 볼까요?"));
        assertTrue(activeUi.contains("이번 답변 진단"));
        assertTrue(activeUi.contains("data-session-mode-list"));
        assertTrue(activeUi.contains("composer-dock"));
        assertFalse(activeUi.contains("project-intro"));

        for (String token : new String[]{"?/button>", "理", "吏", "寃", "濡", "蹂", "媛", "鍮", "?꾩", "?댁", "?좏"}) {
            assertFalse(activeUi.contains(token), "active chat UI still contains mojibake token: " + token);
        }
    }
}
