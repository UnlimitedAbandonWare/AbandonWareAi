package com.example.lms.debug.ai;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugAiDashboardTemplateTest {

    @Test
    void dashboardHasAiDebugAutoPollingAndWindowControls() throws Exception {
        String html = Files.readString(Path.of("main", "resources", "templates", "dashboard.html"));

        assertTrue(html.contains("id=\"debugAiWindow\""));
        assertTrue(html.contains("id=\"debugAiAutoBtn\""));
        assertTrue(html.contains("id=\"debugAiLastRefresh\""));
        assertTrue(html.contains("DEBUG_AI_INTERVAL_MS = 30_000"));
        assertTrue(html.contains("function fetchDebugAi()"));
        assertTrue(html.contains("windowMs=' + windowMs"));
        assertTrue(html.contains("id=\"debugAiTrend\""));
        assertTrue(html.contains("/api/diagnostics/debug/ai/history?maxEntries="));
        assertTrue(html.contains("function renderDebugAiHistory("));
        assertTrue(html.contains("warnTrend") && html.contains("errorTrend"));
    }

    @Test
    void dashboardShowsDebugAiAnomalyScorecardScalars() throws Exception {
        String html = Files.readString(Path.of("main", "resources", "templates", "dashboard.html"));

        assertTrue(html.contains("kv(box, 'anomalyTriggered', score.anomalyTriggered"));
        assertTrue(html.contains("kv(box, 'anomalyReason', score.anomalyReason || 'observe')"));
        assertTrue(html.contains("kv(box, 'anomalyScore', score.anomalyScore || 0)"));
        assertTrue(html.contains("kv(box, 'anomalyTile', score.anomalyTile || score.hotTile)"));
        assertTrue(html.contains("kv(box, 'anomalyFailureClass', score.anomalyFailureClass || '-')"));
    }

    @Test
    void dashboardShowsQueryRewritePostprocessCounts() throws Exception {
        String html = Files.readString(Path.of("main", "resources", "templates", "dashboard.html"));

        assertTrue(html.contains("kv(box, 'queryRewriteSubModelCount', score.queryRewriteSubModelCount || 0)"));
        assertTrue(html.contains("kv(box, 'queryRewriteBranchTitleCount', score.queryRewriteBranchTitleCount || 0)"));
        assertTrue(html.contains("kv(box, 'queryRewriteBranchTitleHashCount', score.queryRewriteBranchTitleHashCount || 0)"));
        assertTrue(html.contains("kv(box, 'queryRewriteBranchAxisCount', score.queryRewriteBranchAxisCount || 0)"));
        assertTrue(html.contains("kv(box, 'queryRewritePaddedCount', score.queryRewritePaddedCount || 0)"));
    }
}
