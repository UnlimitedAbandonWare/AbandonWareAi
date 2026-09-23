package com.example.lms.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.Path;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JdbcJobServiceTest {
    @TempDir Path directory;
    private DriverManagerDataSource database() {
        var ds = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("jobs").toAbsolutePath()
                + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("main/resources/db/migration/V20260912__durable_jobs.sql")).execute(ds);
        return ds;
    }
    @Test void queuedInputAndCompletedResultSurviveIndependentServiceInstances() {
        var ds = database();
        String id;
        try (var first = new JdbcJobService(ds, new ObjectMapper(), Clock.systemUTC())) {
            id = first.enqueue("task_ask", Map.of("message", "fixture"), Map.of("ownerHash", "owner-a"), null);
        }
        try (var second = new JdbcJobService(ds, new ObjectMapper(), Clock.systemUTC())) {
            second.registerHandler("task_ask", input -> "{\"answer\":\"persisted\"}");
            second.runPendingOnce();
            assertEquals("SUCCEEDED", second.status(id));
        }
        try (var third = new JdbcJobService(ds, new ObjectMapper(), Clock.systemUTC())) {
            assertEquals("SUCCEEDED", third.status(id));
            assertEquals("{\"answer\":\"persisted\"}", third.result(id, "owner-a").orElseThrow());
            assertTrue(third.result(id, "owner-b").isEmpty());
            assertTrue(third.find(id, "owner-b").isEmpty());
            assertNotNull(third.find(id, "owner-a").orElseThrow().resultRef());
        }
    }
    @Test void twoWorkersClaimOneInference() throws Exception {
        var ds = database(); var calls = new AtomicInteger();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var first = new JdbcJobService(ds, new ObjectMapper(), Clock.systemUTC());
             var second = new JdbcJobService(ds, new ObjectMapper(), Clock.systemUTC())) {
            JobService.JobHandler handler = input -> {
                calls.incrementAndGet(); entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fixture_timeout");
                return "{}";
            };
            first.registerHandler("task_ask", handler); second.registerHandler("task_ask", handler);
            String id = first.enqueue("task_ask", Map.of(), Map.of("ownerHash", "owner-a"), null);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> a = pool.submit(first::runPendingOnce);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                pool.submit(second::runPendingOnce).get(3, TimeUnit.SECONDS);
                release.countDown(); a.get(3, TimeUnit.SECONDS);
                assertEquals(1, calls.get()); assertEquals("SUCCEEDED", second.status(id));
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }
    @Test void retentionStartsAtCompletionAndNeverExpiresPendingWork() {
        var ds = database(); var clock = new MutableClock();
        try (var service = new JdbcJobService(ds, new ObjectMapper(), clock)) {
            service.registerHandler("task_ask", input -> "{}");
            String pending = service.enqueue("unregistered", Map.of(), Map.of("ownerHash", "owner-a"), null);
            clock.now += Duration.ofDays(2).toMillis(); service.maintenance();
            assertEquals("PENDING", service.status(pending));
            String id = service.enqueue("task_ask", Map.of(), Map.of("ownerHash", "owner-a"), null);
            service.runPendingOnce(); clock.now += Duration.ofHours(24).toMillis() - 1;
            service.maintenance(); assertEquals("SUCCEEDED", service.status(id));
            clock.now++; service.maintenance(); assertEquals("NOT_FOUND", service.status(id));
            assertEquals(0, new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM awx_job_results", Integer.class));
        }
    }
    @Test void lostWorkerIsRecordedWithoutAutomaticExternalReplay() {
        var ds = database(); var clock = new MutableClock();
        try (var service = new JdbcJobService(ds, new ObjectMapper(), clock)) {
            String id = service.enqueue("task_ask", Map.of(), Map.of("ownerHash", "owner-a"), null);
            new JdbcTemplate(ds).update("UPDATE awx_jobs SET state='RUNNING', lease_until=?, worker_token=? WHERE task_id=?", clock.millis()-1, "lost-worker", id);
            service.maintenance();
            assertEquals("OUTCOME_UNKNOWN", service.status(id));
            assertTrue(service.result(id, "owner-a").isEmpty());
        }
    }
    @Test void resultDigestIsFullSha256AndCallbackRetryDoesNotRunInferenceAgain() {
        var ds=database();var clock=new MutableClock();var executions=new AtomicInteger();var callbacks=new AtomicInteger();
        try(var service=new JdbcJobService(ds,new ObjectMapper(),clock)) {
            service.registerHandler("task_ask", new JobService.JobHandler(){
                public String execute(String input){executions.incrementAndGet();return "{}";}
                public boolean needsCompletion(String input){return true;}
                public boolean completed(String id,String input,String output){return callbacks.incrementAndGet()>=2;}
            });
            String id=service.enqueue("task_ask",Map.of(),Map.of("ownerHash","owner-a"),null);
            service.runPendingOnce();service.runPendingOnce();clock.now+=3000;service.runPendingOnce();
            assertEquals(1,executions.get());assertEquals(2,callbacks.get());
            assertEquals("SUCCEEDED",service.status(id));
            var jdbc=new JdbcTemplate(ds);
            assertEquals(64,jdbc.queryForObject("SELECT body_sha256 FROM awx_job_results WHERE task_id=?",String.class,id).length());
            assertEquals("DELIVERED",jdbc.queryForObject("SELECT callback_state FROM awx_jobs WHERE task_id=?",String.class,id));
        }
    }
    @Test void completedCallbackIsNotStarvedByNewPendingJobs() {
        var ds=database();var callbacks=new AtomicInteger();
        try(var service=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())) {
            service.registerHandler("task_ask",new JobService.JobHandler(){
                public String execute(String input){return "{}";}
                public boolean needsCompletion(String input){return true;}
                public boolean completed(String id,String input,String body){callbacks.incrementAndGet();return true;}
            });
            service.enqueue("task_ask",Map.of(),Map.of("ownerHash","owner-a"),null);service.runPendingOnce();
            service.enqueue("task_ask",Map.of(),Map.of("ownerHash","owner-a"),null);service.runPendingOnce();
            assertEquals(1,callbacks.get(),"ready callback must get a turn even while generation queue stays nonempty");
        }
    }
    @Test void remoteCancellationInterruptsWorkerAndRejectsItsLateResult() throws Exception {
        var ds=database();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        try(var worker=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC());var api=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())) {
            worker.registerHandler("task_ask",input->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted.countDown();}return "late result";});
            String id=api.enqueue("task_ask",Map.of(),Map.of("ownerHash","owner-a"),null);
            var thread=new Thread(worker::runPendingOnce);thread.start();
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS));assertFalse(api.cancel(id,"owner-b"));assertTrue(api.cancel(id,"owner-a"));
                worker.maintenance();assertTrue(interrupted.await(1,TimeUnit.SECONDS));thread.join(3000);
                assertFalse(thread.isAlive());assertEquals("CANCELLED",api.status(id));assertTrue(api.result(id,"owner-a").isEmpty());
            }finally{release.countDown();thread.interrupt();thread.join(3000);}
        }
    }
    private static final class MutableClock extends Clock {
        long now=1_800_000_000_000L;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
        public long millis(){return now;}
    }
}
