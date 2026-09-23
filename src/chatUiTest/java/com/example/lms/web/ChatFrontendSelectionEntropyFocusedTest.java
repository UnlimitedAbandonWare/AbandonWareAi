package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFrontendSelectionEntropyFocusedTest {

    private static final Path CHAT_JS = Path.of("main/resources/static/js/chat.js");

    @Test
    void rendersOnlyTypedSelectionEntropyFields() throws Exception {
        String source = Files.readString(CHAT_JS, StandardCharsets.UTF_8);

        assertTrue(source.contains("function selectionEntropySignal(payload = {})"));
        assertTrue(source.contains("function renderSelectionEntropyTrace(payload, bubble)"));
        assertTrue(source.contains("function clearSelectionEntropyTrace(scope)"));
        assertTrue(source.contains("type === \"selection_entropy\""));

        String parser = between(source,
                "function selectionEntropySignal(payload = {})",
                "function selectionEntropyReasonLabel(");
        String renderer = between(source,
                "function renderSelectionEntropyTrace(payload, bubble)",
                "function clearSelectionEntropyTrace(scope)");
        String cardFunctions = between(source,
                "function appendSelectionEntropyRow(list, label, value)",
                "function clearSelectionEntropyTrace(scope)");
        for (String field : new String[] {
                "mode", "replayAccepted", "coherenceStatus", "replayReference",
                "algorithmVersion", "decisionDigest", "decisionCount", "drawCount",
                "stableTieBreakCount", "candidateDriftCount", "routerDrawCount",
                "strategyDrawCount", "ensembleDrawCount", "completionOrderDeterministic",
                "reasonCode"
        }) {
            assertTrue(parser.contains("raw." + field), "missing fixed parser field: " + field);
        }
        for (String label : new String[] {
                "Selection replay", "Mode", "Replay status", "Coherence",
                "Replay reference", "Algorithm", "Decisions", "Draws", "Stable ties",
                "Router draws", "Strategy draws", "Ensemble draws", "Candidate drift",
                "Completion order", "Reason", "Not deterministic"
        }) {
            assertTrue(renderer.contains(label), "missing fixed visible label: " + label);
        }
        assertFalse(parser.contains("Object.keys"));
        assertFalse(renderer.contains("Object.keys"));
        assertFalse(renderer.contains("decisionDigest"),
                "decision digest is validated but must not be displayed");
        assertFalse(parser.contains("innerHTML"));
        assertFalse(renderer.contains("innerHTML"));
        assertTrue(cardFunctions.contains("document.createElement(\"section\")"));
        assertTrue(cardFunctions.contains("document.createElement(\"h3\")"));
        assertTrue(cardFunctions.contains("document.createElement(\"dl\")"));
        assertTrue(cardFunctions.contains("document.createElement(\"dt\")"));
        assertTrue(cardFunctions.contains("document.createElement(\"dd\")"));
    }

    @Test
    void keepsFinalCardAndDefinesEveryScopedResetPoint() throws Exception {
        String source = Files.readString(CHAT_JS, StandardCharsets.UTF_8);

        String render = between(source,
                "function renderChatEvent(payload, assistant, fallbackType = \"message\")",
                "async function requestServerCancel(");
        assertTrue(render.contains("type === \"selection_entropy\""));
        assertTrue(render.contains("renderSelectionEntropyTrace(payload, assistant)"));
        int savedCard = render.indexOf("const selectionEntropyCard =");
        int replace = render.indexOf("bubble.replaceChildren()", savedCard);
        int restore = render.indexOf("bubble.appendChild(selectionEntropyCard)", replace);
        assertTrue(savedCard >= 0 && replace > savedCard && restore > replace,
                "final answer replacement must preserve the early selection card");
        assertTrue(render.substring(render.indexOf("} else if (type === \"status\")"))
                .contains("markAssistantStreamStopped(assistant)"));
        assertTrue(render.substring(render.indexOf("} else if (type === \"error\")"))
                .contains("clearSelectionEntropyTrace(assistant)"));

        String stopped = between(source,
                "function markAssistantStreamStopped(node)",
                "function isLocalSafeFallbackAssistantText(");
        assertTrue(stopped.contains("clearSelectionEntropyTrace(node)"));

        String send = between(source,
                "async function sendMessageUnlocked(text)",
                "function isActiveStreamRenderTarget(");
        assertTrue(send.indexOf("clearSelectionEntropyTrace(dom.chatMessages)")
                        < send.indexOf("appendMessage(\"user\", text)"),
                "fresh request clearing must happen before new bubbles are created");
        assertTrue(count(send, "clearSelectionEntropyTrace(assistant)") >= 2,
                "AbortError and terminal error handling must both clear the request card");

        String newSession = between(source,
                "function startNewChatSession()",
                "function restoredSessionSetting(");
        assertTrue(newSession.indexOf("clearSelectionEntropyTrace(dom.chatMessages)")
                        < newSession.indexOf("dom.chatMessages.replaceChildren()"));

        String selection = between(source,
                "async function selectSessionCandidate(candidateId)",
                "function currentControlSettings(");
        assertTrue(selection.indexOf("clearSelectionEntropyTrace(dom.chatMessages)")
                        < selection.indexOf("dom.chatMessages.replaceChildren()"));

        String hydration = between(source,
                "async function hydrateRestoredSessionTranscript()",
                "async function resumeStoredRunIfNeeded(");
        int acceptedRestore = hydration.indexOf("if (!hasExistingMessages) {");
        int restoreClear = hydration.indexOf("clearSelectionEntropyTrace(dom.chatMessages)", acceptedRestore);
        int restoreAppend = hydration.indexOf("for (const message of messages)", restoreClear);
        assertTrue(acceptedRestore >= 0 && restoreClear > acceptedRestore && restoreAppend > restoreClear,
                "restore clearing must occur only after identity/generation/detail guards accept replacement");
    }

    @Test
    void neverAddsReplayInputOrBrowserPersistence() throws Exception {
        String source = Files.readString(CHAT_JS, StandardCharsets.UTF_8);

        for (String forbidden : new String[] {
                "selectionReplaySeed", "selectionReplayToken", "selectionReplayToggle",
                "localStorage.setItem(\"selection", "sessionStorage.setItem(\"selection"
        }) {
            assertFalse(source.contains(forbidden), "forbidden replay client surface: " + forbidden);
        }
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from + start.length());
        assertTrue(from >= 0, "missing start anchor: " + start);
        assertTrue(to > from, "missing end anchor: " + end);
        return source.substring(from, to);
    }

    private static int count(String value, String needle) {
        int total = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            total += 1;
            offset += needle.length();
        }
        return total;
    }
}
