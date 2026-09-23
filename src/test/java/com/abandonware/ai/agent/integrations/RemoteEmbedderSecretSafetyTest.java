package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteEmbedderSecretSafetyTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void remoteEmbedderSuppressesPlaceholderAuthorizationHeader() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = jsonServer(authorization, "{\"embedding\":[1.0,0.0]}");
        try {
            RemoteEmbedder embedder = new RemoteEmbedder();
            ReflectionTestUtils.setField(embedder, "apiUrl", endpoint(server));
            ReflectionTestUtils.setField(embedder, "apiKind", "tei");
            ReflectionTestUtils.setField(embedder, "apiKey", "dummy");

            embedder.embed("private embedding text");

            assertThat(authorization.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteEmbedderFallsBackWhenRemoteReturnsEmptyEmbedding() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = jsonServer(authorization, "{\"embedding\":[]}");
        try {
            RemoteEmbedder embedder = new RemoteEmbedder();
            ReflectionTestUtils.setField(embedder, "apiUrl", endpoint(server));
            ReflectionTestUtils.setField(embedder, "apiKind", "tei");
            ReflectionTestUtils.setField(embedder, "apiKey", "dummy");

            float[] vector = embedder.embed("private embedding text");

            assertThat(vector).hasSize(256);
            assertThat(authorization.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteTokenEmbedderSuppressesPlaceholderAuthorizationHeader() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = jsonServer(authorization, "{\"token_embeddings\":[[1.0,0.0]]}");
        try {
            RemoteTokenEmbedder embedder = new RemoteTokenEmbedder();
            ReflectionTestUtils.setField(embedder, "url", endpoint(server));
            ReflectionTestUtils.setField(embedder, "key", "dummy");

            embedder.embedTokens("private token text");

            assertThat(authorization.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteTokenEmbedderFallsBackWhenRemoteReturnsEmptyTokenEmbeddings() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = jsonServer(authorization, "{\"token_embeddings\":[]}");
        try {
            RemoteTokenEmbedder embedder = new RemoteTokenEmbedder();
            ReflectionTestUtils.setField(embedder, "url", endpoint(server));
            ReflectionTestUtils.setField(embedder, "key", "dummy");

            float[][] vectors = embedder.embedTokens("private token text");

            assertThat(vectors).isNotEmpty();
            assertThat(vectors[0]).hasSize(256);
            assertThat(authorization.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteTokenEmbedderFallsBackWhenRemoteReturnsInvalidTokenVector() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = jsonServer(authorization, "{\"token_embeddings\":[[\"NaN\"]]}");
        try {
            RemoteTokenEmbedder embedder = new RemoteTokenEmbedder();
            ReflectionTestUtils.setField(embedder, "url", endpoint(server));
            ReflectionTestUtils.setField(embedder, "key", "dummy");

            float[][] vectors = embedder.embedTokens("private token text");

            assertThat(vectors).isNotEmpty();
            assertThat(vectors[0]).hasSize(256);
            assertThat(authorization.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteEmbedderFailureFallsBackWithRedactedBreadcrumb() {
        String rawEndpoint = "http://[::1/raw-secret-token";
        String rawText = "private embedding text raw-secret-token";
        RemoteEmbedder embedder = new RemoteEmbedder();
        ReflectionTestUtils.setField(embedder, "apiUrl", rawEndpoint);
        ReflectionTestUtils.setField(embedder, "apiKind", "tei");
        ReflectionTestUtils.setField(embedder, "apiKey", "dummy");

        float[] vector = embedder.embed(rawText);

        assertThat(vector).hasSize(256);
        assertThat(TraceStore.get("agent.remoteEmbedder.suppressed")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("agent.remoteEmbedder.suppressed.stage")).isEqualTo("embed");
        assertThat(TraceStore.get("agent.remoteEmbedder.suppressed.errorType")).isEqualTo("invalid_url");
        assertThat(TraceStore.get("agent.remoteEmbedder.suppressed.textLength")).isEqualTo(rawText.length());
        assertThat(TraceStore.get("agent.remoteEmbedder.suppressed.endpointLength")).isEqualTo(rawEndpoint.length());
        assertThat(String.valueOf(TraceStore.getAll())).doesNotContain(rawEndpoint, rawText, "raw-secret-token");
    }

    @Test
    void remoteTokenEmbedderFailureFallsBackWithRedactedBreadcrumb() {
        String rawEndpoint = "http://[::1/raw-token-endpoint";
        String rawText = "private token text raw-token-endpoint";
        RemoteTokenEmbedder embedder = new RemoteTokenEmbedder();
        ReflectionTestUtils.setField(embedder, "url", rawEndpoint);
        ReflectionTestUtils.setField(embedder, "key", "dummy");

        float[][] vectors = embedder.embedTokens(rawText);

        assertThat(vectors).isNotEmpty();
        assertThat(TraceStore.get("agent.remoteTokenEmbedder.suppressed")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("agent.remoteTokenEmbedder.suppressed.stage")).isEqualTo("embedTokens");
        assertThat(TraceStore.get("agent.remoteTokenEmbedder.suppressed.errorType")).isEqualTo("invalid_url");
        assertThat(TraceStore.get("agent.remoteTokenEmbedder.suppressed.textLength")).isEqualTo(rawText.length());
        assertThat(TraceStore.get("agent.remoteTokenEmbedder.suppressed.endpointLength")).isEqualTo(rawEndpoint.length());
        assertThat(String.valueOf(TraceStore.getAll())).doesNotContain(rawEndpoint, rawText, "raw-token-endpoint");
    }

    private static HttpServer jsonServer(AtomicReference<String> authorization, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/embed", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/embed";
    }
}
