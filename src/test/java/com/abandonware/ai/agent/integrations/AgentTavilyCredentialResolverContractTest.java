package com.abandonware.ai.agent.integrations;

import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTavilyCredentialResolverContractTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void conflictingAliasesDisableOnlyAgentTavilyWithoutWireAttempt() throws Exception {
        String first = "agent-tavily-secret-a";
        String second = "agent-tavily-secret-b";
        ProviderCredentialResolver resolver = resolver(first, second);
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = countingServer(hits);
        try {
            TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(
                    HttpClient.newHttpClient(), endpoint(server), resolver);

            assertThat(retriever.isEnabled("web")).isFalse();
            assertThat(retriever.search("private agent tavily conflict", 3, "web")).isEmpty();
            assertThat(hits).hasValue(0);
            assertThat(TraceStore.get("web.tavily.providerDisabled")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("web.tavily.disabledReason"))
                    .isEqualTo("conflicting-credential-aliases");
            assertThat(String.valueOf(TraceStore.getAll())).doesNotContain(first, second);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void equalAliasesRemainEnabledWithoutResolvingAnArbitrarySource() {
        String shared = "agent-tavily-shared-secret";
        ProviderCredentialResolver resolver = resolver(shared, shared);
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(resolver);

        assertThat(retriever.isEnabled("web")).isTrue();
        assertThat(String.valueOf(TraceStore.getAll())).doesNotContain(shared);
    }

    @Test
    void springHybridConstructorPassesResolverToAgentTavilyClient() {
        ProviderCredentialResolver resolver = resolver("agent-tavily-value", null);

        HybridRetriever hybrid = new HybridRetriever(resolver);
        Object tavily = ReflectionTestUtils.getField(hybrid, "tavily");

        assertThat(tavily).isInstanceOf(TavilyWebSearchRetriever.class);
        assertThat(ReflectionTestUtils.getField(tavily, "credentialResolver")).isSameAs(resolver);
    }

    @Test
    void defaultConstructorNoLongerReadsTavilyEnvironmentDirectly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/abandonware/ai/agent/integrations/TavilyWebSearchRetriever.java"));

        assertThat(source).doesNotContain("System.getenv(\"TAVILY_API_KEY\")");
    }

    private static ProviderCredentialResolver resolver(String propertyValue, String environmentValue) {
        MockEnvironment environment = new MockEnvironment();
        if (propertyValue != null) {
            environment.setProperty("tavily.api.key", propertyValue);
        }
        if (environmentValue != null) {
            environment.setProperty("TAVILY_API_KEY", environmentValue);
        }
        return new ProviderCredentialResolver(environment);
    }

    private static HttpServer countingServer(AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/search", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = "{\"results\":[]}".getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/search";
    }
}
