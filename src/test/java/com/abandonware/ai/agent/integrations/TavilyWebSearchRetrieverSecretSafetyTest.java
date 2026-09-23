package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyWebSearchRetrieverSecretSafetyTest {

    @Test
    void placeholderApiKeyDisablesRetrieverWithoutOutboundCall() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = countingServer(hits);
        try {
            TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(
                    HttpClient.newHttpClient(),
                    endpoint(server),
                    () -> "dummy");

            assertThat(retriever.isEnabled("web")).isFalse();
            assertThat(retriever.search("private tavily query", 3, "web")).isEmpty();
            assertThat(hits).hasValue(0);
            assertThat(TraceStore.get("web.tavily.providerDisabled")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("web.tavily.skipped")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("web.tavily.skipped.reason")).isEqualTo("missing_tavily_api_key");
            assertThat(TraceStore.get("web.tavily.failureReason")).isEqualTo("provider-disabled");
            assertThat(String.valueOf(TraceStore.getAll())).doesNotContain("private tavily query", "dummy");
        } finally {
            server.stop(0);
            TraceStore.clear();
        }
    }

    @Test
    void disabledDomainAddsSkippedReasonWithoutOutboundCall() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = countingServer(hits);
        try {
            TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(
                    HttpClient.newHttpClient(),
                    endpoint(server),
                    () -> "tvly-live-key");

            assertThat(retriever.isEnabled("local")).isFalse();
            assertThat(retriever.search("private tavily domain query", 3, "local")).isEmpty();
            assertThat(hits).hasValue(0);
            assertThat(TraceStore.get("web.tavily.providerDisabled")).isEqualTo(Boolean.FALSE);
            assertThat(TraceStore.get("web.tavily.skipped")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("web.tavily.skipped.reason")).isEqualTo("domain_not_enabled");
            assertThat(TraceStore.get("web.tavily.failureReason")).isEqualTo("domain-disabled");
            assertThat(String.valueOf(TraceStore.getAll()))
                    .doesNotContain("private tavily domain query", "tvly-live-key");
        } finally {
            server.stop(0);
            TraceStore.clear();
        }
    }

    @Test
    void emptyResultsAddsProviderEmptyBreadcrumbWithoutRawQuery() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = countingServer(hits);
        try {
            TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(
                    HttpClient.newHttpClient(),
                    endpoint(server),
                    () -> "tvly-live-key");

            assertThat(retriever.search("private tavily empty query", 3, "web")).isEmpty();
            assertThat(hits).hasValue(1);
            assertThat(TraceStore.get("web.tavily.providerEmpty")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("web.tavily.failureReason")).isEqualTo("provider-empty");
            assertThat(TraceStore.get("web.tavily.returnedCount")).isEqualTo(0);
            assertThat(TraceStore.get("web.tavily.afterFilterCount")).isEqualTo(0);
            assertThat(String.valueOf(TraceStore.getAll()))
                    .doesNotContain("private tavily empty query", "tvly-live-key");
        } finally {
            server.stop(0);
            TraceStore.clear();
        }
    }

    @Test
    void rateLimitResponseAddsRateLimitBreadcrumbWithoutRawQuery() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = responseServer(hits, 429, "{\"error\":\"rate limited private tavily body\"}");
        try {
            TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(
                    HttpClient.newHttpClient(),
                    endpoint(server),
                    () -> "tvly-live-key");

            assertThat(retriever.search("private tavily rate query", 3, "web")).isEmpty();
            assertThat(hits).hasValue(1);
            assertThat(TraceStore.get("web.tavily.skipped")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("web.tavily.skipped.reason")).isEqualTo("rate-limit");
            assertThat(TraceStore.get("web.tavily.failureReason")).isEqualTo("rate-limit");
            assertThat(TraceStore.get("web.tavily.rateLimited")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("web.tavily.httpStatus")).isEqualTo(429);
            assertThat(TraceStore.get("web.tavily.returnedCount")).isEqualTo(0);
            assertThat(TraceStore.get("web.tavily.afterFilterCount")).isEqualTo(0);
            assertThat(String.valueOf(TraceStore.getAll()))
                    .doesNotContain("private tavily rate query", "rate limited private tavily body", "tvly-live-key");
        } finally {
            server.stop(0);
            TraceStore.clear();
        }
    }

    @Test
    void ioFailureAddsRedactedBreadcrumbWithoutRawQuery() {
        TraceStore.clear();
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(
                new FailingHttpClient(new IOException("boom private tavily query")),
                "http://tavily.local/search?api_key=secret-token",
                () -> "tvly-live-key");

        assertThat(retriever.search("private tavily query", 3, "web")).isEmpty();

        assertThat(TraceStore.get("agent.tavily.suppressed")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("agent.tavily.suppressed.stage")).isEqualTo("search");
        assertThat(TraceStore.get("agent.tavily.suppressed.errorType")).isEqualTo("IOException");
        assertThat(String.valueOf(TraceStore.get("agent.tavily.suppressed.queryHash"))).startsWith("hash:");
        assertThat(TraceStore.get("agent.tavily.suppressed.queryLength")).isEqualTo(20);
        assertThat(String.valueOf(TraceStore.getAll())).doesNotContain("private tavily query", "secret-token");
    }

    @Test
    void interruptedFailureRestoresInterruptAndAddsBreadcrumb() {
        TraceStore.clear();
        Thread.interrupted();
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(
                new FailingHttpClient(new InterruptedException("provider interrupted")),
                "http://tavily.local/search",
                () -> "tvly-live-key");
        try {
            assertThat(retriever.search("interruptible tavily query", 3, "web")).isEmpty();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(TraceStore.get("agent.tavily.suppressed")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("agent.tavily.suppressed.stage")).isEqualTo("search.interrupted");
            assertThat(TraceStore.get("agent.tavily.suppressed.errorType")).isEqualTo("cancelled");
            assertThat(TraceStore.get("web.tavily.failureReason")).isEqualTo("cancelled");
            assertThat(TraceStore.get("web.tavily.skipped.reason")).isEqualTo("cancelled");
        } finally {
            Thread.interrupted();
            TraceStore.clear();
        }
    }

    private static HttpServer countingServer(AtomicInteger hits) throws IOException {
        return responseServer(hits, 200, "{\"results\":[]}");
    }

    private static HttpServer responseServer(AtomicInteger hits, int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/search", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/search";
    }

    private static final class FailingHttpClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newHttpClient();
        private final Exception failure;

        private FailingHttpClient(Exception failure) {
            this.failure = failure;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new AssertionError(failure);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            CompletableFuture<HttpResponse<T>> future = new CompletableFuture<>();
            future.completeExceptionally(failure);
            return future;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }
}
