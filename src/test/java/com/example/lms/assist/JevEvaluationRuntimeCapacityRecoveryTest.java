package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Clock;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JevEvaluationRuntimeCapacityRecoveryTest {
    @Test void onModeRecoversBothPermitsAndThirdRequestArrives() throws Exception { recover("on"); }
    @Test void shadowModeRecoversBothPermitsAndThirdRequestArrives() throws Exception { recover("shadow"); }
    @Test void prefetchRecoversPermitsAndNextRequestArrives() throws Exception { recover("prefetch"); }
    private void recover(String mode) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (var f = new JevGatewayClientBodyDeadlineTest.Fixture(200, false, 2)) {
            var client = new JevGatewayClient(name -> "fixture"); client.evaluate(f.request(65536));
            var env = new MockEnvironment().withProperty("demo.jev.mode", "prefetch".equals(mode) ? "on" : mode)
                    .withProperty("demo.jev.allow-paid", "true").withProperty("demo.jev.endpoint", f.endpoint())
                    .withProperty("demo.jev.prefetch.enabled", "true").withProperty("demo.jev.choice.enabled", "true")
                    .withProperty("demo.jev.request-timeout-ms", "300").withProperty("demo.jev.decision-wait-ms", "50");
            try (var runtime = new JevEvaluationRuntime(env, Clock.systemUTC(), client,
                    (request, questions) -> client.evaluateChoices(request, questions), name -> "fixture", workers, System::nanoTime)) {
                f.stalls.set(2);
                if ("prefetch".equals(mode)) {
                    var admission = new JevEvaluationRuntime.DecisionAdmission(() -> true, System.nanoTime()+TimeUnit.SECONDS.toNanos(3), true);
                    runtime.prefetch(new JevChoiceAdvisor.QuestionKey(java.util.UUID.randomUUID(), 0, "synthetic1"), "main", "synthetic1",
                            java.util.List.of(JevChoiceAdvisor.WEB_NEED), admission);
                    runtime.prefetch(new JevChoiceAdvisor.QuestionKey(java.util.UUID.randomUUID(), 0, "synthetic2"), "main", "synthetic2",
                            java.util.List.of(JevChoiceAdvisor.WEB_NEED), admission);
                } else {
                    runtime.advise("main", "synthetic1", "RECENT_ONLY");
                    runtime.advise("main", "synthetic2", "RECENT_ONLY");
                }
                assertTrue(f.started.await(2, TimeUnit.SECONDS));
                assertTrue(runtime.inFlight.tryAcquire(2, 1500, TimeUnit.MILLISECONDS), "transport deadlines must return both permits");
                runtime.inFlight.release(2);
                int before = f.hits.get();
                runtime.advise("main", "synthetic3", "RECENT_ONLY");
                assertTrue(await(() -> f.hits.get() == before + 1, 1500), "next request must arrive at the fixture");
                assertTrue(runtime.inFlight.tryAcquire(2, 1500, TimeUnit.MILLISECONDS));
                runtime.inFlight.release(2); assertEquals(2, runtime.inFlight.availablePermits());
            }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    static boolean await(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            new CountDownLatch(1).await(10, TimeUnit.MILLISECONDS);
        }
        return condition.getAsBoolean();
    }
}
