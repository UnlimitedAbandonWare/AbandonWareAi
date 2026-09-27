package com.example.lms.service.rag.extract;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whole-request deadline checks for {@link PageContentScraper}: one page budget
 * must cover the entire redirect chain, a request-scoped {@link TimeBudget}
 * must cap it further, and budget exhaustion must never become Jsoup
 * {@code timeout(0)} (infinite). All wire calls are canned — no real network.
 */
class PageContentScraperDeadlineTest {

    @AfterEach
    void cleanup() {
        TimeBudgetContext.clear();
        TraceStore.context().clear();
    }

    /** Seam double: canned responses, captured per-hop timeouts, no DNS/SSRF. */
    private static final class StubScraper extends PageContentScraper {
        final Queue<Connection.Response> responses;
        final long hopDelayMs;
        final List<Integer> hopTimeouts = new CopyOnWriteArrayList<>();
        final AtomicInteger wireAttempts = new AtomicInteger();

        StubScraper(Queue<Connection.Response> responses, long hopDelayMs) {
            this.responses = responses;
            this.hopDelayMs = hopDelayMs;
        }

        @Override
        protected void validatePublicTarget(URI target) {
            // Canned responses only: SSRF/DNS validation bypassed, no wire call.
        }

        @Override
        protected Connection openConnection(String targetUrl) {
            wireAttempts.incrementAndGet();
            Connection connection = mock(Connection.class);
            try {
                when(connection.userAgent(anyString())).thenReturn(connection);
                when(connection.followRedirects(anyBoolean())).thenReturn(connection);
                when(connection.timeout(anyInt())).thenAnswer(inv -> {
                    hopTimeouts.add(inv.getArgument(0));
                    return connection;
                });
                when(connection.execute()).thenAnswer(inv -> {
                    if (hopDelayMs > 0) {
                        Thread.sleep(hopDelayMs);
                    }
                    return responses.poll();
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return connection;
        }
    }

    private static Connection.Response redirect(String location) {
        Connection.Response r = mock(Connection.Response.class);
        when(r.statusCode()).thenReturn(302);
        when(r.header("Location")).thenReturn(location);
        return r;
    }

    private static Connection.Response ok(String text) throws Exception {
        Connection.Response r = mock(Connection.Response.class);
        when(r.statusCode()).thenReturn(200);
        when(r.parse()).thenReturn(Jsoup.parse("<p>" + text + "</p>"));
        return r;
    }

    @Test
    void expiredRequestBudgetBlocksAnyWireAttempt() throws Exception {
        TimeBudgetContext.set(new TimeBudget(1));
        Thread.sleep(30L); // let the budget expire

        StubScraper scraper = new StubScraper(new ArrayDeque<>(List.of(ok("body"))), 0);
        String result = scraper.fetchText("https://example.com/a", 3500);

        assertNull(result);
        assertEquals(0, scraper.wireAttempts.get());
        assertTrue(scraper.hopTimeouts.isEmpty());
        assertEquals("budget", TraceStore.context().get("web.pageScraper.fetchText.stage"));
        assertEquals(false, TraceStore.context().get("web.pageScraper.fetchText.wireAttemptObserved"));
        assertTrue(String.valueOf(TraceStore.context().get("web.pageScraper.fetchText.errorType"))
                .contains("budget"));
    }

    @Test
    void requestBudgetCapsFirstHopTimeout() throws Exception {
        TimeBudgetContext.set(new TimeBudget(400));
        StubScraper scraper = new StubScraper(new ArrayDeque<>(List.of(ok("hello"))), 0);

        String result = scraper.fetchText("https://example.com/a", 6000);

        assertEquals("hello", result);
        assertEquals(1, scraper.wireAttempts.get());
        assertEquals(1, scraper.hopTimeouts.size());
        int hopTimeout = scraper.hopTimeouts.get(0);
        assertTrue(hopTimeout >= 1 && hopTimeout <= 400,
                "hop timeout must be bounded by request remaining, got " + hopTimeout);
    }

    @Test
    void redirectHopsShareCumulativePageBudget() throws Exception {
        // 350ms per hop against a 1000ms page budget: the old code re-granted
        // the full 1000ms on every hop (up to ~5s across MAX_REDIRECTS); now
        // the hops share one cumulative deadline.
        StubScraper scraper = new StubScraper(new ArrayDeque<>(List.of(
                redirect("/r1"), redirect("/r2"), redirect("/r3"),
                redirect("/r4"), redirect("/r5"), ok("late"))), 350);

        String result = scraper.fetchText("https://example.com/start", 1000);

        assertNull(result);
        List<Integer> timeouts = scraper.hopTimeouts;
        assertTrue(timeouts.size() >= 2, "expected multiple hops, got " + timeouts);
        assertTrue(timeouts.size() <= 6, "redirect cap must hold, got " + timeouts);
        assertTrue(timeouts.get(0) >= 990 && timeouts.get(0) <= 1000,
                "first hop sees (nearly) the full page budget, got " + timeouts.get(0));
        assertTrue(timeouts.get(timeouts.size() - 1) < timeouts.get(0),
                "later hops must see only the remainder, got " + timeouts);
        assertTrue(timeouts.stream().allMatch(v -> v >= 1),
                "exhausted budget must never become timeout(0)/infinite, got " + timeouts);
        assertEquals("budget", TraceStore.context().get("web.pageScraper.fetchText.stage"));
        assertTrue(String.valueOf(TraceStore.context().get("web.pageScraper.fetchText.errorType"))
                .contains("budget"));
    }
}
