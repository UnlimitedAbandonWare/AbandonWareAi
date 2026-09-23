package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticModelOverridePolicyTest {

    @Test
    void activeStaticResourcesDoNotForceLegacyModelOverrideHeader() throws IOException {
        for (Path path : java.util.List.of(
                Path.of("main/resources/static/js/chat.js"),
                Path.of("main/resources/static/js/fetch-wrapper.js"))) {
            String js = Files.readString(path);
            assertFalse(js.contains("\"X-Model-Override\": \"gemma3:4b\""),
                    () -> path + " must not hard-code legacy model override");
        }
    }

    @Test
    void chatUiDoesNotExposeLegacyServerAudioEndpoints() throws IOException {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"));
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"));

        assertFalse(js.contains("/api/audio/tts"));
        assertFalse(js.contains("/api/audio/stt"));
        assertFalse(js.contains("/api/audio/tts?text="));
        assertFalse(js.contains("getUserMedia({ audio: true })"));
        assertFalse(html.contains("id=\"btn-mic\""));
        assertFalse(html.contains("id=\"btn-tts\""));
    }

    @Test
    void traceHtmlRenderingUsesSanitizerPath() throws IOException {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"));

        assertTrue(js.contains("const cleanHtml = stripAssistantReasoningBlocks(rawHtml);"));
        assertTrue(js.contains("replaceWithSanitizedHtml(bubble, cleanHtml);"));
        assertFalse(js.contains("bubble.innerHTML = rawHtml"));
    }

    @Test
    void traceStreamEventsRenderHtmlThroughExistingSanitizer() throws IOException {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"));
        int traceBranchStart = js.indexOf("if (type === \"trace\") {");
        int traceBranchEnd = js.indexOf("setStatusRailValue(dom.traceStatus, type);", traceBranchStart);
        String traceBranch = traceBranchStart >= 0 && traceBranchEnd > traceBranchStart
                ? js.substring(traceBranchStart, traceBranchEnd)
                : "";

        assertTrue(traceBranch.contains("if (payload.html) renderTraceHtml(payload, bubble);"));
    }
}
