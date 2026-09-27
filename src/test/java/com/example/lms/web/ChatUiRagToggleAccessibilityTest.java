package com.example.lms.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ChatUiRagToggleAccessibilityTest {

    @Test
    void ragCheckboxKeepsStableAccessibleNameWhileStatusRailShowsState() throws IOException {
        String template = Files.readString(Path.of("main/resources/templates/chat-ui.html"));
        String script = Files.readString(Path.of("main/resources/static/js/chat.js"));

        assertTrue(template.contains("id=\"useRagToggle\""),
                "chat UI should expose the RAG checkbox");
        assertTrue(template.contains("aria-label=\"Use RAG context\""),
                "RAG checkbox accessible name should not change when checked state changes");
        assertFalse(template.contains("id=\"useRagToggle\" type=\"checkbox\" checked"),
                "initial RAG checkbox markup must not expose checked=true when the saved/default state is OFF");
        assertTrue(template.contains("aria-label=\"RAG: OFF\""),
                "initial status rail should match the default unchecked RAG control before JavaScript hydrates");
        assertTrue(Pattern.compile(
                        "<span\\s+[^>]*id=\\\"ragStatus\\\"[^>]*data-orch-field=\\\"rag\\\"[^>]*>OFF</span>")
                .matcher(template).find(),
                "initial RAG status text should match the default unchecked RAG control");
        assertFalse(script.contains("dom.useRag.setAttribute(\"aria-label\", label)"),
                "syncControlStatus should not rename the checkbox to RAG: ON/OFF after user toggles it");
        assertTrue(script.contains("setStatusRailValue(dom.ragStatus, `요청 ${ragState}`)"),
                "the status rail should show the requested RAG state without renaming the checkbox");
        assertTrue(script.contains("setStatusRailValue(dom.ragStatus, `적용 "),
                "the status rail should distinguish the returned applied RAG state");
    }
}
