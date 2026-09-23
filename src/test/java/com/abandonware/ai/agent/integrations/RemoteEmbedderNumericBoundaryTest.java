package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RemoteEmbedderNumericBoundaryTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void finiteLargeComponentsNormalizeWithoutFloatSquareOverflow() throws Exception {
        float[] vector = embedResponse("{\"embedding\":[1.0e20,0]}");
        assertArrayEquals(new float[] {1.0f, 0.0f}, vector, 1.0e-6f);
    }

    @Test
    void representableComponentsNormalizeWhenNormExceedsFloatRange() throws Exception {
        float[] vector = embedResponse("{\"embedding\":[3.0e38,3.0e38]}");
        assertArrayEquals(new float[] {0.70710677f, 0.70710677f}, vector, 1.0e-6f);
    }

    @Test
    void unrepresentableComponentsUseExistingFallbackInsteadOfReturningNan() throws Exception {
        float[] vector = embedResponse("{\"embedding\":[1.0e100,1]}");
        assertEquals(256, vector.length, "invalid remote vector must use the existing fallback");
        for (float value : vector) assertTrue(Float.isFinite(value));
    }

    @Test
    void ordinaryVectorRetainsUnitNormalization() throws Exception {
        assertArrayEquals(new float[] {0.6f, 0.8f},
                embedResponse("{\"embedding\":[3,4]}"), 1.0e-6f);
    }

    private static float[] embedResponse(String json) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] response = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var embedder = new RemoteEmbedder();
            ReflectionTestUtils.setField(embedder, "apiUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/embed");
            ReflectionTestUtils.setField(embedder, "apiKind", "tei");
            ReflectionTestUtils.setField(embedder, "apiKey", "");
            return embedder.embed("synthetic vector boundary");
        } finally {
            server.stop(0);
        }
    }
}
