package com.example.lms.jobs;

import com.example.lms.config.JobConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryJobServiceTraceContractTest {

    private static final int STATUS_CAPACITY = 4096;
    private static final long WAIT_SECONDS = 5L;

    @Test
    void enqueueDoesNotRetainRawPayload() throws Exception {
        InMemoryJobService service = new InMemoryJobService();
        String rawPayload = "synthetic-webhook-body";

        String jobId = service.enqueue(rawPayload);

        assertEquals("PENDING", service.status(jobId));
        for (Field field : InMemoryJobService.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Map<?, ?> values = (Map<?, ?>) field.get(service);
            assertFalse(values.containsValue(rawPayload),
                    () -> field.getName() + " must not retain the raw payload");
        }
    }

    @Test
    void enqueueRejectsNullWithoutCreatingGhostState() throws Exception {
        InMemoryJobService service = new InMemoryJobService();
        int entriesBefore = retainedMapEntryCount(service);

        assertThrows(NullPointerException.class, () -> service.enqueue(null));

        assertEquals(entriesBefore, retainedMapEntryCount(service));
    }

    @Test
    void pendingStatusRetentionCapsAllStatesAt4096() throws Exception {
        InMemoryJobService service = new InMemoryJobService(fixedClock());
        List<String> pendingIds = new ArrayList<>();
        try {
            for (int index = 0; index <= STATUS_CAPACITY; index++) {
                pendingIds.add(service.enqueue("payload-" + index));
            }

            assertEquals(STATUS_CAPACITY, retainedMapEntryCount(service));
            assertEquals("NOT_FOUND", service.status(pendingIds.get(0)));
            assertEquals("PENDING", service.status(pendingIds.get(pendingIds.size() - 1)));
        } finally {
            shutdownExecutor(service);
        }
    }

    @Test
    void pendingRunningSucceededAndFailedStatusesExpireAtExactTtl() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        InMemoryJobService service = new InMemoryJobService(clock);
        CountDownLatch runningEntered = new CountDownLatch(1);
        CountDownLatch runningRelease = new CountDownLatch(1);
        CountDownLatch succeeded = new CountDownLatch(1);
        try {
            String pendingId = service.enqueue("pending");

            String runningId = service.enqueue("running");
            service.executeAsync(runningId, () -> {
                runningEntered.countDown();
                awaitUnchecked(runningRelease);
                return "done";
            }, ignored -> { });
            assertTrue(runningEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("RUNNING", service.status(runningId));

            String succeededId = service.enqueue("succeeded");
            service.executeAsync(succeededId, () -> "done", ignored -> succeeded.countDown());
            assertTrue(succeeded.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("SUCCEEDED", service.status(succeededId));

            String failedId = service.enqueue("failed");
            service.executeAsync(failedId, () -> {
                throw new IllegalStateException("synthetic failure");
            }, ignored -> { });
            assertEquals("FAILED", awaitState(service, failedId, "FAILED"));

            clock.advance(Duration.ofHours(24));

            assertEquals("NOT_FOUND", service.status(pendingId));
            assertEquals("NOT_FOUND", service.status(runningId));
            assertEquals("NOT_FOUND", service.status(succeededId));
            assertEquals("NOT_FOUND", service.status(failedId));
        } finally {
            runningRelease.countDown();
            shutdownExecutor(service);
        }
    }

    @Test
    void staleSameStateOrderEntryCannotEvictNewerVersion() throws Exception {
        InMemoryJobService service = new InMemoryJobService(fixedClock());
        CountDownLatch runningEntered = new CountDownLatch(2);
        CountDownLatch runningRelease = new CountDownLatch(1);
        try {
            String repeatedId = service.enqueue("repeated");
            for (int attempt = 0; attempt < 2; attempt++) {
                service.executeAsync(repeatedId, () -> {
                    runningEntered.countDown();
                    awaitUnchecked(runningRelease);
                    return "done";
                }, ignored -> { });
            }
            assertTrue(runningEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            for (int index = 0; index < STATUS_CAPACITY - 1; index++) {
                service.enqueue("newer-" + index);
            }

            assertEquals("RUNNING", service.status(repeatedId),
                    "evicting stale PENDING/RUNNING order rows must keep the newest RUNNING version");
            assertEquals(STATUS_CAPACITY, retainedMapEntryCount(service));
        } finally {
            runningRelease.countDown();
            shutdownExecutor(service);
        }
    }

    @Test
    void saturationRejectsInsteadOfCreatingUnboundedWorkers() throws Exception {
        InMemoryJobService service = new InMemoryJobService();
        CountDownLatch release = new CountDownLatch(1);
        boolean rejected = false;
        String rejectedJobId = null;
        try {
            for (int index = 0; index < 100; index++) {
                String jobId = service.enqueue("blocked-" + index);
                try {
                    service.executeAsync(jobId, () -> {
                        try {
                            release.await();
                            return "done";
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted");
                        }
                    }, ignored -> { });
                } catch (RejectedExecutionException expected) {
                    rejected = true;
                    rejectedJobId = jobId;
                    break;
                }
            }

            assertTrue(rejected, "bounded admission should reject before creating 100 blocked workers");
            assertEquals("FAILED", service.status(rejectedJobId),
                    "a rejected job must not remain in a ghost RUNNING state");
        } finally {
            release.countDown();
            shutdownExecutor(service);
        }
    }

    @Test
    void springLifecycleInterruptsInflightWorkAndRejectsNewWork() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "test-storage", java.util.Map.of("jobs.storage", "memory")));
        context.register(JobConfig.class);
        context.refresh();
        InMemoryJobService service = (InMemoryJobService) context.getBean(JobService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            String inFlightJobId = service.enqueue("in-flight");
            service.executeAsync(inFlightJobId, () -> {
                entered.countDown();
                try {
                    blocker.await();
                    return "unexpected";
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                } finally {
                    exited.countDown();
                }
            }, ignored -> { });
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "worker should enter before the context closes");

            context.close();
            context = null;

            assertTrue(exited.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "Spring context close should interrupt owned in-flight work");
            assertTrue(interrupted.get(), "owned shutdown should deliver interruption to the worker");

            String rejectedJobId = service.enqueue("after-close");
            assertThrows(RejectedExecutionException.class,
                    () -> service.executeAsync(rejectedJobId, () -> "unexpected", ignored -> { }));
            assertEquals("FAILED", service.status(rejectedJobId),
                    "post-shutdown rejection must not leave a ghost RUNNING state");
        } finally {
            blocker.countDown();
            if (context != null) {
                context.close();
            }
            shutdownExecutor(service);
        }
    }

    @Test
    void shutdownCannotBeResurrectedByInterruptedWorkReturningNormally() throws Exception {
        InMemoryJobService service = new InMemoryJobService(fixedClock());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean successCallback = new AtomicBoolean();
        try {
            String jobId = service.enqueue("interrupted-normal-return");
            service.executeAsync(jobId, () -> {
                entered.countDown();
                try {
                    blocker.await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    return "handled-interrupt";
                }
                return "unexpected";
            }, ignored -> successCallback.set(true));
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            service.shutdown();
            assertTrue(interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS));
            shutdownExecutor(service);

            assertEquals("FAILED", service.status(jobId));
            assertFalse(successCallback.get(),
                    "a task returning after lifecycle interruption must not run its success callback");
        } finally {
            blocker.countDown();
            shutdownExecutor(service);
        }
    }

    @Test
    void workerFailureFallbackLeavesRedactedTraceBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/jobs/InMemoryJobService.java"));

        assertTrue(source.contains("traceSuppressed(\"executeAsync\", t)"),
                "worker failure fallback should leave a redacted breadcrumb");
        assertTrue(source.contains("TraceStore.put(\"jobs.inMemory.suppressed.\" + safeStage, true)"),
                "in-memory job fallback should use the jobs TraceStore namespace");
    }

    private static int retainedMapEntryCount(InMemoryJobService service) throws IllegalAccessException {
        int count = 0;
        for (Field field : InMemoryJobService.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Map<?, ?> values = (Map<?, ?>) field.get(service);
            count += values.size();
        }
        return count;
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
    }

    private static String awaitState(
            InMemoryJobService service,
            String jobId,
            String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        String observed;
        do {
            observed = service.status(jobId);
            if (expected.equals(observed)) {
                return observed;
            }
            Thread.yield();
        } while (System.nanoTime() < deadline);
        return observed;
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        }
    }

    private static void shutdownExecutor(InMemoryJobService service) throws Exception {
        Field field = InMemoryJobService.class.getDeclaredField("exec");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(service);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                "test cleanup should terminate every owned worker");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
