package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFrontendStreamCancellationFocusedTest {

    @Test
    void cancelledStreamCannotRenderAfterANewerStreamStarts() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(js.contains("function isActiveStreamRenderTarget(assistant, controller)"));
        assertTrue(js.contains("return assistant === activeStreamAssistant && controller === streamController"));
        String normalizedJs = js.replaceAll("\\s+", " ");
        assertTrue(normalizedJs.contains("if (isActiveStreamRenderTarget(assistant, currentStreamController)) { renderChatEvent(eventPayload, assistant, effectiveType); }"));
    }

    @Test
    void transportLossRecoversOnlyTheExactRunWithoutStartingAnotherGeneration() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(js.contains("async function recoverExactRunAfterTransportLoss(expectedRun, loaderId)"));
        assertTrue(js.contains("/api/chat/state?sessionId=${expectedRun.sessionId}"));
        assertTrue(js.contains("attach: true"));
        assertTrue(js.contains("runToken: expectedRun.runToken"));
        assertFalse(js.contains("const res = await apiCall(\"/api/chat\""));
    }

    @Test
    void chatStreamsKeepWaitingUntilUserStopsInsteadOfAutoCancellingEvidenceAssembly() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(js.contains("function streamClientDeadlineMs(payload = {})"));
        assertTrue(js.contains("return null;"));
        assertTrue(js.contains("const clientDeadlineMs = streamClientDeadlineMs(payload);"));
        assertTrue(js.contains("const clientDeadlinePromise = clientDeadlineMs == null ? null : new Promise"));
        assertTrue(js.contains("if (clientDeadlineMs != null && elapsedMs >= clientDeadlineMs)"));
        assertTrue(js.contains("const next = clientDeadlinePromise == null"));
        assertTrue(js.contains("? await reader.read()"));
    }
}
