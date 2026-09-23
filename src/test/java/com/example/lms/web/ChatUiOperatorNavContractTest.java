package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUiOperatorNavContractTest {

    @Test
    void protectedOperatorLinksNavigateInTabThroughAllowlistedOpsWindow() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-auth-mode=\"form-login\""));
        assertTrue(html.contains("data-vibe-feature-pool=\"open\""));
        assertTrue(html.contains("data-feature-pool=\"open\""));
        assertTrue(html.contains("data-testid=\"feature-pool\""));
        assertTrue(html.contains("href=\"/login\""));
        assertFalse(html.contains("href=\"/logout\""));

        assertTrue(html.contains("data-menu-action=\"open-brain-state\""));
        assertTrue(html.contains("data-menu-action=\"open-pipeline-status\""));
        assertTrue(html.contains("data-menu-action=\"open-rag-ops\""));
        assertTrue(html.contains("data-menu-action=\"open-vector-diagnostics\""));
        assertTrue(html.contains("data-menu-action=\"open-model-settings\""));

        assertTrue(js.contains("brainState: '/admin/brain-state'"));
        assertTrue(js.contains("pipelineStatus: '/admin/pipeline-status'"));
        assertTrue(js.contains("ragOps: '/admin/rag-ops-cockpit'"));
        assertTrue(js.contains("vectorDiagnostics: '/admin/vector-diagnostics'"));
        assertTrue(js.contains("modelSettings: '/model-settings'"));

        assertTrue(js.contains("'open-brain-state': 'brainState'"));
        assertTrue(js.contains("'open-pipeline-status': 'pipelineStatus'"));
        assertTrue(js.contains("'open-rag-ops': 'ragOps'"));
        assertTrue(js.contains("'open-vector-diagnostics': 'vectorDiagnostics'"));
        assertTrue(js.contains("'open-model-settings': 'modelSettings'"));
        assertTrue(js.contains("const protectedDetail = `admin_sign_in_required target:${target}`;"));
        assertTrue(js.contains("setStatusRailValue(dom.traceStatus, protectedSurface ? protectedDetail : \"opening ops surface\");"));
        assertTrue(js.contains("admin_sign_in_required"));
        assertTrue(js.contains("window.location.assign(target);"));
        assertFalse(js.contains("if (protectedSurface) {\n    return true;\n  }"));
        assertTrue(js.contains("event.preventDefault();"));
        assertTrue(js.contains("openOpsWindow(targetName, {"));
        assertTrue(js.contains("protectedSurface: link.getAttribute(\"data-admin-surface\") === \"protected\""));
        assertFalse(js.contains("window.open(target"));
        assertFalse(js.contains("window.location.href = target"));
    }
}
