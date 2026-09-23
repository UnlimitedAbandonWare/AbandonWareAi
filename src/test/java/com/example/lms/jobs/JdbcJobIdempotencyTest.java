package com.example.lms.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcJobIdempotencyTest {
    @TempDir Path root;
    static final String FP="a".repeat(64),OTHER="b".repeat(64);
    DriverManagerDataSource database(){var ds=new DriverManagerDataSource("jdbc:h2:file:"+root.resolve("jobs")+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE","sa","");new ResourceDatabasePopulator(new FileSystemResource("main/resources/db/migration/V20260912__durable_jobs.sql"),new FileSystemResource("main/resources/db/migration/V20260912_03__job_idempotency.sql")).execute(ds);return ds;}
    JobService.Admission submit(JdbcJobService s,String owner,String key,String fp){return s.enqueueOnce("task_ask",Map.of("message","fixture"),Map.of("ownerHash",owner),null,key,fp);}
    @Test void fiveTrulyConcurrentRequestsAcrossTwoInstancesInvokeHandlerOnce() throws Exception {duplicateRace(false);}
    @Test void fiveRequestsSpacedByOneHundredMillisecondsInvokeHandlerOnce() throws Exception {duplicateRace(true);}
    void duplicateRace(boolean staggered)throws Exception {
        var ds=database();var calls=new AtomicInteger();var pool=Executors.newFixedThreadPool(5);var go=new CountDownLatch(1);
        try(var a=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC());var b=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())){
            a.registerHandler("task_ask",input->{calls.incrementAndGet();return "{}";});b.registerHandler("task_ask",input->{calls.incrementAndGet();return "{}";});
            var futures=new ArrayList<Future<JobService.Admission>>();
            for(int i=0;i<5;i++){int n=i;futures.add(pool.submit(()->{go.await();if(staggered)Thread.sleep(n*100L);return submit(n%2==0?a:b,"owner","same",FP);}));}go.countDown();
            var ids=new HashSet<String>();for(var f:futures)ids.add(f.get(5,TimeUnit.SECONDS).taskId());assertEquals(1,ids.size());
            var x=pool.submit(a::runPendingOnce);var y=pool.submit(b::runPendingOnce);x.get(5,TimeUnit.SECONDS);y.get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertEquals(1,new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM awx_jobs",Integer.class));
            var replay=submit(b,"owner","same",FP);assertTrue(replay.replayed());assertEquals("SUCCEEDED",replay.state());assertEquals("{}",b.result(replay.taskId(),"owner").orElseThrow());assertTrue(a.find(replay.taskId(),"other").isEmpty());
        }finally{pool.shutdownNow();}
    }
    @Test void ownerOperationAndFingerprintAreSeparateAndLegacyNoKeyStillCreatesJobs(){
        var ds=database();try(var s=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())){
            var first=submit(s,"a","k",FP);assertThrows(JobService.IdempotencyConflict.class,()->submit(s,"a","k",OTHER));
            assertNotEquals(first.taskId(),submit(s,"b","k",FP).taskId());
            assertNotEquals(first.taskId(),s.enqueueOnce("other_operation",Map.of(),Map.of("ownerHash","a"),null,"k",FP).taskId());
            assertNotEquals(s.enqueue("task_ask",Map.of(),Map.of(),null),s.enqueue("task_ask",Map.of(),Map.of(),null));
        }
    }
    @Test void keyFenceSurvivesServiceRestartAndNoRedisStateIsNeededForReplay(){
        var ds=database();String id;try(var s=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())){id=submit(s,"a","k",FP).taskId();}
        try(var s=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())){assertEquals(id,submit(s,"a","k",FP).taskId());assertEquals("PENDING",s.status(id));}
    }
    @Test void retentionDoesNotExpireRunningOrUnknownAndReuseStartsOnlyAfterCompletedRetention(){
        var ds=database();var now=new AtomicLong(1800000000000L);Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now.get());}};
        try(var s=new JdbcJobService(ds,new ObjectMapper(),clock)){
            var id=submit(s,"a","k",FP).taskId();now.addAndGet(Duration.ofDays(2).toMillis());assertEquals(id,submit(s,"a","k",FP).taskId());
            s.registerHandler("task_ask",input->"{}");s.runPendingOnce();now.addAndGet(Duration.ofHours(24).toMillis()-1);assertEquals(id,submit(s,"a","k",FP).taskId());
            now.incrementAndGet();assertNotEquals(id,submit(s,"a","k",FP).taskId());assertTrue(s.result(id,"a").isEmpty());
        }
    }
    @Test void executionExceptionIsUnknownWithoutProviderOutcomeContractAndNeverRetries(){
        var ds=database();var calls=new AtomicInteger();try(var s=new JdbcJobService(ds,new ObjectMapper(),Clock.systemUTC())){
            s.registerHandler("task_ask",input->{calls.incrementAndGet();throw new java.io.IOException("synthetic_after_send");});var id=submit(s,"a","k",FP).taskId();s.runPendingOnce();s.runPendingOnce();
            assertEquals("OUTCOME_UNKNOWN",s.status(id));assertEquals(id,submit(s,"a","k",FP).taskId());assertEquals(1,calls.get());assertNull(s.find(id,"a").orElseThrow().expiresAt());
        }
    }
}
