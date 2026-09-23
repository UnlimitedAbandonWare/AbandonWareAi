package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningDataTemplateTest {

    @Test
    void rootEntryRedirectsToChatGateway() {
        PageController controller = new PageController(null, null, null);

        assertEquals("redirect:/chat", controller.home());
    }

    @Test
    void learningDataShowsGpuAdmissionStatusAndReason() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/learning-data.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"gpuAdmissionStatus\""));
        assertTrue(html.contains("id=\"gpuAdmissionReason\""));
        assertTrue(html.contains("nodeProfile.learningLoop.gpuAdmissionStatus"));
        assertTrue(html.contains("nodeProfile.learningLoop.gpuAdmissionReason"));
        assertTrue(html.contains("$('gpuAdmissionStatus').textContent"));
        assertTrue(html.contains("$('gpuAdmissionReason').textContent"));
        assertFalse(html.contains("gpuAdmissionRaw"));
        assertFalse(html.contains("gpuDeviceName"));
    }

    @Test
    void templateUsesOnlyLocalRedactedDiagnosticsSurfaces() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/learning-data.html"), StandardCharsets.UTF_8);
        String dashboard = Files.readString(Path.of("main/resources/templates/dashboard.html"), StandardCharsets.UTF_8);
        String index = Files.readString(Path.of("main/resources/templates/index.html"), StandardCharsets.UTF_8);
        String header = Files.readString(Path.of("main/resources/templates/fragments/header.html"), StandardCharsets.UTF_8);
        String layout = Files.readString(Path.of("main/resources/templates/fragments/layout.html"), StandardCharsets.UTF_8);
        String adminDashboard = Files.readString(Path.of("main/resources/templates/admin/dashboard.html"), StandardCharsets.UTF_8);
        String openSecurity = Files.readString(Path.of("main/java/com/example/lms/security/ChatOpenSecurityConfig.java"), StandardCharsets.UTF_8);
        String appSecurity = Files.readString(Path.of("main/java/com/example/lms/config/AppSecurityConfig.java"), StandardCharsets.UTF_8);

        assertTrue(html.contains("/api/diagnostics/uaw/autolearn/loop"));
        assertTrue(html.contains("/api/diagnostics/uaw/autolearn/quality"));
        assertTrue(html.contains("/api/diagnostics/uaw/autolearn/agent-handoff"));
        assertTrue(html.contains("/api/diagnostics/rag/ops-ledger/summary"));
        assertTrue(html.contains("/api/diagnostics/rag/ops-ledger/recent?entryType=AUTOLEARN_CYCLE"));
        assertTrue(html.contains("Agent Handoff"));
        assertTrue(html.contains("agentHandoff"));
        assertTrue(html.contains("handoffFiles"));
        assertTrue(html.contains("renderHandoff"));
        assertTrue(html.contains("historyDisabledReason"));
        assertTrue(html.contains("rag_ops_ledger_disabled"));
        assertTrue(html.contains("textContent"));
        assertFalse(html.contains("innerHTML"));
        assertTrue(dashboard.contains("/admin/learning-data"));
        assertTrue(dashboard.contains("/admin/rag-ops-cockpit"));
        assertTrue(dashboard.contains("/api/diagnostics/rag/learning-ops/overview?limit=50"));
        assertTrue(dashboard.contains("/api/diagnostics/rag/learning-ops/metrics/prometheus"));
        assertTrue(dashboard.contains("RAG Ops Cockpit"));
        assertFalse(dashboard.contains("Development Corpus Absorption"));
        assertFalse(dashboard.contains("lmsCorpus"));
        assertFalse(dashboard.contains("lmsProjected"));
        assertFalse(dashboard.contains("lmsLegacyCounts"));
        assertFalse(dashboard.contains("lmsDomainCounts"));
        assertTrue(dashboard.contains("Legacy Development Candidates"));
        assertTrue(dashboard.contains("legacyDevelopmentCandidates"));
        assertTrue(dashboard.contains("legacyDevRows"));
        assertTrue(dashboard.contains("legacy-development-candidates.tsv"));
        assertTrue(dashboard.contains("Overlay Connections"));
        assertTrue(dashboard.contains("orchestrationOverlays"));
        assertTrue(dashboard.contains("renderOverlays"));
        assertTrue(dashboard.contains("overlayActiveCount"));
        assertTrue(dashboard.contains("anchorOverlayJson"));
        assertTrue(dashboard.contains("cihRagOverlayJson"));
        assertTrue(dashboard.contains("extremeOverlayJson"));
        assertTrue(dashboard.contains("routingPlanOverlayJson"));
        assertTrue(dashboard.contains("hypernovaOverlayJson"));
        assertTrue(dashboard.contains("matryoshkaOverlayJson"));
        assertTrue(dashboard.contains("localLlmOverlayJson"));
        assertTrue(dashboard.contains("modelGuardOverlayJson"));
        assertTrue(dashboard.contains("zero100OverlayJson"));
        assertTrue(dashboard.contains("virtualMatrixOverlayJson"));
        for (String legacyRoute : new String[]{"/students", "/courses", "/assignments", "/enrollments", "/professors"}) {
            assertFalse(dashboard.contains(legacyRoute));
            assertFalse(index.contains(legacyRoute));
            assertFalse(header.contains(legacyRoute));
            assertFalse(layout.contains(legacyRoute));
            assertFalse(adminDashboard.contains(legacyRoute));
        }
        assertFalse(dashboard.contains("/admin/notices"));
        assertFalse(index.contains("/admin/notices"));
        assertFalse(header.contains("/admin/notices"));
        assertFalse(layout.contains("@{/notices}"));
        assertFalse(adminDashboard.contains("/admin/notices"));
        assertTrue(dashboard.contains("idleHistoryRows"));
        assertTrue(dashboard.contains("recentAutolearn"));
        assertTrue(dashboard.contains("curationQueueRows"));
        assertTrue(dashboard.contains("datasetPipelineQueue"));
        assertTrue(dashboard.contains("Mac mini Curation Collector"));
        assertTrue(dashboard.contains("learningOpsCollector"));
        assertTrue(dashboard.contains("collectorKv"));
        assertTrue(dashboard.contains("collectorBadge"));
        assertTrue(dashboard.contains("outputPathHash"));
        assertTrue(dashboard.contains("read_only_curation_candidates") || dashboard.contains("curationQueue"));
        assertTrue(dashboard.contains("textContent"));
        assertFalse(dashboard.contains("innerHTML"));
        assertTrue(index.contains("RAG/Agent Development Operations"));
        assertFalse(index.contains("RAG/agent dev inputs"));
        assertTrue(index.contains("Legacy Development Candidates"));
        assertTrue(index.contains("legacyCandidateRows"));
        assertTrue(index.contains("renderLegacyCandidates"));
        assertFalse(index.contains("Assignment eval corpus"));
        assertTrue(index.contains("/api/diagnostics/rag/learning-ops/overview?limit=20"));
        assertTrue(index.contains("/admin/dashboard"));
        assertTrue(index.contains("/admin/learning-data"));
        for (String surface : List.of(dashboard, index, header, layout, adminDashboard)) {
            assertTrue(surface.contains("Chatbot feedback corpus"));
            assertFalse(surface.contains("Board feedback corpus"));
        }
        assertTrue(openSecurity.contains("AntPathRequestMatcher.antMatcher(\"/index\")"));
        assertTrue(appSecurity.contains(".defaultSuccessUrl(\"/index\", true)"));

        assertFalse(html.contains("https://cdn."));
        assertFalse(html.contains("https://unpkg."));
        assertFalse(html.contains("https://cdn.jsdelivr."));
        assertFalse(dashboard.contains("https://cdn."));
        assertFalse(dashboard.contains("https://unpkg."));
        assertFalse(dashboard.contains("https://cdn.jsdelivr."));
        assertFalse(index.contains("https://cdn."));
        assertFalse(index.contains("https://unpkg."));
        assertFalse(index.contains("https://cdn.jsdelivr."));
        assertFalse(index.toLowerCase().contains("mock"));

        for (String forbidden : new String[]{
                "rawQuery",
                "raw query",
                "rawAnswer",
                "raw answer",
                "ownerToken",
                "Authorization",
                "api_key",
                "client-secret",
                "clientSecret"
        }) {
            assertFalse(html.contains(forbidden), "learning data page exposes forbidden token: " + forbidden);
            assertFalse(dashboard.contains(forbidden), "dashboard exposes forbidden token: " + forbidden);
            assertFalse(index.contains(forbidden), "index exposes forbidden token: " + forbidden);
        }
    }
}
