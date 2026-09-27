package com.example.lms.conversation.archive;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTopicTimelineBuilderTraceTest {

    @Test
    void limitPreservesSurrogateBoundariesAndExistingContracts() throws Exception {
        Method method = ConversationTopicTimelineBuilder.class.getDeclaredMethod("limit", String.class, int.class);
        method.setAccessible(true);
        String emoji = "\uD83D\uDE00";

        String straddling = invokeLimit(method, "a".repeat(119) + emoji, 120);
        String leading = invokeLimit(method, emoji + "x", 1);
        String afterBoundary = invokeLimit(method, "a".repeat(120) + emoji, 120);
        String fullPair = invokeLimit(method, "a".repeat(118) + emoji + "x", 120);
        String bmpBoundary = invokeLimit(method, "a".repeat(119) + "z" + "x", 120);

        assertAll(
                () -> assertEquals("a".repeat(119), straddling, "POS-STRADDLING-PAIR"),
                () -> assertFalse(Character.isHighSurrogate(straddling.charAt(straddling.length() - 1))),
                () -> assertEquals(straddling, utf8RoundTrip(straddling)),
                () -> assertTrue(straddling.length() <= 120),
                () -> assertEquals("", leading, "POS-STRADDLING-PAIR leading boundary"),
                () -> assertEquals(leading, utf8RoundTrip(leading)),
                () -> assertTrue(leading.length() <= 1),
                () -> assertEquals("a".repeat(120), afterBoundary, "POS-STRADDLING-PAIR after boundary"),
                () -> assertEquals(afterBoundary, utf8RoundTrip(afterBoundary)),
                () -> assertTrue(afterBoundary.length() <= 120),
                () -> assertEquals("a".repeat(118) + emoji, fullPair, "POS-FULL-PAIR-BMP pair"),
                () -> assertEquals(fullPair, utf8RoundTrip(fullPair)),
                () -> assertEquals("a".repeat(119) + "z", bmpBoundary, "POS-FULL-PAIR-BMP BMP"),
                () -> assertEquals(bmpBoundary, utf8RoundTrip(bmpBoundary)),
                () -> assertNull(invokeLimit(method, null, 120), "POS-NULL-ZERO-SHORT null"),
                () -> assertEquals("", invokeLimit(method, "x", 0), "POS-NULL-ZERO-SHORT zero"),
                () -> assertEquals("", invokeLimit(method, "x", Integer.MIN_VALUE),
                        "POS-NULL-ZERO-SHORT minimum"),
                () -> assertEquals("exact", invokeLimit(method, "exact", 5),
                        "POS-NULL-ZERO-SHORT exact"),
                () -> assertEquals("short", invokeLimit(method, "short", 10),
                        "POS-NULL-ZERO-SHORT short"),
                () -> assertEquals("large", invokeLimit(method, "large", Integer.MAX_VALUE),
                        "POS-NULL-ZERO-SHORT maximum"));
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void malformedLinkHostParseLeavesRedactedSuppressionBreadcrumb() throws Exception {
        String raw = "https://[ownerToken-secret";
        Method method = ConversationTopicTimelineBuilder.class.getDeclaredMethod("firstHost", String.class);
        method.setAccessible(true);

        assertNull(method.invoke(null, raw));

        assertEquals(Boolean.TRUE, TraceStore.get("conversation.archive.suppressed.conversationArchive.firstHost"));
        assertEquals("conversationArchive.firstHost", TraceStore.get("conversation.archive.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("conversation.archive.suppressed.errorType"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("conversation.archive.suppressed.conversationArchive.firstHost.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    private static String invokeLimit(Method method, String value, int max) throws Exception {
        return (String) method.invoke(null, value, max);
    }

    private static String utf8RoundTrip(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
