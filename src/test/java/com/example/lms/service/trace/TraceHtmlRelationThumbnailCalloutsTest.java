package com.example.lms.service.trace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TraceHtmlRelationThumbnailCalloutsTest {

    @Test
    void renderBudgetCoercesNumbersBooleansAndFallbackKeys() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("uaw.thumbnail.relationThumbnail.inputAnchorCount", "3");
        meta.put("rag.eval.kgAxis.uawRelationThumbnailSelectedAnchorCount", 2);
        meta.put("uaw.thumbnail.relationThumbnail.sliced", "TRUE");

        String html = TraceHtmlRelationThumbnailCallouts.renderBudget(meta);

        assertTrue(html.contains("UAW Relation Thumbnail Budget"));
        assertTrue(html.contains("inputAnchorCount"));
        assertTrue(html.contains("<code>3</code>"));
        assertTrue(html.contains("selectedAnchorCount"));
        assertTrue(html.contains("<code>2</code>"));
        assertTrue(html.contains("sliced"));
        assertTrue(html.contains("<code>true</code>"));
    }

    @Test
    void renderContextLayersSkipsUnsafeTokensAndZeroCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("gold", 2);
        counts.put("zero", 0);
        counts.put("bad<script>", 5);
        Map<String, Object> meta = Map.of("rgb.soak.strategy.main.relationThumbnailContextLayerCounts", counts);

        String html = TraceHtmlRelationThumbnailCallouts.renderContextLayers(meta);

        assertTrue(html.contains("Relation Thumbnail Context Layers"));
        assertTrue(html.contains("<code>main</code>"));
        assertTrue(html.contains("<code>gold</code>"));
        assertTrue(html.contains("<code>2</code>"));
        assertFalse(html.contains("zero"));
        assertFalse(html.contains("bad<script>"));
    }

    @Test
    void renderSliceMapEscapesRowsAndShowsTruncationHint() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank", 1);
        row.put("hash", "hash123");
        row.put("relationKind", "parent<child>");
        row.put("selectionReason", "picked:top");
        row.put("contextLayer", "gold");
        row.put("overlap", 4);
        Map<String, Object> meta = Map.of(
                "retrieval.kg.relationThumbnail.sliceMap", List.of(row),
                "retrieval.kg.relationThumbnail.sliceMapCount", 10);

        String html = TraceHtmlRelationThumbnailCallouts.renderSliceMap(meta);

        assertTrue(html.contains("Relation Thumbnail Slice Map"));
        assertTrue(html.contains("sliceMapCount=10"));
        assertTrue(html.contains("(showing 1)"));
        assertTrue(html.contains("parent&lt;child&gt;"));
        assertFalse(html.contains("parent<child>"));
    }

    @Test
    void renderSliceMapRedactsSecretShapedValues() {
        String bearer = "Bearer " + "local-placeholder-token";
        String queryKey = "api_" + "key=hidden-secret";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank", 1);
        row.put("hash", "hash123");
        row.put("relationKind", "related " + queryKey);
        row.put("selectionReason", "picked:top");
        row.put("contextLayer", "gold " + bearer);
        row.put("overlap", "4 " + bearer);
        Map<String, Object> meta = Map.of("retrieval.kg.relationThumbnail.sliceMap", List.of(row));

        String html = TraceHtmlRelationThumbnailCallouts.renderSliceMap(meta);

        assertTrue(html.contains("Relation Thumbnail Slice Map"));
        assertFalse(html.contains(queryKey));
        assertFalse(html.contains(bearer));
    }

    @Test
    void numericFallbackParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/trace/TraceHtmlRelationThumbnailCallouts.java"));
        int start = source.indexOf("private static Integer toInt");
        int parse = source.indexOf("Integer.parseInt", start);
        int end = source.indexOf("\n    }", parse);
        assertTrue(start >= 0 && parse > start && end > parse, "toInt parser should be locatable");
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception");
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException");
    }
}
