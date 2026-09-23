package com.example.lms.service.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TraceHtmlCitableEvidenceRendererTest {

    @Test
    void renderReturnsEmptyWhenPublicEvidenceIsMissing() {
        assertEquals("", TraceHtmlCitableEvidenceRenderer.render(Map.of()));
    }

    @Test
    void renderPromotedEvidenceWithEscapedFieldsAndLineRange() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("marker", "E<1>");
        evidence.put("kind", "web");
        evidence.put("title", "Alpha <Title>");
        evidence.put("source", "docs.example.test");
        evidence.put("filePath", "docs/alpha.md");
        evidence.put("lineStart", 12);
        evidence.put("lineEnd", 15);
        evidence.put("confidence", "0.91");

        String html = TraceHtmlCitableEvidenceRenderer.render(Map.of("rag.evidence.public", List.of(evidence)));

        assertTrue(html.contains("C) Citable Evidence"));
        assertTrue(html.contains("E&lt;1&gt;"));
        assertTrue(html.contains("Alpha &lt;Title&gt;"));
        assertTrue(html.contains("source=docs.example.test"));
        assertTrue(html.contains("filePath=docs/alpha.md"));
        assertTrue(html.contains("lines=12-15"));
        assertTrue(html.contains("confidence=0.91"));
        assertFalse(html.contains("E<1>"));
        assertFalse(html.contains("Alpha <Title>"));
    }

    @Test
    void renderSkipsNonMapItemsAndShowsNoPromotedEvidenceWhenNoneRemain() {
        String html = TraceHtmlCitableEvidenceRenderer.render(Map.of("rag.evidence.public", List.of("bad-item")));

        assertTrue(html.contains("(No promoted evidence)"));
    }

    @Test
    void renderTruncatesAfterTwentyPromotedEvidenceRows() {
        List<Object> evidence = new ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            evidence.add(Map.of("marker", "E" + i, "title", "Title " + i));
        }

        String html = TraceHtmlCitableEvidenceRenderer.render(Map.of("rag.evidence.public", evidence));

        assertTrue(html.contains("E20"));
        assertFalse(html.contains("E21"));
        assertTrue(html.contains("...(truncated)..."));
    }

    @Test
    void renderMasksSensitiveFragmentsInPromotedEvidenceFields() {
        String bearer = "Bearer " + "local-placeholder-token";
        String apiValue = "hidden-secret";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("marker", "E1 api_key=" + apiValue);
        evidence.put("title", "Title " + bearer);
        evidence.put("source", "source api_key=" + apiValue);
        evidence.put("filePath", "docs/alpha.md?api_key=" + apiValue);

        String html = TraceHtmlCitableEvidenceRenderer.render(Map.of("rag.evidence.public", List.of(evidence)));

        assertTrue(html.contains("C) Citable Evidence"));
        assertFalse(html.contains("local-placeholder-token"), html);
        assertFalse(html.contains(apiValue), html);
    }
}
