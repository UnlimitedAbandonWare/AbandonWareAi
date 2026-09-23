package com.example.lms.service.vector;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class UpstashVectorStoreAdapterErrorBodyRedactionTest {

    @Test
    void queryErrorBodyIsLoggedAsHashOnly() throws Exception {
        String errorBody = "{\"error\":\"failed api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "\"}";
        HttpServer server = errorServer(errorBody);
        Logger logger = (Logger) LoggerFactory.getLogger(UpstashVectorStoreAdapter.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            UpstashVectorStoreAdapter adapter = new UpstashVectorStoreAdapter(WebClient.builder().build());
            ReflectionTestUtils.setField(adapter, "restUrl", endpoint(server));
            ReflectionTestUtils.setField(adapter, "apiKey", "real-looking-token-for-test");
            ReflectionTestUtils.setField(adapter, "namespace", "");

            adapter.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(new float[] {1.0f}))
                    .maxResults(1)
                    .build());

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertThat(rendered)
                    .doesNotContain(errorBody)
                    .doesNotContain("" + com.example.lms.test.SecretFixtures.openAiKey() + "")
                    .contains("bodyHash=")
                    .contains("bodyLength=");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            server.stop(0);
        }
    }

    private static HttpServer errorServer(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/query", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }
}
