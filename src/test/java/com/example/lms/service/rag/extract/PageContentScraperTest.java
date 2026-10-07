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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PageContentScraperTest {

    @Test
    void fetchTextPreservesMiddleTableColumnsAsSeparateQualifiedPassages() throws Exception {
        String html = "<body><main><article><p>" + "소개 자료 ".repeat(150) + "</p>"
                + "<table><tr><th>무기</th><td>은빛 서약의 활</td><td>청동 나침의 창</td></tr>"
                + "<tr><th>소유자</th><td>카렐로바 전용 무기</td><td>밀로슈 전용 무기</td></tr>"
                + "<tr><th>상태</th><td>비공식 추정</td><td>공식 확정 아님</td></tr></table>"
                + "<p>" + "기타 안내 ".repeat(150) + "</p></article></main></body>";
        String text = fetchHtml(html);
        assertTrue(text.lines().anyMatch(line -> line.contains("은빛 서약의 활")
                && line.contains("카렐로바 전용 무기") && line.contains("비공식 추정")
                && !line.contains("밀로슈") && !line.contains("청동 나침의 창")),
                "same-column relation and qualifier must stay together without another subject");
        assertTrue(text.lines().anyMatch(line -> line.contains("밀로슈 전용 무기")
                && line.contains("청동 나침의 창") && !line.contains("카렐로바")));
    }

    @Test
    void fetchTextDoesNotPromoteAnArticleInsideComplementaryOrHiddenContent() throws Exception {
        for (String wrapper : List.of("aside", "nav", "div hidden", "div aria-hidden='true'", "div role='complementary'")) {
            String closingTag = wrapper.split(" ")[0];
            String html = "<body><main><p>S449_BODY_SENTINEL visible qualified body</p><" + wrapper
                    + "><article>AD_NOISE</article></" + closingTag + "></main></body>";
            assertEquals("S449_BODY_SENTINEL visible qualified body", fetchHtml(html));
        }
    }

    @Test
    void fetchTextSelectsQualifiedArticleInsteadOfQueryMatchingChrome() throws Exception {
        String qualifiedBody = "S449_BODY_SENTINEL 베스나 배경 능력은 합성 예시이며, "
                + "비공식 커뮤니티의 2026-10-01 당시 추정으로 공식 확정은 아니다.";
        String noise = "베스나 배경 능력 메뉴 " + "채널안내 ".repeat(90);
        String html = "<html><body><header>HEADER_NOISE " + noise + "</header>"
                + "<main><nav>NAV_NOISE " + noise + "</nav><article>"
                + "<header><h1>합성 자료</h1></header><p>" + qualifiedBody + "</p>"
                + "<aside>COMMENT_NOISE " + noise + "</aside></article>"
                + "<aside>AD_NOISE " + noise + "</aside></main>"
                + "<footer>OTHER_POST_NOISE " + noise + "</footer></body></html>";
        TraceStore.clear();

        String text = fetchHtml(html);

        assertTrue(text.contains(qualifiedBody), "body, date and unofficial qualifier remain together");
        for (String marker : List.of("HEADER_NOISE", "NAV_NOISE", "COMMENT_NOISE", "AD_NOISE", "OTHER_POST_NOISE")) {
            assertFalse(text.contains(marker), "semantic chrome must not compete with the article");
        }
        assertEquals(true, TraceStore.get("web.pageScraper.fetchText.wireAttemptObserved"));
        assertFalse(TraceStore.getAll().toString().contains("S449_BODY_SENTINEL"));
    }

    @Test
    void fetchTextKeepsUnstructuredAndAmbiguousPagesAsFallbacks() throws Exception {
        for (String html : List.of("<body><p>unstructured body</p></body>",
                "<body><main></main><p>fallback body</p></body>",
                "<body><article>first body</article><article>second body</article></body>")) {
            assertEquals(Jsoup.parse(html).text(), fetchHtml(html));
        }
        assertEquals("", fetchHtml("<body><main></main></body>"));
    }

    @Test
    void fetchTextRecognizesSemanticBodiesAndPreservesTheirPublicationHeaders() throws Exception {
        for (String tag : List.of("main", "article", "div role='main'")) {
            String closingTag = tag.split(" ")[0];
            String html = "<body><header>outside header</header><" + tag + ">"
                    + "<header>비공식 <time>2026-10-01</time></header><p>S449_BODY_SENTINEL 당시 추정</p>"
                    + "<div role='complementary'>AD_NOISE</div><p hidden>HIDDEN_NOISE</p>"
                    + "</" + closingTag + "><footer>outside footer</footer></body>";
            assertEquals("비공식 2026-10-01 S449_BODY_SENTINEL 당시 추정", fetchHtml(html));
        }
    }

    private static String fetchHtml(String html) throws Exception {
        String target = "http://93.184.216.34/fixture";
        Document document = Jsoup.parse(html);
        Connection connection = mockConnection();
        Connection.Response response = mock(Connection.Response.class);
        when(connection.execute()).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.parse()).thenReturn(document);
        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(target)).thenReturn(connection);
            return new PageContentScraper().fetchText(target, 1000);
        }
    }

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
