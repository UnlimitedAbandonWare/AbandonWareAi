package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAwaitEventsSummaryTest {

    @Test
    void awaitSummaryLongParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/WebAwaitEventsSummary.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Long.parseLong(String.valueOf(value).trim());";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "WebAwaitEventsSummary long parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "await summary numeric parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "await summary numeric parser fallback should catch only NumberFormatException");
        assertTrue(window.contains("WebFailSoftTraceSuppressions.trace(\"webAwaitEventsSummary.toLong\", ignore);"),
                "await summary numeric parser fallback should leave a fixed-stage breadcrumb");
    }

    @Test
    void summarizesAwaitEventsWithoutPromotingAwaitTimeoutToProviderTimeout() {
        Map<String, Object> summary = WebAwaitEventsSummary.buildTraceEntries(
                List.of(
                        Map.of(
                                "engine", "Naver",
                                "stage", "hard",
                                "step", "officialJoin",
                                "cause", "await_timeout",
                                "waitedMs", 120L,
                                "timeoutMs", 100L,
                                "nonOk", true),
                        Map.of(
                                "engine", "Brave",
                                "stage", "soft",
                                "step", "cacheOnly",
                                "cause", "budget_exhausted",
                                "waitedMs", 10L,
                                "timeoutMs", 500L),
                        Map.of(
                                "engine", "Naver",
                                "stage", "hard",
                                "step", "officialJoin",
                                "cause", "missing_future",
                                "waitedMs", 0L,
                                "timeoutMs", 0L,
                                "nonOk", true)),
                Map.of());

        assertEquals(3, summary.get("web.await.events.summary.count"));
        assertEquals(1L, summary.get("web.await.events.summary.await_timeout.count"));
        assertEquals(1L, summary.get("web.await.events.summary.engine.Naver.cause.await_timeout.count"));
        assertEquals(0L, summary.get("web.await.events.summary.engine.Brave.cause.await_timeout.count"));
        assertEquals(1L, summary.get("web.await.events.summary.timeout.count"));
        assertEquals(1L, summary.get("web.await.events.summary.timeout.soft.count"));
        assertEquals(0L, summary.get("web.await.events.summary.timeout.hard.count"));
        assertEquals(1L, summary.get("web.await.events.summary.missing_future.count"));
        assertEquals(1L, summary.get("web.await.events.summary.skip.count"));
        assertEquals(2, summary.get("web.await.events.summary.engine.Naver.count"));
    }

    @Test
    void summarizesSerpApiAndTavilyAwaitTimeoutStableCounts() {
        Map<String, Object> summary = WebAwaitEventsSummary.buildTraceEntries(
                List.of(
                        Map.of(
                                "engine", "SerpApi",
                                "stage", "hard",
                                "step", "officialJoin",
                                "cause", "await_timeout",
                                "waitedMs", 120L,
                                "timeoutMs", 100L),
                        Map.of(
                                "engine", "Tavily",
                                "stage", "hard",
                                "step", "officialJoin",
                                "cause", "awaitTimeout",
                                "waitedMs", 130L,
                                "timeoutMs", 100L)),
                Map.of());

        assertEquals(2L, summary.get("web.await.events.summary.await_timeout.count"));
        assertEquals(1L, summary.get("web.await.events.summary.engine.SerpApi.cause.await_timeout.count"));
        assertEquals(1L, summary.get("web.await.events.summary.engine.Tavily.cause.await_timeout.count"));
    }

    @Test
    void summarizesSkippedCountersWithRedactedLastValuesWhenEventsAreMissing() {
        String rawLabel = "private customer query with punctuation !";
        Map<String, Object> summary = WebAwaitEventsSummary.buildTraceEntries(
                List.of(),
                Map.of(
                        "web.await.skipped.Naver.count", 2L,
                        "web.await.skipped.Naver.last", rawLabel,
                        "web.await.skipped.last", rawLabel,
                        "web.await.skipped.last.engine", "Naver",
                        "web.await.skipped.last.reason", rawLabel,
                        "web.await.skipped.last.step", "officialJoin"));

        assertEquals(0, summary.get("web.await.events.summary.count"));
        assertEquals(0L, summary.get("web.await.events.summary.await_timeout.count"));
        assertEquals(0L, summary.get("web.await.events.summary.engine.Naver.cause.await_timeout.count"));
        assertEquals(0L, summary.get("web.await.events.summary.engine.Brave.cause.await_timeout.count"));
        assertEquals(0L, summary.get("web.await.events.summary.engine.SerpApi.cause.await_timeout.count"));
        assertEquals(0L, summary.get("web.await.events.summary.engine.Tavily.cause.await_timeout.count"));
        assertEquals(2L, summary.get("web.await.events.summary.skipped.total"));
        assertEquals(2L, summary.get("web.await.events.summary.skipped.engine.Naver.count"));
        assertTrue(String.valueOf(summary.get("web.await.events.summary.skipped.engine.Naver.last"))
                .startsWith("hash:"));
        assertTrue(String.valueOf(summary.get("web.await.events.summary.skipped.last.reason"))
                .startsWith("hash:"));
        assertFalse(String.valueOf(summary).contains(rawLabel), String.valueOf(summary));
    }
}
