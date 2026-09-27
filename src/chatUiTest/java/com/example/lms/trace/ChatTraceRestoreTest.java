package com.example.lms.trace;

import com.example.lms.api.ChatApiController;
import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.service.trace.TraceHtmlBuilder;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTraceRestoreTest {

    @Test
    void vectorOnlyRunRendersExistingContextAndOrchestrationSections() {
        String html = new TraceHtmlBuilder(null).buildSplitPanel(
                null, List.of(), null, List.of(Content.from("synthetic vector result")),
                Map.of("orch.mode", "CHAT"));

        assertTrue(html.contains("<details"));
        assertTrue(html.contains("B) Final Context"));
        assertTrue(html.contains("C) Orchestration State"));
        assertTrue(html.contains("vector 1"));
        assertFalse(html.contains("provider: &lt;unset&gt;"));
    }

    @Test
    void modelOnlyRunReportsWebWasNotRequestedWithoutInventingSearchSuccess() {
        String html = new TraceHtmlBuilder(null).buildSplitPanel(
                null, List.of(), null, null, Map.of("orch.mode", "CHAT"));

        assertTrue(html.contains("<details"));
        assertTrue(html.contains("C) Orchestration State"));
        assertTrue(html.contains("not_requested"));
        assertFalse(html.contains("Search completed"));
    }

    @Test
    void webContextWithoutRawTraceReportsMissingTraceRatherThanSkippedSearch() {
        String html = new TraceHtmlBuilder(null).buildSplitPanel(
                null, List.of(), List.of(Content.from("synthetic web result")), null,
                Map.of("orch.mode", "CHAT"));

        assertTrue(html.contains("Web trace_unavailable"));
        assertFalse(html.contains("Web not_requested"));
    }

    @Test
    void emptyWebContextUsesActualSearchDecision() {
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);
        String skipped = builder.buildSplitPanel(null, List.of(), List.of(), null,
                Map.of("orch.mode", "CHAT"), false);
        String attempted = builder.buildSplitPanel(null, List.of(), List.of(), null,
                Map.of("orch.mode", "CHAT"), true);

        assertTrue(skipped.contains("Web not_requested"));
        assertFalse(skipped.contains("Web trace_unavailable"));
        assertTrue(attempted.contains("Web trace_unavailable"));
    }

    @Test
    void replayProjectionRemovesOnlyHtmlForSubscriberWithoutTracePermission() throws Exception {
        ServerSentEvent<ChatStreamEvent> original = ServerSentEvent
                .<ChatStreamEvent>builder(ChatStreamEvent.trace("<details>private</details>"))
                .event("trace").build();
        ServerSentEvent<ChatStreamEvent> projected = project(original, false);

        assertNotNull(projected.data());
        assertEquals("trace", projected.data().type());
        assertEquals("trace", projected.event());
        assertEquals(null, projected.data().html());
        assertSame(original, project(original, true));

        ServerSentEvent<ChatStreamEvent> token = ServerSentEvent
                .<ChatStreamEvent>builder(ChatStreamEvent.token("synthetic token"))
                .event("token").build();
        assertSame(token, project(token, false));
    }

    @SuppressWarnings("unchecked")
    private static ServerSentEvent<ChatStreamEvent> project(
            ServerSentEvent<ChatStreamEvent> event, boolean mayReadHtml) throws Exception {
        Method projection = Arrays.stream(ChatApiController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("projectTraceForSubscriber"))
                .findFirst().orElse(null);
        assertNotNull(projection, "stream replay needs a subscriber-specific trace projection");
        projection.setAccessible(true);
        return (ServerSentEvent<ChatStreamEvent>) projection.invoke(null, event, mayReadHtml);
    }
}
