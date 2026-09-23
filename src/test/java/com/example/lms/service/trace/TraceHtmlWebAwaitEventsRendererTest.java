package com.example.lms.service.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TraceHtmlWebAwaitEventsRendererTest {

    @Test
    void appendDoesNothingWhenEventsMetadataIsMissing() {
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();

        TraceHtmlWebAwaitEventsRenderer.append(html, Map.of("web.await.events", "not-a-list"), shown);

        assertEquals("", html.toString());
        assertTrue(shown.isEmpty());
    }

    @Test
    void appendRendersGroupedTimeoutSummaryAndRedactsDetails() {
        String rawDetail = "private web await detail from user prompt";
        String rawError = "private await errMsg ownerToken=await-secret";
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();

        TraceHtmlWebAwaitEventsRenderer.append(html, Map.of("web.await.events", List.of(
                Map.of(
                        "stage", "hard",
                        "engine", "naver<script>",
                        "step", "search <step>",
                        "cause", "timeout_hard",
                        "timeout", true,
                        "timeoutMs", 900,
                        "waitedMs", 950,
                        "detail", rawDetail,
                        "errMsg", rawError),
                Map.of(
                        "stage", "soft",
                        "engine", "brave",
                        "step", "join",
                        "cause", "budget_exhausted",
                        "waitedMs", 15))), shown);

        String out = html.toString();
        assertTrue(out.contains("Web Await Events"));
        assertTrue(out.contains("count=2"));
        assertTrue(out.contains("timeout=1"));
        assertTrue(out.contains("timeoutSoft=1"));
        assertTrue(out.contains("timeoutAll=2"));
        assertTrue(out.contains("naver&lt;script&gt;"));
        assertTrue(out.contains("search &lt;step&gt;"));
        assertTrue(out.contains("hash12") || out.contains("hash:"));
        assertFalse(out.contains("naver<script>"));
        assertFalse(out.contains("search <step>"));
        assertFalse(out.contains(rawDetail));
        assertFalse(out.contains(rawError));
        assertFalse(out.contains("await-secret"));
        assertTrue(shown.contains("web.await.events"));
        assertTrue(shown.contains("web.await.last"));
    }

    @Test
    void appendRedactsSecretShapedLabels() {
        String bearer = "Bearer " + "local-placeholder-token";
        String queryKey = "api_" + "key=hidden-secret";
        StringBuilder html = new StringBuilder();
        Set<String> shown = new LinkedHashSet<>();

        TraceHtmlWebAwaitEventsRenderer.append(html, Map.of("web.await.events", List.of(Map.of(
                "stage", "hard " + bearer,
                "engine", "naver " + queryKey,
                "step", "search " + bearer,
                "cause", "timeout " + queryKey,
                "timeout", true))), shown);

        String out = html.toString();
        assertTrue(out.contains("Web Await Events"));
        assertFalse(out.contains(bearer));
        assertFalse(out.contains(queryKey));
    }
}
