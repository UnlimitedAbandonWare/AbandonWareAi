package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertTrue(html.contains("질문 분석"));
        assertTrue(html.contains("운영 안정성"));
        assertTrue(html.contains("data-q=\"운영 안정성 관점에서 Provider Guard, Trace, Fail-soft 구조를 정리해줘\""));
        assertTrue(html.contains(">운영 안정성</button>"));
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
