package com.example.lms.harmony;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarmonyDashboardTemplateTest {

    @Test
    void dashboardUsesHarmonyApisAndDoesNotExposeSecretSurfaces() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/harmony-dashboard.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("EventSource('/api/harmony/stream')"));
        assertTrue(html.contains("fetch('/api/harmony/score'"));
        assertTrue(html.contains("fetch('/api/metrics/faithfulness'"));
        assertTrue(html.contains("harmonyScore"));
        assertTrue(html.contains("contaminationScore"));
        assertTrue(html.contains("achievementPct"));
        assertTrue(html.contains("faithfulnessScore"));
        assertTrue(html.contains("faithfulnessDecision"));
        assertTrue(html.contains("faithfulnessDocCount"));
        assertTrue(html.contains("render(await response.json());\n    await loadFaithfulness().catch(() => renderFaithfulness({available: false}));"));
        assertTrue(html.contains("topContaminants"));
        assertTrue(html.contains("textContent"));
        assertFalse(html.contains("innerHTML"));
        assertFalse(html.contains("https://fonts.googleapis.com"));
        assertFalse(html.contains("https://cdn."));
        assertFalse(html.contains("Authorization"));
        assertFalse(html.contains("api_key"));
        assertFalse(html.contains("client-secret"));
        assertFalse(html.contains("clientSecret"));
        assertFalse(html.contains("ownerToken"));
    }

    @Test
    void dashboardShowsFaithfulnessReasonAndRiskScalars() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/harmony-dashboard.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"faithfulnessReason\""));
        assertTrue(html.contains("id=\"faithfulnessDistinctSources\""));
        assertTrue(html.contains("id=\"faithfulnessConfidence\""));
        assertTrue(html.contains("id=\"faithfulnessCompositeRisk\""));
        assertTrue(html.contains("id=\"faithfulnessHallucinationLikelihood\""));
        assertTrue(html.contains("id=\"faithfulnessRoutingDecision\""));
        assertTrue(html.contains("setText('faithfulnessReason', available ? (snapshot.reason || snapshot['quality.reason'] || 'UNKNOWN') : '--')"));
        assertTrue(html.contains("setText('faithfulnessCompositeRisk', available ? riskValue(snapshot['scorecard.compositeRisk']) : '--')"));
        assertTrue(html.contains("setText('faithfulnessHallucinationLikelihood', available ? riskValue(snapshot['scorecard.hallucinationLikelihood']) : '--')"));
        assertTrue(html.contains("setText('faithfulnessRoutingDecision', available ? (snapshot['scorecard.routingDecision'] || 'UNKNOWN') : '--')"));
    }
}
