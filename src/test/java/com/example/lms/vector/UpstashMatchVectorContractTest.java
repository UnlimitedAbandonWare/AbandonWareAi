package com.example.lms.vector;

import com.example.lms.service.vector.UpstashVectorStoreAdapter;

import com.example.lms.vector.FederatedEmbeddingStore;
import com.example.lms.vector.TopicRoutingSettings;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class UpstashMatchVectorContractTest {
    @Test void langchainAcceptsUnavailableMatchedVector() {
        var match = new EmbeddingMatch<>(0.8, "doc", null, TextSegment.from("text"));
        assertNull(match.embedding()); assertEquals("text", match.embedded().text());
    }
    @Test void searchNeverRepresentsQueryVectorAsTwoDocumentVectors() throws Exception {
        var sent = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", e -> {
            sent.set(new ObjectMapper().readTree(e.getRequestBody()));
            byte[] data = ("{\"result\":[{\"id\":\"a\",\"score\":0.9,\"data\":\"alpha\",\"metadata\":{\"source\":\"A\"}},"
                    + "{\"id\":\"b\",\"score\":0.8,\"data\":\"beta\",\"metadata\":{\"source\":\"B\"}}]}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, data.length); e.getResponseBody().write(data); e.close();
        }); server.start();
        try {
            var adapter = new UpstashVectorStoreAdapter(WebClient.builder().build());
            ReflectionTestUtils.setField(adapter, "restUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(adapter, "apiKey", "fixture");
            ReflectionTestUtils.setField(adapter, "namespace", "");
            var request = EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1,0}))
                    .maxResults(2).minScore(0.0).build();
            var matches = adapter.search(request).matches();
            assertEquals(2, matches.size());
            assertFalse(sent.get().path("includeVectors").asBoolean());
            assertEquals(List.of("alpha", "beta"), matches.stream().map(m -> m.embedded().text()).toList());
            assertEquals(List.of("a", "b"), matches.stream().map(EmbeddingMatch::embeddingId).toList());
            assertEquals("A", matches.get(0).embedded().metadata().getString("source"));
            assertEquals(0.9, matches.get(0).score());
            assertTrue(matches.stream().allMatch(m -> m.embedding() == null));
            var federated = new FederatedEmbeddingStore(
                    List.of(new FederatedEmbeddingStore.NamedStore("upstash", adapter)),
                    new TopicRoutingSettings(Map.of("default", Map.of("upstash", 1.0)), 1), 3000, 1);
            try {
                var merged = federated.search(request).matches();
                assertEquals(2, merged.size());
                assertTrue(merged.stream().allMatch(m -> m.embedding() == null));
            } finally { federated.shutdownPool(); }
        } finally { server.stop(0); }
    }
}
