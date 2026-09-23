package com.example.lms.service.rag.extract;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PageContentScraperTest {

    @Test
    void fetchFailureReturnsNullAndLeavesRedactedTraceBreadcrumb() {
        PageContentScraper scraper = new PageContentScraper();

        TraceStore.clear();
        String text = scraper.fetchText("http://[raw-page-secret", 10);

        assertNull(text);
        assertEquals(true, TraceStore.get("web.pageScraper.fetchText.failed"));
        assertEquals(1L, TraceStore.get("web.pageScraper.fetchText.failed.count"));
        assertEquals("fetchText", TraceStore.get("web.pageScraper.fetchText.stage"));
        assertEquals(1000, TraceStore.get("web.pageScraper.fetchText.timeoutMs"));
        assertFalse(TraceStore.getAll().toString().contains("raw-page-secret"));
    }

    @Test
    void fetchTextRejectsLoopbackBeforeOpeningConnection() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal", exchange -> {
            requests.incrementAndGet();
            byte[] body = "<html><body>internal-only</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            TraceStore.clear();
            String target = "http://127.0.0.1:" + server.getAddress().getPort() + "/internal";

            assertNull(new PageContentScraper().fetchText(target, 1000));
            assertEquals(0, requests.get());
            assertEquals(true, TraceStore.get("web.pageScraper.fetchText.blocked"));
            assertEquals("non_public_address", TraceStore.get("web.pageScraper.fetchText.blockedReason"));
            assertEquals(false, TraceStore.get("web.pageScraper.fetchText.wireAttemptObserved"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void outboundPolicyRejectsNonHttpAndNonPublicAddressesBeforeJsoup() {
        List<String> targets = List.of(
                "file:///internal-secret",
                "ftp://93.184.216.34/internal-secret",
                "http://10.0.0.1/internal-secret",
                "http://172.16.0.1/internal-secret",
                "http://192.168.0.1/internal-secret",
                "http://169.254.169.254/internal-secret",
                "http://[::1]/internal-secret",
                "http://[fc00::1]/internal-secret",
                "http://224.0.0.1/internal-secret",
                "http://2130706433/internal-secret"
        );

        for (String target : targets) {
            TraceStore.clear();
            try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
                assertNull(new PageContentScraper().fetchText(target, 1000));
                jsoup.verify(() -> Jsoup.connect(target), never());
            }
            assertEquals(true, TraceStore.get("web.pageScraper.fetchText.blocked"));
            assertEquals(false, TraceStore.get("web.pageScraper.fetchText.wireAttemptObserved"));
            assertFalse(TraceStore.getAll().toString().contains("internal-secret"));
        }
    }

    @Test
    void publicToPrivateRedirectIsRejectedBeforeSecondConnection() throws Exception {
        String publicTarget = "http://93.184.216.34/start";
        String privateTarget = "http://127.0.0.1/internal-secret";
        Connection connection = mockConnection();
        Connection.Response redirect = mock(Connection.Response.class);
        Document oldAutomaticResult = mock(Document.class);
        when(oldAutomaticResult.text()).thenReturn("unsafe automatic redirect result");
        when(connection.get()).thenReturn(oldAutomaticResult);
        when(connection.execute()).thenReturn(redirect);
        when(redirect.statusCode()).thenReturn(302);
        when(redirect.header("Location")).thenReturn(privateTarget);

        TraceStore.clear();
        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(publicTarget)).thenReturn(connection);

            assertNull(new PageContentScraper().fetchText(publicTarget, 1000));
            jsoup.verify(() -> Jsoup.connect(privateTarget), never());
        }
        assertEquals("non_public_address", TraceStore.get("web.pageScraper.fetchText.blockedReason"));
        assertEquals(true, TraceStore.get("web.pageScraper.fetchText.wireAttemptObserved"));
        assertFalse(TraceStore.getAll().toString().contains("internal-secret"));
    }

    @Test
    void redirectBodyCloseFailureIsFailSoftAndLeavesRedactedBreadcrumb() throws Exception {
        String publicTarget = "http://93.184.216.34/start";
        String privateTarget = "http://127.0.0.1/internal-secret";
        Connection connection = mockConnection();
        Connection.Response redirect = mock(Connection.Response.class);
        BufferedInputStream body = mock(BufferedInputStream.class);
        when(connection.execute()).thenReturn(redirect);
        when(redirect.statusCode()).thenReturn(302);
        when(redirect.header("Location")).thenReturn(privateTarget);
        when(redirect.bodyStream()).thenReturn(body);
        doThrow(new IOException("private-close-secret")).when(body).close();

        TraceStore.clear();
        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(publicTarget)).thenReturn(connection);

            assertNull(new PageContentScraper().fetchText(publicTarget, 1000));
            jsoup.verify(() -> Jsoup.connect(privateTarget), never());
        }
        assertEquals(true, TraceStore.get("web.pageScraper.redirectBodyCloseFailed"));
        assertEquals(1L, TraceStore.get("web.pageScraper.redirectBodyCloseFailed.count"));
        assertEquals("IOException", TraceStore.get("web.pageScraper.redirectBodyCloseFailed.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("private-close-secret"));
    }

    @Test
    void publicRedirectIsValidatedThenFetched() throws Exception {
        String firstTarget = "http://93.184.216.34/start";
        String secondTarget = "http://93.184.216.35/final";
        Connection firstConnection = mockConnection();
        Connection secondConnection = mockConnection();
        Connection.Response redirect = mock(Connection.Response.class);
        Connection.Response success = mock(Connection.Response.class);
        Document oldAutomaticResult = mock(Document.class);
        Document finalDocument = mock(Document.class);
        when(oldAutomaticResult.text()).thenReturn("unvalidated automatic result");
        when(finalDocument.text()).thenReturn("  validated public result  ");
        when(firstConnection.get()).thenReturn(oldAutomaticResult);
        when(firstConnection.execute()).thenReturn(redirect);
        when(redirect.statusCode()).thenReturn(302);
        when(redirect.header("Location")).thenReturn(secondTarget);
        when(secondConnection.execute()).thenReturn(success);
        when(success.statusCode()).thenReturn(200);
        when(success.parse()).thenReturn(finalDocument);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(firstTarget)).thenReturn(firstConnection);
            jsoup.when(() -> Jsoup.connect(secondTarget)).thenReturn(secondConnection);

            assertEquals("validated public result", new PageContentScraper().fetchText(firstTarget, 1000));
        }
    }

    private static Connection mockConnection() throws IOException {
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.followRedirects(false)).thenReturn(connection);
        return connection;
    }
}
