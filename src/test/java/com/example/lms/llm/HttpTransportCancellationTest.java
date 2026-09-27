package com.example.lms.llm;

import com.example.lms.api.ChatCancellationCommandHandler;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class HttpTransportCancellationTest {
    @org.junit.jupiter.api.Test void completedCallDoesNotInterruptReusedWorker() throws Exception {
        var registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var run = registry.beginOrJoin(72002L).context();
        CountDownLatch completed = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try (var binding = ChatRunExecutionContext.bind(run)) {
                for (int i = 0; i < 16; i++) {
                    try (var call = ChatRunExecutionContext.interruptibleCall("jdk_http")) { /* completed call */ }
                }
                completed.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("test_release_timeout");
            } catch (Throwable t) { failure.set(t); completed.countDown(); }
        });
        try {
            worker.start(); assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(new ChatCancellationCommandHandler().cancel(72002L, run.clientToken(), () -> true, registry, () -> {}).cancelled()).isTrue();
            release.countDown(); worker.join(3000);
            assertThat(failure.get()).isNull(); assertThat(worker.isAlive()).isFalse();
        } finally { release.countDown(); worker.join(3000); ReflectionTestUtils.invokeMethod(registry, "shutdown"); }
    }

    @ParameterizedTest @ValueSource(strings = {"sdk", "native", "embedding"})
    void expiredBudgetDoesNotStartANewHttpCall(String transport) throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"message\":{\"content\":\"fixture\"},\"embeddings\":[[1,0]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(1));
            Thread.sleep(10);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                var tracker = new ModelRuntimeHealthTracker();
                if ("sdk".equals(transport)) {
                    tracker.observedHttpClientBuilder("primary").build().execute(dev.langchain4j.http.client.HttpRequest.builder()
                            .method(dev.langchain4j.http.client.HttpMethod.POST).url(base).body("{}").build());
                } else if ("native".equals(transport)) {
                    new OllamaNativeChatModel(base, "fixture", Duration.ofSeconds(3), 32, 0.0, null, tracker)
                            .chat(List.of(dev.langchain4j.data.message.UserMessage.from("fixture")));
                } else {
                    var embedding = new com.example.lms.service.embedding.OllamaEmbeddingModel(
                            org.springframework.web.reactive.function.client.WebClient.create());
                    ReflectionTestUtils.setField(embedding, "apiUrl", base + "/api/embed");
                    ReflectionTestUtils.invokeMethod(embedding, "postJsonWithFallback", java.util.Map.of("input", "fixture"), 3L);
                }
            }).isInstanceOf(RuntimeException.class);
            assertThat(calls).hasValue(0);
        } finally { com.abandonware.ai.addons.budget.TimeBudgetContext.clear(); server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"sdk", "native", "embedding"})
    void exactRunCancellationClosesHeldHttpConnection(String transport) throws Exception {
        assertTransportCloses(transport, false);
    }

    @ParameterizedTest @ValueSource(strings = {"sdk", "native", "embedding"})
    void lastInteractiveDisconnectClosesUpstreamWithinOneSecond(String transport) throws Exception {
        assertTransportCloses(transport, true);
    }

    private void assertTransportCloses(String transport, boolean disconnect) throws Exception {
        CountDownLatch received = new CountDownLatch(1), socketClosed = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var run = registry.beginOrJoin(72001L).context();
        var interactive = disconnect ? registry.attachInteractiveExact(72001L, run.clientToken()).orElseThrow().subscribe() : null;
        var tracker = new ModelRuntimeHealthTracker();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            var receiver = Executors.newSingleThreadExecutor();
            Future<?> peer = receiver.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var in = socket.getInputStream();
                    var header = new StringBuilder();
                    while (!header.toString().endsWith("\r\n\r\n") && header.length() < 16384) {
                        int b = in.read(); if (b < 0) throw new java.io.IOException("premature_eof"); header.append((char)b);
                    }
                    var length = java.util.regex.Pattern.compile("(?im)^content-length: *([0-9]+)").matcher(header);
                    if (length.find()) in.readNBytes(Integer.parseInt(length.group(1)));
                    received.countDown();
                    try { if (in.read() == -1) socketClosed.countDown(); }
                    catch (SocketException reset) { socketClosed.countDown(); }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            String base = "http://127.0.0.1:" + server.getLocalPort();
            Thread worker = new Thread(() -> {
                try (var binding = ChatRunExecutionContext.bind(run)) {
                    if ("sdk".equals(transport)) {
                        tracker.observedHttpClientBuilder("primary").connectTimeout(Duration.ofSeconds(2))
                                .readTimeout(Duration.ofSeconds(6)).build().execute(dev.langchain4j.http.client.HttpRequest.builder()
                                .method(dev.langchain4j.http.client.HttpMethod.POST).url(base + "/v1/chat/completions").body("{}").build());
                    } else if ("native".equals(transport)) {
                        new OllamaNativeChatModel(base, "fixture", Duration.ofSeconds(6), 32, 0.0, null, tracker)
                                .chat(List.of(dev.langchain4j.data.message.UserMessage.from("fixture")));
                    } else {
                        var model = new com.example.lms.service.embedding.OllamaEmbeddingModel(
                                org.springframework.web.reactive.function.client.WebClient.create());
                        ReflectionTestUtils.setField(model, "apiUrl", base + "/api/embed");
                        ReflectionTestUtils.invokeMethod(model, "postJsonWithFallback", java.util.Map.of("input", "fixture"), 6L);
                    }
                } catch (Throwable t) { failure.set(t); }
                finally { Thread.interrupted(); exited.countDown(); }
            }, "exact-http-cancel-fixture");
            try {
                worker.start();
                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(exited.getCount()).as("upstream caller is still blocked before cancellation").isEqualTo(1);
                assertThat(socketClosed.getCount()).as("peer socket is still open before cancellation").isEqualTo(1);
                long start = System.nanoTime();
                if (disconnect) interactive.dispose();
                else assertThat(new ChatCancellationCommandHandler().cancel(72001L, run.clientToken(), () -> true, registry, () -> {}).cancelled()).isTrue();
                long allowance = TimeUnit.SECONDS.toNanos(disconnect ? 1 : 2);
                assertThat(exited.await(Math.max(1, allowance - (System.nanoTime() - start)), TimeUnit.NANOSECONDS)).as("caller released before provider response/timeout").isTrue();
                assertThat(socketClosed.await(Math.max(1, allowance - (System.nanoTime() - start)), TimeUnit.NANOSECONDS)).as("peer observed EOF/reset, independently of app completion").isTrue();
                if (disconnect) System.out.println("disconnectFixture transport=" + transport + " upstreamClosedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                assertThat(failure.get()).isNotNull();
                peer.get(1, TimeUnit.SECONDS);
            } finally {
                if (interactive != null) interactive.dispose();
                worker.interrupt(); worker.join(7000);
                receiver.shutdownNow(); receiver.awaitTermination(6, TimeUnit.SECONDS);
                ReflectionTestUtils.invokeMethod(registry, "shutdown");
            }
        }
    }
}
