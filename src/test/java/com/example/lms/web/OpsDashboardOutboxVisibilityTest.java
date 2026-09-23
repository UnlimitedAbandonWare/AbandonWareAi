package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsDashboardOutboxVisibilityTest {

    @Test
    void dashboardShowsDegradedOutboxPressureScalars() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/dashboard.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("Outbox pressure"));
        assertTrue(html.contains("id=\"outboxPressureTotal\""));
        assertTrue(html.contains("id=\"outboxPressureDetail\""));
        assertTrue(html.contains("function renderOutbox(stats)"));
        assertTrue(html.contains("fetchJson('/api/nova/outbox/stats')"));
        assertTrue(html.contains("$('outboxPressureTotal').textContent = String(pending + inflight);"));
        assertTrue(html.contains("outbox.pendingCount"));
        assertTrue(html.contains("outbox.inflightCount"));
        assertTrue(html.contains("outbox.nackTotal"));
        assertTrue(html.contains("outbox.droppedExpiredTotal"));
        assertTrue(html.contains("outbox.parseErrorTotal"));
        assertTrue(html.contains("outbox.lastSweepEpochMs"));
        assertTrue(html.contains("outbox: results[7].status === 'fulfilled' ? results[7].value : { error: results[7].reason.message }"));
        assertFalse(html.contains("rawPath"));
        assertFalse(html.contains("Authorization"));
        assertFalse(html.contains("ownerToken"));
    }
}
