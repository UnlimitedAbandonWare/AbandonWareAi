package com.example.lms.service.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.lms.trace.attribution.TraceAblationAttributionResult;

class TraceHtmlAblationAttributionCalloutRendererTest {

    @Test
    void renderReturnsEmptyWhenResultHasNoContributors() {
        TraceAblationAttributionResult result = new TraceAblationAttributionResult(
                "v1", "ok", 0.0, List.of(), List.of(), Map.of());

        assertEquals("", TraceHtmlAblationAttributionCalloutRenderer.render(result));
    }

    @Test
    void renderEscapesContributorAndSelfAskText() {
        TraceAblationAttributionResult result = new TraceAblationAttributionResult(
                "v<1>",
                "degraded<script>",
                0.8123,
                List.of(new TraceAblationAttributionResult.Contributor(
                        "c1",
                        "web<script>",
                        "title <b>",
                        0.42,
                        0.7,
                        List.of("evidence <raw>"),
                        List.of("fix <hint>"))),
                List.of(new TraceAblationAttributionResult.Beam(
                        0.7,
                        0.4,
                        List.of(new TraceAblationAttributionResult.QaStep(
                                "question <raw>",
                                "answer <raw>",
                                0.6,
                                List.of())))),
                Map.of());

        String html = TraceHtmlAblationAttributionCalloutRenderer.render(result);

        assertTrue(html.contains("Trace-Ablation Attribution"));
        assertTrue(html.contains("risk=0.812"));
        assertTrue(html.contains("degraded&lt;script&gt;"));
        assertTrue(html.contains("v&lt;1&gt;"));
        assertTrue(html.contains("web&lt;script&gt;"));
        assertTrue(html.contains("title &lt;b&gt;"));
        assertTrue(html.contains("hash12"));
        assertTrue(html.contains("present"));
        assertFalse(html.contains("evidence <raw>"));
        assertFalse(html.contains("fix <hint>"));
        assertFalse(html.contains("question <raw>"));
        assertFalse(html.contains("answer <raw>"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("title <b>"));
    }

    @Test
    void renderRedactsSecretShapedDisplayLabels() {
        String bearer = "Bearer " + "local-placeholder-token";
        String queryKey = "api_" + "key=hidden-secret";
        TraceAblationAttributionResult result = new TraceAblationAttributionResult(
                "v1 " + bearer,
                "degraded " + queryKey,
                0.8123,
                List.of(new TraceAblationAttributionResult.Contributor(
                        "c1",
                        "web " + bearer,
                        "title " + queryKey,
                        0.42,
                        0.7,
                        List.of(),
                        List.of())),
                List.of(),
                Map.of());

        String html = TraceHtmlAblationAttributionCalloutRenderer.render(result);

        assertTrue(html.contains("Trace-Ablation Attribution"));
        assertFalse(html.contains(bearer));
        assertFalse(html.contains(queryKey));
    }

    @Test
    void renderSimpleListsAreLimitedToEightItems() {
        TraceAblationAttributionResult result = new TraceAblationAttributionResult(
                "v1",
                "degraded",
                0.25,
                List.of(new TraceAblationAttributionResult.Contributor(
                        "c1",
                        "group",
                        "title",
                        0.2,
                        0.1,
                        List.of("one", "two", "three", "four", "five", "six", "seven", "eight", "nine"),
                        List.of())),
                List.of(),
                Map.of());

        String html = TraceHtmlAblationAttributionCalloutRenderer.render(result);

        assertTrue(html.contains("hash12"));
        assertFalse(html.contains("<li>nine</li>"));
        assertFalse(html.contains("nine"));
        assertTrue(html.contains("(1 more)"));
    }
}
