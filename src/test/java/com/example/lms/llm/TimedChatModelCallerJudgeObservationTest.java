package com.example.lms.llm;

import com.abandonware.ai.addons.budget.*;
import com.example.lms.dto.GenerationObservation;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static com.example.lms.llm.JudgeCallObservationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TimedChatModelCallerJudgeObservationTest {
    @AfterEach void cleanup(){TraceStore.clear();TimeBudgetContext.clear();}
    @Test void timedJudgeKeepsMainGenerationIdentity(){
        var main=new GenerationObservation("synthetic","main-M",null,0,null,null);main.store();
        TraceStore.putInternal("llm.call.responseModel","main-M");
        TimeBudgetContext.set(new TimeBudget(2000));fact(model(()->response("PASS","judge-J")));
        assertEquals(main,GenerationObservation.current());assertEquals("main-M",TraceStore.get("llm.call.responseModel"));
        assertEquals("judge-J",receipt("fact_status_classifier").get("responseModel"));
    }
    @Test void timedOutLateWorkerCannotOverwriteNextReceipt() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);
        try {
            TimeBudgetContext.set(new TimeBudget(100));
            fact(model(()->{entered.countDown();while(true){try{release.await();break;}catch(InterruptedException ignored){}}
                try{return response("PASS","late-J");}finally{exited.countDown();}}));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertNull(receipt("fact_status_classifier").get("httpStatus"));assertEquals(false,receipt("fact_status_classifier").get("outcomeKnown"));
            TimeBudgetContext.clear();fact(model(()->response("PASS","next-J")));
            var before=receipt("fact_status_classifier");release.countDown();assertTrue(exited.await(1,TimeUnit.SECONDS));
            assertEquals(before,receipt("fact_status_classifier"));assertEquals("next-J",before.get("responseModel"));
        } finally {release.countDown();}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {200, 401, 403, 429})
    void observedHttpSeamCountsWireAndPreservesMainIdentity(int status) throws Exception {
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String body = status == 200
                    ? "{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"judge-J\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"PASS\"},\"finish_reason\":\"stop\"}]}"
                    : "{\"error\":{\"message\":\"synthetic private body\",\"type\":\"fixture\"}}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var tracker = new ModelRuntimeHealthTracker();
            var client = dev.langchain4j.model.openai.OpenAiChatModel.builder().baseUrl(base).modelName("fixture")
                    .apiKey("fixture").maxRetries(0).timeout(java.time.Duration.ofSeconds(2))
                    .httpClientBuilder(tracker.observedHttpClientBuilder("judge")).build();
            var wrapped = tracker.decorateRequestAttempt(client, "judge",
                    tracker.redactedRequestAttemptRoute("fixture", "fixture", base, "openai"), java.util.Map.of());
            var main = new GenerationObservation("synthetic", "main-M", "primary", 0, null, null);
            main.store();
            TraceStore.putInternal("llm.call.responseModel", "main-M");
            TimeBudgetContext.set(new TimeBudget(2000));
            fact(wrapped);
            var receipt = receipt("fact_status_classifier");
            assertEquals(1, received.get());
            assertEquals(1, receipt.get("httpAttemptCount"));
            assertEquals(status, receipt.get("httpStatus"));
            assertEquals(status == 200, receipt.get("outcomeKnown"));
            assertNull(receipt.get("observedProvider"));
            assertEquals(main, GenerationObservation.current());
            assertEquals("main-M", TraceStore.get("llm.call.responseModel"));
            assertNull(JudgeCallObservation.current());
            assertFalse(receipt.toString().contains("synthetic private body"));
        } finally {
            server.stop(0);
        }
    }

    @Test void executorRejectionHasKnownZeroWireWithoutModelInvocation() throws Exception {
        TimedChatModelCaller.resetSharedExecutorForTest(1, 1);
        var callers = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocking = model(() -> { entered.countDown(); release.await(); return response("PASS", "fixture"); });
        Callable<Object> call = () -> TimedChatModelCaller.chat(blocking,
                java.util.List.of(dev.langchain4j.data.message.UserMessage.from("synthetic")),
                java.time.Duration.ofSeconds(5), "fixture", "fixture");
        var first = callers.submit(call);
        Future<Object> second = null;
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            second = callers.submit(call);
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (TimedChatModelCaller.sharedExecutorMetricsForTest().get("queued") == 0 && System.nanoTime() < limit) {
                Thread.sleep(10);
            }
            assertEquals(1, TimedChatModelCaller.sharedExecutorMetricsForTest().get("queued"));
            TimeBudgetContext.set(new TimeBudget(2000));
            fact(model(() -> { fail("rejected model must not run"); return response("PASS", "fixture"); }));
            var receipt = receipt("fact_status_classifier");
            assertEquals(false, receipt.get("invocationStarted"));
            assertEquals(0, receipt.get("httpAttemptCount"));
            assertEquals(false, receipt.get("outcomeKnown"));
            assertEquals("executor_saturated", receipt.get("reason"));
        } finally {
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            if (second != null) second.get(2, TimeUnit.SECONDS);
            callers.shutdownNow();
            assertTrue(TimedChatModelCaller.shutdownSharedExecutorForTest(1000));
        }
    }
}
