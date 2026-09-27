package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteEmbedderInterruptTest {

    @AfterEach
    void tearDown() {
        Thread.interrupted();
        TraceStore.clear();
    }

    @Test
    void embedReusesTheConfiguredHttpClientAcrossCalls() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = response(200, "{\"embedding\":[1.0,0.0]}");
        when(client.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);
        HttpServer server = jsonServer("{\"embedding\":[1.0,0.0]}");
        try {
            RemoteEmbedder embedder = embedder(client, endpoint(server));

            assertThat(embedder.embed("first synthetic text")).containsExactly(1.0f, 0.0f);
            assertThat(embedder.embed("second synthetic text")).containsExactly(1.0f, 0.0f);

            verify(client, times(2)).send(any(HttpRequest.class), anyStringBodyHandler());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedRestoresInterruptFlagBeforeReturningFallback() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenThrow(new InterruptedException("synthetic interrupt"));
        HttpServer server = jsonServer("{\"embedding\":[1.0,0.0]}");
        try {
            RemoteEmbedder embedder = embedder(client, endpoint(server));

            float[] vector = embedder.embed("synthetic interrupted text");

            assertThat(vector).hasSize(256);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static RemoteEmbedder embedder(HttpClient client, String endpoint) {
        RemoteEmbedder embedder = new RemoteEmbedder();
        ReflectionTestUtils.setField(embedder, "client", client);
        ReflectionTestUtils.setField(embedder, "apiUrl", endpoint);
        ReflectionTestUtils.setField(embedder, "apiKind", "tei");
        ReflectionTestUtils.setField(embedder, "apiKey", "dummy");
        return embedder;
    }

    private static HttpResponse<String> response(int statusCode, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static HttpResponse.BodyHandler<String> anyStringBodyHandler() {
        return any();
    }

    private static HttpServer jsonServer(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/embed", exchange -> {
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
