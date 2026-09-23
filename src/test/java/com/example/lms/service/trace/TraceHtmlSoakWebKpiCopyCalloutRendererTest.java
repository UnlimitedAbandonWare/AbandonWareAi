package com.example.lms.service.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class TraceHtmlSoakWebKpiCopyCalloutRendererTest {

    @Test
    void renderReturnsEmptyWhenNoSoakKpiLinesExist() {
        assertEquals("", TraceHtmlSoakWebKpiCopyCalloutRenderer.render(Map.of("unrelated", "value")));
    }

    @Test
    void renderLastLineWithEscapedJsonAndCopyButton() {
        String html = TraceHtmlSoakWebKpiCopyCalloutRenderer.render(Map.of(
                "web.failsoft.soakKpiJson.last", "{\"rid\":\"r1\",\"q\":\"alpha <query>\"}"));

        assertTrue(html.contains("SOAK_WEB_KPI (copy)"));
        assertTrue(html.contains("data-copy-target="));
        assertTrue(html.contains("&lt;query&gt;"));
        assertTrue(html.contains("/internal/probe/websoak-kpi/last?format=line"));
        assertFalse(html.contains("alpha <query>"));
    }

    @Test
    void renderRedactsSensitiveTokensInsideCopyableKpiLine() {
        String bearer = "Bearer " + "local-placeholder-token";
        String rawLine = "{\"headers\":\"" + bearer + "\",\"reason\":\"api_key=hidden-secret\"}";

        String html = TraceHtmlSoakWebKpiCopyCalloutRenderer.render(Map.of(
                "web.failsoft.soakKpiJson.last", rawLine));

        assertTrue(html.contains("SOAK_WEB_KPI (copy)"));
        assertFalse(html.contains("local-placeholder-token"), html);
        assertFalse(html.contains("api_key=hidden-secret"), html);
    }

    @Test
    void renderRunIdLinesSortedAndLimitedToLatestThree() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("web.failsoft.soakKpiJson.runId.1", "one");
        meta.put("web.failsoft.soakKpiJson.runId.4", "four");
        meta.put("web.failsoft.soakKpiJson.runId.2", "two");
        meta.put("web.failsoft.soakKpiJson.runId.3", "three");

        String html = TraceHtmlSoakWebKpiCopyCalloutRenderer.render(meta);

        assertFalse(html.contains("runId=1"));
        assertTrue(html.contains("runId=2"));
        assertTrue(html.contains("runId=3"));
        assertTrue(html.contains("runId=4"));
        assertTrue(html.indexOf("runId=2") < html.indexOf("runId=3"));
        assertTrue(html.indexOf("runId=3") < html.indexOf("runId=4"));
    }

    @Test
    void rendererScriptDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/trace/TraceHtmlSoakWebKpiCopyCalloutRenderer.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "copy fallback script should leave a fixed-stage breadcrumb instead of an exact empty catch");
    }
}
