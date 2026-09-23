package com.example.lms.service.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

class TraceHtmlContentListRendererTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void rendererDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/trace/TraceHtmlContentListRenderer.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "trace HTML content rendering needs safe breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void renderLimitsDocumentsAndRedactsRawUrl() {
        Content first = Content.from(TextSegment.from("Evidence body",
                Metadata.from(Map.of(
                        "title", "Alpha <Title>",
                        "url", "https://example.com/path?secret=raw-value"))));
        Content second = Content.from(TextSegment.from("Second body"));

        String html = TraceHtmlContentListRenderer.render(Arrays.asList(first, null, second), "W", 1);

        assertTrue(html.contains("<ol class=\"trace-docs\">"));
        assertTrue(html.contains("W1"));
        assertFalse(html.contains("W2"));
        assertTrue(html.contains("example.com"));
        assertTrue(html.contains("urlHash="));
        assertTrue(html.contains(SafeRedactor.hash12("Alpha <Title>")));
        assertFalse(html.contains("secret=raw-value"));
    }

    @Test
    void renderUsesBracketTitleAndSnippetFallbacksWithoutRawUrlLeak() {
        Content content = Content.from(TextSegment.from(
                "[Doc Title | https://docs.example.test/a?token=hidden]\nSnippet body https://docs.example.test/b?token=hidden"));

        String html = TraceHtmlContentListRenderer.render(List.of(content), "V", 5);

        assertTrue(html.contains("V1"));
        assertTrue(html.contains(SafeRedactor.hash12("Doc Title")));
        assertTrue(html.contains(SafeRedactor.hash12("Snippet body https://docs.example.test/b?token=hidden")));
        assertFalse(html.contains("token=hidden"));
    }

    @Test
    void renderEmptyListAsMutedEmptyMarker() {
        String html = TraceHtmlContentListRenderer.render(List.of(), "W", 10);

        assertTrue(html.contains("(empty)"));
        assertFalse(html.contains("<ol class=\"trace-docs\">"));
    }

    @Test
    void invalidUrlHostFallbackLeavesStableBreadcrumbWithoutRawUrl() throws Exception {
        Method hostOf = TraceHtmlContentListRenderer.class.getDeclaredMethod("hostOf", String.class);
        hostOf.setAccessible(true);
        String rawUrl = "https://example .com/private?token=hidden";

        assertNull(hostOf.invoke(null, rawUrl));

        assertEquals("host", TraceStore.get("traceHtml.contentList.suppressed.stage"));
        assertEquals("invalid_uri", TraceStore.get("traceHtml.contentList.suppressed.errorType"));
        assertEquals(1L, TraceStore.get("traceHtml.contentList.suppressed.count"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceHtml.contentList.suppressed.host"));
        assertEquals("invalid_uri", TraceStore.get("traceHtml.contentList.suppressed.host.errorType"));
        assertEquals(1L, TraceStore.get("traceHtml.contentList.suppressed.host.count"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawUrl));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("token=hidden"));
    }
}
