package com.example.lms.assist;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class JevGatewayClientBodyDeadlineTest {
    @ParameterizedTest @ValueSource(ints = {200, 403})
    void headersThenStalledBodyTimesOut(int status) throws Exception {
        try (Fixture f = new Fixture(status, false, 1)) {
            var client = new JevGatewayClient(name -> "fixture");
            client.evaluate(f.request(65536));
            f.stalls.set(1);
            Future<JevDecisionAdvisor.EvalResponse> call = f.clients.submit(() -> client.evaluate(f.request(65536)));
            assertTrue(f.started.await(2, TimeUnit.SECONDS));
            assertEquals("timeout", call.get(1200, TimeUnit.MILLISECONDS).failure());
            assertEquals(JevDecisionAdvisor.Verdict.WEB, client.evaluate(f.request(65536)).verdict());
        }
    }
    @Test void slowTrickleCannotExtendTotalDeadline() throws Exception {
        try (Fixture f = new Fixture(200, true, 1)) {
            var client = new JevGatewayClient(name -> "fixture");
            client.evaluate(f.request(65536)); f.stalls.set(1);
            Future<JevDecisionAdvisor.EvalResponse> call = f.clients.submit(() -> client.evaluate(f.request(65536)));
            assertTrue(f.started.await(2, TimeUnit.SECONDS));
            assertEquals("timeout", call.get(1200, TimeUnit.MILLISECONDS).failure());
        }
    }
    @ParameterizedTest @ValueSource(ints = {200, 403})
    void oversizedResponseStopsBeforeParsing(int status) throws Exception {
        try (Fixture f = new Fixture(status, false, 0)) {
            f.normalStatus = status; f.response = "x".repeat(1024);
            assertEquals("oversized_response", new JevGatewayClient(name -> "fixture").evaluate(f.request(32)).failure());
        }
    }
    @Test void completeResponsePreservesChoiceAndModel() throws Exception {
        try (Fixture f = new Fixture(200, false, 0)) {
            var result = new JevGatewayClient(name -> "fixture").evaluate(f.request(65536));
            assertEquals(200, result.httpStatus()); assertNull(result.failure());
            assertEquals(JevDecisionAdvisor.Verdict.WEB, result.verdict());
        }
    }
    @Test void interruptionCancelsAndPreservesCallerFlag() throws Exception {
        try (Fixture f = new Fixture(200, false, 1)) {
            var client = new JevGatewayClient(name -> "fixture");
            client.evaluate(f.request(65536)); f.stalls.set(1);
            AtomicReference<Thread> caller = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Future<JevDecisionAdvisor.EvalResponse> call = f.clients.submit(() -> {
                caller.set(Thread.currentThread());
                var result = client.evaluate(f.request(65536));
                interrupted.set(Thread.currentThread().isInterrupted()); return result;
            });
            assertTrue(f.started.await(2, TimeUnit.SECONDS)); caller.get().interrupt();
            assertEquals("cancelled", call.get(1200, TimeUnit.MILLISECONDS).failure());
            assertTrue(interrupted.get());
        }
    }
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService handlers = Executors.newCachedThreadPool(), clients = Executors.newCachedThreadPool();
        final AtomicInteger stalls = new AtomicInteger(), hits = new AtomicInteger(), active = new AtomicInteger();
        final CountDownLatch started, release = new CountDownLatch(1);
        volatile int normalStatus = 200;
        volatile String response = "{\"model\":\"typesafe-ai/jev\",\"answers\":{\"routeDecision\":{\"choice\":\"WEB\"}}}";
        Fixture(int stalledStatus, boolean trickle, int stalledRequests) throws Exception {
            started = new CountDownLatch(stalledRequests);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(handlers);
            server.createContext("/v1/evaluate", exchange -> {
                active.incrementAndGet(); hits.incrementAndGet();
                try {
                    exchange.getRequestBody().readAllBytes();
                    boolean stall = stalls.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
                    if (stall) {
                        exchange.sendResponseHeaders(stalledStatus, 0);
                        exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); started.countDown();
                        if (trickle) {
                            while (!release.await(50, TimeUnit.MILLISECONDS)) {
                                exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
                            }
                        } else release.await(5, TimeUnit.SECONDS);
                    } else {
                        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(normalStatus, bytes.length); exchange.getResponseBody().write(bytes);
                    }
                } catch (java.io.IOException closed) { /* client cancelled its owned transport */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); active.decrementAndGet(); }
            }); server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/evaluate"; }
        JevDecisionAdvisor.EvalRequest request(int limit) {
            return new JevDecisionAdvisor.EvalRequest(endpoint(), "typesafe-ai/jev", "FIXTURE", "synthetic",
                    "main", "RECENT_ONLY", 250, 300, 8192, limit);
        }
        public void close() throws Exception {
            release.countDown(); server.stop(0); handlers.shutdown(); clients.shutdown();
            assertTrue(handlers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(clients.awaitTermination(3, TimeUnit.SECONDS)); assertEquals(0, active.get());
        }
    }
}

