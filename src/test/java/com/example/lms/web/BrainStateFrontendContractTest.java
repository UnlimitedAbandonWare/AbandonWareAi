package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainStateFrontendContractTest {

    @Test
    void brainStateRendererUsesTextNodesAndRedactedDiagnosticsOnly() throws Exception {
        String js = read("main/resources/static/js/brain-state-ui.js");

        assertTrue(js.contains("textContent"));
        assertTrue(js.contains("replaceChildren"));
        assertTrue(js.contains("sourceSummaries"));
        assertTrue(js.contains("recentChanges"));
        assertTrue(js.contains("anchorMap"));
        assertTrue(js.contains("queryTime"));
        assertFalse(js.contains("innerHTML"));
        assertFalse(js.contains("insertAdjacentHTML"));
        assertFalse(js.contains("Authorization"));
        assertFalse(js.contains("ownerToken"));
        assertFalse(js.contains("apiKey"));
        assertFalse(js.contains("clientSecret"));
        assertFalse(js.contains("sourceText"));
        assertFalse(js.contains("/api/admin/vector/brain/ingest"));
        assertFalse(js.contains("/api/admin/vector/brain/infer"));
    }

    @Test
    void brainStateQueryTimeShowsFallbackReasonAndFailureClass() throws Exception {
        String js = read("main/resources/static/js/brain-state-ui.js");

        assertTrue(js.contains("queryTime.fallbackUsed ? \"fallback\" : \"direct\""));
        assertTrue(js.contains("failure=${safe(queryTime.failureClass, \"-\")}"));
        assertTrue(js.contains("disabled=${safe(queryTime.disabledReason, \"-\")}"));
        assertFalse(js.contains("rawQuery"));
        assertFalse(js.contains("sourceText"));
    }

    @Test
    void chatAndAdminPagesLoadPinnedBrainStateAssets() throws Exception {
        String chat = read("main/resources/templates/chat-ui.html");
        String page = read("main/resources/templates/brain-state.html");
        String dashboard = read("main/resources/templates/dashboard.html");
        String vector = read("main/resources/templates/vector-diagnostics.html");

        assertFalse(chat.contains("cytoscape"), "chat page must not load cytoscape (dead asset removed)");
        assertTrue(page.contains("cytoscape@3.33.4"));
        assertTrue(chat.contains("/js/brain-state-ui.js"));
        assertTrue(page.contains("/js/brain-state-ui.js"));
        assertTrue(chat.contains("data-brain-state-root"));
        assertTrue(page.contains("data-brain-state-root"));
        assertTrue(page.contains("data-brain-list=\"sources\""));
        assertTrue(page.contains("data-brain-list=\"recent\""));
        assertTrue(page.contains("data-brain-list=\"anchor-map\""));
        assertTrue(page.contains("data-brain-list=\"query-time\""));
        assertTrue(chat.contains("data-menu-action=\"open-brain-state\""));
        assertTrue(dashboard.contains("/admin/brain-state"));
        assertTrue(vector.contains("/admin/brain-state"));
    }

    @Test
    void chatDispatchesOnlySafeBrainStateRefreshSignals() throws Exception {
        String js = read("main/resources/static/js/chat.js");

        assertTrue(js.contains("dispatchBrainStateSignal('session'"));
        assertTrue(js.contains("dispatchBrainStateSignal('answer'"));
        assertTrue(js.contains("new CustomEvent(`brain-state:${name}`"));
        assertFalse(js.contains("brain-state:prompt"));
        assertFalse(js.contains("brain-state:raw"));
    }

    @Test
    void embeddedBrainStatePanelDoesNotCollapseChatConversationViewport() throws Exception {
        String css = read("main/resources/static/css/chat-style.css").replace("\r\n", "\n");

        assertTrue(css.contains(".diagnostics-stack > .brain-state-panel--compact"),
                "diagnostics-embedded Brain State must have a compact scrolling rule");
        assertTrue(css.contains(".diagnostics-stack > .brain-state-panel--compact > div"),
                "each live Brain State row must stay compact inside diagnostics");
        assertTrue(css.contains("#chatWindow {\n    flex: 1 1"),
                "chatWindow must keep a flexible conversation viewport");
        assertTrue(css.contains("min-height: clamp("),
                "chatWindow must keep enough height for conversation text");
        assertTrue(css.contains("overflow: auto"),
                "chatWindow or compact Brain State must scroll instead of expanding over the conversation");
    }

    @Test
    void chatUiLayersDiagnosticsBehindAdminAndCompactSurfaceStripsChrome() throws Exception {
        String chat = read("main/resources/templates/chat-ui.html");
        String controller = read("main/java/com/example/lms/web/PageController.java");

        assertTrue(chat.contains(
                "<details class=\"diagnostics-disclosure\" data-testid=\"chat-diagnostics\" th:if=\"${chatDiagnosticsEnabled}\">"),
                "diagnostics disclosure must render only when chatDiagnosticsEnabled is set");
        assertTrue(chat.contains("data-session-mode-list"));
        assertTrue(chat.contains("th:if=\"${chatSurface != 'compact'}\""),
                "heavy chrome must be suppressed for the compact surface");
        assertTrue(chat.contains("th:attr=\"data-chat-surface=${chatSurface}\""),
                "body must expose the resolved surface for styling hooks");
        assertFalse(chat.contains("cytoscape"),
                "chat page must not load the unused cytoscape CDN bundle");

        assertTrue(controller.contains("chatDiagnosticsEnabled"),
                "chatUi must publish chatDiagnosticsEnabled");
        assertTrue(controller.contains("chatSurface"),
                "chatUi must publish chatSurface");
        assertTrue(controller.contains("ROLE_ADMIN"),
                "admin detection must use ROLE_ADMIN authority");
        assertTrue(controller.contains("normalizeChatSurface"),
                "surface values must normalize to compact|web");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
