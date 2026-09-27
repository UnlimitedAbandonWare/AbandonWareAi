package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFrontendFallbackEvidenceFocusedTest {

    @Test
    void chatComposerExposesStableBrowserSmokeTargets() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-testid=\"chat-composer\""));
        assertTrue(html.contains("data-testid=\"chat-message-input\""));
        assertTrue(html.contains("data-testid=\"chat-send-button\""));
        assertTrue(html.contains("data-testid=\"chat-stop-button\""));
    }

    @Test
    void chatIntroKeepsReadableUtf8CopyAndClosedQuickPromptButton() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        var document = org.jsoup.Jsoup.parse(html);
        assertFalse(document.select("#chatEmptyState h2").text().isBlank());
        var prompts = document.select(".quick-prompts button.qa");
        assertEquals(3, prompts.size());
        for (var prompt : prompts) {
            assertFalse(prompt.attr("data-q").isBlank());
            assertFalse(prompt.attr("aria-label").isBlank());
            assertFalse(prompt.select("strong").text().isBlank());
            assertEquals("button", prompt.attr("type"));
        }
        assertEquals(3L, java.util.regex.Pattern.compile(
                "(?s)<button\\b[^>]*class=\"qa\"[^>]*>.*?</button>").matcher(html).results().count());
        assertFalse(html.contains("吏"));
        assertFalse(html.contains("\u0080"));
        assertFalse(html.contains("?댁쁺 ?덉젙??/button"));
    }

    @Test
    void fallbackEvidenceModeRendersRedactedDiagnosticCardForStreamWithoutSyncRegeneration() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(js.contains("function fallbackEvidenceDiagnosticDetail(mode, pipeline = {}, evidence = [], model = \"\")"));
        assertTrue(js.contains("function renderFallbackEvidenceDiagnostic(target, mode, pipeline = {}, evidence = [], model = \"\")"));
        assertTrue(js.contains("card.dataset.role = \"fallback-evidence-diagnostic\";"));
        assertTrue(js.contains("card.dataset.answerMode = normalized;"));
        assertTrue(js.contains("card.dataset.evidenceCount = String(safeEvidenceCount);"));
        assertTrue(js.contains("renderFallbackEvidenceDiagnostic(bubble?.parentElement || dom.chatMessages, finalMode, pipeline, payload.evidence, model);"));
        assertFalse(js.contains("renderFallbackEvidenceDiagnostic(finalWrap, syncMode, syncPipeline, syncEvidence, model);"));
        assertTrue(js.contains("Fallback evidence"));
        assertTrue(js.contains("next:${nextAction}"));
        assertFalse(js.contains("fallbackDiagnosticRawQuery"));
        assertFalse(js.contains("fallbackDiagnosticPrompt"));
        assertFalse(js.contains("fallbackDiagnosticSecret"));
    }
}
